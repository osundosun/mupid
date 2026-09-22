"""Pinecone 전환 검증 — 운영 인덱스도 실제 키도 쓰지 않는다.

`test_pinecone_store` 는 모듈 계약을, `test_pinecone_recommend` 는 추천 동등성을
본다. 여기서는 **전환 자체가 성립하는지**를 항목별로 확인한다.

    1  후보=normalize(Z-mu) · 원본=Z 가 실제로 그렇게 들어가는가
    2  candidate·raw·pinecone_center.npz 가 한 세대로 묶이는가
    3  전환 전 적재 수와 카탈로그 external_id 가 대조되는가
    4  삭제곡·flow 밖·카탈로그 밖이 후보에 남지 않는가
    5  (test_pinecone_recommend 가 본다 — similar·daily·next·DNA·온보딩 동등성)
    6  실패에서 로컬 색인으로 조용히 내려가지 않는가
    7  track_features 에서 dur_sec·sound_raw 가 실제로 빠졌는가
    8  남은 열이 실제 호출 경로에 닿는가
"""
from __future__ import annotations

import inspect
import sys
import types

import numpy as np
import pytest

from ai_server.app import catalog_api, pinecone_store as ps

from test_pinecone_store import FakePinecone
from test_pipeline_synthetic import GENRES, _build, _catalog_payload


# ------------------------------------------------------------------ 1·2 세대

@pytest.fixture
def staged(tmp_path, monkeypatch):
    """판 하나 + 가짜 Pinecone. 적재는 시험이 직접 한다."""
    module = types.ModuleType("pinecone")
    module.Pinecone = FakePinecone
    monkeypatch.setitem(sys.modules, "pinecone", module)
    monkeypatch.setenv("MUPID_PINECONE_API_KEY", "pckey-test")
    monkeypatch.setenv("MUPID_PINECONE_CANDIDATE_INDEX", "mupid-moment-candidate")
    monkeypatch.setenv("MUPID_PINECONE_RAW_INDEX", "mupid-moment-raw")
    monkeypatch.setenv("MUPID_PINECONE_NAMESPACE", "prod__mert-v1-95m")
    ps.reset()
    ps.forget_center()

    data = _build(tmp_path)
    ids = [f"T{t:06d}#{s}" for t in range(6) for s in range(4)]
    rng = np.random.default_rng(11)
    Z = rng.normal(size=(len(ids), 16)).astype(np.float32)
    Z /= np.linalg.norm(Z, axis=1, keepdims=True)
    mu = Z.mean(0)
    snap = ps.snapshot_hash(ids, Z, "mert-v1-95m", {"flow"})
    ps.save_center(data / ps.CENTER_FILE, mu, "mert-v1-95m",
                   "prod__mert-v1-95m", len(ids), snap)
    yield types.SimpleNamespace(data=data, ids=ids, Z=Z, mu=mu, snapshot=snap)
    ps.reset()
    ps.forget_center()


def test_1_candidate_holds_transformed_and_raw_holds_original(staged):
    """후보에는 normalize(Z-mu), 원본에는 Z. 섞이면 순위가 조용히 달라진다."""
    center = ps.center(staged.data / ps.CENTER_FILE)
    ps.upsert(ps.RAW, staged.ids, staged.Z)
    ps.upsert(ps.CANDIDATE, staged.ids, center.apply(staged.Z))

    store = FakePinecone.last.indexes
    ns = "prod__mert-v1-95m"
    raw0 = np.asarray(store["mupid-moment-raw"].vectors[ns][staged.ids[0]], np.float32)
    cand0 = np.asarray(store["mupid-moment-candidate"].vectors[ns][staged.ids[0]], np.float32)

    assert np.allclose(raw0, staged.Z[0], atol=1e-6), "원본이 변환돼 들어갔다"
    expect = staged.Z[0] - staged.mu
    expect /= np.linalg.norm(expect)
    assert np.allclose(cand0, expect, atol=1e-6), "후보가 원본 그대로 들어갔다"
    # 원본은 이미 L2=1 이고, 변환본도 L2=1 이지만 **값이 달라야** 한다.
    assert not np.allclose(raw0, cand0, atol=1e-4)


