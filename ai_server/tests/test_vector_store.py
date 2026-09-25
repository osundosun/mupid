"""`VectorStore` 로컬 구현과 "요청 경로가 풀 전체를 점수 매기지 않는다" (S15P21A204-452).

`test_pipeline_synthetic.py` 의 카탈로그는 24구간이라 후보 상한(씨앗당 200, 콜드스타트
300)보다 작다 — 좁히든 안 좁히든 같은 결과가 나와 **좁혔는지**를 볼 수 없다. 여기서는
곡을 늘리고 상한을 낮춰 후보 수와 원본 fetch 수로 확인한다.

좁혀서 순위가 바뀔 수 있는 자리는 둘이고, 둘 다 설계(§9.3 "후보 약 300개 → 점수")가
정한 동작이다.

  - 씨앗이 있는 경로: 결합기 특징(규칙 유사도·학습 점수·길이·위치·BPM)이 코사인 상위
    K 밖의 구간을 더 높게 칠 수 있다. qdrant 갈래도 같은 후보 단계를 거친다.
  - 분위 임계값(`P_TOP_FRAC`)·백분위(`pct`)가 풀이 아니라 후보 안에서 센다.

콜드스타트는 순서(에너지 백분위·위치)를 사본 전체로 세므로 뽑히는 곡이 예전과 같다 —
`test_cold_start_picks_the_same_tracks_as_scoring_the_whole_pool` 이 그것을 본다.
"""
from __future__ import annotations

import importlib
import os
import sys

import numpy as np
import pytest

from ai_server.app import catalog_body, vector_store as vstore

from test_pipeline_synthetic import GENRES, _build, _catalog_body   # noqa: E402

N_TRACKS, PER_TRACK = 60, 4


@pytest.fixture(scope="module")
def big(tmp_path_factory):
    root = tmp_path_factory.mktemp("vs")
    _build(root, n_tracks=N_TRACKS, per_track=PER_TRACK)
    saved = {k: os.environ.get(k) for k in ("MUPID_AI_ARTIFACT_DIR", "MUPID_VECTOR_BACKEND")}
    os.environ["MUPID_AI_ARTIFACT_DIR"] = str(root)
    os.environ.pop("MUPID_VECTOR_BACKEND", None)

    def _reload():
        for name in ("features", "labeler", "stage1_torch"):
            full = f"ai_server.app.{name}"
            if full in sys.modules:
                importlib.reload(sys.modules[full])
        from ai_server.app import recommender as mod
        return importlib.reload(mod)

    rc = _reload()
    rc.CAT = rc.Catalog().with_catalog(
        catalog_body.from_body(_catalog_body(n_tracks=N_TRACKS, per_track=PER_TRACK)))
    yield rc

    for k, v in saved.items():
        if v is None:
            os.environ.pop(k, None)
        else:
            os.environ[k] = v
    _reload().CAT = None


def _fresh_view(rc):
    """캐시가 비어 있는 새 사본. 같은 본문이라도 사본을 새로 만들면 사본 캐시가 없다."""
    return rc.CAT.with_catalog(
        catalog_body.from_body(_catalog_body(n_tracks=N_TRACKS, per_track=PER_TRACK)))


def _count_fetch(rc, monkeypatch):
    """저장소 fetch 로 읽은 id 수를 센다. 원본 읽기가 곧 후보 수다."""
    store = rc.vector_store()
    seen: list[int] = []
    real = store.fetch

    def counted(ids):
        seen.append(len(ids))
        return real(ids)

    monkeypatch.setattr(store, "fetch", counted)
    return seen


# ------------------------------------------------------------------ 로컬 구현

