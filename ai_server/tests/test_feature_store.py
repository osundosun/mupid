"""서빙 특징 조회(`app/feature_store.py`)가 요청 본문 `catalog` 와 **같은 값**을 내는가 (S15P21A204-455).

458 이 추천 요청의 본문 `catalog` 를 `Lookup.body()` 로 바꿔 끼운다. 그날 순위가 바뀌지 않으려면
같은 후보에 대해 두 길이 같은 값·같은 순서를 내야 한다. 여기서는 합성 MySQL 한 벌
(`tools/feature_store_synth.py`)에서

    Spring 이 본문에 싣는 catalog         spring_catalog_body
    Spring 이 ClickHouse 에 쓰는 사본 행   feature_rows · section_rows

를 만들고, 뒤의 것을 읽기 모듈에 먹여 앞의 것과 비교한다. ClickHouse 없이 도는 시험은 행을
JSON 으로 한 번 오가게 해 ClickHouse 가 내주는 형(정수로 찍힌 실수 등)을 흉내 낸다.

실제 ClickHouse 로 같은 비교를 하는 시험은 `MUPID_TEST_CLICKHOUSE_HTTP` 가 있을 때만 돈다
(로컬 dev 클러스터에 `mupid_test455` DB 를 만들었다 지운다).
"""
from __future__ import annotations

import json
import os
import random
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT))

from ai_server.app import catalog_body, feature_store as fs  # noqa: E402
from ai_server.tools import feature_store_synth as synth  # noqa: E402

N_TRACKS = 400


@pytest.fixture(scope="module")
def db():
    return synth.synth(N_TRACKS, seed=7)


def _json(rows):
    """ClickHouse JSONEachRow 를 오간 모양 — 120.0 이 120 으로 온다."""
    return [json.loads(json.dumps(r).replace(".0,", ",").replace(".0}", "}")) for r in rows]


def _dict_rows(db):
    """`dict_serving_sections` 가 들고 있는 행 — 특징 행이 있고 버전이 같은 곡의 구간."""
    features = {r["track_id"]: r for r in synth.feature_rows(db)}
    out = []
    for r in synth.section_rows(db):
        f = features.get(r["track_id"])
        if f is None or f["analysis_version"] != r["analysis_version"]:
            continue
        out.append({"section_uid": r["section_uid"], "track_id": r["track_id"],
                    "section_label": r["fn"], "start_ms": r["start_ms"], "end_ms": r["end_ms"],
                    "energy": r["energy"], "analysis_version": r["analysis_version"]})
    return out


def _sample(db, k=300, seed=1):
    rng = random.Random(seed)
    uids = [s.external_id for s in db.sections]
    return rng.sample(uids, k - 3) + ["T999999#0", "nothash", "T999999#1"]


def _assert_same(lookup: fs.Lookup, expected_body: dict):
    assert lookup.body() == expected_body
    got, want = lookup.snapshot(), catalog_body.from_body(expected_body)
    assert list(got.tracks) == list(want.tracks)
    assert list(got.sections) == list(want.sections)
    assert got.tracks == want.tracks
    assert got.sections == want.sections


def test_assemble_matches_catalog_body(db):
    asked = _sample(db)
    sections = [r for r in _json(_dict_rows(db)) if r["section_uid"] in set(asked)]
    tracks = _json(synth.feature_rows(db))
    lookup = fs.assemble(sections, tracks, asked)

    _assert_same(lookup, synth.spring_catalog_body(db, keep=set(asked).__contains__))
    assert {"T999999#0", "nothash", "T999999#1"} <= set(lookup.missing)
    # 삭제·재생 불가 곡의 구간은 본문에 없고 이유별로 센다.
    unservable = {r["track"] for r in synth.feature_rows(db) if r["is_deleted"] or not r["playable"]}
    assert lookup.dropped
    assert all(u.split("#")[0] in unservable for u in lookup.dropped)


def test_whole_catalog_is_the_same(db):
    """후보를 전부 물으면 조건 없는 본문(`RecommenderCatalog.body()`)과 같다."""
    asked = [s.external_id for s in db.sections]
    lookup = fs.assemble(_json(_dict_rows(db)), _json(synth.feature_rows(db)), asked)
    _assert_same(lookup, synth.spring_catalog_body(db))


def test_stale_section_version_is_dropped(db):
    rows = _dict_rows(db)
    target = rows[0]
    target = {**target, "analysis_version": "v0"}
    lookup = fs.assemble([target], synth.feature_rows(db), [target["section_uid"]])
    assert lookup.sections == []
    assert lookup.stale == [target["section_uid"]]


def _fake_join_output(db, asked):
    """`lookup_sql` 한 번의 응답 — 구간 한 줄에 곡 열이 `f_` 로 붙는다(INNER JOIN)."""
    features = {r["track_id"]: r for r in synth.feature_rows(db)}
    lines = []
    for r in _dict_rows(db):
        if r["section_uid"] not in asked:
            continue
        f = features[r["track_id"]]
        row = dict(r)
        row.update({f"f_{c}": f[c] for c in fs._FEATURE_COLS})
        lines.append(json.dumps(row))
    return "\n".join(lines) + "\n"