def test_2_center_binds_namespace_version_and_snapshot(staged, monkeypatch):
    """평균에 namespace·임베딩 버전·적재본 지문이 함께 적힌다."""
    center = ps.center(staged.data / ps.CENTER_FILE)
    assert center.namespace == "prod__mert-v1-95m"
    assert center.embedding_version == "mert-v1-95m"
    assert center.snapshot == staged.snapshot
    assert center.rows == len(staged.ids)


def test_2_a_center_from_another_namespace_is_refused(staged, monkeypatch):
    """새 namespace 로 돌려 놓고 평균을 안 바꾸면 질의만 옛 공간으로 나간다."""
    monkeypatch.setenv("MUPID_PINECONE_NAMESPACE", "prod__mert-v2-next")
    ps.forget_center()
    with pytest.raises(ps.GenerationMismatch) as e:
        ps.center(staged.data / ps.CENTER_FILE)
    assert "mert-v2-next" in str(e.value)


def test_2_b_center_from_another_snapshot_is_refused(staged):
    """판을 다시 구우면 적재본이 달라진다. 옛 평균을 그대로 쓰면 안 된다."""
    center = ps.center(staged.data / ps.CENTER_FILE)
    center.require_snapshot(staged.snapshot)             # 같은 적재본 — 통과
    other = ps.snapshot_hash(staged.ids + ["T000099#0"],
                             np.vstack([staged.Z, staged.Z[:1]]),
                             "mert-v1-95m", {"flow"})
    with pytest.raises(ps.GenerationMismatch) as e:
        center.require_snapshot(other)
    assert "적재본" in str(e.value)


def test_2_c_snapshot_ignores_order(staged):
    """같은 구성·같은 값이면 적재 차례가 달라도 같은 지문이어야 한다."""
    order = list(range(len(staged.ids)))[::-1]
    same = ps.snapshot_hash([staged.ids[i] for i in order], staged.Z[order],
                            "mert-v1-95m", {"flow"})
    assert same == staged.snapshot


def test_2_d_a_reanalysed_vector_changes_the_snapshot(staged):
    """**id 목록은 그대로인데 벡터만 바뀐 경우.** 여기가 pool_hash 로는 안 잡혔다.

    재분석이 흔하므로 이것을 못 잡으면 옛 평균과 새 벡터가 섞인 상태가 남는다 —
    오류 없이 유사도만 내려앉는다.
    """
    changed = staged.Z.copy()
    changed[3] = -changed[3]                             # 한 구간만 다시 분석됐다
    after = ps.snapshot_hash(staged.ids, changed, "mert-v1-95m", {"flow"})
    assert after != staged.snapshot

    center = ps.center(staged.data / ps.CENTER_FILE)
    with pytest.raises(ps.GenerationMismatch):
        center.require_snapshot(after)


def test_2_e_snapshot_covers_version_and_pool_config(staged):
    """임베딩 버전과 추천 풀 설정이 바뀌어도 달라져야 한다."""
    assert ps.snapshot_hash(staged.ids, staged.Z, "mert-v2-next", {"flow"})         != staged.snapshot
    assert ps.snapshot_hash(staged.ids, staged.Z, "mert-v1-95m", {"flow", "jamendo"})         != staged.snapshot


# ------------------------------------------------------------------ 3·4 적재 대상

