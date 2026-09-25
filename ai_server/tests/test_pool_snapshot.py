"""기준 풀 스냅샷(`app/pool_snapshot.py`) — 갱신·실패·벡터 전용 (S15P21A204-458).

    갱신은 원자적으로 바꾼다          읽는 쪽은 옛 것이든 새 것이든 온전한 한 벌을 본다
    갱신이 실패하면 직전 것을 쓴다    나이·연속 실패가 상태·지표에 남는다
    한 번도 못 읽었으면 벡터 전용     추천은 계속되고 조건 검색만 꺼진다(설계 §10.1-5)
    준비 판정은 막지 않는다
"""
from __future__ import annotations

import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_server.app import feature_store as fs, internal_api, metrics, pool_snapshot as ps
from test_contract_v3 import FakeClickHouse, rc  # noqa: F401 — 합성 색인 fixture 를 함께 쓴다

BODY = {"tracks": {"track": ["A", "B"], "genres": [["pop"], ["rock"]]},
        "sections": {"external_id": ["A#0", "B#0"], "section_label": ["CHORUS", "VERSE"],
                     "start_ms": [0, 0], "end_ms": [20_000, 20_000]}}


def _body(bpm: float) -> dict:
    """곡 값 하나(BPM)만 다른 본문 — 내용이 바뀐 갱신."""
    return {**BODY, "tracks": {**BODY["tracks"], "bpm": [bpm, 100.0]}}


@pytest.fixture(autouse=True)
def isolated():
    prev = ps.HOLDER
    ps.install(None)
    internal_api._VIEWS.clear()
    internal_api._VECTOR_ONLY.clear()
    metrics.reset()
    yield
    ps.install(prev)
    internal_api._VIEWS.clear()
    internal_api._VECTOR_ONLY.clear()


def test_a_failed_refresh_keeps_the_previous_snapshot():
    calls = {"n": 0}

    def load():
        calls["n"] += 1
        if calls["n"] == 2:
            raise ConnectionRefusedError("clickhouse-0 down")
        return ps.static(_body(80.0 + calls["n"]))

    h = ps.Holder(load, ttl=60)
    assert h.refresh()
    first = h.current()
    assert first.generation == 1 and first.conditions

    assert not h.refresh()
    assert h.current() is first                  # 직전 스냅샷 그대로
    st = h.status()
    assert st["consecutive_failures"] == 1 and "clickhouse-0 down" in st["last_error"]
    assert st["age_seconds"] is not None and st["tracks"] == 2

    assert h.refresh()
    assert h.current().generation == 2 and h.status()["consecutive_failures"] == 0
    assert metrics.snapshot()["pool_snapshot_refresh"]["ok"] == 2
    assert metrics.snapshot()["pool_snapshot_refresh"]["failed"] == 1


def test_a_snapshot_is_swapped_only_after_it_is_warmed():
    """미리 만들기(`on_swap`)가 실패하면 갈아 끼우지 않는다 — 요청이 반쯤 만든 사본을 만나지 않게."""
    boom = {"on": False}

    def warm(p):
        if boom["on"]:
            raise RuntimeError("사본을 만들지 못했다")

    bpm = iter(range(90, 200))
    h = ps.Holder(lambda: ps.static(_body(float(next(bpm)))), ttl=60, on_swap=warm)
    assert h.refresh()
    first = h.current()
    boom["on"] = True
    assert not h.refresh()
    assert h.current() is first


