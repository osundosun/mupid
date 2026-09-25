"""서빙 특징 조회 — ClickHouse 피처 저장소를 읽는 **유일한 입구** (S15P21A204-455).

설계 `infra/VECTOR-STORE-DESIGN.md` §6.2 "읽는 법"·§9.3-1·2. 벡터 저장소(`vector_store`)가
"어떤 순간이 닮았나" 를 답하면, 이 모듈은 "그 순간은 어떤 곡의 어느 구간이고 지금 내보내도
되나" 를 답한다. 둘은 나란히 쓴다.

    lookup(section_uids)        후보 수백 개의 곡·구간 특징 → Lookup
    lookup_many(section_uids)   색인 풀 전체를 조각으로 나눠 읽는다 → Lookup (기준 풀 스냅샷)
    solve(conditions, limit)    조건에 맞는 구간 수와 ID (Qdrant 사전 필터 자리)

추천 요청은 이것을 **직접 부르지 않는다**(계약 v3, S15P21A204-458). `pool_snapshot` 이
백그라운드에서 `lookup_many` 로 기준 풀 스냅샷을 만들어 두고, 요청은 그 스냅샷만 본다.
`Lookup.body()` 는 예전 요청 본문 `catalog` 와 **같은 모양·같은 값·같은 순서**다 — 본문을
이것으로 바꿔 끼워도 순위가 바뀌지 않는다는 근거다 (`tests/test_feature_store.py` 가 묶는다).

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
from .log import log

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
                  "analysis_version", "instruments")
# `lookup_many` 한 조각의 구간 수. 서빙 프로파일의 `max_result_rows`(20만)보다 한참 작고,
# 문장(구간 ID 배열 리터럴)이 수백 KB 를 넘지 않게 잡았다.
CHUNK = 20_000


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

    `instruments` 는 구간 → 악기 코드 이름(MySQL 그대로, `other` 도 들어 있다)이다. 본문
    `catalog` 에는 없던 값이라 `body()` 에 싣지 않는다 — 조건 풀기(`query_conditions`)만 읽는다.
    """

    tracks: list = field(default_factory=list)
    sections: list = field(default_factory=list)
    missing: list = field(default_factory=list)
    dropped: list = field(default_factory=list)
    stale: list = field(default_factory=list)
    instruments: dict = field(default_factory=dict)
    versions: dict = field(default_factory=dict)

    def body(self) -> dict:
        """`catalog` 본문과 같은 모양. `catalog_body.from_body` 가 그대로 받는다."""
        tracks = {c: [t[c] for t in self.tracks] for c in TRACK_COLS}
        sections = {c: [s[c] for s in self.sections] for c in SECTION_COLS}
        return {"tracks": tracks, "sections": sections}

    def snapshot(self) -> catalog_body.Snapshot:
        """추천 계산부가 읽는 스냅샷. 본문을 거쳐 만든다 — 값을 옮기는 규칙이 한 벌이다.
        구간 지문(`versions`)은 본문에 없던 값이라 따로 붙인다."""
        snap = catalog_body.from_body(self.body())
        snap.versions = dict(self.versions)
        return snap


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
        # 06 마이그레이션 전의 사전에는 열이 없다 — 빈 목록("악기를 모른다")으로 둔다.
        out.instruments[uid] = [str(x) for x in (r.get("instruments") or [])]
        out.versions[uid] = str(r["analysis_version"])
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

    def lookup_many(self, section_uids: Sequence[str], chunk: int = CHUNK) -> Lookup:
        """많은 ID 를 조각으로 나눠 읽고 **한 번에** 맞춘다. 기본은 조각마다 `lookup` 이다."""
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
        # 사전에서 꺼낼 구간 열. 악기 열(06 마이그레이션)이 아직 없는 사전을 만나면 한 번 빼고
        # 다시 읽는다 — 마이그레이션 전후 어느 쪽에서도 특징 조회 자체가 멈추지 않게.
        self._section_attrs = _SECTION_ATTRS

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
        attrs = "(" + ",".join(_lit(a) for a in self._section_attrs) + ")"
        picks = ", ".join(f"c.s.{i + 1} AS {a}" for i, a in enumerate(self._section_attrs))
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
        return self.lookup_many(section_uids, chunk=max(len(section_uids), 1))

    def lookup_many(self, section_uids: Sequence[str], chunk: int = CHUNK) -> Lookup:
        """조각마다 문장 하나. 조각은 **곡 단위로 자른다** — 한 곡의 구간이 두 조각에 걸치면
        곡 특징 행이 조각마다 한 번씩 와서 합칠 때 겹친다(값은 같지만 셀 이유가 없다).
        맞추기(`assemble`)는 모든 조각을 모은 뒤 한 번 한다 — 정렬·셈이 조각에 따라 갈리지 않는다.
        """
        uids = list(dict.fromkeys(str(u) for u in section_uids))
        if not uids:
            return Lookup()
        sections, tracks = [], {}
        for part in _chunks(uids, chunk):
            for r in self._lookup_rows(part):
                sections.append({a: r.get(a) for a in ("section_uid", *_SECTION_ATTRS)})
                tracks[int(r["f_track_id"])] = {c: r[f"f_{c}"] for c in _FEATURE_COLS}
        return assemble(sections, tracks.values(), uids)

    def _lookup_rows(self, part: list) -> list:
        try:
            return self._rows(self.lookup_sql(part))
        except FeatureStoreError as e:
            if "instruments" not in self._section_attrs or "instruments" not in str(e):
                raise
            self._section_attrs = tuple(a for a in self._section_attrs if a != "instruments")
            log("서빙 사전에 instruments 가 없다 — 악기 없이 읽는다 (06 마이그레이션 전)")
            return self._rows(self.lookup_sql(part))

    # ------------------------------------------------------------------ 바뀌었나
    def change_token_sql(self) -> str:
        """내용이 바뀌었는지 싸게 보는 문장. 곡 특징의 최신 버전·행 수와 서빙 사전이 마지막으로
        다시 읽힌 시각. 사전은 두 표의 활성 파트가 바뀔 때만 다시 읽히므로(INVALIDATE_QUERY) 구간이
        바뀌면 시각이 바뀐다 — 병합만 일어나도 바뀌지만 그때는 한 번 더 읽을 뿐이다."""
        db, name = self.database, "dict_serving_sections"
        return f"""
            SELECT (SELECT concat(toString(max(version)), ':', toString(count()))
                    FROM {self._features} FINAL) AS features,
                   (SELECT toString(max(last_successful_update_time)) FROM system.dictionaries
                    WHERE database = {_lit(db)} AND name = {_lit(name)}) AS sections
            FORMAT JSONEachRow
        """

    def change_token(self) -> str:
        rows = self._rows(self.change_token_sql())
        return json.dumps(rows[0] if rows else {}, sort_keys=True)

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


def _chunks(uids: list, size: int) -> list:
    """곡(`#` 앞)이 바뀌는 자리에서만 자른다. 한 곡이 `size` 보다 크면 그 곡 하나가 한 조각이다."""
    out, cur, last = [], [], None
    for u in uids:
        t = u.split("#", 1)[0]
        if len(cur) >= size and t != last:
            out.append(cur)
            cur = []
        cur.append(u)
        last = t
    if cur:
        out.append(cur)
    return out


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
