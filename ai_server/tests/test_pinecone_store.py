"""순간 벡터 저장소 — **실제 키 없이** 계약을 못박는다.

SDK 를 가짜로 바꿔 끼운다. 망을 타지 않을 뿐 담기고 나가는 길은 운영과 같다.

여기서 지키는 것은 주로 **틀려도 오류가 나지 않는 것들**이다.

  · 원본을 잘못 복원하면 DNA·온보딩 평균이 어긋난 공간에 놓인다
  · DB 소유 metadata 가 한 번 실리면 DB 를 고쳐도 검색이 옛 값으로 돈다
  · 차원이 다르면 upsert 가 건건이 거절되고 배치 로그에만 쌓인다
  · Pinecone 장애를 빈 목록으로 바꾸면 "닮은 구간 없음" 과 구분되지 않는다
"""
from __future__ import annotations

import sys
import types

import numpy as np
import pytest

from ai_server.app import pinecone_store as ps


# ------------------------------------------------------------------ 가짜 SDK

class FakeIndex:
    # DB 가 소유한 값. 하나라도 실리면 시험이 여기서 터져야 한다 — 한 번 들어가면
    # DB 를 고쳐도 검색 쪽이 옛 값으로 돌고, 그건 오류가 나지 않는 고장이다.
    DB_OWNED = {"title", "music_id", "genre", "genres", "mood", "moods", "vocal",
                "vocal_gender", "has_vocal", "instrument", "instruments",
                "section_label", "start_ms", "end_ms", "start_sec", "end_sec",
                "is_deleted", "user_id", "track_id"}

    def __init__(self, name, dim=1536):
        self.name = name
        self.dim = dim
        self.vectors: dict = {}          # namespace -> {id: (values, metadata)}
        self.calls: list = []
        self.fail: Exception | None = None

    def _ns(self, namespace):
        return self.vectors.setdefault(namespace or "", {})

    def upsert(self, vectors, namespace=None):
        self.calls.append(("upsert", namespace, len(vectors)))
        if self.fail:
            raise self.fail
        store = self._ns(namespace)
        for v in vectors:
            assert set(v) <= {"id", "values", "metadata"}, f"모르는 필드: {sorted(v)}"
            meta = v.get("metadata") or {}
            leaked = sorted(set(meta) & self.DB_OWNED)
            assert not leaked, f"DB 소유 메타데이터가 실렸다: {leaked}"
            store[v["id"]] = (list(v["values"]), dict(meta))
        return {"upserted_count": len(vectors)}

    def delete(self, ids=None, namespace=None, delete_all=False):
        self.calls.append(("delete_all" if delete_all else "delete",
                           namespace, 0 if delete_all else len(ids or [])))
        if self.fail:
            raise self.fail
        store = self._ns(namespace)
        if delete_all:
            store.clear()
            return {}
        for i in ids or []:
            store.pop(i, None)
        return {}

    def query(self, vector, top_k, namespace=None, include_values=False,
              include_metadata=False):
        self.calls.append(("query", namespace, top_k))
        if self.fail:
            raise self.fail
        assert include_metadata is False, "후보 검색에 metadata 를 켜면 안 된다"
        store = self._ns(namespace)
        q = np.asarray(vector, dtype=np.float32)
        scored = sorted(((i, float(np.dot(np.asarray(v, np.float32), q)))
                         for i, (v, _m) in store.items()), key=lambda kv: -kv[1])
        return {"matches": [{"id": i, "score": s} for i, s in scored[:top_k]]}

    def fetch(self, ids, namespace=None):
        self.calls.append(("fetch", namespace, len(ids)))
        if self.fail:
            raise self.fail
        store = self._ns(namespace)
        return {"vectors": {i: {"values": store[i][0], "metadata": store[i][1]}
                            for i in ids if i in store}}

    def describe_index_stats(self):
        if self.fail:
            raise self.fail
        return {"dimension": self.dim,
                "namespaces": {ns: {"vector_count": len(v)}
                               for ns, v in self.vectors.items()},
                "total_vector_count": sum(len(v) for v in self.vectors.values())}

    def list(self, namespace=None):
        yield list(self._ns(namespace))


class FakePinecone:
    last: "FakePinecone | None" = None

    def __init__(self, api_key):
        assert api_key, "키 없이 클라이언트를 만들면 안 된다"
        self.api_key = api_key
        self.indexes: dict = {}
        FakePinecone.last = self

    def Index(self, name):                       # noqa: N802  (SDK 이름 그대로)
        return self.indexes.setdefault(name, FakeIndex(name))


INDEX = "mupid-moment"
NS = "prod__mert-v1-95m"
CONTROL_NS = NS + "__control"


