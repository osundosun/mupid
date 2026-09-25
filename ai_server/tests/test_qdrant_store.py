"""Qdrant 갈래 (S15P21A204-456) — 정의·갈래 선택·검색/조회·준비 판정.

가짜 Qdrant(`fake_qdrant.FakeQdrant`)를 `qdrant_store.request` 자리에 끼운다. 검색이 정확한
코사인이라 **로컬 갈래와 같은 결과**가 나와야 한다 — 갈래를 바꿔도 추천이 바뀌지 않는다는 것이
이 파일의 요점이다. 실제 Qdrant 로 재는 것은 `tools/bench_request_path.py --backend qdrant` 다.
"""
from __future__ import annotations

import importlib
import inspect
import json
import os
import sys
import uuid

import numpy as np
import pytest

from ai_server.app import catalog_body, qdrant_schema, qdrant_store, vector_store as vstore

from fake_qdrant import FakeQdrant
from test_pipeline_synthetic import _build, _catalog_body   # noqa: E402

N_TRACKS, PER_TRACK = 60, 4


# ------------------------------------------------------------------ 정의 (한 곳)

def test_the_collection_definition_matches_the_design():
    """설계 §6.1 표 그대로. Spring(457)도 이 파일로 만든다 — 값이 바뀌면 여기서 먼저 걸린다."""
    body = qdrant_schema.collection_body(["v3"])
    v = body["vectors"]
    assert qdrant_schema.collection_name() == "moments__mert-v1-95m"
    assert v["mert"] == {"size": 1536, "distance": "Cosine", "on_disk": True,
                         "quantization_config": {"scalar": {"type": "int8", "quantile": 0.99,
                                                            "always_ram": True}}}
    assert v["stage1_v3"]["size"] == 128
    assert v["clap"]["size"] == 512
    assert v["feat_v1"]["size"] == 285 and v["feat_v1"]["hnsw_config"] == {"m": 0}
    indexed = dict(qdrant_schema.payload_indexes())
    assert indexed["track_id"] == "integer" and indexed["analysis_version"] == "keyword"
    assert {"external_id", "source"} <= set(indexed)
    # MySQL 값(장르·무드·삭제 여부…)은 payload 에 없다 (§5.1 "Qdrant 에는 MySQL 값을 넣지 않는다").
    assert not {"genre", "mood", "is_deleted", "playable", "language"} & set(
        qdrant_schema.payload_fields())
    # Qdrant 는 모르는 키를 거절한다 — 설명 키(`_…`)가 본문에 새면 안 된다.
    def keys(node):
        if isinstance(node, dict):
            for k, val in node.items():
                yield k
                yield from keys(val)
    assert not [k for k in keys(body) if k.startswith("_")]


def test_point_ids_are_uuid5_of_the_external_id():
    """Spring 이 같은 id 를 만들어야 한다. 이 값을 457 의 시험에 그대로 옮긴다."""
    ns = uuid.UUID(qdrant_schema.schema()["point_id"]["namespace"])
    assert qdrant_schema.point_id("T000001#3") == str(uuid.uuid5(ns, "T000001#3"))
    assert qdrant_schema.point_id("T000001#3") == "08155de7-3e4e-58ff-8625-f9a049bc76d0"


def test_model_vector_names_only_take_versions():
    assert qdrant_schema.model_vector("v12") == "stage1_v12"
    with pytest.raises(ValueError):
        qdrant_schema.model_vector("v1; drop")


# ------------------------------------------------------------------ 갈래 선택

@pytest.mark.parametrize("new,legacy,want", [
    (None, None, "local"),                 # 기본은 local — 457 전 운영이 그대로 돈다
    (None, "1", "pinecone"),               # 예전 스위치도 계속 받는다
    ("qdrant", None, "qdrant"),
    ("local", "true", "local"),            # 새 변수가 이긴다
    (" Qdrant ", "1", "qdrant"),
])
def test_backend_selection(monkeypatch, new, legacy, want):
    from ai_server.app import pinecone_store, recommender
    for k, v in (("MUPID_VECTOR_BACKEND", new), ("MUPID_PINECONE_ENABLED", legacy)):
        if v is None:
            monkeypatch.delenv(k, raising=False)
        else:
            monkeypatch.setenv(k, v)
    assert vstore.backend_name(pinecone_store.enabled()) == want
    assert recommender.vector_backend() == want


