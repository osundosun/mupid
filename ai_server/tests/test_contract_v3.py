"""연동 계약 v3 — 요청 본문에서 `catalog` 를 뺐다 (S15P21A204-458).

**순위의 의미는 그대로다.** 같은 데이터에서

    예전   Spring 이 MySQL 로 만든 본문 `catalog` → 요청 사본        (09-23 계약의 AI 가 하던 일)
    v3     Spring 이 ClickHouse 에 쓴 사본 → 피처 저장소 → 기준 풀 스냅샷 → 요청 사본

두 길이 같은 추천을 내야 한다. 여기서는 합성 색인 하나에 두 길을 나란히 태운다. 피처 저장소
쪽에는 **지워진 곡과 재생할 수 없는 곡을 그대로 두고**(`is_deleted`·`playable` 로만 표시),
Spring 본문 쪽에서는 뺀다 — 예전에 Spring 이 빼던 것을 이제 스냅샷(`feature_store.assemble`)이
뺀다. 조건 검색은 예전에 Spring `QueryPlanFilter` 가 거른 본문과, v3 가 `query_conditions` 로
푼 결과를 견준다.

옛 Spring(본문에 `catalog` 를 싣는다)이 새 AI 를 불러도 결과가 같고, 개수는 예전처럼 `limit`
이다(호환). 새 Spring 에는 `limit` 뒤에 여유분이 붙는다 — 앞의 `limit` 개는 같다.
"""
from __future__ import annotations

import importlib
import json
import os
import re
import sys
from types import SimpleNamespace

import numpy as np
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from ai_server.app import catalog_body, feature_store as fs, internal_api, pool_snapshot
from test_pipeline_synthetic import GENRES, _build, _catalog_body   # noqa: E402

N_TRACKS, PER_TRACK = 40, 4
DELETED, UNPLAYABLE = "T000003", "T000007"
VERSION = "v1"


def _full_body() -> dict:
    """색인의 곡 전부. 구간 에너지를 채운다 — 흐름 감점·에너지 방향 조건이 값을 읽게."""
    body = _catalog_body(n_tracks=N_TRACKS, per_track=PER_TRACK)
    rng = np.random.default_rng(458)
    body["sections"]["energy"] = [round(float(x), 4) for x in
                                  rng.uniform(0.02, 0.4, len(body["sections"]["external_id"]))]
    return body


def _spring_body(keep_track=lambda t: True) -> dict:
    """예전 Spring 이 보내던 본문 — 지워진 곡·재생 불가 곡을 빼고, `keep_track` 을 통과한 곡만."""
    body = _full_body()
    t_idx = [i for i, t in enumerate(body["tracks"]["track"])
             if t not in (DELETED, UNPLAYABLE) and keep_track(t)]
    kept = {body["tracks"]["track"][i] for i in t_idx}
    s_idx = [i for i, u in enumerate(body["sections"]["external_id"]) if u.split("#")[0] in kept]
    return {"tracks": {c: [v[i] for i in t_idx] for c, v in body["tracks"].items()},
            "sections": {c: [v[i] for i in s_idx] for c, v in body["sections"].items()}}


def _music_id(track: str) -> int:
    return 1000 + int(track[1:])


def _clickhouse_rows() -> tuple[list, list]:
    """Spring 이 ClickHouse 에 쓰는 사본(`track_features_local` · `dict_serving_sections`)."""
    body = _full_body()
    tr = body["tracks"]
    features = []
    for i, t in enumerate(tr["track"]):
        features.append({
            "track_id": _music_id(t), "track": t,
            **{c: tr[c][i] for c in catalog_body.TRACK_COLS if c != "track"},
            "is_deleted": t == DELETED, "playable": t != UNPLAYABLE,
            "analysis_version": VERSION})
    sec = body["sections"]
    sections = []
    for i, u in enumerate(sec["external_id"]):
        sections.append({"section_uid": u, "track_id": _music_id(u.split("#")[0]),
                         **{c: sec[c][i] for c in catalog_body.SECTION_COLS if c != "external_id"},
                         "analysis_version": VERSION,
                         "instruments": ["piano" if int(u[1:7]) % 2 == 0 else "guitar"]})
    return sections, features