def test_local_search_is_the_old_bruteforce(big):
    """예전 `center(Z[pool_rows]) @ q` 와 같은 순서·값이다 (반지름으로 푼 식)."""
    c = big.CAT
    q = big.center(c.Z[[int(c.pool_rows[3])]])[0]
    expect = big.center(np.asarray(c.Z[c.pool_rows], dtype=np.float32)) @ q
    order = np.argsort(-expect)[:10]

    got = big.candidate_search(q, 10)
    assert [m for m, _ in got] == c.pool.moment_id.iloc[order].astype(str).tolist()
    assert np.allclose([s for _, s in got], expect[order], atol=1e-5)


def test_allow_list_restricts_the_search(big):
    """허용 목록 밖은 나오지 않는다. 작은 목록은 그 행만 꺼내 곱하는 길로 간다."""
    store = big.vector_store()
    ids = big.CAT.pool.moment_id.astype(str).tolist()[::17]
    allow = store.allow(ids + ["NOPE#0"])
    assert len(allow) == len(ids), "색인에 없는 id 는 허용 목록에서 빠진다"
    q = big.center(store.fetch([ids[0]])[ids[0]])[0]
    got = store.search(q, 100, allow)
    assert {m for m, _ in got} == set(ids)
    assert got[0][0] == ids[0]
    assert [s for _, s in got] == sorted((s for _, s in got), reverse=True)


def test_search_many_equals_one_by_one(big):
    store = big.vector_store()
    Q = big.center(np.asarray(big.CAT.Z[big.CAT.pool_rows[:3]]))
    many = store.search_many(Q, 7)
    for q, got in zip(Q, many):
        one = store.search(q, 7)
        assert [m for m, _ in got] == [m for m, _ in one]


def test_fetch_returns_raw_rows_and_skips_unknown(big):
    store = big.vector_store()
    mid = str(big.CAT.pool.moment_id.iloc[5])
    row = int(big.CAT.pool.emb_row.iloc[5])
    got = store.fetch([mid, "NOPE#1"])
    assert list(got) == [mid]
    assert np.array_equal(got[mid], np.asarray(big.CAT.Z[row], dtype=np.float32))


def test_mean_generation_and_snapshot(big):
    """μ 는 색인 풀 전체의 평균이고, 지문은 기동 대조(`current_snapshot`)와 같은 값이다."""
    store = big.vector_store()
    pool = big.index_pool(big.CAT)
    expect = np.asarray(big.CAT.Z[pool.emb_row.to_numpy()], dtype=np.float32).mean(0)
    assert np.allclose(store.mu, expect, atol=1e-6)
    assert store.dim == big.CAT.Z.shape[1]
    assert store.generation == big.vector_store().generation, "판이 같으면 세대도 같다"
    assert store.snapshot() == big.current_snapshot()
    assert isinstance(store, vstore.LocalVectorStore) and store.backend == "local"


# ------------------------------------------------------- 풀 전체를 점수 매기지 않는다

def test_cold_start_scores_only_k_candidates(big, monkeypatch):
    """씨앗이 없어도 풀 전체를 점수 매기지 않는다. 9/24 이그레스 소진이 여기서 났다."""
    monkeypatch.setattr(big, "COLD_START_CANDIDATES", 20)
    view = _fresh_view(big)
    with big.using(view):
        fetched = _count_fetch(big, monkeypatch)
        out = big.recommend("internal:cold", 2, set(), False)
    d = out["diagnostics"]
    assert d.get("cold_start") is True
    assert d["vector_search"]["candidates_from"] == "cold_start_order"
    assert d["candidates"] == 20 < len(view.pool)
    assert sum(fetched) == 20, f"원본을 후보보다 많이 읽었다: {fetched}"


