"""검색 조건(QueryPlan)을 **여기서** 푼다 — 계약 v3 (S15P21A204-458).

2026-09-23 계약에서는 Spring `QueryPlanFilter` 가 MySQL 후보에 조건을 걸어 통과한 구간만 본문
`catalog` 로 보냈다. 본문이 사라지면서 조건 풀기가 이쪽으로 돌아왔다. 규칙은 **그 자바 구현
그대로다** — 09-23 에 이 서버의 `filter_query_pool`·`_canonicalize_query_tags` 를 자바로 옮긴
것을 다시 옮겼다(`QueryPlanFilter`·`CatalogFacts`, 시험은 `QueryPlanFilterTest` 의 사례 전부를
`tests/test_query_conditions.py` 로). 규칙을 두 곳에 두지 않도록 Spring 쪽은 걷어냈다.

하는 일
    1. **어휘 맞춤** — 요청 값 가운데 이 풀에 실제로 있는 것만 남긴다(정확 일치. 넓히지 않는다)
    2. **만족할 수 없는 조건** — 장르·언어·보컬·악기 중 요청했는데 하나도 남지 않은 것이 있으면
       **빈 결과와 그 이유**다. 조건을 몰래 풀면 사용자가 말한 것과 다른 곡이 나간다
    3. **게이트** — 장르(곡 장르 ∪ 스타일)·언어·악기(구간, 없으면 곡 합집합)·보컬·구간형, 빼는
       조건(무드 포함), BPM 범위(요청 BPM 중간값)·템포/에너지 방향(기준 곡·구간이 있을 때만)

`filters.mood` 는 거르지 않는다 — 추천기가 가점으로 쓴다. `activity`·`goal` 은 풀에 필드가
없어 무시하고 진단에 남긴다.

어디서 읽나
    기준 풀 스냅샷(`pool_snapshot`) 하나다. 요청마다 ClickHouse 를 부르지 않는다. 스냅샷마다 한 번
    곡·구간 파생값(`Facts`)을 만들어 두고, 게이트는 곡·값 조합 단위로 판정해 구간에 펼친다 —
    구간 수만큼 파이썬 반복을 돌지 않는다. 같은 조건의 결과는 스냅샷 세대 안에서 재사용한다.
"""
from __future__ import annotations

import json
import threading
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Any, Callable, Optional

import numpy as np

# 필수 조건 가운데 "어휘에 없음" 을 따지는 칸. 여기 없는 칸은 어휘에 없으면 조용히 빠진다
# (무드는 가점, 구간형은 한 칸 고르기라 — `QueryPlanFilter.REQUIRED` 와 같다).
REQUIRED = ("genre", "language", "vocal", "instrument")
VOCAB_FIELDS = ("genre", "language", "mood", "instrument", "vocal", "flow")
# 검색 근거로 쓰지 않는 악기 이름 — demucs 스템 넷 중 `other`. code_name 과 display_name 을
# 둘 다 적는다(코드표가 한국어→영문으로 바뀌는 중. `recommender._NONSPECIFIC_INSTRUMENTS` 와 같다).
NONSPECIFIC_INSTRUMENTS = frozenset({"other", "그 외 악기", "그외 악기"})
_MISSING = frozenset({"", "unknown", "nan", "none", "null", "<na>", "nat"})
_GENDER_WORD = {"M": "male", "F": "female", "MIX": "mixed"}
_UP_TEMPO, _DOWN_TEMPO = frozenset({"up", "faster", "fast"}), frozenset({"down", "slower", "slow"})
_UP_ENERGY, _DOWN_ENERGY = frozenset({"up", "higher", "energetic"}), frozenset({"down", "lower", "calm"})
CACHE_MAX = 64


# ------------------------------------------------------------------ 값 규칙 (`CatalogFacts`)
def text(v) -> str:
    """누락 표기를 어휘로 노출하지 않는다."""
    if v is None:
        return ""
    s = str(v).strip()
    return "" if s.lower() in _MISSING else s