@pytest.fixture
def pc(monkeypatch):
    """가짜 SDK 를 물리고 환경을 세운다."""
    module = types.ModuleType("pinecone")
    module.Pinecone = FakePinecone
    monkeypatch.setitem(sys.modules, "pinecone", module)

    monkeypatch.setenv("MUPID_PINECONE_API_KEY", "pckey-secret-value")
    monkeypatch.setenv("MUPID_PINECONE_INDEX", INDEX)
    monkeypatch.setenv("MUPID_PINECONE_NAMESPACE", NS)
    monkeypatch.setenv("MUPID_PINECONE_ENABLED", "1")
    ps.reset()
    yield
    ps.reset()


def _unit(rows, dim=1536, seed=0):
    rng = np.random.default_rng(seed)
    V = rng.normal(size=(rows, dim)).astype(np.float32)
    return V / np.linalg.norm(V, axis=1, keepdims=True)


def _store(namespace=NS):
    return FakePinecone.last.indexes[INDEX].vectors[namespace]


def _seed_center(dim=1536, seed=5, rows=0, snapshot="snap", version="mert-v1-95m",
                 state=None):
    """control 에 평균을 심는다. 적재·복원이 모두 이것을 기준으로 돈다."""
    mu = np.random.default_rng(seed).normal(size=dim).astype(np.float32) * 0.01
    ps.save_center(mu, version, snapshot, rows, state=state or ps.READY)
    return mu


# ------------------------------------------------------------------ 설정

def test_settings_never_carry_the_key(pc):
    """설정은 로그와 /health 로 나간다. 값도 앞자리도 실리지 않아야 한다."""
    raw = repr(ps.settings())
    assert "pckey-secret-value" not in raw
    assert ps.settings()["api_key_present"] is True
    assert ps.settings()["configured"] is True


def test_not_configured_without_the_index_name(pc, monkeypatch):
    monkeypatch.delenv("MUPID_PINECONE_INDEX")
    assert ps.configured() is False


def test_search_is_off_by_default(monkeypatch):
    """키만 넣으면 켜지면 안 된다. 비어 있는 인덱스로 검색이 나가면 전부 0건이다."""
    monkeypatch.delenv("MUPID_PINECONE_ENABLED", raising=False)
    assert ps.enabled() is False


def test_control_namespace_is_derived_and_distinct(pc):
    """평균이 데이터와 같은 namespace 에 있으면 검색 결과에 섞여 나온다."""
    assert ps.namespace() == NS
    assert ps.control_namespace() == CONTROL_NS != NS


# -------------------------------------------------------- 저장 형태: u 와 r

def test_upsert_stores_direction_and_radius_not_the_original(pc):
    """저장하는 것은 `u=(Z-mu)/‖Z-mu‖` 와 `r=‖Z-mu‖` 다. 원본 Z 가 아니다."""
    mu = _seed_center()
    Z = _unit(2, seed=1)
    ps.upsert(["A#0", "A#1"], Z)

    values, meta = _store()["A#0"]
    stored = np.asarray(values, np.float32)
    assert np.isclose(np.linalg.norm(stored), 1.0, atol=1e-5), "방향이 단위벡터가 아니다"
    assert not np.allclose(stored, Z[0], atol=1e-4), "원본이 그대로 들어갔다"

    expect = Z[0] - mu
    r = float(np.linalg.norm(expect))
    assert np.allclose(stored, expect / r, atol=1e-5)
    assert set(meta) == {ps.RADIUS_KEY}, f"실린 metadata: {sorted(meta)}"
    assert meta[ps.RADIUS_KEY] == pytest.approx(r, rel=1e-5)


def test_fetch_raw_restores_the_original_exactly(pc):
    """`Z = mu + r·u`. raw 전용 인덱스를 두지 않는 근거가 이 한 줄이다."""
    _seed_center()
    Z = _unit(4, seed=3)
    ids = [f"A#{i}" for i in range(4)]
    ps.upsert(ids, Z)

    got = ps.fetch_raw(ids)
    for i, mid in enumerate(ids):
        assert np.allclose(got[mid], Z[i], atol=1e-5), mid


def test_fetch_raw_skips_records_without_a_radius(pc):
    """`r` 이 없으면 복원할 수 없다. 0 으로 채우면 평균이 조용히 끌려간다."""
    _seed_center()
    ps.upsert(["A#0"], _unit(1))
    values, _meta = _store()["A#0"]
    _store()["A#1"] = (values, {})               # r 이 없는 레코드

    assert set(ps.fetch_raw(["A#0", "A#1"])) == {"A#0"}


