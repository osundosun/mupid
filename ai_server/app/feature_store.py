"""서빙 특징 조회 — ClickHouse 피처 저장소를 읽는 **유일한 입구** (S15P21A204-455).

설계 `infra/VECTOR-STORE-DESIGN.md` §6.2 "읽는 법"·§9.3-1·2. 벡터 저장소(`vector_store`)가
"어떤 순간이 닮았나" 를 답하면, 이 모듈은 "그 순간은 어떤 곡의 어느 구간이고 지금 내보내도
되나" 를 답한다. 둘은 나란히 쓴다.

    lookup(section_uids)        후보 수백 개의 곡·구간 특징 → Lookup
    solve(conditions, limit)    조건에 맞는 구간 수와 ID (Qdrant 사전 필터 자리)

**아직 요청 경로에 연결하지 않는다.** 지금 추천은 Spring 이 본문에 싣는 `catalog` 를
읽는다(`catalog_body`). 458 이 그 본문을 `Lookup.body()` 로 바꿔 끼운다 — 그래서
`Lookup.body()` 는 `catalog` 와 **같은 모양·같은 값·같은 순서**다. 값이 하나라도 다르면
본문을 바꿔 끼운 날 순위가 바뀐다 (`tests/test_feature_store.py` 가 묶는다).

    catalog.tracks    track_features_local FINAL — 곡 단위 사본
    catalog.sections  dict_serving_sections      — section_uid 로 바로 찾는 사전

어디서 읽나
    Spring 이 MySQL 과 함께 쓰는 사본이다(원칙 1·3 — AI 는 읽기만 한다). 표 두 개와 사전
    하나가 모두 `mupid_all`(전 노드 복제)이라 붙은 노드 안에서 끝난다. 서빙은
    `MUPID_CLICKHOUSE_SERVING_URL` 로 같은 노드(node-b 의 `clickhouse-0`)에 붙고, 읽기 전용
    서빙 계정(`mupid_serving`, `api/clickhouse/users/20-serving-user.xml`)이 실행 시간·동시성을
    묶는다. 관리자 대시보드 같은 분석 조회와 줄을 나눠 서지 않게 하려는 것이다.

읽는 규칙
    `track_features` 는 ReplacingMergeTree 라 병합 전 옛 행이 남는다 — **FINAL 로 읽는다**
    (설계 §8.2-6). 사전은 원본을 통째로 다시 읽어 만들어지므로 FINAL 이 필요 없다.
    사전의 구간 버전이 지금 곡의 `analysis_version` 과 다르면(재분석 직후 사전이 30초 안에
    따라오기 전) 그 구간은 빼고 센다.

ClickHouse 가 안 닿으면 `FeatureStoreError` 다. 부르는 쪽이 벡터만으로 계속할지 정한다
(설계 결정 "ClickHouse 장애 시 벡터만으로 계속").
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Callable, Iterable, Optional, Sequence

from . import catalog_body, clickhouse

SERVING_ENV = "MUPID_CLICKHOUSE_SERVING"
# 서빙 조회 한 번의 HTTP 제한. 서버 쪽 `max_execution_time`(서빙 프로파일 2초)보다 조금 길다 —
# 서버가 먼저 끊고 이유를 돌려주게 한다.
TIMEOUT = 3.0

# `catalog.tracks`·`catalog.sections` 의 열 순서(`catalog_body.TRACK_COLS`·`SECTION_COLS`).
TRACK_COLS = catalog_body.TRACK_COLS
SECTION_COLS = catalog_body.SECTION_COLS

_FEATURE_COLS = ("track_id", "track", "duration_ms", "bpm", "bpm_requested_min",
                 "bpm_requested_max", "language", "category", "genres", "moods",
                 "has_vocal", "vocal_gender", "is_deleted", "playable", "analysis_version")
_SECTION_ATTRS = ("track_id", "section_label", "start_ms", "end_ms", "energy",
                  "analysis_version")


class FeatureStoreError(Exception):
    """특징 저장소를 읽지 못했다."""


def _lit(value: str) -> str:
    """ClickHouse 문자열 리터럴. 역슬래시를 먼저 늘린다 — 따옴표를 먼저 하면 그때 넣은
    역슬래시까지 다시 늘어난다 (`TrackSectionRepository.quote` 와 같은 규칙)."""
    return "'" + str(value).replace("\\", "\\\\").replace("'", "\\'") + "'"


def _array(values: Iterable[str]) -> str:
    return "[" + ",".join(_lit(v) for v in values) + "]"


@dataclass(frozen=True)
class Conditions:
    """조건 풀기의 입력. 빈 칸은 조건이 없다는 뜻이다.

    장르·무드는 **하나라도** 맞으면(`hasAny`), 언어·보컬 성별·구간 라벨은 목록 안에 있으면
    통과다. 어휘 맞춤(요청 값 → 카탈로그 어휘)은 여기서 하지 않는다 — 넘어온 값 그대로 건다.
    삭제된 곡·재생할 수 없는 곡은 기본으로 뺀다.
    """

    genres: tuple = ()
    moods: tuple = ()
    languages: tuple = ()
    has_vocal: Optional[bool] = None
    vocal_genders: tuple = ()
    section_labels: tuple = ()
    include_deleted: bool = False
    include_unplayable: bool = False

    def track_where(self) -> str:
        out = []
        if not self.include_deleted:
            out.append("NOT is_deleted")
        if not self.include_unplayable:
            out.append("playable")
        if self.genres:
            out.append(f"hasAny(genres, {_array(self.genres)})")
        if self.moods:
            out.append(f"hasAny(moods, {_array(self.moods)})")
        if self.languages:
            out.append(f"language IN {_tuple(self.languages)}")
        if self.has_vocal is not None:
            out.append(f"has_vocal = {'true' if self.has_vocal else 'false'}")
        if self.vocal_genders:
            out.append(f"vocal_gender IN {_tuple(self.vocal_genders)}")
        return " AND ".join(out) or "1"

    def section_where(self) -> str:
        if self.section_labels:
            return f"s.section_label IN {_tuple(self.section_labels)}"
        return "1"


def _tuple(values: Iterable[str]) -> str:
    return "(" + ",".join(_lit(v) for v in values) + ")"


@dataclass
class Solved:
    """조건 풀기 결과. `ids` 는 정렬돼 있고 `limit` 개에서 잘린다 — `count` 는 자르기 전이다.
    `count > len(ids)` 면 사전 필터로 쓰기에 너무 많다는 뜻이다(설계 §9.3-1, 미결 2)."""

    count: int
    ids: list


@dataclass
class Lookup:
    """후보 구간들의 특징. `tracks` 는 곡 번호순, `sections` 는 곡 번호·시작 시각순이다 —
    Spring `RecommenderCatalogServiceImpl.build` 가 본문을 싣는 순서와 같다.

    `tracks`·`sections` 는 내보내도 되는 것만 담는다. 나머지는 이유별로 센다.

        missing    사전에 없다 (모르는 ID, 곡 특징 행이 아직 없는 곡)
        dropped    삭제됐거나 재생할 수 없는 곡의 구간 (설계 §9.3-3)
        stale      구간 버전이 곡의 지금 분석 버전과 다르다 (재분석 직후)
    """

    tracks: list = field(default_factory=list)
    sections: list = field(default_factory=list)
    missing: list = field(default_factory=list)
    dropped: list = field(default_factory=list)
    stale: list = field(default_factory=list)

    def body(self) -> dict:
        """`catalog` 본문과 같은 모양. `catalog_body.from_body` 가 그대로 받는다."""
        tracks = {c: [t[c] for t in self.tracks] for c in TRACK_COLS}
        sections = {c: [s[c] for s in self.sections] for c in SECTION_COLS}
        return {"tracks": tracks, "sections": sections}

    def snapshot(self) -> catalog_body.Snapshot:
        """추천 계산부가 읽는 스냅샷. 본문을 거쳐 만든다 — 값을 옮기는 규칙이 한 벌이다."""
        return catalog_body.from_body(self.body())


def assemble(section_rows: Iterable[dict], track_rows: Iterable[dict],
             asked: Sequence[str]) -> Lookup:
    """ClickHouse 행 → `Lookup`. SQL 없이 판단만 한다 — 시험이 이 길을 직접 지난다.

    `section_rows` 는 사전에서 찾은 구간(`section_uid` + `_SECTION_ATTRS`), `track_rows` 는 그
    곡들의 `track_features` 행이다. 행의 값은 **ClickHouse 가 JSON 으로 내준 그대로** 옮긴다 —
    Spring 이 본문에 싣는 값과 같은 것을 같은 형으로 쓰므로 고칠 것이 없다.
    """
    by_track = {int(r["track_id"]): r for r in track_rows}
    found: dict = {}
    for r in section_rows:
        found[str(r["section_uid"])] = r

    out = Lookup()
    kept = []
    for uid in dict.fromkeys(str(u) for u in asked):
        r = found.get(uid)
        if r is None:
            out.missing.append(uid)
            continue
        t = by_track.get(int(r["track_id"]))
        if t is None:
            out.missing.append(uid)
            continue
        if t["is_deleted"] or not t["playable"]:
            out.dropped.append(uid)
            continue
        if str(r["analysis_version"]) != str(t["analysis_version"]):
            out.stale.append(uid)
            continue
        kept.append((int(r["track_id"]), int(r["start_ms"]), uid, r))

    kept.sort(key=lambda x: (x[0], x[1]))
    used: list = []
    for tid, _, uid, r in kept:
        if not used or used[-1] != tid:
            used.append(tid)
        out.sections.append({
            "external_id": uid,
            "section_label": r["section_label"],
            "start_ms": int(r["start_ms"]),
            "end_ms": int(r["end_ms"]),
            "energy": r["energy"],
        })
    for tid in used:
        t = by_track[tid]
        out.tracks.append({
            "track": t["track"],
            "duration_ms": t["duration_ms"],
            "bpm": t["bpm"],
            "bpm_requested_min": t["bpm_requested_min"],
            "bpm_requested_max": t["bpm_requested_max"],
            "language": t["language"],
            "category": t["category"],
            "genres": list(t["genres"] or []),
            "moods": list(t["moods"] or []),
            "has_vocal": t["has_vocal"],
            "vocal_gender": t["vocal_gender"],
        })
    return out


class FeatureStore:
    """인터페이스. 구현은 ClickHouse 하나다 — 앞단 캐시가 필요해지면(설계 §6.2 "지연은 먼저
    잰다") 같은 자리에 감싼다."""

    backend = "abstract"

    def lookup(self, section_uids: Sequence[str]) -> Lookup:
        raise NotImplementedError

    def solve(self, conditions: Conditions, limit: int = 10_000) -> Solved:
        raise NotImplementedError


Query = Callable[[str], str]


class ClickHouseFeatureStore(FeatureStore):
    """ClickHouse 구현. 조회 한 번이 HTTP 요청 한 번이다.

    `query` 는 SQL → 응답 문자열이다. 기본은 서빙 계정(`MUPID_CLICKHOUSE_SERVING_*`, 없으면
    `MUPID_CLICKHOUSE_*`)으로 붙는 `clickhouse.query` 이고, 시험·측정 도구가 바꿔 끼운다.
    `database` 는 측정 도구가 합성 데이터를 따로 둔 DB 를 가리킬 때만 바꾼다.
    """

    backend = "clickhouse"

    def __init__(self, query: Optional[Query] = None, database: str = "default") -> None:
        self._query = query or _default_query()
        self.database = database
        self._dict = f"{database}.dict_serving_sections"
        self._features = f"{database}.track_features_local"

    # ------------------------------------------------------------------ 특징 조회

    def lookup_sql(self, section_uids: Sequence[str]) -> str:
        """후보 구간과 그 곡의 특징을 **한 번에** 읽는 문장.

        구간은 사전에서 `dictGet` 으로 하나씩 찾는다(후보 수에 비례, 표를 훑지 않는다).
        곡 특징은 그 곡 번호로만 `FINAL` 을 건다 — 정렬 키가 `track_id` 라 범위가 좁다.
        결과 한 줄은 구간 하나이고 곡 열이 붙어 온다(곡 열 이름 앞에 `f_`). 곡 특징 행이 없는
        구간은 조인에서 빠지고 `missing` 으로 센다.

        `SETTINGS` 절을 쓰지 않는다 — 서빙 계정은 `readonly = 1` 이라 질의에서 설정을 바꾸면
        거절된다(그래야 프로파일의 한도를 질의가 풀지 못한다).
        """
        uids = _array(dict.fromkeys(str(u) for u in section_uids))
        d = _lit(self._dict)
        attrs = "(" + ",".join(_lit(a) for a in _SECTION_ATTRS) + ")"
        picks = ", ".join(f"c.s.{i + 1} AS {a}" for i, a in enumerate(_SECTION_ATTRS))
        fcols = ", ".join(f"f.{c} AS f_{c}" for c in _FEATURE_COLS)
        return f"""
            WITH candidates AS (
                SELECT uid, dictGet({d}, {attrs}, tuple(uid)) AS s
                FROM (SELECT arrayJoin({uids}) AS uid)
                WHERE dictHas({d}, tuple(uid))
            )
            SELECT c.uid AS section_uid, {picks}, {fcols}
            FROM candidates AS c
            INNER JOIN (
                SELECT * FROM {self._features} FINAL
                WHERE track_id IN (SELECT s.1 FROM candidates)
            ) AS f ON f.track_id = c.s.1
            FORMAT JSONEachRow
        """

    def lookup(self, section_uids: Sequence[str]) -> Lookup:
        if not section_uids:
            return Lookup()
        rows = self._rows(self.lookup_sql(section_uids))
        sections, tracks = [], {}
        for r in rows:
            sections.append({a: r[a] for a in ("section_uid", *_SECTION_ATTRS)})
            tracks[int(r["f_track_id"])] = {c: r[f"f_{c}"] for c in _FEATURE_COLS}
        return assemble(sections, tracks.values(), section_uids)

    # ------------------------------------------------------------------ 조건 풀기

    def solve_sql(self, conditions: Conditions, limit: int) -> str:
        """조건에 맞는 구간 수와 앞의 `limit` 개. 곡 조건은 `track_features` 에서, 구간 라벨은
        사전에서 건다. 사전 쪽은 메모리 스캔이고 곡 쪽은 곡 수만큼의 FINAL 이다."""
        return f"""
            SELECT count() AS n,
                   arraySlice(arraySort(groupArray(s.section_uid)), 1, {int(limit)}) AS ids
            FROM dictionary({_lit(self._dict)}) AS s
            INNER JOIN (
                SELECT track_id, analysis_version FROM {self._features} FINAL
                WHERE {conditions.track_where()}
            ) AS f ON f.track_id = s.track_id AND f.analysis_version = s.analysis_version
            WHERE {conditions.section_where()}
            FORMAT JSONEachRow
        """

    def solve(self, conditions: Conditions, limit: int = 10_000) -> Solved:
        rows = self._rows(self.solve_sql(conditions, limit))
        if not rows:
            return Solved(0, [])
        return Solved(int(rows[0]["n"]), [str(x) for x in rows[0]["ids"]])

    # ------------------------------------------------------------------ 접속

    def _rows(self, sql: str) -> list:
        try:
            text = self._query(sql)
        except Exception as e:  # noqa: BLE001 — 접속·HTTP·서버 오류를 한 종류로 돌려준다
            raise FeatureStoreError(f"{type(e).__name__}: {e}") from e
        return [json.loads(line) for line in text.splitlines() if line.strip()]


def _default_query() -> Query:
    env = SERVING_ENV if clickhouse.configured(SERVING_ENV) else clickhouse.ENV

    def run(sql: str) -> str:
        return clickhouse.query(sql, timeout=TIMEOUT, env=env)

    return run


def configured() -> bool:
    return clickhouse.configured(SERVING_ENV) or clickhouse.configured()


def serving_env() -> str:
    """지금 붙을 접속 변수 머리. 진단용."""
    return SERVING_ENV if clickhouse.configured(SERVING_ENV) else clickhouse.ENV


__all__ = ["Conditions", "Lookup", "Solved", "FeatureStore", "ClickHouseFeatureStore",
           "FeatureStoreError", "assemble", "configured", "serving_env"]