def test_an_unknown_backend_is_an_error_not_a_fallback(monkeypatch):
    monkeypatch.setenv("MUPID_VECTOR_BACKEND", "qdarnt")
    with pytest.raises(ValueError):
        vstore.backend_name(False)


def test_the_manifest_default_keeps_ai_on_local():
    """병합이 곧 배포다 — Qdrant 가 떠도 ai 는 local 로 돌아야 한다 (이 MR 의 안전 조건)."""
    # PyYAML 은 서빙 의존성이 아니다(CI 이미지에 없을 수 있다) — 글자로 본다.
    import re
    root = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    with open(os.path.join(root, "infra_k8s", "base", "ai.yaml"), encoding="utf-8") as f:
        text = f.read()
    got = re.findall(r"name:\s*MUPID_VECTOR_BACKEND,\s*value:\s*'?(\w+)'?", text)
    assert got in ([], ["local"]), got


# ------------------------------------------------------------------ 저장소 단독

def _store(fake, mu, **kw):
    return vstore.QdrantVectorStore(mu, "test", ["flow"], http=fake, **kw)


@pytest.fixture()
def small():
    rng = np.random.default_rng(3)
    Z = rng.normal(size=(40, 16)).astype(np.float32)
    ids = [f"T{i // 4:06d}#{i % 4}" for i in range(40)]
    mu = Z.mean(0)
    fake = FakeQdrant()
    fake.vectors["mert"] = dict(fake.vectors["mert"], size=16)
    fake.load(ids, Z, mu)
    return fake, ids, Z, mu


def test_fetch_restores_the_raw_vector(small):
    fake, ids, Z, mu = small
    got = _store(fake, mu).fetch([ids[5], "NOPE#0", ids[5], ids[7]])
    assert list(got) == [ids[5], ids[7]]
    assert np.allclose(got[ids[5]], Z[5], atol=1e-5)


def test_fetch_splits_large_requests(small):
    fake, ids, Z, mu = small
    store = _store(fake, mu)
    store.FETCH_BATCH = 7
    got = store.fetch(ids)
    assert len(got) == len(ids)
    assert sum(1 for m, p, _ in fake.calls if p.endswith("/points")) == 6


def test_search_scores_are_the_local_centred_cosine(small):
    fake, ids, Z, mu = small
    store = _store(fake, mu)
    q = store.centered(Z[3])[0]
    expect = store.centered(Z) @ q
    got = store.search(q, 5)
    assert [m for m, _ in got] == [ids[i] for i in np.argsort(-expect)[:5]]
    assert np.allclose([s for _, s in got], np.sort(expect)[::-1][:5], atol=1e-5)


def test_a_small_allow_list_goes_as_has_id(small):
    fake, ids, Z, mu = small
    store = _store(fake, mu)
    allow = store.allow(ids[:6])
    got = store.search(store.centered(Z[30])[0], 10, allow)
    assert {m for m, _ in got} == set(ids[:6])
    body = fake.calls[-1][2]["searches"][0]
    assert {"has_id": [qdrant_schema.point_id(i) for i in ids[:6]]} in body["filter"]["must"]
    assert {"key": "source", "match": {"any": ["flow"]}} in body["filter"]["must"]
    assert allow.qdrant_has_id is not None, "HasId 조각은 목록마다 한 번만 만든다"