class FakeClickHouse:
    """`lookup_sql` 한 번에 JSONEachRow 로 답한다(구간 한 줄에 곡 열이 `f_` 로 붙는다)."""

    def __init__(self) -> None:
        self.sections, self.features = _clickhouse_rows()
        self.by_track = {f["track_id"]: f for f in self.features}
        self.calls = 0

    def __call__(self, sql: str) -> str:
        self.calls += 1
        asked = set(re.findall(r"'(T\d{6}#\d+)'", sql))
        lines = []
        for r in self.sections:
            if r["section_uid"] not in asked:
                continue
            f = self.by_track[r["track_id"]]
            lines.append(json.dumps({**r, **{f"f_{c}": f[c] for c in fs._FEATURE_COLS}}))
        return "\n".join(lines) + "\n"


@pytest.fixture(scope="module")
def rc(tmp_path_factory):
    root = tmp_path_factory.mktemp("v3")
    _build(root, n_tracks=N_TRACKS, per_track=PER_TRACK)
    saved = {k: os.environ.get(k) for k in ("MUPID_AI_ARTIFACT_DIR", "MUPID_PINECONE_ENABLED")}
    os.environ["MUPID_AI_ARTIFACT_DIR"] = str(root)
    os.environ.pop("MUPID_PINECONE_ENABLED", None)

    def _reload():
        for name in ("features", "labeler", "stage1_torch"):
            full = f"ai_server.app.{name}"
            if full in sys.modules:
                importlib.reload(sys.modules[full])
        from ai_server.app import recommender as mod
        return importlib.reload(mod)

    mod = _reload()
    mod.CAT = mod.Catalog()           # 기동 직후처럼 후보가 빈 기준 판
    yield mod
    for k, v in saved.items():
        if v is None:
            os.environ.pop(k, None)
        else:
            os.environ[k] = v
    _reload().CAT = None


@pytest.fixture()
def v3(rc, monkeypatch):
    """피처 저장소를 읽는 기준 풀 스냅샷을 두고 앱을 띄운다. 이어 듣기의 탐색 칸은 분 단위 시각을
    시드로 쓰므로 시각을 묶는다 — 두 길을 다른 분에 태우면 탐색 곡이 달라진다."""
    fixed = SimpleNamespace(time=lambda: 1_790_000_000.0,
                            perf_counter=__import__("time").perf_counter)
    monkeypatch.setattr(rc, "time", fixed)
    ch = FakeClickHouse()
    store = fs.ClickHouseFeatureStore(query=ch)

    def pool_ids():
        return rc.index_pool(rc.CAT).moment_id.astype(str).tolist()

    holder = pool_snapshot.Holder(pool_snapshot.feature_store_loader(store, pool_ids), ttl=3600,
                                  on_swap=internal_api.warm(rc))
    prev = pool_snapshot.install(holder)
    internal_api._VIEWS.clear()
    assert holder.refresh(), holder.last_error
    app = FastAPI()
    internal_api.install(app, rc)
    yield SimpleNamespace(client=TestClient(app), holder=holder, ch=ch)
    pool_snapshot.install(prev)
    internal_api._VIEWS.clear()


def _old_ai(rc, body: dict, run):
    """09-23 계약의 AI: 요청 본문을 그대로 사본에 묶고 계산한다."""
    view = rc.CAT.with_catalog(catalog_body.from_body(body))
    with rc.using(view):
        return run()


def _ids(items) -> list:
    return [(i["external_id"], i["rank_order"], round(i["score"], 6)) for i in items]


def _post(v3, path: str, body: dict) -> dict:
    r = v3.client.post(path, json=body)
    assert r.status_code == 200, r.text
    return r.json()


ONBOARDING = {"moods": ["calm"], "genres": [GENRES[0]]}