@pytest.fixture
def loader(staged, monkeypatch):
    """적재 도구가 보는 상태를 세운다 — 카탈로그 + 판의 **실제** 벡터.

    `staged` 의 Z 는 모듈 계약을 보려고 지어낸 값이라 판의 `moments_emb.npy` 와
    다르다. 대사는 판을 기준으로 하므로 여기서는 실제 대상을 읽어 평균을 굳힌다.
    """
    from ai_server.tools import pinecone_sync as sync

    monkeypatch.setenv("MUPID_AI_ARTIFACT_DIR", str(staged.data.parents[2]))
    catalog_api.install(catalog_api.snapshot_from_tracks(_catalog_payload()))

    ids, Z, version, skipped = sync._targets()
    snapshot = sync._snapshot(ids, Z, version)
    # 가짜 인덱스는 1536차가 기본이다. 합성 판의 차원에 맞춘다 — 안 맞추면
    # 차원 검사가 (제대로) 막아서 적재까지 가지 못한다.
    for role in ps.ROLES:
        ps._index(role).dim = int(Z.shape[1])
    ps.save_center(staged.data / ps.CENTER_FILE, Z.mean(0), version,
                   "prod__mert-v1-95m", len(ids), snapshot)
    ps.forget_center()
    yield types.SimpleNamespace(data=staged.data, ids=ids, Z=Z,
                                snapshot=snapshot, skipped=skipped)
    catalog_api.reset()


def test_3_audit_compares_both_indexes_against_the_catalog(loader):
    """전환 전에 후보·원본 적재 수와 카탈로그 external_id 를 모두 대조한다."""
    from ai_server.tools import pinecone_sync as sync

    center = ps.center(loader.data / ps.CENTER_FILE)
    ps.upsert(ps.RAW, loader.ids, loader.Z)
    ps.upsert(ps.CANDIDATE, loader.ids, center.apply(loader.Z))

    rep = sync._audit()
    assert rep["expected"] == len(loader.ids)
    for role in ps.ROLES:
        assert rep["roles"][role]["present"] == len(loader.ids)
        assert rep["roles"][role]["missing_count"] == 0
        assert rep["roles"][role]["stale_count"] == 0
    assert rep["roles_disagree_count"] == 0
    assert rep["ok"] is True


def test_3_a_audit_catches_a_half_loaded_generation(loader):
    """한쪽만 올라간 상태는 후보로는 나오는데 평균에는 안 섞인다."""
    from ai_server.tools import pinecone_sync as sync

    center = ps.center(loader.data / ps.CENTER_FILE)
    ps.upsert(ps.CANDIDATE, loader.ids, center.apply(loader.Z))
    ps.upsert(ps.RAW, loader.ids[:5], loader.Z[:5])      # 원본만 모자라게

    rep = sync._audit()
    assert rep["ok"] is False
    assert rep["roles"]["raw"]["missing_count"] == len(loader.ids) - 5
    assert rep["roles_disagree_count"] == len(loader.ids) - 5


def test_4_a_ghost_in_the_index_is_reported_as_stale(loader):
    """카탈로그에 없는 external_id 는 잔존으로 잡혀야 한다."""
    from ai_server.tools import pinecone_sync as sync

    center = ps.center(loader.data / ps.CENTER_FILE)
    ps.upsert(ps.RAW, loader.ids, loader.Z)
    ps.upsert(ps.CANDIDATE, loader.ids, center.apply(loader.Z))
    ps.upsert(ps.RAW, ["SANSIL_DELETED#0"], loader.Z[:1])
    ps.upsert(ps.CANDIDATE, ["SANSIL_DELETED#0"], center.apply(loader.Z[:1]))

    rep = sync._audit()
    assert rep["ok"] is False
    assert "SANSIL_DELETED#0" in rep["roles"]["raw"]["stale"]


def test_4_a_targets_exclude_moments_the_catalog_does_not_know(loader):
    """MySQL/Spring 등록이 먼저다. 카탈로그에 없는 순간은 적재 대상이 아니다."""
    from ai_server.app import catalog_api as ca
    from ai_server.tools import pinecone_sync as sync

    snap = ca.snapshot()
    trimmed = {k: v for k, v in snap.sections.items() if k != loader.ids[0]}
    snap.sections = trimmed
    try:
        ids, _Z, _v, skipped = sync._targets()
        assert loader.ids[0] not in ids
        assert skipped >= 1
    finally:
        ca.install(ca.snapshot_from_tracks(_catalog_payload()))


