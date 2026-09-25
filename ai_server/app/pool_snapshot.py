"""기준 풀 스냅샷 — 추천 요청이 보는 곡·구간 값 (계약 v3, S15P21A204-458).

예전에는 Spring 이 요청마다 본문 `catalog`(약 0.5MB, 곡 수에 비례)에 후보와 그 값을 실어
보냈다. 계약 v3 에서는 AI 가 그 값을 **ClickHouse 피처 저장소**(`feature_store`)에서 직접
읽는다. 다만 요청마다 읽지 않는다 —

    백그라운드   색인 풀 ID 전부로 `feature_store.lookup_many` → 스냅샷 → 원자적으로 교체
    요청         지금 스냅샷을 **참조만** 한다 (풀 읽기 없음)

왜 후보 K 개만 읽지 않나
    추천기에는 사용자와 무관한 "풀 전체" 값이 있다 — 콜드스타트 순서(풀 전체 에너지 백분위),
    온보딩 씨앗의 곡 고르기(전 곡 + 단계 완화), 온보딩 고정 게이트, 곡 안 에너지 백분위,
    8초 미만 제외, 온보딩 장르 어휘. 후보 K 개의 값만으로는 이것을 같은 값으로 못 만들어,
    본문 `catalog` 로 받던 때와 순위가 달라진다. 그래서 본문 자리를 이 스냅샷이 그대로 대신한다
    (2026-09-25 coordinator 결정). ClickHouse 집계로 풀 전체 값을 대신 세는 것은 후속이다
    (설계 §9.3 "기준 풀 스냅샷").

갱신
    주기는 `MUPID_POOL_SNAPSHOT_TTL_SEC`(기본 60초). 실패하면 **직전 스냅샷을 계속 쓴다** —
    값이 낡아 가는 것은 `status()` 의 `age_seconds`·`consecutive_failures` 와 지표에 남는다.
    한 번도 읽지 못했으면(기동 때 ClickHouse 가 죽어 있었다) 색인만으로 만든 **벡터 전용**
    스냅샷을 쓴다 — 곡·구간 값을 모르는 채 벡터만으로 추천하고, 조건 검색은 끈다(설계 §10.1-5
    "ClickHouse 둘 다 죽으면 벡터만"). 준비 판정은 막지 않는다.

`Snapshot` 은 만들고 나면 바꾸지 않는다(`catalog_body.Snapshot`). 교체는 참조 하나를 바꾸는
것이라 계산 도중에 값이 반쯤 바뀌지 않는다.
"""
from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from dataclasses import dataclass, field
from typing import Callable, Optional

from . import catalog_body, metrics
from .log import log

TTL_ENV = "MUPID_POOL_SNAPSHOT_TTL_SEC"
DEFAULT_TTL = 60.0

SOURCE_FEATURE_STORE = "feature_store"
SOURCE_VECTOR_ONLY = "vector_only"
SOURCE_STATIC = "static"


def ttl_seconds() -> float:
    raw = (os.environ.get(TTL_ENV) or "").strip()
    if not raw:
        return DEFAULT_TTL
    try:
        v = float(raw)
    except ValueError:
        raise ValueError(f"{TTL_ENV} 는 초 단위 숫자다: {raw!r}") from None
    if v < 1:
        raise ValueError(f"{TTL_ENV} 는 1초 이상이어야 한다: {raw!r}")
    return v


@dataclass(frozen=True)
class PoolSnapshot:
    """한 시점의 기준 풀. `snap` 이 추천기가 읽는 값이고 `instruments` 는 조건 풀기만 읽는다.

    `conditions` 가 거짓이면 조건 검색을 하지 않는다 — 곡·구간 값을 모르는 벡터 전용이다.
    """

    snap: catalog_body.Snapshot
    instruments: dict
    source: str
    generation: int
    built_at: float
    stats: dict = field(default_factory=dict)
    content_key: str = ""

    @property
    def conditions(self) -> bool:
        return self.source != SOURCE_VECTOR_ONLY