@pytest.mark.parametrize("onboarding", [None, ONBOARDING], ids=["cold", "onboarding"])
def test_daily_is_the_same_as_the_catalog_body(rc, v3, onboarding):
    req = internal_api.DailyRequest(recommendation_id=1, user_id=7, onboarding=onboarding)

    def run():
        out = rc.recommend("internal:7", 4, set(), True, None, None, None,
                           beh_events=internal_api._behavior_events(7),
                           onboarding=internal_api._onboarding(req, rc), daily=True)
        return internal_api.to_response(1, out, 4, False).model_dump()

    old = _old_ai(rc, _spring_body(), run)
    new = _post(v3, "/internal/recommendations/daily",
                {"recommendation_id": 1, "user_id": 7,
                 **({"onboarding": onboarding} if onboarding else {})})
    assert _ids(new["items"])[:4] == _ids(old["items"])
    assert len(new["items"]) == 4 + internal_api.spare_for(4)
    assert new["shortfall"] == old["shortfall"] == 0
    # 지워진 곡·재생 불가 곡은 스냅샷에 없다 — 여유분에도 나오지 않는다.
    assert not {i["external_id"].split("#")[0] for i in new["items"]} & {DELETED, UNPLAYABLE}


def test_next_is_the_same_as_the_catalog_body(rc, v3):
    cur_t, cur_m = "T000005", "T000005#1"

    def run():
        out = rc.recommend("internal:9", 5, {"T000011"}, True, None, cur_t, cur_m,
                           beh_events=internal_api._behavior_events(9), onboarding=None)
        return internal_api.to_response(2, out, 5, False).model_dump()

    old = _old_ai(rc, _spring_body(), run)
    new = _post(v3, "/internal/recommendations/next",
                {"recommendation_id": 2, "user_id": 9, "limit": 5, "exclude_tracks": ["T000011"],
                 "current_track": cur_t, "current_section": cur_m})
    assert _ids(new["items"])[:5] == _ids(old["items"])
    assert len(new["items"]) == 5 + internal_api.spare_for(5)


def test_search_is_the_same_as_what_spring_filtered(rc, v3):
    """예전 Spring `QueryPlanFilter`: 장르(곡 장르 ∪ 스타일) contemporary_pop, 무드 bright 빼기
    → `t % 3 == 0` 이고 `t % 2 == 0` 인 곡(`t % 6 == 0`)만 본문에 실었다."""
    plan = {"filters": {"genre": [GENRES[0]], "mood": ["calm"]}, "exclude": {"mood": ["bright"]}}

    def run():
        out = rc.recommend("internal:3", 4, set(), True, plan, None, None,
                           beh_events=internal_api._behavior_events(3), onboarding=None)
        return internal_api.to_response(3, out, 4, True).model_dump()

    old = _old_ai(rc, _spring_body(lambda t: int(t[1:]) % 6 == 0), run)
    new = _post(v3, "/internal/recommendations/search",
                {"recommendation_id": 3, "user_id": 3, "limit": 4, "query_plan": plan})
    assert _ids(new["items"])[:4] == _ids(old["items"])
    assert all(int(i["external_id"][1:7]) % 6 == 0 for i in new["items"])


def test_similar_is_the_same_as_the_catalog_body(rc, v3):
    old = _old_ai(rc, _spring_body(), lambda: rc.similar_moments("T000010#2", 5, {"T000012"}))
    new = _post(v3, "/internal/similar",
                {"external_id": "T000010#2", "limit": 5, "exclude_tracks": ["T000012"]})
    assert [(i["external_id"], round(i["similarity"], 6)) for i in new["items"]][:5] == \
        [(r["moment_id"], round(r["similarity"], 6)) for r in old]
    assert len(new["items"]) == 5 + internal_api.spare_for(5)