def qvals(v) -> list:
    """비교용 정규화. 소문자, `-`·공백은 `_`. 값 하나든 목록이든 받는다."""
    if v is None:
        return []
    items = v if isinstance(v, (list, tuple, set, frozenset)) else [v]
    out = []
    for item in items:
        if item is None:
            continue
        s = str(item).strip()
        if s:
            out.append(s.lower().replace("-", "_").replace(" ", "_"))
    return out


def qmatch(values, wanted) -> bool:
    """`_` 경계 부분 일치 — `rock` 은 `alternative_rock` 에 맞는다."""
    have = qvals(values)
    for w in qvals(wanted):
        for h in have:
            if w == h or f"_{w}_" in f"_{h}_":
                return True
    return False


def vocal_values(has_vocal, vocal_gender) -> list:
    """실제 두 컬럼을 검색 어휘로 투영한다. 모르면 빈 목록이다."""
    if has_vocal is None:
        return []
    if not has_vocal:
        return ["instrumental"]
    gender = _GENDER_WORD.get(vocal_gender) if vocal_gender is not None else None
    return ["voice"] if gender is None else ["voice", gender]


def parse_style(category: str, vocal: list, language: str) -> str:
    """category 에서 보컬·언어 접두어를 떼어 스타일만 남긴다(`female_korean_ballad` → `ballad`)."""
    parts = [p for p in category.split("_") if p]
    if parts and parts[0] in vocal:
        parts = parts[1:]
    if parts and parts[0] == language:
        parts = parts[1:]
    return "_".join(parts)


def facet_bpm(bpm, lo, hi) -> float:
    """조건에 쓰는 BPM. 측정 BPM 은 옥타브 오류가 잦아서, 생성 때 요청한 범위가 있으면 그
    중간값을 쓴다. 둘 다 없으면 0 이다."""
    if lo is not None and hi is not None and lo > 0:
        return (int(lo) + int(hi)) / 2.0
    return 0.0 if bpm is None else float(bpm)


def specific_instruments(names) -> list:
    out = []
    for name in names or ():
        v = text(name)
        if v and v.lower() not in NONSPECIFIC_INSTRUMENTS and v not in out:
            out.append(v)
    return out


def _split(v) -> list:
    """스냅샷의 장르·무드(쉼표로 이은 문자열)를 목록으로. 값마다 `text` 를 건다."""
    return [t for x in str(v or "").split(",") if (t := text(x))]


# ------------------------------------------------------------------ 스냅샷 파생값
@dataclass
class TrackFacet:
    genres: list
    style: str
    language: str
    vocal: list
    moods: list
    instruments: list
    bpm: float