Loader = Callable[[], tuple]      # () -> (Snapshot, instruments, stats)


def content_key(snap: catalog_body.Snapshot, instruments: dict) -> str:
    """내용이 같으면 같은 값. 본문 해시(`Snapshot.digest`)에 악기와 구간 지문을 더한다 — 재분석이
    경계·값은 그대로 두고 버전만 바꿔도 새 내용이다(벡터 저장소 지문 대조가 그 버전을 본다)."""
    raw = json.dumps([sorted((str(k), list(v)) for k, v in instruments.items()),
                      sorted(getattr(snap, "versions", {}).items())],
                     ensure_ascii=False, separators=(",", ":"))
    return snap.digest + ":" + hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


class Holder:
    """스냅샷 하나를 들고 주기적으로 갈아 끼운다. 요청은 `current()` 만 부른다."""

    # 바뀌지 않았다는 확인만으로 건너뛰는 최대 횟수. 확인 문장이 놓치는 변경이 있어도 이만큼 뒤에는
    # 전부 다시 읽는다(기본 60초 × 10 = 10분).
    FULL_EVERY = 10

    def __init__(self, load: Loader, ttl: Optional[float] = None,
                 on_swap: Optional[Callable[[PoolSnapshot], None]] = None,
                 source: str = SOURCE_FEATURE_STORE,
                 probe: Optional[Callable[[], object]] = None) -> None:
        self._load = load
        # 싼 변경 확인(`ClickHouseFeatureStore.change_token`). 값이 그대로면 풀 전체를 읽지 않는다 —
        # 10만 곡이면 읽고 해석하는 데만 수십 초다(`tools/bench_pool_snapshot.py`).
        self._probe = probe
        self._token: object = None
        self._skipped = 0
        self.ttl = float(ttl if ttl is not None else ttl_seconds())
        self._on_swap = on_swap
        self._source = source
        self._current: Optional[PoolSnapshot] = None
        self._lock = threading.Lock()          # 갱신끼리만 막는다. 읽기는 잠그지 않는다
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None
        self._gen = 0
        self.failures = 0
        self.consecutive_failures = 0
        self.last_error: Optional[str] = None
        self.last_attempt: Optional[float] = None
        self.last_success: Optional[float] = None
        self.last_seconds: Optional[float] = None
        self.unchanged = 0

    def current(self) -> Optional[PoolSnapshot]:
        return self._current

    def refresh(self) -> bool:
        """한 번 읽어 갈아 끼운다. 실패하면 직전 것을 그대로 두고 거짓을 돌려준다.

        **내용이 같으면 갈아 끼우지 않는다.** 사본·풀 색인을 다시 만드는 것은 풀 크기에 비례하는
        일이라(10만 곡이면 수십 초) 곡이 바뀌었을 때만 치른다 — 예전에 같은 본문이 거듭 오면 사본을
        재사용하던 것과 같다. 읽기는 성공한 것이므로 나이(`age_seconds`)는 새로 센다.
        """
        with self._lock:
            t0 = time.perf_counter()
            self.last_attempt = time.time()
            token = None
            if self._probe is not None and self._current is not None:
                try:
                    token = self._probe()
                except Exception as e:  # noqa: BLE001 — 확인이 안 되면 전부 읽어 본다
                    token = None
                    log(f"기준 풀 변경 확인 실패 — 전부 읽는다: {type(e).__name__}: {e}")
                if (token is not None and token == self._token
                        and self._skipped < self.FULL_EVERY):
                    self._skipped += 1
                    self.unchanged += 1
                    self.consecutive_failures = 0
                    self.last_error = None
                    self.last_success = time.time()
                    self.last_seconds = round(time.perf_counter() - t0, 3)
                    metrics.observe_snapshot_refresh(True, self.last_seconds)
                    return True
            elif self._probe is not None:
                try:
                    token = self._probe()
                except Exception:  # noqa: BLE001
                    token = None
            try:
                snap, instruments, stats = self._load()
                key = content_key(snap, instruments)
                cur = self._current
                self._token, self._skipped = token, 0
                if cur is not None and cur.content_key == key:
                    self.unchanged += 1
                    self.consecutive_failures = 0
                    self.last_error = None
                    self.last_success = time.time()
                    self.last_seconds = round(time.perf_counter() - t0, 3)
                    metrics.observe_snapshot_refresh(True, self.last_seconds)
                    return True
                nxt = PoolSnapshot(snap, instruments, self._source, self._gen + 1,
                                   time.time(), {**stats, "seconds": round(time.perf_counter() - t0, 3)},
                                   key)
                if self._on_swap is not None:
                    # 사본·풀 색인을 **여기서** 미리 만든다. 요청이 첫 사용에서 O(풀) 을 치르지 않게.
                    self._on_swap(nxt)
            except Exception as e:  # noqa: BLE001 — 어떤 실패든 직전 스냅샷으로 계속 돈다
                self.failures += 1
                self.consecutive_failures += 1
                self.last_error = f"{type(e).__name__}: {e}"
                self.last_seconds = round(time.perf_counter() - t0, 3)
                metrics.observe_snapshot_refresh(False, self.last_seconds)
                log(f"기준 풀 스냅샷 갱신 실패 ({self.consecutive_failures}번째 연속) — "
                    f"{'직전 스냅샷으로 계속' if self._current else '벡터 전용'}: {self.last_error}")
                return False
            self._gen = nxt.generation
            self._current = nxt                  # 참조 하나를 바꾼다 — 원자적이다
            self.consecutive_failures = 0
            self.last_error = None
            self.last_success = nxt.built_at
            self.last_seconds = round(time.perf_counter() - t0, 3)
            metrics.observe_snapshot_refresh(True, self.last_seconds)
            return True

    def start(self) -> None:
        """백그라운드 갱신을 띄운다. 첫 갱신은 부르는 쪽이 `refresh()` 로 먼저 한다."""
        if self._thread is not None:
            return

        def loop() -> None:
            while not self._stop.wait(self.ttl):
                self.refresh()

        self._thread = threading.Thread(target=loop, name="pool-snapshot", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    def status(self) -> dict:
        cur = self._current
        now = time.time()
        return {
            "source": cur.source if cur else None,
            "generation": cur.generation if cur else 0,
            # 마지막으로 **읽기에 성공한** 뒤 지난 시간. 갱신이 실패하는 동안 늘어난다.
            "age_seconds": round(now - self.last_success, 1) if self.last_success else None,
            # 내용이 마지막으로 바뀐(갈아 끼운) 뒤 지난 시간.
            "content_age_seconds": round(now - cur.built_at, 1) if cur else None,
            "unchanged_refreshes": self.unchanged,
            "ttl_seconds": self.ttl,
            "tracks": len(cur.snap.tracks) if cur else 0,
            "sections": len(cur.snap.sections) if cur else 0,
            **({k: v for k, v in cur.stats.items()} if cur else {}),
            "last_refresh_seconds": self.last_seconds,
            "failures": self.failures,
            "consecutive_failures": self.consecutive_failures,
            "last_error": self.last_error,
        }


# ------------------------------------------------------------------ 만드는 법
def from_lookup(lookup) -> tuple:
    """`feature_store.Lookup` → (Snapshot, instruments, stats). 값을 옮기는 규칙은
    `Lookup.snapshot()`(= 예전 본문을 읽던 `catalog_body.from_body`) 한 벌이다."""
    snap = lookup.snapshot()
    stats = {"missing": len(lookup.missing), "dropped": len(lookup.dropped),
             "stale": len(lookup.stale)}
    return snap, dict(lookup.instruments), stats


def feature_store_loader(store, pool_ids: Callable[[], list]) -> Loader:
    """색인 풀 ID 전부로 피처 저장소를 읽는다. 삭제·재생 불가·버전 불일치는 `assemble` 이 뺀다 —
    예전에 Spring 이 본문에서 뺐던 것과 같다."""

    def load() -> tuple:
        ids = pool_ids()
        got = from_lookup(store.lookup_many(ids))
        if ids and not got[0].sections:
            # 읽기는 됐는데 색인 풀의 구간이 하나도 없다 — 백필 전이거나 다른 DB 를 보고 있다.
            # 빈 스냅샷으로 갈아 끼우면 추천이 통째로 NO_CANDIDATES 가 되므로 실패로 센다.
            raise RuntimeError(f"피처 저장소에 색인 풀 구간({len(ids)}개)이 하나도 없다 — "
                               "track_features·track_sections 백필 전인가")
        return got

    return load


def vector_only(index_rows) -> tuple:
    """곡·구간 값을 모르는 스냅샷. **값을 지어내지 않는다** — 모두 빈 칸("모른다")이다.

    `index_rows` 는 (moment_id, track_id) 쌍이다. 구간 경계를 모르므로 8초 미만 제외를
    판정하지 못하고(추천기가 "모름" 을 통과로 본다), 라벨·에너지·BPM 이 비어 그 항은 0 이다.
    지워진 곡이 섞일 수 있다 — Spring 최종 필터(MySQL)가 뺀다(설계 §10.1-5).
    """
    tracks: dict = {}
    sections: dict = {}
    for mid, tid in index_rows:
        tid = str(tid)
        tracks.setdefault(tid, {
            "music_id": None, "title": None, "genre_code": "", "mood_codes": "",
            "has_vocal": None, "vocal_gender": None, "language": "", "category": "",
            "bpm": None, "bpm_lo": None, "bpm_hi": None, "duration_ms": None})
        suffix = str(mid).split("#", 1)[1] if "#" in str(mid) else ""
        sections[str(mid)] = {
            "track_id": tid, "music_section_analysis_id": None,
            "section_idx": int(suffix) if suffix.isdigit() else 0,
            "section_label": "", "start_ms": None, "end_ms": None,
            "instruments": "", "moment_moods": "", "energy": None}
    return catalog_body.Snapshot(tracks, sections, digest="vector-only"), {}, {}


def static(body: dict, instruments: Optional[dict] = None) -> tuple:
    """본문 `catalog` 모양 그대로의 스냅샷 — 시험·측정 도구가 피처 저장소 없이 쓴다."""
    return catalog_body.from_body(body), dict(instruments or {}), {}


# ------------------------------------------------------------------ 전역 자리
HOLDER: Optional[Holder] = None


def install(holder: Optional[Holder]) -> Optional[Holder]:
    """전역 자리를 바꾼다. 앞의 것의 갱신 스레드는 멈춘다."""
    global HOLDER
    prev = HOLDER
    if prev is not None and prev is not holder:
        prev.stop()
    HOLDER = holder
    return prev


def install_static(body: dict, instruments: Optional[dict] = None,
                   on_swap: Optional[Callable[[PoolSnapshot], None]] = None) -> Holder:
    """본문 하나로 고정된 스냅샷을 둔다(시험·측정). 갱신 스레드는 없다."""
    h = Holder(lambda: static(body, instruments), ttl=3600.0, on_swap=on_swap,
               source=SOURCE_STATIC)
    if not h.refresh():
        raise ValueError(f"스냅샷을 만들지 못했다: {h.last_error}")
    install(h)
    return h


def current() -> Optional[PoolSnapshot]:
    h = HOLDER
    return None if h is None else h.current()


def status() -> dict:
    h = HOLDER
    if h is None:
        return {"source": None, "installed": False}
    return {"installed": True, **h.status()}


__all__ = ["PoolSnapshot", "Holder", "HOLDER", "install", "install_static", "current",
           "status", "feature_store_loader", "from_lookup", "vector_only", "static",
           "ttl_seconds", "TTL_ENV"]
