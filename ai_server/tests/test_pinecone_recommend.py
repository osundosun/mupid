"""Pinecone 갈래로 추천 경로 전체를 태운다. **실제 키 없이.**

핵심 주장은 하나다 — **Pinecone 으로 돌려도 같은 결과가 나온다.** 후보 검색만
Pinecone 이 하고 점수·순위는 그대로이므로, 같은 카탈로그에서 두 갈래의 결과가
달라지면 그건 전환이 무언가를 바꿨다는 뜻이다.

  local     moments_emb.npy 를 읽는다 (전환 전·개발용)
  pinecone  인덱스 하나. 후보는 저장된 `u`, 원본은 `mu + r·u` 로 되돌린다

복원이 어긋나면 오류 없이 순위만 달라지므로, 여기서 값으로 못박는다.
"""
from __future__ import annotations

import importlib
import json
import os
import sys
import types

import numpy as np
import pytest

from ai_server.app import catalog_api, pinecone_store as ps

# `ai_server/tests` 에는 `__init__.py` 가 없다. pytest 가 이 디렉터리를 sys.path 에
# 넣으므로 같은 폴더의 시험 모듈은 평범한 import 로 가져온다.
from test_pinecone_store import INDEX, NS, FakePinecone     # noqa: E402
from test_pipeline_synthetic import _build, _catalog_payload  # noqa: E402


@pytest.fixture(scope="module")
def wired(tmp_path_factory):
    """합성 판 + 카탈로그 + 가짜 Pinecone 을 한 벌로 세운다."""
    root = tmp_path_factory.mktemp("pc")
    data = _build(root)

    saved = {k: os.environ.get(k) for k in (
        "MUPID_AI_ARTIFACT_DIR", "MUPID_PINECONE_API_KEY",
        "MUPID_PINECONE_INDEX", "MUPID_PINECONE_NAMESPACE",
        "MUPID_PINECONE_ENABLED")}
    os.environ["MUPID_AI_ARTIFACT_DIR"] = str(root)

    module = types.ModuleType("pinecone")
    module.Pinecone = FakePinecone
    sys.modules["pinecone"] = module
    os.environ.update({
        "MUPID_PINECONE_API_KEY": "pckey-test",
        "MUPID_PINECONE_INDEX": INDEX,
        "MUPID_PINECONE_NAMESPACE": NS,
    })
    os.environ.pop("MUPID_PINECONE_ENABLED", None)      # 처음엔 local 로 연다

    catalog_api.install(catalog_api.snapshot_from_tracks(_catalog_payload()))
    for name in ("features", "labeler", "stage1_torch"):
        full = f"ai_server.app.{name}"
        if full in sys.modules:
            importlib.reload(sys.modules[full])
    from ai_server.app import recommender as rc
    rc = importlib.reload(rc)
    rc.CAT = rc.Catalog()

    # 평균은 **현재 추천 가능한 풀 전체**에서 잰다. control 에 넣고 그 평균으로
    # 쪼개어 올린다 — 운영 배치가 하는 것과 같은 순서다.
    Z = np.asarray(rc.CAT.Z[rc.CAT.pool_rows], dtype=np.float32)
    ids = rc.CAT.pool.moment_id.astype(str).tolist()
    ps.reset()
    ps.save_center(Z.mean(0), "mert-v1-95m", "test-snapshot", len(Z))
    # 가짜 인덱스는 1536차가 기본이다. 합성 판의 차원에 맞춰 둔다.
    FakePinecone.last.indexes[INDEX].dim = int(Z.shape[1])
    ps.upsert(ids, Z, center_=ps.center(reload=True))

    yield rc

    ps.reset()
    catalog_api.reset()
    sys.modules.pop("pinecone", None)
    for k, v in saved.items():
        if v is None:
            os.environ.pop(k, None)
        else:
            os.environ[k] = v
    importlib.reload(rc)
    rc.CAT = None


@pytest.fixture
def pinecone_on(wired, monkeypatch):
    monkeypatch.setenv("MUPID_PINECONE_ENABLED", "1")
    wired.CAT._mu_g = None
    yield wired
    wired.CAT._mu_g = None


# ------------------------------------------------------------------ 동등성