def test_old_spring_body_gets_the_same_answer_and_the_old_count(rc, v3):
    """옛 Spring 이 본문 `catalog` 를 실어 보낸다 — 받되 쓰지 않는다. 결과는 같고 개수는 `limit`
    이다(옛 Spring 은 받은 것을 전부 저장한다 — 오늘의 모먼트가 6개가 되면 안 된다)."""
    from ai_server.app import metrics

    metrics.reset()
    base = {"recommendation_id": 4, "user_id": 7}
    new = _post(v3, "/internal/recommendations/daily", base)
    # 옛 본문이 틀린 값을 싣고 와도(곡이 하나뿐) 쓰지 않는다.
    legacy = _post(v3, "/internal/recommendations/daily", {**base, "catalog": _spring_body(
        lambda t: t == "T000001")})
    assert _ids(legacy["items"]) == _ids(new["items"])[:4]
    assert len(legacy["items"]) == 4
    plan = {"filters": {"genre": [GENRES[1]]}}
    s_new = _post(v3, "/internal/recommendations/search",
                  {**base, "limit": 3, "query_plan": plan})
    s_old = _post(v3, "/internal/recommendations/search",
                  {**base, "limit": 3, "query_plan": plan,
                   "catalog": _spring_body(lambda t: int(t[1:]) % 3 == 1)})
    assert _ids(s_old["items"]) == _ids(s_new["items"])[:3] and len(s_old["items"]) == 3
    sim = _post(v3, "/internal/similar", {"external_id": "T000010#2", "limit": 2,
                                          "catalog": _spring_body()})
    assert len(sim["items"]) == 2
    assert metrics.snapshot()["legacy_catalog_requests"] == {"daily": 1, "search": 1, "similar": 1}


def test_unsatisfiable_condition_is_an_empty_result_with_a_reason(v3):
    r = v3.client.post("/internal/recommendations/search",
                       json={"recommendation_id": 5, "user_id": 1,
                             "query_plan": {"filters": {"genre": ["없는장르"], "mood": ["calm"]}}})
    assert r.status_code == 404
    assert r.json()["error_code"] == "UNSATISFIABLE_CONDITIONS"
    assert "genre" in r.json()["message"]
    # 어휘에는 있지만 맞는 구간이 없는 조합은 예전처럼 NO_CANDIDATES 다.
    r = v3.client.post("/internal/recommendations/search",
                       json={"recommendation_id": 6, "user_id": 1,
                             "query_plan": {"filters": {"genre": [GENRES[0]], "bpm_min": 1000}}})
    assert r.status_code == 404 and r.json()["error_code"] == "NO_CANDIDATES"


def test_instrument_condition_reads_the_feature_store(v3):
    """악기 조건은 피처 저장소의 구간 악기(`track_sections.instruments`)로 푼다."""
    got = _post(v3, "/internal/recommendations/search",
                {"recommendation_id": 7, "user_id": 1, "limit": 3,
                 "query_plan": {"filters": {"instrument": ["guitar"]}}})
    assert got["items"] and all(int(i["external_id"][1:7]) % 2 == 1 for i in got["items"])


def test_request_bodies_are_small_and_the_snapshot_is_read_off_the_request_path(v3):
    """v3 본문은 수백 B 이고, 요청은 피처 저장소를 부르지 않는다(갱신만 부른다)."""
    calls = v3.ch.calls
    bodies = {
        "/internal/recommendations/daily": {"recommendation_id": 8, "user_id": 7,
                                            "onboarding": ONBOARDING},
        "/internal/recommendations/next": {"recommendation_id": 9, "user_id": 7, "limit": 5,
                                           "exclude_tracks": [f"T{i:06d}" for i in range(200)][:30],
                                           "current_track": "T000005",
                                           "current_section": "T000005#1"},
        "/internal/recommendations/search": {"recommendation_id": 10, "user_id": 7, "limit": 3,
                                             "query_plan": {"filters": {"genre": [GENRES[0]]}}},
        "/internal/similar": {"external_id": "T000010#2", "limit": 5},
    }
    for path, body in bodies.items():
        assert len(json.dumps(body).encode()) < 5 * 1024
        assert v3.client.post(path, json=body).status_code == 200
    assert v3.ch.calls == calls


def test_facets_fast_path_equals_track_info(rc, v3):
    """조건 어휘(`catalog_facets`)가 곡마다 `track_info()` 를 부르지 않게 바꿨다 — 값은 같다."""
    view = internal_api._pool(rc)
    with rc.using(internal_api._view(rc, view)):
        c = rc.current()
        df = rc.catalog_facets()["_df"].set_index("track_id")
        for t in sorted(c.by_track):
            ti = c.track_info(t)
            row = df.loc[t]
            assert row.language == ti["language"] and row.genre == ti["genre"]
            assert row.has_vocal == ti["has_vocal"] and row.vocal_gender == ti["vocal_gender"]
            assert list(row.moods) == ti["moods"]
            assert row.bpm_measured == float(ti["bpm"] or 0.0)
