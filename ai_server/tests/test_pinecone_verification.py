"""단일 정본 전환 검증 — 운영 인덱스도 실제 키도 쓰지 않는다.

`test_pinecone_store` 는 모듈 계약을, `test_pinecone_recommend` 는 추천 동등성을
본다. 여기서는 **전환 자체가 성립하는지**를 항목별로 확인한다.

    1  저장은 u·r 뿐이고 원본은 mu + r·u 로 정확히 되돌아오는가
    2  control 이 평균·버전·적재본을 한 묶음으로 들고 있는가
    3  전체 갱신 뒤 카탈로그 external_id 와 대조되는가
    4  삭제곡·flow 밖·카탈로그 밖이 적재·후보에서 빠지는가
    5  (test_pinecone_recommend 가 본다 — similar·daily·next·DNA·온보딩 동등성)
    6  실패에서 로컬 색인으로 조용히 내려가지 않는가
    7  track_features 에서 dur_sec·sound_raw 가 빠졌는가
    8  남은 열이 실제 호출 경로에 닿는가
    9  멈춤 → 전체 갱신 → 검증 → 재개가 그 순서로 도는가
"""
from __future__ import annotations

import inspect
import sys
import types

import numpy as np
import pytest

from ai_server.app import pinecone_store as ps

from test_pinecone_store import INDEX, NS, FakePinecone
from test_pipeline_synthetic import _build, _catalog_body


@pytest.fixture
def staged(tmp_path, monkeypatch):
    """판 하나 + 가짜 Pinecone. 적재는 시험이 한다. **Spring 카탈로그는 없다.**"""
    from ai_server.app import recommender as rc
    from ai_server.tools import pinecone_sync as sync

    module = types.ModuleType("pinecone")
    module.Pinecone = FakePinecone
    monkeypatch.setitem(sys.modules, "pinecone", module)
    monkeypatch.setenv("MUPID_PINECONE_API_KEY", "pckey-test")
    monkeypatch.setenv("MUPID_PINECONE_INDEX", INDEX)
    monkeypatch.setenv("MUPID_PINECONE_NAMESPACE", NS)
    ps.reset()

    data = _build(tmp_path)
    monkeypatch.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    monkeypatch.setattr(rc, "CAT", None)          # 이 판으로 새로 올린다

    ids, Z, version, skipped = sync._targets()
    snapshot = sync._snapshot(ids, Z, version)
    yield types.SimpleNamespace(data=data, ids=ids, Z=Z, version=version,
                                snapshot=snapshot, skipped=skipped)
    ps.reset()


def _server_says(snapshot):
    """AI 서버가 새 세대를 읽었다고 답하게 한다. 되돌리는 함수를 준다."""
    from ai_server.tools import pinecone_sync as sync

    original = sync._wait_server
    sync._wait_server = lambda snap, tries, wait: snap == snapshot

    def undo():
        sync._wait_server = original
    return undo


def _load(staged):
    """운영 배치와 같은 순서로 채운다 — control 먼저, 그 평균으로 쪼개어 적재."""
    ps.save_center(staged.Z.mean(0), staged.version, staged.snapshot, len(staged.ids))
    FakePinecone.last.indexes[INDEX].dim = int(staged.Z.shape[1])
    ps.upsert(staged.ids, staged.Z, center_=ps.center(reload=True))


# ------------------------------------------------- 1 저장 형태와 복원

def test_1_only_direction_and_radius_are_stored(staged):
    """원본 Z 를 따로 저장하지 않는다. 복원은 `mu + r·u` 다."""
    _load(staged)
    store = FakePinecone.last.indexes[INDEX].vectors[NS]

    values, meta = store[staged.ids[0]]
    stored = np.asarray(values, np.float32)
    assert not np.allclose(stored, staged.Z[0], atol=1e-4), "원본이 그대로 들어갔다"
    assert set(meta) == {ps.RADIUS_KEY}, f"실린 metadata: {sorted(meta)}"

    got = ps.fetch_raw(staged.ids)
    for i, mid in enumerate(staged.ids):
        assert np.allclose(got[mid], staged.Z[i], atol=1e-5), mid


def test_1_a_there_is_exactly_one_physical_index(staged):
    """raw 전용 인덱스를 두지 않는다."""
    _load(staged)
    assert set(FakePinecone.last.indexes) == {INDEX}
    assert set(FakePinecone.last.indexes[INDEX].vectors) == {NS, ps.control_namespace()}


# ------------------------------------------------- 2 control 이 세대를 든다