def test_4_b_only_flow_moments_are_loaded(loader):
    """추천 후보인 flow 순간만 올린다. 학습용 소스는 대상이 아니다."""
    from ai_server.app import recommender as rc
    from ai_server.tools import pinecone_sync as sync

    cat = sync._pool()                       # 앞 시험의 상태에 기대지 않는다
    assert set(cat.pool.source.unique()) <= rc.POOL_SOURCES
    assert (cat.pool.source == "flow").all(), "flow 밖 소스가 후보에 섞였다"
    # 적재 대상은 색인의 풀 ∩ 카탈로그다.
    assert set(cat.pool.moment_id.astype(str)) == set(loader.ids)


# ------------------------------------------------------------------ 6 실패 모양

def test_6_a_missing_key_does_not_fall_back(staged, monkeypatch):
    monkeypatch.delenv("MUPID_PINECONE_API_KEY")
    ps.reset()
    with pytest.raises(ps.PineconeNotConfigured):
        ps.search(staged.Z[0], top_k=3)


def test_6_b_missing_index_does_not_fall_back(staged, monkeypatch):
    monkeypatch.delenv("MUPID_PINECONE_RAW_INDEX")
    ps.reset()
    with pytest.raises(ps.PineconeNotConfigured):
        ps.fetch(["T000000#0"])


def test_6_c_dimension_mismatch_does_not_fall_back(staged):
    ps.upsert(ps.RAW, staged.ids[:1], staged.Z[:1])
    FakePinecone.last.indexes["mupid-moment-raw"].dim = 999
    with pytest.raises(ps.DimensionMismatch):
        ps.check_dimension(16, ps.RAW)


def test_6_d_an_outage_does_not_fall_back(staged):
    ps.upsert(ps.CANDIDATE, staged.ids[:1], staged.Z[:1])
    FakePinecone.last.indexes["mupid-moment-candidate"].fail = RuntimeError("504")
    with pytest.raises(ps.PineconeError):
        ps.search(staged.Z[0], top_k=3)


def test_6_e_no_code_path_silently_falls_back(staged):
    """**소스로** 확인한다 — 예외를 잡아 로컬로 내려가는 자리가 없어야 한다.

    시험은 지나간 경로만 본다. 안 지나간 자리에 조용한 폴백이 하나 생기면
    그때부터 장애가 품질 저하로만 보인다.
    """
    from ai_server.app import recommender as rc

    src = inspect.getsource(rc.raw_vectors) + inspect.getsource(rc.candidate_search)
    assert "except" not in src, "벡터 접근에 예외를 삼키는 자리가 생겼다"
    # 갈래를 고르는 것은 **설정 하나**여야 한다.
    assert inspect.getsource(rc.vector_backend).count("pinecone_store.enabled()") == 1


# ------------------------------------------------------------------ 7·8 열 정리

def test_7_dropped_columns_are_gone_everywhere(staged):
    """`dur_sec`(곡)·`sound_raw` 는 읽는 곳이 없어 뺐다."""
    from ai_server.app import recommender as rc
    from ai_server.tools import slice_index as si

    assert "dur_sec" not in rc.TRACK_FEATURE_COLS
    assert "sound_raw" not in rc.TRACK_FEATURE_COLS
    assert "sound_raw" not in rc.META_FROM_FEATURES
    assert si.FEATURE_FROM_CONTENT == ("bpm",)
    assert "sound_raw" not in si.FEATURE_FROM_FLOW_META
    # 곡 메타를 만드는 자리에도 남아 있지 않아야 한다.
    assert "sound_raw" not in inspect.getsource(rc.Catalog.track_info)


def test_8_every_remaining_column_reaches_a_caller(staged):
    """남긴 열은 **실제 호출 경로**가 있어야 한다. 없으면 다음에 또 지우게 된다."""
    from ai_server.app import recommender as rc

    assert set(rc.TRACK_FEATURE_COLS) == {
        "bpm", "language", "category", "bpm_lo", "bpm_hi"}

    facets = inspect.getsource(rc.catalog_facets)
    gate = inspect.getsource(rc.filter_query_pool)
    seeds = inspect.getsource(rc.onboarding_seeds)
    info = inspect.getsource(rc.Catalog.track_info)

    # bpm — 템포 버킷 · bpm_min/max · tempo_direction · 이어 듣기 감점
    assert "bpm_source" in facets and "bpm_min" in gate
    assert "tempo_direction" in gate
    assert "bpm" in inspect.getsource(rc._flow_penalty)
    # bpm_lo/bpm_hi — bpm_requested 로 묶여 측정 BPM 보다 우선 쓰인다
    assert "bpm_lo" in info and "bpm_requested" in facets
    # language — 게이트 + 온보딩
    assert 'gate("language"' in gate and "want_lang" in seeds
    # category — parse_style 로 style 이 되고 그 style 이 게이트에 쓰인다
    assert "category" in info and "parse_style" in facets
    assert '_m(t).get("style"' in gate