def test_cold_start_picks_the_same_tracks_as_scoring_the_whole_pool(big, monkeypatch):
    """콜드스타트 순서는 사본 전체로 센다 — 좁혀도 뽑히는 곡은 풀 전체를 매긴 것과 같다."""
    monkeypatch.setattr(big, "COLD_START_CANDIDATES", 20)
    view = _fresh_view(big)
    with big.using(view):
        out = big.recommend("internal:cold", 3, set(), False)
    pool = view.pool[view.pool.dur_sec >= big.MIN_MOMENT_SEC]
    # 예전 식 그대로 풀 전체에 매긴다.
    s = (pool.rms.rank(pct=True) * 0.5
         + (1.0 - (((pool.rel_start + pool.rel_end) / 2 - 0.4).abs() / 0.6).clip(0, 1)))
    expect = list(dict.fromkeys(pool.track_id.to_numpy()[np.argsort(-s.to_numpy(),
                                                                     kind="stable")]))[:3]
    assert [r["track_id"] for r in out["results"]] == expect


def test_hard_exclusions_do_not_shrink_the_candidates(big, monkeypatch):
    """제외한 곡만큼 더 받아 와서 "제외하지 않은 것 중 상위 K" 가 된다."""
    monkeypatch.setattr(big, "COLD_START_CANDIDATES", 20)
    view = _fresh_view(big)
    with big.using(view):
        first = big.recommend("internal:cold", 3, set(), False)
        gone = {r["track_id"] for r in first["results"]}
        again = big.recommend("internal:cold", 3, gone, False)
    assert again["diagnostics"]["candidates"] == max(20, 3 * 8)   # 탐색 칸이 n·6 위까지 본다
    assert not gone & {r["track_id"] for r in again["results"]}


def _answers(**extra):
    from ai_server.app import onboarding as onb

    ans = onb.from_request({"moods": [], "genres": [GENRES[0]]}, GENRES)
    ans.update(extra)
    return ans


def test_seeded_path_scores_only_what_the_store_returned(big, monkeypatch):
    """씨앗이 있으면 씨앗당 K 개의 합집합만 점수 매긴다. 로컬 갈래도 그렇다."""
    monkeypatch.setattr(big, "DNA_CANDIDATES_PER_SEED", 5)
    view = _fresh_view(big)
    with big.using(view):
        big.onboarding_seeds("internal:seeded", _answers())      # 씨앗 계산의 fetch 는 뺀다
        fetched = _count_fetch(big, monkeypatch)
        out = big.recommend("internal:seeded", 3, set(), False, None, onboarding=_answers())
    d = out["diagnostics"]
    assert d["query_source"] == "onboarding"
    assert d["vector_search"]["candidates_from"] == "seeds"
    assert d["candidates"] <= 5 * d["dna"] < len(view.pool)
    assert sum(fetched) == d["candidates"]


def test_onboarding_gate_is_applied_before_the_search(big, monkeypatch):
    """언어 게이트는 후보 단계에서 건다 — 후보부터 게이트 안이다."""
    monkeypatch.setattr(big, "DNA_CANDIDATES_PER_SEED", 5)
    view = _fresh_view(big)
    ans = _answers(language=["ko"])
    with big.using(view):
        out = big.recommend("internal:gated", 3, set(), False, None, onboarding=ans)
        langs = {big.catalog_facet_rows()[r["track_id"]]["language"] for r in out["results"]}
    d = out["diagnostics"]
    assert out["results"] and langs == {"ko"}
    assert d["dropped_onboarding_filter"] > 0
    assert d["vector_search"]["allowed"] < len(view.pool)


def test_view_level_work_happens_once(big, monkeypatch):
    """풀 전체를 훑는 준비(PoolIndex·게이트·온보딩 씨앗)는 사본마다 **첫 요청 한 번**이다."""
    counts = {"index": 0, "seeds": 0}
    real_init, real_seeds = big.PoolIndex.__init__, big.compute_onboarding_seeds

    def init(self, c):
        counts["index"] += 1
        real_init(self, c)

    def seeds(ans):
        counts["seeds"] += 1
        return real_seeds(ans)

    monkeypatch.setattr(big.PoolIndex, "__init__", init)
    monkeypatch.setattr(big, "compute_onboarding_seeds", seeds)
    view = _fresh_view(big)
    with big.using(view):
        for user in ("internal:a", "internal:b", "internal:c"):
            big.recommend(user, 3, set(), False, None, onboarding=_answers())
    assert counts == {"index": 1, "seeds": 1}