def test_2_control_holds_mean_version_and_snapshot(staged):
    _load(staged)
    center = ps.center(reload=True)

    assert np.allclose(center.mu, staged.Z.mean(0), atol=1e-6)
    assert center.embedding_version == staged.version
    assert center.snapshot == staged.snapshot
    assert center.rows == len(staged.ids)


def test_2_a_a_stale_control_is_refused(staged):
    """벡터만 바뀐 재분석도 잡는다 — id 목록만 세면 못 잡는다."""
    _load(staged)
    changed = staged.Z.copy()
    changed[1] = -changed[1]
    after = ps.snapshot_hash(staged.ids, changed, staged.version, {"flow"})

    with pytest.raises(ps.GenerationMismatch):
        ps.center(reload=True).require_snapshot(after)


def test_2_b_no_center_file_is_written_anywhere(staged):
    """평균은 control 에만 있다. 파일은 판마다 따로 움직여 어긋난다."""
    _load(staged)
    assert not list(staged.data.glob("pinecone_center*"))
    assert not hasattr(ps, "CENTER_FILE")


def test_2_c_control_carries_the_serving_state(staged):
    """중단의 근거는 control 하나다 — 파일 표시를 따로 두지 않는다."""
    from ai_server.app import paths as pp

    _load(staged)
    assert ps.center(reload=True).state == ps.READY
    assert not hasattr(pp, "pause_marker")


# ------------------------------------------------- 3·4 적재 대상과 대조

def test_3_audit_compares_the_index_against_the_catalog(staged):
    from ai_server.tools import pinecone_sync as sync

    _load(staged)
    rep = sync._audit()

    assert rep["expected"] == len(staged.ids)
    assert rep["present"] == len(staged.ids)
    assert rep["missing_count"] == 0 and rep["stale_count"] == 0
    assert rep["center"]["ok"] is True
    assert rep["ok"] is True


def test_3_a_audit_catches_a_half_loaded_index(staged):
    from ai_server.tools import pinecone_sync as sync

    ps.save_center(staged.Z.mean(0), staged.version, staged.snapshot, len(staged.ids))
    ps.upsert(staged.ids[:5], staged.Z[:5], center_=ps.center(reload=True))

    rep = sync._audit()
    assert rep["ok"] is False
    assert rep["missing_count"] == len(staged.ids) - 5


def test_4_a_ghost_in_the_index_is_reported_as_stale(staged):
    """카탈로그에 없는 external_id 는 잔존으로 잡혀야 한다."""
    from ai_server.tools import pinecone_sync as sync

    _load(staged)
    ps.upsert(["SANSIL_DELETED#0"], staged.Z[:1])

    rep = sync._audit()
    assert rep["ok"] is False
    assert "SANSIL_DELETED#0" in rep["stale"]


def test_4_a_targets_are_the_whole_index_pool_without_spring(staged, monkeypatch):
    """적재 도구도 Spring 을 부르지 않는다(연동 계약 2026-09-23 절). 대상은 색인 풀 전체다.

    예전에는 Spring 카탈로그와 교집합을 냈고, 그러려면 ROLE_SYSTEM 토큰이 있어야 했다.
    지워진 곡의 벡터가 남아도 Spring 이 보낸 후보에 없으므로 추천되지 않는다.
    """
    from ai_server.app import recommender as rc
    from ai_server.app import spring_api
    from ai_server.tools import pinecone_sync as sync

    monkeypatch.setattr(spring_api, "access_token",
                        lambda *a, **k: pytest.fail("적재 도구가 Spring 을 불렀다"))
    ids, _Z, _v, skipped = sync._targets()
    pool = rc.index_pool(sync._base())
    assert ids == pool.moment_id.astype(str).tolist()
    assert skipped == len(sync._base()._mom_index) - len(pool)


def test_4_b_only_flow_moments_are_loaded(staged):
    """추천 후보인 flow 순간만 올린다. 학습용 소스는 대상이 아니다."""
    from ai_server.app import recommender as rc
    from ai_server.tools import pinecone_sync as sync

    pool = rc.index_pool(sync._base())
    assert set(pool.source.unique()) <= rc.POOL_SOURCES
    assert (pool.source == "flow").all(), "flow 밖 소스가 후보에 섞였다"
    assert set(pool.moment_id.astype(str)) == set(staged.ids)


# ------------------------------------------------- 6 실패에서 내려가지 않는다

def test_6_a_missing_key_does_not_fall_back(staged, monkeypatch):
    monkeypatch.delenv("MUPID_PINECONE_API_KEY")
    ps.reset()
    with pytest.raises(ps.PineconeNotConfigured):
        ps.search(staged.Z[0], top_k=3)