def test_unchanged_content_is_not_rebuilt():
    """같은 내용이면 사본·풀 색인을 다시 만들지 않는다 — 풀 크기에 비례하는 일이라 곡이 바뀌었을
    때만 치른다. 읽기는 성공했으므로 나이는 새로 센다."""
    warmed = []
    body = {"v": _body(90.0)}
    h = ps.Holder(lambda: ps.static(body["v"]), ttl=60, on_swap=warmed.append)
    assert h.refresh() and h.refresh() and h.refresh()
    assert len(warmed) == 1 and h.current().generation == 1
    assert h.status()["unchanged_refreshes"] == 2 and h.status()["age_seconds"] is not None
    body["v"] = _body(91.0)
    assert h.refresh()
    assert len(warmed) == 2 and h.current().generation == 2
    # 악기만 바뀌어도 새 내용이다(조건 풀기가 읽는다).
    h2 = ps.Holder(lambda: ps.static(BODY, {"A#0": [str(len(warmed))]}), ttl=60,
                   on_swap=warmed.append)
    assert h2.refresh()
    warmed.append("x")
    assert h2.refresh() and h2.current().generation == 2


def test_ttl_is_a_setting(monkeypatch):
    monkeypatch.delenv(ps.TTL_ENV, raising=False)
    assert ps.ttl_seconds() == ps.DEFAULT_TTL == 60
    monkeypatch.setenv(ps.TTL_ENV, "15")
    assert ps.ttl_seconds() == 15
    for bad in ("0", "soon"):
        monkeypatch.setenv(ps.TTL_ENV, bad)
        with pytest.raises(ValueError):
            ps.ttl_seconds()


def test_an_empty_feature_store_is_a_failure_not_an_empty_pool():
    """백필 전(표가 비었다)에 빈 스냅샷으로 갈아 끼우면 추천이 통째로 NO_CANDIDATES 가 된다."""
    store = fs.ClickHouseFeatureStore(query=lambda sql: "")
    h = ps.Holder(ps.feature_store_loader(store, lambda: ["A#0", "B#0"]), ttl=60)
    assert not h.refresh()
    assert "백필" in h.last_error and h.current() is None


def test_chunked_lookup_is_the_same_as_one_lookup():
    """기준 풀은 조각으로 나눠 읽는다(서빙 프로파일 `max_result_rows`). 한 번에 읽은 것과 같다."""
    ch = FakeClickHouse()
    store = fs.ClickHouseFeatureStore(query=ch)
    uids = [r["section_uid"] for r in ch.sections] + ["T999999#0"]
    whole = store.lookup(uids)
    ch.calls = 0
    parts = store.lookup_many(uids, chunk=7)
    assert ch.calls > 1
    assert parts.body() == whole.body()
    assert parts.instruments == whole.instruments
    assert (parts.missing, parts.dropped, parts.stale) == (whole.missing, whole.dropped, whole.stale)
    # 조각은 곡 단위로 자른다 — 한 곡의 구간이 두 조각에 걸치지 않는다.
    chunks = fs._chunks(uids, 7)
    tracks = [{u.split("#")[0] for u in part} for part in chunks]
    assert sum(len(t) for t in tracks) == len(set().union(*tracks))


def test_a_dictionary_without_instruments_still_serves():
    """06 마이그레이션 전의 사전에는 `instruments` 가 없다 — 한 번 빼고 다시 읽는다."""
    ch = FakeClickHouse()

    def old_dictionary(sql):
        if "'instruments'" in sql:
            raise RuntimeError("Code: 36. DB::Exception: No such attribute 'instruments'")
        return ch(sql)

    store = fs.ClickHouseFeatureStore(query=old_dictionary)
    got = store.lookup([ch.sections[0]["section_uid"]])
    assert got.sections and store._section_attrs == tuple(
        a for a in fs._SECTION_ATTRS if a != "instruments")


def _app(rc):  # noqa: F811
    app = FastAPI()
    internal_api.install(app, rc)
    return TestClient(app)