def test_the_frozen_mean_matches_the_pool_mean(wired):
    """고정 평균이 풀 평균과 다르면 두 갈래가 다른 공간에서 논다."""
    local = np.asarray(wired.CAT.Z[wired.CAT.pool_rows], dtype=np.float32).mean(0)
    assert np.allclose(ps.center().mu, local, atol=1e-6)


def test_similar_moments_agree_between_backends(wired, monkeypatch):
    """후보 검색을 Pinecone 으로 돌려도 **같은 곡·같은 순서**여야 한다."""
    seed = str(wired.CAT.pool.moment_id.iloc[2])

    monkeypatch.delenv("MUPID_PINECONE_ENABLED", raising=False)
    local = wired.similar_moments(seed, 3)

    monkeypatch.setenv("MUPID_PINECONE_ENABLED", "1")
    remote = wired.similar_moments(seed, 3)

    assert [r["moment_id"] for r in local] == [r["moment_id"] for r in remote]
    for a, b in zip(local, remote):
        assert a["similarity"] == pytest.approx(b["similarity"], abs=1e-5)


def _answers():
    """조사 답변은 **어휘로 옮겨진 것**이어야 한다 — 원문 dict 가 아니다."""
    from ai_server.app import onboarding as onb
    from test_pipeline_synthetic import GENRES

    ans = onb.from_request({"moods": [], "genres": [GENRES[0]]}, GENRES)
    assert ans["genre"] == [GENRES[0]], "조사 어휘가 옮겨지지 않았다"
    return ans


def test_recommend_agrees_between_backends(wired, monkeypatch):
    """점수·순위는 Pinecone 이 맡지 않는다. 후보만 좁힐 뿐이다."""
    ans = _answers()

    monkeypatch.delenv("MUPID_PINECONE_ENABLED", raising=False)
    wired.CAT._mu_g = None
    local = wired.recommend("internal:41", 5, set(), False, None, onboarding=ans)

    monkeypatch.setenv("MUPID_PINECONE_ENABLED", "1")
    wired.CAT._mu_g = None
    remote = wired.recommend("internal:41", 5, set(), False, None, onboarding=ans)

    assert local["results"], "local 이 빈 결과라 비교가 성립하지 않는다"
    assert [r["moment_id"] for r in local["results"]] \
        == [r["moment_id"] for r in remote["results"]]
    assert remote["diagnostics"]["vector_search"]["backend"] == "pinecone"
    assert remote["diagnostics"]["query_source"] == "onboarding"


def test_onboarding_seeds_use_raw_vectors(pinecone_on):
    """조건별 평균은 **원본**으로 낸다. 변환본으로 내면 두 번 중심화된다."""
    seeds, W, meta = pinecone_on.onboarding_seeds("internal:42", injected=_answers())

    assert len(seeds) and meta[0]["source"] == "onboarding"
    client = FakePinecone.last
    assert any(call[0] == "fetch" for call in client.indexes[INDEX].calls)


def test_dna_clusters_on_raw_vectors(pinecone_on, monkeypatch):
    """DNA 군집도 원본 공간에서 한다 (`build_dna` 의 V0)."""
    calls_before = len([c for c in FakePinecone.last.indexes[INDEX].calls
                        if c[0] == "fetch"])
    pinecone_on.recommend("internal:43", 3, set(), False, None, onboarding=_answers())
    after = len([c for c in FakePinecone.last.indexes[INDEX].calls
                 if c[0] == "fetch"])
    assert after > calls_before, "원본 인덱스를 읽지 않았다"


# ------------------------------------------------------------ 카탈로그가 기준이다

def test_a_candidate_missing_from_the_catalog_is_dropped(pinecone_on):
    """Pinecone 에 남아 있어도 MySQL 에서 지워졌으면 후보가 아니다.

    남기면 Spring 이 `external_id` 를 못 찾아 그 줄을 버린다 — 화면에는 오류가
    아니라 "추천이 몇 개 비어 보인다" 로 나온다.
    """
    ghost = "SANSIL_GHOST#0"
    vec = np.asarray(pinecone_on.CAT.Z[pinecone_on.CAT.pool_rows[0]], dtype=np.float32)
    ps.upsert([ghost], vec.reshape(1, -1))
    try:
        seed = str(pinecone_on.CAT.pool.moment_id.iloc[0])
        got = pinecone_on.similar_moments(seed, 5)
        assert ghost not in [r["moment_id"] for r in got]
        assert all("SANSIL" not in r["track_id"] for r in got)
    finally:
        ps.delete([ghost])