# --------------------------------------------- 전체 갱신 · 멈춤 · 재개 (04:00 배치)

def test_9_reload_pauses_loads_verifies_then_resumes(loader):
    """멈춤 → 잔존 삭제 → 전량 적재 → 검증 → 재개. 순서가 전부다."""
    from ai_server.tools import pinecone_sync as sync

    ps.upsert(ps.RAW, ["SANSIL_STALE#0"], loader.Z[:1])      # 지난 판의 잔존
    assert ps.pause_state() is None

    assert sync.main(["--reload"]) == 0

    assert ps.pause_state() is None, "검증을 통과했는데 멈춘 채로 남았다"
    for role in ps.ROLES:
        have = set(ps.list_ids(role))
        assert have == set(loader.ids), role
        assert "SANSIL_STALE#0" not in have, "잔존이 지워지지 않았다"
    assert ps.center(reload=True).snapshot == loader.snapshot


def test_9_a_a_failed_reload_leaves_serving_paused(loader, monkeypatch):
    """적재·검증이 실패하면 **재개하지 않는다.** 반쯤 적재된 벡터로 추천하지 않는다."""
    from ai_server.tools import pinecone_sync as sync

    # 검증 단계에서 대사가 어긋나게 만든다 — raw 만 모자란 상태를 흉내 낸다.
    real_upsert = ps.upsert

    def half(role, ids, vectors):
        return real_upsert(role, ids[:3] if role == ps.RAW else ids,
                           vectors[:3] if role == ps.RAW else vectors)

    monkeypatch.setattr(ps, "upsert", half)
    with pytest.raises(SystemExit) as e:
        sync.main(["--reload"])
    assert e.value.code == 4
    assert ps.pause_state() is not None, "검증 실패인데 추천이 재개됐다"
    assert ps.pause_state()["reason"] == "pinecone-reload"


def test_9_b_serving_is_not_ready_while_paused(loader):
    """멈춘 동안 추천은 **분명한 오류**를 낸다. 로컬 옛 벡터로 내려가지 않는다."""
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from ai_server.app import internal_api, recommender as rc

    app = FastAPI()
    internal_api.install(app, rc)
    client = TestClient(app)

    ps.pause_serving("pinecone-reload", {"targets": len(loader.ids)})
    try:
        r = client.post("/internal/recommendations/daily",
                        json={"recommendation_id": 1, "user_id": 7})
        assert r.status_code == 503, r.text
        assert r.json()["error_code"] == "RELOAD_IN_PROGRESS"
        assert client.get("/internal/ready").status_code == 503
    finally:
        ps.resume_serving()


def test_9_c_an_unreadable_marker_counts_as_paused(loader):
    """표시를 못 읽었다고 추천을 내보내면 반쯤 적재된 벡터로 답하게 된다."""
    from ai_server.app import paths as pp

    marker = pp.pause_marker()
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text("{망가진 json", encoding="utf-8")
    try:
        state = ps.pause_state()
        assert state is not None and "읽지 못했다" in state["reason"]
    finally:
        ps.resume_serving()


def test_9_d_reload_is_a_dry_run_when_asked(loader):
    """무엇을 할지만 말하고 Pinecone 을 바꾸지 않는다."""
    from ai_server.tools import pinecone_sync as sync

    assert sync.main(["--reload", "--dry-run"]) == 0
    assert ps.pause_state() is None
    assert set(ps.list_ids(ps.RAW)) == set(), "모의 실행인데 적재됐다"