def test_never_loaded_means_vector_only_and_no_condition_search(rc):  # noqa: F811
    """기동 때 ClickHouse 가 죽어 있었다 — 추천은 벡터만으로 계속되고, 조건 검색은 503 이다.
    준비 판정은 막지 않는다."""
    h = ps.Holder(lambda: (_ for _ in ()).throw(ConnectionError("down")), ttl=60,
                  on_swap=internal_api.warm(rc))
    ps.install(h)
    assert not h.refresh()
    client = _app(rc)
    ready = client.get("/internal/ready")
    assert ready.status_code == 200
    snap = ready.json()["pool_snapshot"]
    assert snap["serving"] == ps.SOURCE_VECTOR_ONLY and snap["consecutive_failures"] == 1

    daily = client.post("/internal/recommendations/daily", json={"recommendation_id": 1,
                                                                  "user_id": 7})
    assert daily.status_code == 200, daily.text
    assert len(daily.json()["items"]) >= 4
    search = client.post("/internal/recommendations/search",
                         json={"recommendation_id": 2, "user_id": 7,
                               "query_plan": {"filters": {"genre": ["pop"]}}})
    assert search.status_code == 503
    assert search.json()["error_code"] == "CONDITIONS_UNAVAILABLE"
    assert metrics.snapshot()["conditions"] == {"unavailable": 1}


def test_recovery_turns_condition_search_back_on(rc):  # noqa: F811
    """ClickHouse 가 늦게 살아나면 다음 갱신에서 스냅샷이 생기고 조건 검색이 돌아온다."""
    ch = FakeClickHouse()
    up = {"on": False}

    def query(sql):
        if not up["on"]:
            raise ConnectionError("down")
        return ch(sql)

    def pool_ids():
        return rc.index_pool(rc.CAT).moment_id.astype(str).tolist()

    h = ps.Holder(ps.feature_store_loader(fs.ClickHouseFeatureStore(query=query), pool_ids),
                  ttl=60, on_swap=internal_api.warm(rc))
    ps.install(h)
    assert not h.refresh()
    client = _app(rc)
    body = {"recommendation_id": 3, "user_id": 7,
            "query_plan": {"filters": {"instrument": ["piano"]}}}
    assert client.post("/internal/recommendations/search", json=body).status_code == 503
    up["on"] = True
    assert h.refresh()
    r = client.post("/internal/recommendations/search", json=body)
    assert r.status_code == 200, r.text
    assert json.loads(r.text)["items"]


def test_an_unchanged_probe_skips_the_full_read():
    """저장소가 그대로라는 싼 확인(`change_token`)이면 풀 전체를 읽지 않는다. 확인이 바뀌거나 실패하면,
    그리고 `FULL_EVERY` 번 건너뛴 뒤에는 전부 읽는다."""
    loads, token = [], {"v": "a", "boom": False}

    def probe():
        if token["boom"]:
            raise ConnectionError("down")
        return token["v"]

    def load():
        loads.append(1)
        return ps.static(_body(80.0 + len(loads)))

    h = ps.Holder(load, ttl=60, probe=probe)
    h.FULL_EVERY = 3
    assert h.refresh() and len(loads) == 1
    assert h.refresh() and h.refresh() and len(loads) == 1       # 그대로 — 읽지 않는다
    assert h.status()["age_seconds"] is not None
    token["v"] = "b"
    assert h.refresh() and len(loads) == 2 and h.current().generation == 2
    token["boom"] = True
    assert h.refresh() and len(loads) == 3                         # 확인 실패 → 읽어 본다
    token["boom"] = False
    for _ in range(5):          # 실패 뒤 첫 확인은 새 값이라 읽고, 3번 건너뛰고, 다시 읽는다
        h.refresh()
    assert len(loads) == 5


def test_change_token_reads_the_version_and_the_dictionary_reload():
    seen = []

    def query(sql):
        seen.append(sql)
        return '{"features":"17:1060","sections":"2026-09-25 10:00:00"}\n'

    store = fs.ClickHouseFeatureStore(query=query)
    assert "17:1060" in store.change_token()
    assert "system.dictionaries" in seen[0] and "FINAL" in seen[0] and "SETTINGS" not in seen[0]
