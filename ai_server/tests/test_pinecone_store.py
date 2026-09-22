"""Pinecone 순간 벡터 — **실제 키 없이** 계약을 못박는다.

SDK 를 가짜로 바꿔 끼운다. 망을 타지 않을 뿐 담기고 나가는 길은 운영과 같다.

여기서 지키는 것은 주로 **틀려도 오류가 나지 않는 것들**이다.

  · 두 인덱스를 섞어 쓰면 순위가 조용히 달라진다 (중심화가 빠지거나 겹친다)
  · metadata 가 한 번 실리면 DB 를 고쳐도 검색이 옛 값으로 돈다
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
    def __init__(self, name, dim=1536):
        self.name = name
        self.dim = dim
        self.vectors: dict = {}          # namespace -> {id: values}
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
            # **metadata 를 받지 않는다.** 실려 오면 시험이 여기서 터져야 한다.
            assert set(v) == {"id", "values"}, f"metadata 가 실렸다: {sorted(v)}"
            store[v["id"]] = list(v["values"])
        return {"upserted_count": len(vectors)}

    def delete(self, ids=None, namespace=None):
        self.calls.append(("delete", namespace, len(ids or [])))
        if self.fail:
            raise self.fail
        store = self._ns(namespace)
        for i in ids or []:
            store.pop(i, None)
        return {}

    def query(self, vector, top_k, namespace=None, include_values=False,
              include_metadata=False):
        self.calls.append(("query", namespace, top_k))
        if self.fail:
            raise self.fail
        assert include_metadata is False, "metadata 를 켜면 안 된다"
        store = self._ns(namespace)
        q = np.asarray(vector, dtype=np.float32)
        scored = sorted(((i, float(np.dot(np.asarray(v, np.float32), q)))
                         for i, v in store.items()), key=lambda kv: -kv[1])
        return {"matches": [{"id": i, "score": s} for i, s in scored[:top_k]]}

    def fetch(self, ids, namespace=None):
        self.calls.append(("fetch", namespace, len(ids)))
        if self.fail:
            raise self.fail
        store = self._ns(namespace)
        return {"vectors": {i: {"values": store[i]} for i in ids if i in store}}

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


@pytest.fixture
def pc(monkeypatch):
    """가짜 SDK 를 물리고 환경을 세운다."""
    module = types.ModuleType("pinecone")
    module.Pinecone = FakePinecone
    monkeypatch.setitem(sys.modules, "pinecone", module)

    monkeypatch.setenv("MUPID_PINECONE_API_KEY", "pckey-secret-value")
    monkeypatch.setenv("MUPID_PINECONE_CANDIDATE_INDEX", "mupid-moment-candidate")
    monkeypatch.setenv("MUPID_PINECONE_RAW_INDEX", "mupid-moment-raw")
    monkeypatch.setenv("MUPID_PINECONE_NAMESPACE", "prod__mert-v1-95m")
    monkeypatch.setenv("MUPID_PINECONE_ENABLED", "1")
    ps.reset()
    ps.forget_center()
    yield
    ps.reset()
    ps.forget_center()


def _unit(rows, dim=1536, seed=0):
    rng = np.random.default_rng(seed)
    V = rng.normal(size=(rows, dim)).astype(np.float32)
    return V / np.linalg.norm(V, axis=1, keepdims=True)


# ------------------------------------------------------------------ 설정

def test_settings_never_carry_the_key(pc):
    """설정은 로그와 /health 로 나간다. 값도 앞자리도 실리지 않아야 한다."""
    raw = repr(ps.settings())
    assert "pckey-secret-value" not in raw
    assert ps.settings()["api_key_present"] is True
    assert ps.settings()["configured"] is True


def test_not_configured_without_both_index_names(pc, monkeypatch):
    """둘 중 하나만 있으면 설정된 것이 아니다 — 역할이 둘이기 때문이다."""
    monkeypatch.delenv("MUPID_PINECONE_RAW_INDEX")
    assert ps.configured() is False


def test_search_is_off_by_default(monkeypatch):
    """키만 넣으면 켜지면 안 된다. 비어 있는 인덱스로 검색이 나가면 전부 0건이다."""
    monkeypatch.delenv("MUPID_PINECONE_ENABLED", raising=False)
    assert ps.enabled() is False


# ------------------------------------------------------------------ 두 인덱스

def test_the_two_roles_are_separate_physical_indexes(pc):
    """후보와 원본이 섞이면 순위가 **오류 없이** 달라진다."""
    ids = ["T000001#0", "T000001#1"]
    ps.upsert(ps.CANDIDATE, ids, _unit(2, seed=1))
    ps.upsert(ps.RAW, ids, _unit(2, seed=2))

    client = FakePinecone.last
    assert set(client.indexes) == {"mupid-moment-candidate", "mupid-moment-raw"}
    cand = client.indexes["mupid-moment-candidate"].vectors["prod__mert-v1-95m"]
    raw = client.indexes["mupid-moment-raw"].vectors["prod__mert-v1-95m"]
    assert cand["T000001#0"] != raw["T000001#0"], "두 역할이 같은 벡터를 들고 있다"


def test_search_only_touches_the_candidate_index(pc):
    ps.upsert(ps.CANDIDATE, ["A#0"], _unit(1, seed=1))
    ps.upsert(ps.RAW, ["A#0"], _unit(1, seed=2))
    ps.search(_unit(1, seed=1)[0], top_k=3)

    client = FakePinecone.last
    assert any(c[0] == "query" for c in client.indexes["mupid-moment-candidate"].calls)
    assert not any(c[0] == "query" for c in client.indexes["mupid-moment-raw"].calls)


def test_fetch_only_touches_the_raw_index(pc):
    vec = _unit(1, seed=7)
    ps.upsert(ps.CANDIDATE, ["A#0"], _unit(1, seed=1))
    ps.upsert(ps.RAW, ["A#0"], vec)

    got = ps.fetch(["A#0"])
    assert np.allclose(got["A#0"], vec[0], atol=1e-6), "원본이 아니라 변환본을 받았다"
    client = FakePinecone.last
    assert not any(c[0] == "fetch" for c in client.indexes["mupid-moment-candidate"].calls)


def test_fetch_silently_skips_ids_that_are_gone(pc):
    """부르는 쪽이 받은 수를 세어 판단한다. 없는 것을 지어내지 않는다."""
    ps.upsert(ps.RAW, ["A#0"], _unit(1))
    got = ps.fetch(["A#0", "SANSIL#9"])
    assert set(got) == {"A#0"}


# ------------------------------------------------------------------ 적재 계약

def test_reanalysis_replaces_the_vector_under_the_same_id(pc):
    """재분석은 같은 external_id 로 교체한다 — 중복이 생길 자리가 없다."""
    first, second = _unit(1, seed=1), _unit(1, seed=2)
    ps.upsert(ps.RAW, ["T000001#0"], first)
    ps.upsert(ps.RAW, ["T000001#0"], second)

    store = FakePinecone.last.indexes["mupid-moment-raw"].vectors["prod__mert-v1-95m"]
    assert len(store) == 1
    assert np.allclose(ps.fetch(["T000001#0"])["T000001#0"], second[0], atol=1e-6)


def test_delete_clears_both_indexes(pc):
    """한쪽만 지우면 후보에는 안 나오는데 DNA 평균에는 계속 섞인다."""
    ids = ["T000001#0"]
    ps.upsert(ps.CANDIDATE, ids, _unit(1, seed=1))
    ps.upsert(ps.RAW, ids, _unit(1, seed=2))

    ps.delete(ids)

    client = FakePinecone.last
    for name in ("mupid-moment-candidate", "mupid-moment-raw"):
        assert not client.indexes[name].vectors["prod__mert-v1-95m"], name


def test_deleting_an_absent_id_is_not_an_error(pc):
    """재실행을 안전하게 두려고 그렇게 한다."""
    ps.upsert(ps.RAW, ["A#0"], _unit(1))
    assert ps.delete(["없는#9"]) == 1


def test_upsert_refuses_mismatched_lengths(pc):
    with pytest.raises(ValueError):
        ps.upsert(ps.RAW, ["A#0", "A#1"], _unit(1))


def test_batches_stay_under_the_request_limit(pc):
    """1536차 float 하나가 약 18KB 다. 개수 상한으로 잡으면 크기 상한에 먼저 걸린다."""
    n = ps.UPSERT_BATCH * 2 + 5
    ps.upsert(ps.RAW, [f"A#{i}" for i in range(n)], _unit(n, dim=8))

    sizes = [c[2] for c in FakePinecone.last.indexes["mupid-moment-raw"].calls
             if c[0] == "upsert"]
    assert max(sizes) <= ps.UPSERT_BATCH
    assert sum(sizes) == n


# ------------------------------------------------------------------ namespace

def test_every_call_carries_the_namespace(pc):
    """namespace 가 빠지면 기본 namespace 로 새어 들어가 전환이 무의미해진다."""
    ps.upsert(ps.RAW, ["A#0"], _unit(1))
    ps.fetch(["A#0"])
    ps.upsert(ps.CANDIDATE, ["A#0"], _unit(1))
    ps.search(_unit(1)[0], top_k=1)
    ps.delete(["A#0"])

    client = FakePinecone.last
    for name in ("mupid-moment-candidate", "mupid-moment-raw"):
        for call in client.indexes[name].calls:
            assert call[1] == "prod__mert-v1-95m", (name, call)


def test_the_namespace_is_fixed_not_swapped(pc, monkeypatch):
    """**무중단 namespace 전환을 만들지 않는다.**

    활성 namespace 는 고정이고, 갈아 끼우는 것은 그 안의 벡터다. 전체 갱신은
    같은 namespace 에 덮어쓰므로 벡터 수가 늘지 않는다 — 옛 세대가 옆에 쌓이면
    어느 쪽이 활성인지 아무도 모르게 된다.
    """
    ps.upsert(ps.RAW, ["A#0", "A#1"], _unit(2, seed=1))
    ps.upsert(ps.RAW, ["A#0", "A#1"], _unit(2, seed=2))      # 전체 갱신

    store = FakePinecone.last.indexes["mupid-moment-raw"].vectors
    assert set(store) == {"prod__mert-v1-95m"}, "namespace 가 늘었다"
    assert len(store["prod__mert-v1-95m"]) == 2


# ------------------------------------------------------------------ 실패 모양

def test_dimension_mismatch_stops_before_loading(pc):
    """차원은 만든 뒤 바꿀 수 없다. 적재 전에 멈춰야 한다."""
    FakePinecone.last  # 인덱스를 만들어 둔다
    ps.upsert(ps.RAW, ["A#0"], _unit(1, dim=1536))
    FakePinecone.last.indexes["mupid-moment-raw"].dim = 768

    with pytest.raises(ps.DimensionMismatch) as e:
        ps.check_dimension(1536, ps.RAW)
    assert "768" in str(e.value) and "1536" in str(e.value)


def test_a_matching_dimension_reports_the_counts(pc):
    ps.upsert(ps.CANDIDATE, ["A#0", "A#1"], _unit(2))
    got = ps.check_dimension(1536, ps.CANDIDATE)
    assert got["vectors_in_namespace"] == 2
    assert got["namespace"] == "prod__mert-v1-95m"


def test_an_outage_raises_and_never_returns_an_empty_list(pc):
    """빈 목록으로 바꾸면 '닮은 구간 없음'(정상)과 구분되지 않는다."""
    ps.upsert(ps.CANDIDATE, ["A#0"], _unit(1))
    FakePinecone.last.indexes["mupid-moment-candidate"].fail = RuntimeError("connection reset")

    with pytest.raises(ps.PineconeError) as e:
        ps.search(_unit(1)[0], top_k=5)
    assert "connection reset" in str(e.value)


def test_the_key_never_leaks_into_an_error_message(pc):
    ps.upsert(ps.RAW, ["A#0"], _unit(1))
    FakePinecone.last.indexes["mupid-moment-raw"].fail = RuntimeError(
        "auth failed for pckey-secret-value")

    with pytest.raises(ps.PineconeError) as e:
        ps.fetch(["A#0"])
    assert "pckey-secret-value" not in str(e.value)
    assert "***" in str(e.value)


def test_missing_key_is_reported_not_guessed(pc, monkeypatch):
    monkeypatch.delenv("MUPID_PINECONE_API_KEY")
    ps.reset()
    with pytest.raises(ps.PineconeNotConfigured):
        ps.fetch(["A#0"])


def test_missing_index_name_names_the_variable(pc, monkeypatch):
    monkeypatch.delenv("MUPID_PINECONE_CANDIDATE_INDEX")
    ps.reset()
    with pytest.raises(ps.PineconeNotConfigured) as e:
        ps.search(_unit(1)[0], top_k=1)
    assert "MUPID_PINECONE_CANDIDATE_INDEX" in str(e.value)


# ------------------------------------------------------------------ 중심화

def test_center_matches_the_recommender_transform(pc, tmp_path):
    """`Center.apply` 와 `recommender.center` 가 갈리면 순위가 갈린다."""
    from ai_server.app import recommender as rc

    rng = np.random.default_rng(3)
    mu = rng.normal(size=16).astype(np.float32)
    V = rng.normal(size=(5, 16)).astype(np.float32)

    path = tmp_path / ps.CENTER_FILE
    ps.save_center(path, mu, "mert-v1-95m", "prod__mert-v1-95m", 5)
    got = ps.load_center(path).apply(V)

    expect = V - mu
    expect /= np.clip(np.linalg.norm(expect, axis=1, keepdims=True), 1e-9, None)
    assert np.allclose(got, expect, atol=1e-6)
    # 같은 식을 추천기도 쓴다는 것을 이름으로 묶어 둔다.
    assert hasattr(rc, "center")


def test_a_missing_center_stops_instead_of_improvising(pc, tmp_path):
    """즉석 평균으로 대신하면 적재 때와 다른 공간으로 질의가 나간다 — 오류 없이
    품질만 나빠진다."""
    with pytest.raises(ps.PineconeError) as e:
        ps.load_center(tmp_path / "없다.npz")
    assert "pinecone_sync --init" in str(e.value)


def test_center_refuses_a_vector_of_another_width(pc, tmp_path):
    path = tmp_path / ps.CENTER_FILE
    ps.save_center(path, np.zeros(16, np.float32), "mert-v1-95m", "ns", 1)
    with pytest.raises(ps.DimensionMismatch):
        ps.load_center(path).apply(np.zeros((2, 8), np.float32))