def test_6_b_missing_index_does_not_fall_back(staged, monkeypatch):
    monkeypatch.delenv("MUPID_PINECONE_INDEX")
    ps.reset()
    with pytest.raises(ps.PineconeNotConfigured):
        ps.fetch_raw([staged.ids[0]])


def test_6_c_dimension_mismatch_does_not_fall_back(staged):
    _load(staged)
    FakePinecone.last.indexes[INDEX].dim = 999
    with pytest.raises(ps.DimensionMismatch):
        ps.check_dimension(int(staged.Z.shape[1]))


def test_6_d_an_outage_does_not_fall_back(staged):
    _load(staged)
    FakePinecone.last.indexes[INDEX].fail = RuntimeError("504")
    with pytest.raises(ps.PineconeError):
        ps.search(staged.Z[0], top_k=3)


def test_6_e_no_code_path_silently_falls_back(staged):
    """**소스로** 확인한다 — 예외를 잡아 로컬로 내려가는 자리가 없어야 한다."""
    from ai_server.app import recommender as rc

    src = inspect.getsource(rc.raw_vectors) + inspect.getsource(rc.candidate_search)
    assert "except" not in src, "벡터 접근에 예외를 삼키는 자리가 생겼다"
    assert inspect.getsource(rc.vector_backend).count("pinecone_store.enabled()") == 1


# ------------------------------------------------- 7·8 로컬 파일 정리

def test_7_dropped_columns_are_gone_everywhere(staged):
    from ai_server.app import recommender as rc
    from ai_server.tools import slice_index as si

    from ai_server.app import catalog_body as cb

    # 곡 수준 값은 이제 요청 본문으로 온다. 읽는 곳 없는 열은 계약에도 없다.
    assert "dur_sec" not in cb.TRACK_COLS and "sound_raw" not in cb.TRACK_COLS
    assert not hasattr(si, "FEATURE_FROM_CONTENT"), "색인이 곡 값을 다시 뽑는다"
    assert "track_features.parquet" in si.NOT_SHIPPED
    assert "sound_raw" not in inspect.getsource(rc.Catalog.track_info)


def test_7_a_the_embedding_file_never_ships(staged):
    """순간 벡터의 정본은 Pinecone 이다. 배송에 실으면 두 곳에서 관리하게 된다."""
    from ai_server.tools import slice_index as si

    assert "moments_emb.npy" in si.NOT_SHIPPED


def test_8_every_remaining_column_reaches_a_caller(staged):
    """남긴 열은 **실제 호출 경로**가 있어야 한다."""
    from ai_server.app import recommender as rc

    from ai_server.app import catalog_body as cb

    # 본문의 곡 값이 **실제로 읽히는지** 본다. 조건 게이트(bpm 범위·언어·스타일)는
    # Spring 으로 옮겼으므로 여기 남는 호출자는 점수·온보딩·표시다.
    assert set(rc.META_FROM_CATALOG) == {"language", "category", "bpm_lo", "bpm_hi"}
    assert {"bpm", "language", "category", "bpm_requested_min",
            "bpm_requested_max", "duration_ms"} <= set(cb.TRACK_COLS)

    facets = inspect.getsource(rc.catalog_facets)
    seeds = inspect.getsource(rc.onboarding_seeds)
    info = inspect.getsource(rc.Catalog.track_info)

    assert "bpm_source" in facets
    assert "bpm" in inspect.getsource(rc._flow_penalty)
    assert "bpm_lo" in info and "bpm_requested" in facets
    assert "want_lang" in seeds
    assert "category" in info and "parse_style" in facets
    assert "duration_ms" in inspect.getsource(rc._attach_section_meta)


# ------------------------------------------------- 9 전체 갱신 · 중단 · 재개

def test_9_reload_pauses_loads_verifies_then_resumes(staged):
    """멈춤 → 전체 삭제 → control → 전량 적재 → 검증 → 재개."""
    from ai_server.tools import pinecone_sync as sync

    _load(staged)
    ps.upsert(["SANSIL_STALE#0"], staged.Z[:1])          # 지난 판의 잔존
    ps.set_state(ps.UPDATING)                            # 지난 회차가 끊긴 상태
    undo = _server_says(staged.snapshot)

    try:
        assert sync.main(["--reload"]) == 0
    finally:
        undo()

    assert ps.center(reload=True).state == ps.READY, "검증을 통과했는데 UPDATING 이다"
    have = set(ps.list_ids())
    assert have == set(staged.ids)
    assert "SANSIL_STALE#0" not in have, "잔존이 지워지지 않았다"
    assert ps.center(reload=True).snapshot == staged.snapshot