class Facts:
    """스냅샷 하나의 곡·구간 파생값. **스냅샷마다 한 번** 만든다(`facts_of`)."""

    def __init__(self, snap, instruments: dict) -> None:
        self.uids = list(snap.sections)
        uid_tracks = [str(snap.sections[u].get("track_id")) for u in self.uids]
        self.track_ids = list(snap.tracks)
        self.tpos = {t: i for i, t in enumerate(self.track_ids)}
        self.sec_track = np.asarray([self.tpos.get(t, -1) for t in uid_tracks], dtype=np.int64)
        self.labels = [str(snap.sections[u].get("section_label") or "").lower() for u in self.uids]
        self.energy = np.asarray([np.nan if snap.sections[u].get("energy") is None
                                  else float(snap.sections[u]["energy"]) for u in self.uids],
                                 dtype=np.float64)
        self.own_instruments = [tuple(specific_instruments(instruments.get(u, ())))
                                for u in self.uids]
        union: dict = {}
        for t, own in zip(uid_tracks, self.own_instruments):
            bucket = union.setdefault(t, [])
            for name in own:
                if name not in bucket:
                    bucket.append(name)
        self.by_track: dict = {}
        for t in self.track_ids:
            r = snap.tracks[t]
            vocal = vocal_values(r.get("has_vocal"), r.get("vocal_gender"))
            language = text(r.get("language"))
            self.by_track[t] = TrackFacet(
                genres=_split(r.get("genre_code")),
                style=parse_style(text(r.get("category")), vocal, language),
                language=language, vocal=vocal, moods=_split(r.get("mood_codes")),
                instruments=union.get(t, []),
                bpm=facet_bpm(r.get("bpm"), r.get("bpm_lo"), r.get("bpm_hi")))
        facets = [self.by_track[t] for t in self.track_ids]
        # 곡 BPM 을 구간에 펼친 것. 곡이 없는 구간(-1)은 0 이다.
        tb = np.asarray([f.bpm for f in facets] + [0.0], dtype=np.float64)
        self.sec_bpm = tb[self.sec_track]
        self.vocab = self._vocabulary(facets)
        self.available = {"instrument": any(f.instruments for f in facets),
                          "vocal": any(f.vocal for f in facets)}
        self._cache: "OrderedDict[str, Outcome]" = OrderedDict()
        self._lock = threading.Lock()

    def _vocabulary(self, facets) -> dict:
        """이 풀에 실제로 있는 값. 장르 칸은 장르 코드와 스타일을 함께 받는다."""
        genre, language, mood, instrument, vocal, flow = ({} for _ in range(6))
        for f in facets:
            for v in qvals(f.genres) + qvals(f.style):
                genre[v] = None
            for v in qvals(f.language):
                language[v] = None
            for v in qvals(f.moods):
                mood[v] = None
            for v in qvals(f.instruments):
                instrument[v] = None
            for v in f.vocal:
                vocal[v] = None
        for lab in dict.fromkeys(self.labels):
            for v in qvals(lab):
                flow[v] = None
        return {"genre": genre, "language": language, "mood": mood,
                "instrument": instrument, "vocal": vocal, "flow": flow}

    # -------------------------------------------------------------- 값 → 구간 마스크
    def track_mask(self, values: Callable[[TrackFacet], list], wanted: list) -> np.ndarray:
        """곡 단위로 한 번 판정해 구간에 펼친다. 곡을 모르는 구간은 값이 없다(맞지 않음)."""
        hit = np.asarray([qmatch(values(self.by_track[t]), wanted) for t in self.track_ids]
                         + [qmatch([], wanted)], dtype=bool)
        return hit[self.sec_track]

    def instrument_mask(self, wanted: list) -> np.ndarray:
        """구간 악기, 없으면 곡의 구간 합집합. 같은 악기 조합은 한 번만 판정한다."""
        track_hit = np.asarray([qmatch(self.by_track[t].instruments, wanted)
                                for t in self.track_ids] + [False], dtype=bool)
        memo: dict = {}
        out = track_hit[self.sec_track].copy()
        for i, own in enumerate(self.own_instruments):
            if own:
                got = memo.get(own)
                if got is None:
                    got = memo[own] = qmatch(list(own), wanted)
                out[i] = got
        return out

    def label_mask(self, wanted: list) -> np.ndarray:
        memo = {lab: qmatch([lab], wanted) for lab in dict.fromkeys(self.labels)}
        return np.asarray([memo[lab] for lab in self.labels], dtype=bool)

    def reference_energy(self, current_track: Optional[str], current_section: Optional[str]) -> float:
        """기준 구간의 에너지. 없으면 기준 곡 구간들의 평균, 그것도 없으면 0."""
        if current_section is not None:
            try:
                i = self.uids.index(current_section)
            except ValueError:
                i = -1
            if i >= 0 and not np.isnan(self.energy[i]):
                return float(self.energy[i])
        if current_track is None:
            return 0.0
        t = self.tpos.get(current_track)
        if t is None:
            return 0.0
        e = self.energy[(self.sec_track == t) & ~np.isnan(self.energy)]
        return float(e.mean()) if len(e) else 0.0


@dataclass
class Unsatisfiable:
    field: str
    requested: list


