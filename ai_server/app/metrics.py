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
            "reason_codes": dict(_reasons),
            "query_source": qs,
            # 콜드스타트 비율: 이력도 온보딩도 없어 근거 없이 나간 추천의 몫.
            # 온보딩·라벨 배치가 도는지 보는 핵심 지표다.
            "cold_start_rate": (round(qs.get("none", 0) / served, 4) if served else None),
        }


def reset() -> None:
    """테스트 전용. 운영 중에 부르면 추세가 끊긴다."""
    with _LOCK:
        _lat.clear()
        _calls.clear()
        _errors.clear()
        _reasons.clear()
        _query_source.clear()
        _behavior.clear()