def test_9_a_a_failed_reload_leaves_serving_paused(staged, monkeypatch):
    """적재·검증이 실패하면 **재개하지 않는다.**"""
    from ai_server.tools import pinecone_sync as sync

    _load(staged)
    ps.set_state(ps.UPDATING)
    real = ps.upsert
    monkeypatch.setattr(ps, "upsert",
                        lambda ids, V, center_=None: real(ids[:3], V[:3], center_))

    with pytest.raises(SystemExit) as e:
        sync.main(["--reload"])
    assert e.value.code == 4
    assert ps.center(reload=True).state == ps.UPDATING, "검증 실패인데 재개됐다"


def test_9_b_serving_is_not_ready_while_paused(staged, monkeypatch):
    """멈춘 동안 추천은 **분명한 오류**를 낸다."""
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from ai_server.app import internal_api, recommender as rc

    _load(staged)
    monkeypatch.setenv('MUPID_PINECONE_ENABLED', '1')
    app = FastAPI()
    internal_api.install(app, rc)
    client = TestClient(app)

    ps.set_state(ps.UPDATING)
    try:
        r = client.post("/internal/recommendations/daily",
                        json={"recommendation_id": 1, "user_id": 7,
                              "catalog": _catalog_body()})
        assert r.status_code == 503, r.text
        assert r.json()["error_code"] == "RELOAD_IN_PROGRESS"
        assert client.get("/internal/ready").status_code == 503
    finally:
        ps.set_state(ps.READY)


def test_9_c_a_same_snapshot_reload_is_a_no_op(staged):
    """바뀐 것이 없으면 지우지도 멈추지도 않는다. 그 중단은 순전한 손해다."""
    from ai_server.tools import pinecone_sync as sync

    _load(staged)
    before = len(FakePinecone.last.indexes[INDEX].calls)

    assert sync.main(["--reload"]) == 0

    after = FakePinecone.last.indexes[INDEX].calls[before:]
    assert not any(c[0] == "delete_all" for c in after), "같은 적재본인데 지웠다"
    assert not any(c[0] == "upsert" for c in after), "같은 적재본인데 다시 올렸다"
    assert ps.center(reload=True).state == ps.READY


def test_9_f_the_server_must_confirm_before_ready(staged, monkeypatch):
    """서버가 새 세대를 읽었는지 확인하지 못하면 READY 로 넘어가지 않는다."""
    from ai_server.tools import pinecone_sync as sync

    _load(staged)
    ps.set_state(ps.UPDATING)
    monkeypatch.delenv("MUPID_AI_SERVER_URL", raising=False)

    with pytest.raises(SystemExit) as e:
        sync.main(["--reload", "--server-tries", "1", "--server-wait", "0"])
    assert e.value.code == 4
    assert ps.center(reload=True).state == ps.UPDATING


def test_9_d_reload_is_a_dry_run_when_asked(staged):
    """무엇을 할지만 말하고 Pinecone 을 바꾸지 않는다."""
    from ai_server.tools import pinecone_sync as sync

    _load(staged)
    before = set(ps.list_ids())
    assert sync.main(["--reload", "--dry-run"]) == 0
    assert ps.center(reload=True).state == ps.READY
    assert set(ps.list_ids()) == before


def test_9_e_resume_rechecks_before_it_lets_serving_back(staged):
    """`--resume` 은 상태만 바꾸는 것이 아니다. 정합성을 다시 통과해야 한다.

    우회 경로(`--force`)는 두지 않는다 — 있으면 결국 그 길로 열게 된다.
    """
    from ai_server.tools import pinecone_sync as sync

    ps.save_center(staged.Z.mean(0), staged.version, staged.snapshot,
                   len(staged.ids), state=ps.UPDATING)
    FakePinecone.last.indexes[INDEX].dim = int(staged.Z.shape[1])
    ps.upsert(staged.ids[:2], staged.Z[:2], center_=ps.center(reload=True))

    with pytest.raises(SystemExit) as e:
        sync.main(["--resume"])
    assert e.value.code == 4
    assert ps.center(reload=True).state == ps.UPDATING, "대사가 틀렸는데 재개됐다"

    # 우회 스위치가 아예 없어야 한다.
    with pytest.raises(SystemExit):
        sync.main(["--resume", "--force"])