def test_a_wide_allow_list_is_filtered_after_the_search(small):
    """허용 목록이 컬렉션 대부분이면 UUID 수천 개를 보내지 않고 넉넉히 뽑아 거른다."""
    fake, ids, Z, mu = small
    store = _store(fake, mu)
    keep = ids[:36]
    got = store.search(store.centered(Z[39])[0], 8, store.allow(keep))
    q = store.centered(Z[39])[0]
    sim = store.centered(Z[:36]) @ q
    assert [m for m, _ in got] == [keep[i] for i in np.argsort(-sim)[:8]]
    assert all("has_id" not in json.dumps(c) for c in
               fake.calls[-1][2]["searches"][0]["filter"]["must"])


def test_search_many_equals_one_by_one(small):
    fake, ids, Z, mu = small
    store = _store(fake, mu)
    Q = store.centered(Z[:3])
    for q, got in zip(Q, store.search_many(Q, 4, store.allow(ids[::2]))):
        assert got == store.search(q, 4, store.allow(ids[::2]))


# ------------------------------------------------------------------ 준비 판정

def test_ready_needs_the_collection_and_points(small):
    fake, ids, Z, mu = small
    assert _store(fake, mu).status()["ok"] is True
    fake.exists = False
    st = _store(fake, mu).status()
    assert not st["ok"] and "없다" in st["errors"][0]
    fake.exists, fake.points = True, {}
    st = _store(fake, mu).status()
    assert not st["ok"] and "비었다" in " ".join(st["errors"])


def test_ready_fails_when_qdrant_is_down(small):
    fake, ids, Z, mu = small
    fake.down = True
    st = _store(fake, mu).status()
    assert not st["ok"] and "닿지 못했다" in st["errors"][0]
    with pytest.raises(vstore.QdrantNotReady):
        _store(fake, mu).require_ready()


def test_ready_needs_the_image_model_vector_on_every_point(small):
    """설계 §10.2-1: 이미지의 모델 버전 벡터가 전 구간에 없으면 준비 판정을 거부한다."""
    fake, ids, Z, mu = small
    st = _store(fake, mu, model_version="v3").status()
    assert not st["ok"] and "stage1_v3 벡터가 컬렉션에 없다" in st["errors"][0]

    s1 = np.ones((len(ids), 128), np.float32)
    fake.load(ids, Z, mu, stage1=s1[:30], model_version="v3")   # 30/40 만 재계산됐다
    st = _store(fake, mu, model_version="v3").status()
    assert not st["ok"] and "30/40" in st["errors"][0]

    fake.load(ids, Z, mu, stage1=s1, model_version="v3")
    st = _store(fake, mu, model_version="v3").status()
    assert st["ok"] and st["model_vector"] == {"name": "stage1_v3", "points": 40, "of": 40}
    # 모델 버전이 없으면(models.lock null) 검사하지 않는다.
    assert _store(fake, mu, model_version=None).status()["ok"]


def test_health_never_waits_for_qdrant(big, qdrant_on):
    """/health 는 liveness 다. Qdrant 를 부르면 Qdrant 가 멈춘 동안 멀쩡한 ai 가 재시작된다."""
    from ai_server.app import main
    big.vector_store().status(refresh=True)
    n = len(qdrant_on.calls)
    qdrant_on.down = True
    got = main._generation()
    assert got["backend"] == "qdrant" and got["status"]["ok"] is True
    assert len(qdrant_on.calls) == n


def test_ready_rejects_a_dimension_mismatch(small):
    fake, ids, Z, mu = small
    st = _store(fake, np.zeros(1536, np.float32)).status()
    assert not st["ok"] and "차원" in st["errors"][0]


def test_status_is_remembered_for_a_few_seconds(small):
    fake, ids, Z, mu = small
    store = _store(fake, mu)
    store.status()
    n = len(fake.calls)
    store.status()
    assert len(fake.calls) == n, "요청마다 Qdrant 를 두드리지 않는다"
    store.status(refresh=True)
    assert len(fake.calls) > n


# ------------------------------------------------------------------ 추천 경로 전체