def test_fetch_raw_silently_skips_ids_that_are_gone(pc):
    _seed_center()
    ps.upsert(["A#0"], _unit(1))
    assert set(ps.fetch_raw(["A#0", "SANSIL#9"])) == {"A#0"}


def test_db_owned_metadata_is_rejected(pc):
    """가짜 SDK 가 막는다. 한 번 들어가면 DB 를 고쳐도 검색이 옛 값으로 돈다."""
    _seed_center()
    index = FakePinecone.last.indexes[INDEX]
    with pytest.raises(AssertionError, match="DB 소유"):
        index.upsert([{"id": "A#0", "values": [0.0] * 4,
                       "metadata": {"title": "곡 이름"}}], namespace=NS)


# ------------------------------------------------------------------ 적재 계약

def test_reanalysis_replaces_the_vector_under_the_same_id(pc):
    """재분석은 같은 external_id 로 교체한다 — 중복이 생길 자리가 없다."""
    _seed_center()
    first, second = _unit(1, seed=1), _unit(1, seed=2)
    ps.upsert(["T000001#0"], first)
    ps.upsert(["T000001#0"], second)

    assert len(_store()) == 1
    assert np.allclose(ps.fetch_raw(["T000001#0"])["T000001#0"], second[0], atol=1e-5)


def test_delete_removes_the_moment(pc):
    _seed_center()
    ps.upsert(["A#0"], _unit(1))
    ps.delete(["A#0"])
    assert not _store()


def test_deleting_an_absent_id_is_not_an_error(pc):
    """재실행을 안전하게 두려고 그렇게 한다."""
    _seed_center()
    ps.upsert(["A#0"], _unit(1))
    assert ps.delete(["없는#9"]) == 1


def test_delete_all_empties_data_but_keeps_control(pc):
    """전체 갱신의 첫 단계다. 평균까지 지우면 복원할 근거가 사라진다."""
    _seed_center()
    ps.upsert(["A#0", "A#1"], _unit(2))

    ps.delete_all()

    assert not _store()
    assert ps.CONTROL_ID in _store(CONTROL_NS), "control 까지 지웠다"


def test_delete_all_on_a_namespace_that_never_existed_is_empty(pc):
    """serverless 는 한 번도 쓰지 않은 namespace 를 지우라면 404 를 준다 — 최초 적재."""
    class NotFoundException(Exception):
        status = 404

    _seed_center()
    FakePinecone.last.indexes[INDEX].fail = NotFoundException("(404) Not Found")
    ps.delete_all()                               # 예외가 없어야 한다


def test_delete_all_still_fails_on_other_errors(pc):
    _seed_center()
    FakePinecone.last.indexes[INDEX].fail = RuntimeError("connection reset pckey-secret-value")
    with pytest.raises(ps.PineconeError) as got:
        ps.delete_all()
    assert "pckey-secret-value" not in str(got.value), "키가 메시지에 실렸다"


def test_upsert_refuses_mismatched_lengths(pc):
    _seed_center()
    with pytest.raises(ValueError):
        ps.upsert(["A#0", "A#1"], _unit(1))


def test_batches_stay_under_the_request_limit(pc):
    """1536차 float 하나가 약 18KB 다. 개수 상한으로 잡으면 크기 상한에 먼저 걸린다."""
    _seed_center(dim=8)
    n = ps.UPSERT_BATCH * 2 + 5
    ps.upsert([f"A#{i}" for i in range(n)], _unit(n, dim=8))

    sizes = [c[2] for c in FakePinecone.last.indexes[INDEX].calls
             if c[0] == "upsert" and c[1] == NS]
    assert max(sizes) <= ps.UPSERT_BATCH
    assert sum(sizes) == n


def test_the_namespace_is_fixed_not_swapped(pc):
    """**무중단 namespace 전환을 만들지 않는다.** 갈아 끼우는 것은 그 안의 벡터다."""
    _seed_center()
    ps.upsert(["A#0", "A#1"], _unit(2, seed=1))
    ps.upsert(["A#0", "A#1"], _unit(2, seed=2))

    store = FakePinecone.last.indexes[INDEX].vectors
    assert set(store) == {NS, CONTROL_NS}, "namespace 가 늘었다"
    assert len(store[NS]) == 2


def test_every_call_carries_the_right_namespace(pc):
    """데이터와 control 이 섞이면 평균이 검색 결과로 나온다."""
    _seed_center()
    ps.upsert(["A#0"], _unit(1))
    ps.search(_unit(1)[0], top_k=1)
    ps.fetch_raw(["A#0"])
    ps.delete(["A#0"])

    seen = {(c[0], c[1]) for c in FakePinecone.last.indexes[INDEX].calls}
    assert ("upsert", CONTROL_NS) in seen          # save_center
    assert {("upsert", NS), ("query", NS), ("fetch", NS), ("delete", NS)} <= seen
    assert ("query", CONTROL_NS) not in seen