def test_onboarding_seeds_equal_the_direct_mean(big):
    """곡·구간 종류별 합으로 만든 씨앗이 예전 식(조건에 맞는 구간 원본을 읽어 평균)과 같다."""
    ans = _answers()
    view = _fresh_view(big)
    with big.using(view):
        C, W, meta = big.onboarding_seeds("internal:direct", ans)
        # 예전 식: 조건에 맞는 곡의 추천 구간을 모아 구간 종류별로 평균.
        ok = big.onboarding_track_ok(ans)
        pool = view.pool[view.pool.track_id.map(lambda t: ok(t, 0))]
        pool = pool[pool.dur_sec >= big.MIN_MOMENT_SEC]
        V0, found = big.raw_vectors(pool.moment_id.astype(str).tolist())
        V = big.center(V0)
        groups = sorted(pool.groupby("section_label", sort=False), key=lambda kv: -len(kv[1]))
    assert len(pool) >= big.ONBOARD_MIN_MATCH, "완화 없이 모여야 비교가 단순하다"
    place = {m: i for i, m in enumerate(found)}
    assert len(C) == min(len(groups), big.ONBOARD_SEEDS)
    for (lab, g), v, m in zip(groups, C, meta):
        mean = V[[place[str(x)] for x in g.moment_id]].mean(0)
        assert np.allclose(v, mean / np.linalg.norm(mean), atol=1e-5)
        assert m["dna_id"] == f"internal:direct#seed_{lab}"
        assert m["n_members"] == len(g)
        assert m["rep_moment_id"] == str(g.iloc[0].moment_id)
        assert m["relax_level"] == 0


def test_daily_plays_the_first_chorus_even_outside_the_candidates(big, monkeypatch):
    """첫 chorus 는 후보에 없어도 찾는다 — 후보는 닮은 순간만 뽑았다."""
    from datetime import datetime

    monkeypatch.setattr(big, "COLD_START_CANDIDATES", 4)
    view = _fresh_view(big)
    with big.using(view):
        out = big.recommend("internal:chorus", 4, set(), True, None, None, None, daily=True,
                            now=datetime.fromisoformat("2026-09-25T12:00:00+09:00"))
    assert len(out["results"]) == 4
    # 합성 본문의 chorus 는 곡마다 #2 다.
    assert all(r["moment_id"] == f'{r["track_id"]}#2' for r in out["results"])


def test_exported_seed_table_rebuilds_the_serving_seeds(big, tmp_path):
    """내보낸 표(`onboarding_seeds.npz`)만으로 서빙이 만든 씨앗을 다시 만들 수 있다."""
    from ai_server.tools import export_onboarding_seeds as ex

    view = _fresh_view(big)
    ans = _answers()
    with big.using(view):
        C, _, meta = big.onboarding_seeds("internal:export", ans)
        table = ex.build_table(big)
        ok = big.onboarding_track_ok(ans)
    np.savez(tmp_path / "seeds.npz", **table)
    got = np.load(tmp_path / "seeds.npz")
    assert int(got["count"].sum()) == int((view.pool.dur_sec >= big.MIN_MOMENT_SEC).sum())
    assert np.allclose(got["mu"], big.vector_store().mu)
    for v, m in zip(C, meta):
        lab = m["dna_id"].rsplit("#seed_", 1)[1]
        pick = (got["section_label"] == lab) & np.asarray([ok(str(t), 0) for t in got["track_id"]])
        s = got["sum_u"][pick].sum(0)
        assert np.allclose(v, s / np.linalg.norm(s), atol=1e-5)
        assert int(got["count"][pick].sum()) == m["n_members"]