@dataclass
class Outcome:
    """`kept` 는 통과한 구간 ID(스냅샷 순서). 비었으면 추천을 계산하지 않는다.

    `unsatisfiable` 는 어휘에 없어 통째로 사라진 필수 조건, `applied` 는 실제로 건 조건
    (`filters.genre` 처럼), `ignored` 는 판정할 수 없어 무시한 조건과 그 이유다.
    """

    kept: list
    unsatisfiable: list = field(default_factory=list)
    applied: list = field(default_factory=list)
    ignored: list = field(default_factory=list)

    @property
    def satisfiable(self) -> bool:
        return not self.unsatisfiable

    def summary(self) -> dict:
        return {"kept": len(self.kept), "applied": list(self.applied),
                "ignored": list(self.ignored),
                "unsatisfiable": [{"field": u.field, "requested": list(u.requested)}
                                  for u in self.unsatisfiable]}


def _section(plan, key: str) -> dict:
    v = plan.get(key) if isinstance(plan, dict) else None
    return v if isinstance(v, dict) else {}


def _number(v) -> Optional[float]:
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        return float(v)
    if isinstance(v, str) and v.strip():
        try:
            return float(v.strip())
        except ValueError:
            return None
    return None


def _lower(v) -> str:
    return "" if v is None else str(v).strip().lower()


def _canonical(raw: dict, vocab: dict) -> dict:
    """어휘에 있는 값만 남긴다. **어휘에 있는 값은 건드리지 않는다**(별칭으로 바꾸지 않는다)."""
    out = {}
    for name, values in vocab.items():
        kept = []
        for v in qvals(raw.get(name)):
            if v in values and v not in kept:
                kept.append(v)
        out[name] = kept
    return out


def apply(facts: Facts, plan: dict, current_track: Optional[str] = None,
          current_section: Optional[str] = None) -> Outcome:
    """조건을 푼다. 같은 스냅샷·같은 조건이면 같은 결과를 재사용한다."""
    key = json.dumps([plan, current_track, current_section], sort_keys=True,
                     ensure_ascii=False, default=str)
    with facts._lock:
        hit = facts._cache.get(key)
        if hit is not None:
            facts._cache.move_to_end(key)
            return hit
    out = _apply(facts, plan, current_track, current_section)
    with facts._lock:
        facts._cache[key] = out
        while len(facts._cache) > CACHE_MAX:
            facts._cache.popitem(last=False)
    return out