# ------------------------------------------------------------------ 실패 모양

def test_dimension_mismatch_stops_before_loading(pc):
    """차원은 만든 뒤 바꿀 수 없다. 적재 전에 멈춰야 한다."""
    _seed_center()
    FakePinecone.last.indexes[INDEX].dim = 768
    with pytest.raises(ps.DimensionMismatch) as e:
        ps.check_dimension(1536)
    assert "768" in str(e.value) and "1536" in str(e.value)


def test_a_matching_dimension_reports_the_counts(pc):
    _seed_center()
    ps.upsert(["A#0", "A#1"], _unit(2))
    got = ps.check_dimension(1536)
    assert got["vectors_in_namespace"] == 2
    assert got["vectors_in_control"] == 1
    assert got["namespace"] == NS


def test_an_outage_raises_and_never_returns_an_empty_list(pc):
    """빈 목록으로 바꾸면 '닮은 구간 없음'(정상)과 구분되지 않는다."""
    _seed_center()
    ps.upsert(["A#0"], _unit(1))
    FakePinecone.last.indexes[INDEX].fail = RuntimeError("connection reset")

    with pytest.raises(ps.PineconeError) as e:
        ps.search(_unit(1)[0], top_k=5)
    assert "connection reset" in str(e.value)


def test_the_key_never_leaks_into_an_error_message(pc):
    _seed_center()
    ps.forget_center()
    FakePinecone.last.indexes[INDEX].fail = RuntimeError(
        "auth failed for pckey-secret-value")
    with pytest.raises(ps.PineconeError) as e:
        ps.fetch_raw(["A#0"])
    assert "pckey-secret-value" not in str(e.value)
    assert "***" in str(e.value)


def test_missing_key_is_reported_not_guessed(pc, monkeypatch):
    monkeypatch.delenv("MUPID_PINECONE_API_KEY")
    ps.reset()
    with pytest.raises(ps.PineconeNotConfigured):
        ps.fetch_raw(["A#0"])


def test_missing_index_name_names_the_variable(pc, monkeypatch):
    monkeypatch.delenv("MUPID_PINECONE_INDEX")
    ps.reset()
    with pytest.raises(ps.PineconeNotConfigured) as e:
        ps.search(_unit(1)[0], top_k=1)
    assert "MUPID_PINECONE_INDEX" in str(e.value)


def test_a_missing_control_record_stops_instead_of_improvising(pc):
    """즉석 평균으로 대신하면 적재 때와 다른 공간으로 질의가 나간다."""
    with pytest.raises(ps.PineconeError) as e:
        ps.center(reload=True)
    assert ps.CONTROL_ID in str(e.value)


# ------------------------------------------------------------------ 중심화

def test_center_matches_the_recommender_transform(pc):
    """`Center.apply` 와 `recommender.center` 가 갈리면 순위가 갈린다."""
    from ai_server.app import recommender as rc

    mu = _seed_center(dim=16, seed=9)
    V = np.random.default_rng(3).normal(size=(5, 16)).astype(np.float32)
    got = ps.center(reload=True).apply(V)

    expect = V - mu
    expect /= np.clip(np.linalg.norm(expect, axis=1, keepdims=True), 1e-9, None)
    assert np.allclose(got, expect, atol=1e-5)
    assert hasattr(rc, "center")


def test_split_and_restore_round_trip(pc):
    _seed_center(dim=16, seed=9)
    center = ps.center(reload=True)
    V = np.random.default_rng(4).normal(size=(6, 16)).astype(np.float32)
    U, r = center.split(V)
    assert np.allclose(center.restore(U, r), V, atol=1e-5)


def test_center_refuses_a_vector_of_another_width(pc):
    _seed_center(dim=16)
    with pytest.raises(ps.DimensionMismatch):
        ps.center(reload=True).apply(np.zeros((2, 8), np.float32))


def test_the_control_record_carries_the_generation(pc):
    _seed_center(dim=16, rows=24, snapshot="deadbeef")
    center = ps.center(reload=True)
    assert center.rows == 24
    assert center.snapshot == "deadbeef"
    assert center.embedding_version == "mert-v1-95m"
    center.require_snapshot("deadbeef")
    with pytest.raises(ps.GenerationMismatch):
        center.require_snapshot("other")


# ------------------------------------------------------------------ 적재본 지문