def test_requests_never_build_the_pool(big, monkeypatch):
    """요청은 기준 풀 스냅샷을 **참조만** 한다 — 스냅샷·사본·풀 색인을 요청마다 만들지 않는다
    (계약 v3, S15P21A204-458).

    갱신(여기서는 `install_static` 의 첫 갱신)이 `internal_api.warm` 으로 사본과 풀 색인을 미리
    만든다. 예전에는 요청 본문 `catalog` 의 해시를 요청마다 쟀고(O(본문)), 새 본문이면 요청이
    스냅샷을 만들었다. 옛 Spring 이 본문을 실어 보내도 — 모양이 틀려도 — 읽지 않는다.
    """
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from ai_server.app import internal_api, pool_snapshot

    internal_api._VIEWS.clear()
    prev = pool_snapshot.HOLDER
    body = _catalog_body(n_tracks=N_TRACKS, per_track=PER_TRACK)
    pool_snapshot.install_static(body, on_swap=internal_api.warm(big))
    built, indexed = [], []
    monkeypatch.setattr(catalog_body, "from_body", lambda *a, **k: built.append(1))
    real_index = big.PoolIndex
    monkeypatch.setattr(big, "PoolIndex", lambda c: indexed.append(1) or real_index(c))
    try:
        app = FastAPI()
        internal_api.install(app, big)
        client = TestClient(app)
        for rid in (1, 2, 3):
            r = client.post("/internal/recommendations/daily",
                            json={"recommendation_id": rid, "user_id": 7})
            assert r.status_code == 200, r.text
        legacy = client.post("/internal/recommendations/daily",
                             json={"recommendation_id": 4, "user_id": 7,
                                   "catalog": {**body, "extra": {}}})
        assert legacy.status_code == 200, legacy.text
        assert built == [] and indexed == []
    finally:
        pool_snapshot.install(prev)
        internal_api._VIEWS.clear()


# ------------------------------------------------------------------ 적재본 지문
# 판의 지문(`snapshot_hash`)이 씨앗 표를 판에 묶는다. 예전 Pinecone 저장소에서 옮겼다(459).

def _unit(n, dim=8, seed=0):
    Z = np.random.default_rng(seed).standard_normal((n, dim)).astype(np.float32)
    return Z / np.linalg.norm(Z, axis=1, keepdims=True)


def test_snapshot_changes_when_only_a_vector_changes():
    """재분석으로 값만 바뀐 경우. id 만 세면 못 잡는다."""
    ids = ["A#0", "A#1", "A#2"]
    Z = _unit(3, seed=1)
    base = vstore.snapshot_hash(ids, Z, "mert-v1-95m", {"flow"})

    changed = Z.copy()
    changed[1] = -changed[1]
    assert vstore.snapshot_hash(ids, changed, "mert-v1-95m", {"flow"}) != base


def test_snapshot_ignores_order():
    ids = ["A#0", "A#1", "A#2"]
    Z = _unit(3, seed=1)
    order = [2, 0, 1]
    shuffled = vstore.snapshot_hash([ids[i] for i in order], Z[order], "mert-v1-95m", {"flow"})
    assert shuffled == vstore.snapshot_hash(ids, Z, "mert-v1-95m", {"flow"})


def test_snapshot_covers_version_and_pool_config():
    ids = ["A#0"]
    Z = _unit(1)
    base = vstore.snapshot_hash(ids, Z, "mert-v1-95m", {"flow"})
    assert vstore.snapshot_hash(ids, Z, "mert-v2", {"flow"}) != base
    assert vstore.snapshot_hash(ids, Z, "mert-v1-95m", {"flow", "jamendo"}) != base


def test_the_embedding_file_ships():
    """운영은 판 안의 `moments_emb.npy` 로 추천한다(local 갈래). 배송에서 빠지면 새 판을
    올리는 순간 멈춘다."""
    from ai_server.tools import slice_index as si

    assert "moments_emb.npy" not in si.NOT_SHIPPED
    assert "moments_emb.npy" in si.SHIPPED_ALIGNED