def _apply(facts: Facts, plan: dict, current_track: Optional[str],
           current_section: Optional[str]) -> Outcome:
    raw_f = _section(plan, "filters")
    raw_ex = _section(plan, "exclude")
    f = _canonical(raw_f, facts.vocab)
    ex = _canonical(raw_ex, facts.vocab)

    ignored: list = []
    for name in ("instrument", "vocal"):
        if not facts.available[name] and (qvals(raw_f.get(name)) or qvals(raw_ex.get(name))):
            ignored.append(f"{name}(카탈로그 데이터 없음)")
            f[name] = []
            ex[name] = []

    # 사전에 없어 통째로 사라진 필수 조건. 조용히 넘기면 조건을 푼 것이 된다.
    unsat = [Unsatisfiable(name, qvals(raw_f.get(name))) for name in REQUIRED
             if qvals(raw_f.get(name)) and facts.available.get(name, True) and not f[name]]
    if unsat:
        return Outcome([], unsat, [], ignored)

    keep = np.ones(len(facts.uids), dtype=bool)
    applied: list = []

    def genre_values(t: TrackFacet) -> list:
        return [*t.genres, t.style]

    def gate(name: str, wanted: list, mask_of: Callable[[list], np.ndarray], negate: bool) -> None:
        nonlocal keep
        if not wanted:
            return
        hit = mask_of(wanted)
        keep &= ~hit if negate else hit
        applied.append(name)

    by = facts.track_mask
    gate("filters.genre", f["genre"], lambda w: by(genre_values, w), False)
    gate("filters.language", f["language"], lambda w: by(lambda t: [t.language], w), False)
    gate("filters.instrument", f["instrument"], facts.instrument_mask, False)
    gate("filters.vocal", f["vocal"], lambda w: by(lambda t: t.vocal, w), False)
    gate("exclude.genre", ex["genre"], lambda w: by(genre_values, w), True)
    gate("exclude.language", ex["language"], lambda w: by(lambda t: [t.language], w), True)
    gate("exclude.mood", ex["mood"], lambda w: by(lambda t: t.moods, w), True)
    gate("exclude.instrument", ex["instrument"], facts.instrument_mask, True)
    gate("exclude.vocal", ex["vocal"], lambda w: by(lambda t: t.vocal, w), True)
    if f["flow"]:
        # 구간형은 하나만 고른다 — 예전 계약과 같다.
        gate("filters.flow", [f["flow"][0]], facts.label_mask, False)
    gate("exclude.flow", ex["flow"], facts.label_mask, True)

    bpm_min, bpm_max = _number(raw_f.get("bpm_min")), _number(raw_f.get("bpm_max"))
    if bpm_min is not None:
        keep &= facts.sec_bpm >= bpm_min
        applied.append("filters.bpm_min")
    if bpm_max is not None:
        keep &= facts.sec_bpm <= bpm_max
        applied.append("filters.bpm_max")

    tempo = _lower(raw_f.get("tempo_direction"))
    ref = facts.by_track.get(current_track) if current_track is not None else None
    ref_bpm = ref.bpm if ref is not None else 0.0
    if tempo in _UP_TEMPO and ref_bpm > 0:
        keep &= facts.sec_bpm > ref_bpm
        applied.append("filters.tempo_direction")
    elif tempo in _DOWN_TEMPO and ref_bpm > 0:
        keep &= (facts.sec_bpm > 0) & (facts.sec_bpm < ref_bpm)
        applied.append("filters.tempo_direction")
    elif tempo:
        ignored.append("filters.tempo_direction(기준곡 없음)")

    energy = _lower(raw_f.get("energy_direction"))
    ref_energy = facts.reference_energy(current_track, current_section)
    known = ~np.isnan(facts.energy)
    if energy in _UP_ENERGY and ref_energy > 0:
        keep &= known & (np.nan_to_num(facts.energy, nan=0.0) > ref_energy)
        applied.append("filters.energy_direction")
    elif energy in _DOWN_ENERGY and ref_energy > 0:
        keep &= known & (np.nan_to_num(facts.energy, nan=0.0) < ref_energy)
        applied.append("filters.energy_direction")
    elif energy:
        ignored.append("filters.energy_direction(기준곡 없음)")

    for name in ("activity", "goal"):
        if qvals(raw_f.get(name)) or qvals(raw_ex.get(name)):
            ignored.append(f"{name}(카탈로그 필드 없음)")

    kept = [facts.uids[i] for i in np.flatnonzero(keep)]
    return Outcome(kept, [], applied, ignored)


# ------------------------------------------------------------------ 스냅샷마다 한 번
_FACTS: "OrderedDict[int, Facts]" = OrderedDict()
_FACTS_LOCK = threading.Lock()


def facts_of(pool: Any) -> Facts:
    """`pool_snapshot.PoolSnapshot` 의 파생값. 스냅샷 객체마다 한 번 만들고 둘까지만 든다
    (갱신 직후 옛 스냅샷으로 도는 요청이 남아 있을 수 있다)."""
    key = id(pool.snap)
    with _FACTS_LOCK:
        hit = _FACTS.get(key)
        if hit is not None and hit._snap is pool.snap:
            return hit
    got = Facts(pool.snap, pool.instruments)
    got._snap = pool.snap
    with _FACTS_LOCK:
        _FACTS[key] = got
        while len(_FACTS) > 2:
            _FACTS.popitem(last=False)
    return got


__all__ = ["Facts", "Outcome", "Unsatisfiable", "apply", "facts_of", "qvals", "qmatch",
           "vocal_values", "parse_style", "facet_bpm", "specific_instruments", "REQUIRED"]
