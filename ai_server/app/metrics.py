"""운영 관측용 프로세스 내 카운터.

지표 하나 때문에 수집 라이브러리를 들이지 않는다. 이 서비스는 **워커 한 개**로 돌고,
필요한 것은 "지금 이 프로세스가 어떻게 답하고 있나" 뿐이다.

**프로세스가 살아 있는 동안만 쌓인다.** 재시작하면 0 이다. 장기 추세는 수집기 쪽에서
주기적으로 읽어 쌓아야 한다 — `started_at` 과 `uptime_seconds` 를 함께 내보내는 이유다.

지연은 전부 기록하지 않고 최근 `_KEEP` 건만 남겨 분위를 낸다. 평균은 내보내지 않는다.
느린 요청 몇 건이 평균에 묻혀 "괜찮아 보이는" 그래프가 되기 때문이다.
"""
from __future__ import annotations

import threading
import time
from collections import Counter, deque

_KEEP = 1000            # 분위 계산에 쓰는 최근 표본 수
_LOCK = threading.Lock()
_STARTED = time.time()

_lat: dict[str, deque] = {}
_calls: Counter = Counter()
_errors: Counter = Counter()          # error_code -> 건수
_reasons: Counter = Counter()         # reason_code -> 건수
_query_source: Counter = Counter()    # labels | onboarding | none
_behavior: deque = deque(maxlen=_KEEP)
_behavior_source: Counter = Counter()   # clickhouse|file|unavailable|mapping_empty
_onb_unmapped: Counter = Counter()    # mood | genre — 답변은 왔는데 하나도 못 옮긴 축
_legacy_catalog: Counter = Counter()  # path -> 옛 Spring 이 본문에 catalog 를 실어 보낸 건수
_conditions: Counter = Counter()      # 조건 풀기 결과 — kept | unsatisfiable | empty | unavailable
_snapshot_refresh: Counter = Counter()  # ok | failed — 기준 풀 스냅샷 갱신
_snapshot_seconds: deque = deque(maxlen=_KEEP)
_stale_vectors: Counter = Counter()   # 구간 지문이 피처 저장소와 달라 버린 후보 수


def observe_request(path: str, seconds: float, error_code: str | None = None) -> None:
    with _LOCK:
        _calls[path] += 1
        _lat.setdefault(path, deque(maxlen=_KEEP)).append(seconds)
        if error_code:
            _errors[error_code] += 1


def observe_reason(code: str) -> None:
    with _LOCK:
        _reasons[code] += 1


def observe_query_source(source: str) -> None:
    """추천 한 건이 무엇을 근거로 질의를 만들었나. 콜드스타트 비율의 원천이다."""
    with _LOCK:
        _query_source[source or "none"] += 1


def observe_behavior_source(source: str) -> None:
    """행동을 어디서 읽었나.

    | 값 | 뜻 |
    |---|---|
    | `clickhouse` | 정상 조회 (0행이었고 원본도 없었으면 여기 든다 — 안 들은 것이다) |
    | `file` | 주소가 없어 파일로 물러났다 (개발·오프라인) |
    | `unavailable` | 조회 자체가 실패했다 |
    | `mapping_empty` | **원본 이벤트는 있는데 곡 매핑 결과가 0건이다** |

    `unavailable` 이 쌓이면 ClickHouse 조회가 실패하고 있다는 뜻이다. 추천은 계속
    나가지만 **개인화가 빠진 채**라 실패율만 봐서는 드러나지 않는다.

    `mapping_empty` 는 그보다 찾기 어렵다 — 조회는 성공했고 결과만 비어 있어서
    "이 사람은 안 들었다" 와 구분되지 않는다. 실제로는 `v_track_sections_current`
    가 그 곡을 되돌리지 못한 것이고, 한 사람이 아니라 전 사용자에게 걸린다.
    """
    with _LOCK:
        _behavior_source[source or "unknown"] += 1


def observe_onboarding_unmapped(axis: str) -> None:
    """온보딩 한 축을 **하나도** 카탈로그 어휘로 옮기지 못했다.

    무드는 되는데 장르만 안 되는 경우가 지금 실제로 있다 — 코드표의 세분류
    장르 45종(`49~93`)은 카탈로그 태그(`Electronic---Ambient` 꼴)와 철자가 달라
    부분일치가 걸리지 않는다. 그 축만 조용히 빈 채 추천은 정상으로 나가기 때문에
    응답만 보아서는 못 찾는다. 세어 두어야 계약이 필요하다는 것을 알 수 있다.
    """
    with _LOCK:
        _onb_unmapped[axis] += 1