@pytest.fixture(scope="module")
def big(tmp_path_factory):
    root = tmp_path_factory.mktemp("qd")
    _build(root, n_tracks=N_TRACKS, per_track=PER_TRACK)
    keys = ("MUPID_AI_ARTIFACT_DIR", "MUPID_PINECONE_ENABLED", "MUPID_VECTOR_BACKEND")
    saved = {k: os.environ.get(k) for k in keys}
    os.environ["MUPID_AI_ARTIFACT_DIR"] = str(root)
    os.environ.pop("MUPID_PINECONE_ENABLED", None)
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


def _fresh(rc):
    return rc.CAT.with_catalog(
        catalog_body.from_body(_catalog_body(n_tracks=N_TRACKS, per_track=PER_TRACK)))


@pytest.fixture()
def qdrant_on(big, monkeypatch):
    """판의 벡터를 가짜 Qdrant 에 넣고 갈래를 qdrant 로 돌린다. 판 자체는 그대로 둔다."""
    pool = big.index_pool(big.CAT)
    Z = np.asarray(big.CAT.Z[pool.emb_row.to_numpy()], dtype=np.float32)
    fake = FakeQdrant()
    fake.vectors["mert"] = dict(fake.vectors["mert"], size=int(Z.shape[1]))
    fake.load(pool.moment_id.astype(str).tolist(), Z, Z.mean(0))
    monkeypatch.setattr(qdrant_store, "request", fake)
    monkeypatch.setenv("MUPID_VECTOR_BACKEND", "qdrant")
    big.CAT._index_cache.pop("vector_store:qdrant", None)
    yield fake
    big.CAT._index_cache.pop("vector_store:qdrant", None)


def _both(big, monkeypatch, fn):
    """같은 호출을 local 과 qdrant 로 한 번씩. 각자 새 사본으로 돌린다 (캐시를 나누지 않게)."""
    monkeypatch.delenv("MUPID_VECTOR_BACKEND", raising=False)
    with big.using(_fresh(big)):
        local = fn()
    monkeypatch.setenv("MUPID_VECTOR_BACKEND", "qdrant")
    with big.using(_fresh(big)):
        remote = fn()
    return local, remote


def _ids(out):
    return [(r.get("track_id"), r.get("moment_id")) for r in out.get("results", out)] \
        if isinstance(out, dict) else [(r.get("track_id"), r.get("moment_id")) for r in out]


def test_the_qdrant_store_uses_the_plate_pool_mean(big, qdrant_on):
    store = big.vector_store()
    assert isinstance(store, vstore.QdrantVectorStore)
    assert store.mu_source == "plate:pool_mean"
    rows = big.index_pool(big.CAT).emb_row.to_numpy()
    assert np.allclose(store.mu, np.asarray(big.CAT.Z[rows], dtype=np.float32).mean(0),
                       atol=1e-6)
    assert big.vector_store_problem() is None


def test_similar_is_the_same_on_both_backends(big, qdrant_on, monkeypatch):
    seed = str(big.CAT.pool.moment_id.iloc[9])
    local, remote = _both(big, monkeypatch, lambda: big.similar_moments(seed, 6))
    assert _ids(local) == _ids(remote) and len(_ids(local)) == 6


def test_daily_and_next_are_the_same_on_both_backends(big, qdrant_on, monkeypatch):
    local, remote = _both(big, monkeypatch, lambda: big.recommend(
        "internal:cold", 4, set(), False))
    assert _ids(local) == _ids(remote) and _ids(local)
    cur = str(big.CAT.pool.moment_id.iloc[13])
    local, remote = _both(big, monkeypatch, lambda: big.recommend(
        "internal:cold", 5, set(), False, None, cur.split("#")[0], cur))
    assert _ids(local) == _ids(remote) and _ids(local)


def test_a_missing_collection_fails_readiness_and_requests(big, qdrant_on):
    from fastapi.testclient import TestClient
    from ai_server.app import internal_api
    from fastapi import FastAPI

    app = FastAPI()
    internal_api.install(app, big)
    client = TestClient(app)
    assert client.get("/internal/ready").status_code == 200
    qdrant_on.exists = False
    big.vector_store().status(refresh=True)
    got = client.get("/internal/ready")
    assert got.status_code == 503
    assert "Qdrant" in got.text and "없다" in got.text