def test_filters_come_from_the_catalog_not_pinecone(pinecone_on):
    """장르·무드·보컬·악기는 Spring 카탈로그 기준으로 건다. metadata 를 쓰지 않는다."""
    pool, diag = pinecone_on.filter_query_pool(
        pinecone_on.CAT.pool, {"filters": {"genre": ["contemporary_pop"]}}, None, None)
    assert "filters.genre" in diag["applied"]
    assert 0 < len(pool) < len(pinecone_on.CAT.pool)


# ------------------------------------------------------------------ 실패 모양

def test_an_outage_does_not_fall_back_to_local(pinecone_on):
    """켜 둔 상태에서 Pinecone 이 죽으면 **노드에 남은 옛 벡터로 내려가지 않는다.**

    내려가면 조용히 다른 추천이 나가고 그 사실이 응답 어디에도 드러나지 않는다.
    """
    index = FakePinecone.last.indexes[INDEX]
    index.fail = RuntimeError("connection reset")
    try:
        with pytest.raises(ps.PineconeError):
            pinecone_on.similar_moments(str(pinecone_on.CAT.pool.moment_id.iloc[0]), 3)
    finally:
        index.fail = None


def test_a_missing_center_stops_the_pinecone_path(pinecone_on, monkeypatch):
    """control 평균이 없으면 적재 때와 다른 공간으로 질의가 나간다 — 멈춘다."""
    ps.forget_center()
    monkeypatch.setattr(ps, "load_center",
                        lambda: (_ for _ in ()).throw(ps.PineconeError("없다")))
    with pytest.raises(ps.PineconeError):
        pinecone_on.similar_moments(str(pinecone_on.CAT.pool.moment_id.iloc[0]), 3)


def test_daily_and_next_agree_between_backends(wired, monkeypatch):
    """`daily`·`next` 는 어댑터까지 붙여 본다.

    둘 다 `recommend()` 를 지나지만, 계약 층에서 같은 줄이 나오는지는 별개다 —
    후보가 좁아지면 `shortfall` 이나 곡 중복 제거 결과가 달라질 수 있다.
    """
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from ai_server.app import internal_api

    app = FastAPI()
    internal_api.install(app, wired)
    client = TestClient(app)
    seed = str(wired.CAT.pool.moment_id.iloc[1])
    calls = {
        "daily": ("/internal/recommendations/daily",
                  {"recommendation_id": 1, "user_id": 7}),
        "next": ("/internal/recommendations/next",
                 {"recommendation_id": 2, "user_id": 7, "limit": 3,
                  "current_track": seed.split("#")[0], "current_section": seed}),
    }

    got: dict = {}
    for backend, on in (("local", False), ("pinecone", True)):
        if on:
            monkeypatch.setenv("MUPID_PINECONE_ENABLED", "1")
        else:
            monkeypatch.delenv("MUPID_PINECONE_ENABLED", raising=False)
        wired.CAT._mu_g = None
        for name, (url, payload) in calls.items():
            r = client.post(url, json=payload)
            assert r.status_code == 200, (backend, name, r.text)
            body = r.json()
            got[(backend, name)] = [i["external_id"] for i in body["items"]]

    for name in calls:
        assert got[("local", name)], f"{name}: local 이 빈 결과라 비교가 성립하지 않는다"
        assert got[("local", name)] == got[("pinecone", name)], name


def test_backend_name_is_visible(wired, monkeypatch):
    """어느 갈래로 돌고 있는지가 응답으로 보여야 한다."""
    monkeypatch.delenv("MUPID_PINECONE_ENABLED", raising=False)
    assert wired.vector_backend() == "local"
    monkeypatch.setenv("MUPID_PINECONE_ENABLED", "1")
    assert wired.vector_backend() == "pinecone"