def observe_legacy_catalog(path: str) -> None:
    """요청 본문에 `catalog` 가 왔다 — **옛 Spring 이다** (계약 v3, S15P21A204-458).

    받되 쓰지 않는다. 그래서 새 AI 를 먼저 올려도 옛 Spring 과 함께 돈다. 새 Spring 이 다
    올라간 뒤에도 0 이 아니면 어딘가 옛 호출자가 남아 약 0.5MB 를 요청마다 보내고 있다.
    """
    with _LOCK:
        _legacy_catalog[path] += 1


def observe_conditions(outcome: str) -> None:
    """조건 검색 한 건의 조건 풀기 결과. `unavailable` 은 피처 저장소가 없어 검색을 껐다는 뜻이다."""
    with _LOCK:
        _conditions[outcome] += 1


def observe_snapshot_refresh(ok: bool, seconds: float) -> None:
    """기준 풀 스냅샷 갱신 한 번. 실패가 이어지면 요청은 **직전 스냅샷**으로 돈다 —
    값이 낡아 가는 것은 `/internal/ready` 의 `pool_snapshot.age_seconds` 로 본다."""
    with _LOCK:
        _snapshot_refresh["ok" if ok else "failed"] += 1
        _snapshot_seconds.append(seconds)


def observe_stale_vectors(n: int) -> None:
    """벡터 저장소(Qdrant)의 구간 지문이 피처 저장소와 달라 버린 후보(설계 §9.3-3). 재분석 직후
    잠깐 생기는 것이 정상이고, 계속 쌓이면 Spring 이 한쪽만 쓴 것이다(§10.1-1)."""
    with _LOCK:
        _stale_vectors["dropped"] += int(n)


def observe_behavior(seconds: float) -> None:
    """행동 로그 조회에 걸린 시간. 개인화 사용자가 느린 원인을 분리해 본다."""
    with _LOCK:
        _behavior.append(seconds)


def _pct(samples, q: float) -> float | None:
    if not samples:
        return None
    xs = sorted(samples)
    i = min(len(xs) - 1, max(0, int(round(q * (len(xs) - 1)))))
    return round(xs[i], 4)


def _lat_block(samples) -> dict:
    return {"n": len(samples), "p50": _pct(samples, 0.50),
            "p95": _pct(samples, 0.95), "max": round(max(samples), 4) if samples else None}


def snapshot() -> dict:
    with _LOCK:
        calls = dict(_calls)
        total = sum(calls.values())
        errors = dict(_errors)
        qs = dict(_query_source)
        served = sum(qs.values())
        return {
            "started_at": round(_STARTED, 3),
            "uptime_seconds": round(time.time() - _STARTED, 1),
            "sample_window": _KEEP,
            "requests": {"total": total, "by_path": calls},
            # 실패율은 4xx 를 포함한다. NO_CANDIDATES 는 장애가 아니므로 따로 센다.
            "errors": {"total": sum(errors.values()), "by_code": errors,
                       "rate": round(sum(errors.values()) / total, 4) if total else None},
            "latency_seconds": {p: _lat_block(list(d)) for p, d in _lat.items()},
            "behavior_lookup_seconds": _lat_block(list(_behavior)),
            "behavior_source": dict(_behavior_source),
            # 답변은 왔는데 그 축을 하나도 못 옮긴 건수. 응답에는 드러나지 않는다.
            "onboarding_unmapped": dict(_onb_unmapped),
            "reason_codes": dict(_reasons),
            # 옛 Spring(본문에 catalog)이 보낸 건수. 새 Spring 배포 뒤에는 0 이어야 한다.
            "legacy_catalog_requests": dict(_legacy_catalog),
            "conditions": dict(_conditions),
            "stale_vectors": dict(_stale_vectors),
            "pool_snapshot_refresh": {**dict(_snapshot_refresh),
                                      "seconds": _lat_block(list(_snapshot_seconds))},
            "query_source": qs,
            # 콜드스타트 비율: 이력도 온보딩도 없어 근거 없이 나간 추천의 몫.
            # 온보딩·라벨 배치가 도는지 보는 핵심 지표다.
            "cold_start_rate": (round(qs.get("none", 0) / served, 4) if served else None),
        }


def reset() -> None:
    """테스트 전용. 운영 중에 부르면 추세가 끊긴다."""
    with _LOCK:
        _onb_unmapped.clear()
        _legacy_catalog.clear()
        _conditions.clear()
        _snapshot_refresh.clear()
        _snapshot_seconds.clear()
        _stale_vectors.clear()
        _lat.clear()
        _calls.clear()
        _errors.clear()
        _reasons.clear()
        _query_source.clear()
        _behavior.clear()
        _behavior_source.clear()