def test_snapshot_changes_when_only_a_vector_changes(pc):
    """재분석으로 값만 바뀐 경우. id 만 세면 못 잡는다."""
    ids = ["A#0", "A#1", "A#2"]
    Z = _unit(3, dim=8, seed=1)
    base = ps.snapshot_hash(ids, Z, "mert-v1-95m", {"flow"})

    changed = Z.copy()
    changed[1] = -changed[1]
    assert ps.snapshot_hash(ids, changed, "mert-v1-95m", {"flow"}) != base


def test_snapshot_ignores_order(pc):
    ids = ["A#0", "A#1", "A#2"]
    Z = _unit(3, dim=8, seed=1)
    order = [2, 0, 1]
    shuffled = ps.snapshot_hash([ids[i] for i in order], Z[order],
                                "mert-v1-95m", {"flow"})
    assert shuffled == ps.snapshot_hash(ids, Z, "mert-v1-95m", {"flow"})


def test_snapshot_covers_version_and_pool_config(pc):
    ids = ["A#0"]
    Z = _unit(1, dim=8)
    base = ps.snapshot_hash(ids, Z, "mert-v1-95m", {"flow"})
    assert ps.snapshot_hash(ids, Z, "mert-v2", {"flow"}) != base
    assert ps.snapshot_hash(ids, Z, "mert-v1-95m", {"flow", "jamendo"}) != base


# ------------------------------------------------------------------ control 상태

def test_state_defaults_to_ready_and_round_trips(pc):
    _seed_center(dim=16)
    assert ps.center(reload=True).state == ps.READY

    ps.set_state(ps.UPDATING)
    assert ps.center(reload=True).state == ps.UPDATING

    ps.set_state(ps.READY)
    assert ps.center(reload=True).state == ps.READY


def test_set_state_keeps_the_mean_and_generation(pc):
    """상태만 바꾼다. 평균까지 갈아 끼우면 그 순간 질의 공간이 달라진다."""
    mu = _seed_center(dim=16, rows=7, snapshot="gen-1")
    ps.set_state(ps.UPDATING)

    got = ps.center(reload=True)
    assert np.allclose(got.mu, mu, atol=1e-6)
    assert got.rows == 7 and got.snapshot == "gen-1"


def test_serving_center_refuses_while_updating(pc):
    """UPDATING 이면 추천하지 않는다. 로컬로 내려가지도 않는다."""
    _seed_center(dim=16, state=ps.UPDATING)
    with pytest.raises(ps.ServingPaused):
        ps.serving_center()


def test_serving_center_swaps_the_mean_when_the_generation_changes(pc):
    """**서버를 다시 띄우지 않아도** 새 평균을 쓴다."""
    first = _seed_center(dim=16, seed=1, snapshot="gen-1")
    assert np.allclose(ps.serving_center().mu, first, atol=1e-6)

    second = _seed_center(dim=16, seed=2, snapshot="gen-2")
    got = ps.serving_center()
    assert np.allclose(got.mu, second, atol=1e-6), "옛 평균을 계속 쓰고 있다"
    assert got.snapshot == "gen-2"


def test_serving_center_refreshes_even_while_updating(pc):
    """배치가 '서버가 새 세대를 읽었나' 를 확인한 뒤 READY 로 간다.

    UPDATING 에서 캐시를 갱신하지 않으면 서로 기다리다 멈춘다.
    """
    _seed_center(dim=16, seed=1, snapshot="gen-1")
    ps.serving_center()

    _seed_center(dim=16, seed=2, snapshot="gen-2", state=ps.UPDATING)
    with pytest.raises(ps.ServingPaused):
        ps.serving_center()
    assert ps.center().snapshot == "gen-2", "UPDATING 이라고 세대를 안 읽었다"


def test_serving_center_reads_one_record_not_the_whole_list(pc):
    """요청마다 전체 목록을 훑으면 안 된다."""
    _seed_center(dim=16)
    ps.upsert(["A#0", "A#1"], _unit(2, dim=16))
    before = len(FakePinecone.last.indexes[INDEX].calls)

    ps.serving_center()

    calls = FakePinecone.last.indexes[INDEX].calls[before:]
    assert [c[0] for c in calls] == ["fetch"], calls
    assert all(c[0] != "list" for c in calls)


def test_the_file_based_pause_marker_is_gone(pc):
    """중단의 근거는 control 하나다. 두 곳에 두면 어느 쪽이 참인지 갈린다."""
    from ai_server.app import paths as pp

    for name in ("pause_serving", "resume_serving", "pause_state"):
        assert not hasattr(ps, name), name
    assert not hasattr(pp, "pause_marker")