def test_lookup_parses_clickhouse_output(db):
    asked = _sample(db, seed=3)
    seen = []

    def query(sql):
        seen.append(sql)
        return _fake_join_output(db, set(asked))

    store = fs.ClickHouseFeatureStore(query=query)
    _assert_same(store.lookup(asked), synth.spring_catalog_body(db, keep=set(asked).__contains__))
    assert len(seen) == 1                       # 조회 한 번이 HTTP 한 번이다
    assert "FINAL" in seen[0] and "dictGet" in seen[0]


def test_sql_never_sets_settings_and_escapes_ids():
    """서빙 계정은 readonly=1 이라 SETTINGS 가 있으면 거절된다. 따옴표가 든 ID 도 문장을 깨지 않는다."""
    store = fs.ClickHouseFeatureStore(query=lambda sql: "")
    lookup_sql = store.lookup_sql(["T1#0", "x'); DROP TABLE t; --", "a\\b"])
    solve_sql = store.solve_sql(fs.Conditions(genres=("it's",), section_labels=("CHORUS",),
                                              has_vocal=True), 100)
    for sql in (lookup_sql, solve_sql):
        assert "SETTINGS" not in sql
    assert "'x\\'); DROP TABLE t; --'" in lookup_sql
    assert "'a\\\\b'" in lookup_sql
    assert "hasAny(genres, ['it\\'s'])" in solve_sql
    assert "NOT is_deleted" in solve_sql and "playable" in solve_sql
    assert "has_vocal = true" in solve_sql
    assert "s.section_label IN ('CHORUS')" in solve_sql


def test_conditions_can_include_deleted_and_unplayable():
    where = fs.Conditions(include_deleted=True, include_unplayable=True).track_where()
    assert where == "1"


def test_errors_are_one_kind():
    def boom(sql):
        raise OSError("connection refused")

    store = fs.ClickHouseFeatureStore(query=boom)
    with pytest.raises(fs.FeatureStoreError, match="connection refused"):
        store.lookup(["T1#0"])
    assert store.lookup([]).sections == []      # 물을 것이 없으면 부르지 않는다


def test_default_connection_prefers_the_serving_account(monkeypatch):
    monkeypatch.setenv("MUPID_CLICKHOUSE_URL", "http://clickhouse:8123")
    monkeypatch.delenv("MUPID_CLICKHOUSE_SERVING_URL", raising=False)
    assert fs.serving_env() == "MUPID_CLICKHOUSE"
    monkeypatch.setenv("MUPID_CLICKHOUSE_SERVING_URL", "http://clickhouse-0.clickhouse:8123")
    assert fs.serving_env() == "MUPID_CLICKHOUSE_SERVING"
    assert fs.configured()


# --------------------------------------------------------------------- 실제 ClickHouse

LIVE = os.environ.get("MUPID_TEST_CLICKHOUSE_HTTP", "").strip()


@pytest.mark.skipif(not LIVE, reason="MUPID_TEST_CLICKHOUSE_HTTP 가 있을 때만 (로컬 dev 클러스터)")
def test_live_clickhouse_matches_catalog(db):
    from ai_server.tools import bench_feature_store as bench

    password = os.environ.get("MUPID_TEST_CLICKHOUSE_PASSWORD") or os.environ.get(
        "MUPID_CLICKHOUSE_PASSWORD", "")
    name = "mupid_test455"
    http = bench.Http(LIVE, "mupid", password, name)
    try:
        bench.setup(http, name, db)
        store = fs.ClickHouseFeatureStore(query=http, database=name)

        asked = _sample(db, seed=5)
        _assert_same(store.lookup(asked), synth.spring_catalog_body(db, keep=set(asked).__contains__))
        everything = [s.external_id for s in db.sections]
        _assert_same(store.lookup(everything), synth.spring_catalog_body(db))

        # 조건 풀기 — 같은 조건을 파이썬으로 센 값과 같다.
        body = synth.spring_catalog_body(db)
        tracks = {t: i for i, t in enumerate(body["tracks"]["track"])}
        cond = fs.Conditions(genres=("hip_hop", "edm"), section_labels=("CHORUS", "BRIDGE"))
        want = sorted(
            uid for uid, label in zip(body["sections"]["external_id"], body["sections"]["section_label"])
            if label in cond.section_labels
            and set(body["tracks"]["genres"][tracks[uid.split("#")[0]]]) & set(cond.genres))
        solved = store.solve(cond, limit=10_000)
        assert solved.count == len(want)
        assert solved.ids == want
        assert store.solve(cond, limit=3).ids == want[:3]
    finally:
        http(f"DROP DATABASE IF EXISTS {name} ON CLUSTER mupid_all SYNC", database="default")