def test_local_stays_untouched_by_a_dead_qdrant(big, qdrant_on, monkeypatch):
    """갈래가 local 이면 Qdrant 가 죽어 있어도 아무 영향이 없다 — 병합 직후 운영의 모양이다."""
    qdrant_on.down = True
    monkeypatch.delenv("MUPID_VECTOR_BACKEND", raising=False)
    assert big.vector_store_problem() is None
    with big.using(_fresh(big)):
        assert big.recommend("internal:cold", 2, set(), False)["results"]
    assert not qdrant_on.calls


def test_startup_with_qdrant_unready_still_loads(monkeypatch):
    """Qdrant 갈래는 기동에서 멈추지 않는다 — 준비 판정만 실패하고, 살아나면 재기동 없이 돌아온다.

    여기서는 μ 를 만들 판 벡터도, 이미지의 mu.npy 도 없다. /health 는 그래도 답해야 한다."""
    import types
    from ai_server.app import main, recommender

    monkeypatch.setenv("MUPID_VECTOR_BACKEND", "qdrant")
    monkeypatch.setattr(main, "artifact_status", lambda: {"missing": []})
    monkeypatch.setattr(main.index_contract, "check_plate",
                        lambda: {"ok": True, "warnings": [], "errors": []})
    monkeypatch.setattr(main, "check_integrity", lambda: {"ok": True, "errors": []})
    fake = types.SimpleNamespace(_mom_index={"T1#0": 0}, Z=None, _index_cache={})
    monkeypatch.setattr(recommender, "Catalog", lambda: fake)
    monkeypatch.setattr(recommender, "CAT", None)
    monkeypatch.setattr(main, "_STATE", dict(main._STATE, loaded=False, error=None))

    main._startup()

    assert main._STATE["loaded"] is True and main._STATE["error"] is None
    assert "mu.npy" in main._STATE["vector_search"]["problem"]
    assert recommender.vector_store_problem()
    assert "error" in main._generation()


def test_an_unknown_backend_stops_startup(monkeypatch):
    import types
    from ai_server.app import main, recommender

    monkeypatch.setenv("MUPID_VECTOR_BACKEND", "qdarnt")
    monkeypatch.setattr(main, "artifact_status", lambda: {"missing": []})
    monkeypatch.setattr(main.index_contract, "check_plate",
                        lambda: {"ok": True, "warnings": [], "errors": []})
    monkeypatch.setattr(main, "check_integrity", lambda: {"ok": True, "errors": []})
    monkeypatch.setattr(recommender, "Catalog",
                        lambda: types.SimpleNamespace(_mom_index={}, Z=None, _index_cache={}))
    monkeypatch.setattr(recommender, "CAT", None)
    monkeypatch.setattr(main, "_STATE", dict(main._STATE, loaded=False, error=None))
    main._startup()
    assert main._STATE["loaded"] is False and "MUPID_VECTOR_BACKEND" in main._STATE["error"]
    assert main._vector_settings()["backend"] is None


# ------------------------------------------------------------------ 쓰지 않는다

def test_serving_never_writes_to_qdrant():
    """벡터를 쓰는 것은 Spring(457) 하나다. 서빙 코드에 쓰기 호출이 없어야 한다."""
    from ai_server.app import qdrant_store as qs
    src = inspect.getsource(qs) + inspect.getsource(vstore.QdrantVectorStore)
    for verb in ('"PUT"', '"DELETE"', '"PATCH"', "/points/delete", "/points/payload"):
        assert verb not in src, f"서빙 코드에 쓰기 호출이 생겼다: {verb}"


def test_the_pinecone_loader_does_not_know_qdrant():
    from ai_server.tools import pinecone_sync
    assert "qdrant" not in inspect.getsource(pinecone_sync).lower()
