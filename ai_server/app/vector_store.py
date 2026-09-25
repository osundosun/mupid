"""순간 벡터 저장소 — 추천 계산부가 벡터를 읽는 **유일한 입구** (S15P21A204-452).

설계 `infra/VECTOR-STORE-DESIGN.md` §9.3·§13 2단계. 추천 계산부(`recommender`)는
벡터가 어디에 있는지 모른다. 이 인터페이스만 부른다.

    search(query, top_k, allow=None)   중심화된 질의와 닮은 순간 [(id, score)]
    search_many(queries, top_k, allow) 질의 여럿을 한 번에 (DNA 중심·온보딩 씨앗)
    fetch(ids)                         {id: 원본 벡터}. 없는 id 는 빠진다
    allow(ids)                         사전 필터. 검색마다 다시 만들지 않는다
    mu · dim · centered(V)             중심화 평균과 그 변환
    generation                         이 저장소 세대를 가리키는 싼 값 (캐시 열쇠)
    snapshot()                         적재본 지문 (비쌀 수 있다 — 기동·내보내기에서만)

구현은 둘이다. 5단계(S15P21A204-456)에서 Qdrant 가 같은 자리에 들어온다.

    local     색인 판의 `moments_emb.npy`. 운영이 지금 이것이다 (2026-09-24~)
    pinecone  예전 갈래. 459 에서 지운다 — 그래서 옮기기만 하고 고치지 않는다

**점수는 저장소가 매기지 않는다.** 저장소는 후보를 좁히고 원본을 돌려줄 뿐이고, 순위는
언제나 `recommender` 가 원본에서 다시 센다. 그래야 갈래가 바뀌어도 같은 값이 나온다.
"""
from __future__ import annotations

from typing import Iterable, Optional, Sequence

import numpy as np

from . import pinecone_store


class AllowList:
    """검색을 이 id 들 안으로 묶는다. Qdrant 의 `HasId` 사전 필터 자리다.

    **만들 때 한 번만 센다.** 추천 요청마다 풀 전체의 id 로 새로 만들면 그것이 곧 요청당
    O(N) 이 된다 — 그래서 부르는 쪽(요청 사본)이 한 번 만들어 들고 있는다.
    `rows` 는 로컬 구현이 쓰는 행 번호이고, 다른 구현은 id 만 본다.
    """

    def __init__(self, ids: Sequence[str], rows: Optional[np.ndarray] = None) -> None:
        self.ids = np.asarray(list(ids), dtype=object)
        self.rows = None if rows is None else np.asarray(rows, dtype=np.int64)
        self._set: Optional[set] = None

    def __len__(self) -> int:
        return int(len(self.ids))

    def __contains__(self, mid) -> bool:
        if self._set is None:
            self._set = set(self.ids.tolist())
        return str(mid) in self._set


def _center_rows(V: np.ndarray, mu: np.ndarray) -> np.ndarray:
    """`(V - mu) / ‖V - mu‖`. `pinecone_store.Center.split` 과 **같은 식**이다."""
    W = np.atleast_2d(np.asarray(V, dtype=np.float32)) - mu
    W /= np.clip(np.linalg.norm(W, axis=1, keepdims=True), 1e-9, None)
    return W


def _top_k(sim: np.ndarray, k: int) -> np.ndarray:
    """큰 값 순 상위 k 의 자리. 정렬은 k 개에만 한다 (전체 정렬이 아니다)."""
    k = min(int(k), len(sim))
    if k <= 0:
        return np.zeros(0, dtype=np.int64)
    part = np.argpartition(-sim, k - 1)[:k] if k < len(sim) else np.arange(len(sim))
    return part[np.argsort(-sim[part], kind="stable")]


class VectorStore:
    """인터페이스. 구현은 아래 둘이고, Qdrant 가 세 번째로 들어온다."""

    backend = "abstract"

    @property
    def dim(self) -> int:
        raise NotImplementedError

    @property
    def mu(self) -> np.ndarray:
        raise NotImplementedError

    @property
    def generation(self) -> str:
        raise NotImplementedError

    def snapshot(self) -> str:
        raise NotImplementedError

    def allow(self, ids: Iterable[str]) -> AllowList:
        return AllowList([str(i) for i in ids])

    def search(self, query: np.ndarray, top_k: int,
               allow: Optional[AllowList] = None) -> list[tuple[str, float]]:
        return self.search_many(np.atleast_2d(query), top_k, allow)[0]

    def search_many(self, queries: np.ndarray, top_k: int,
                    allow: Optional[AllowList] = None) -> list[list[tuple[str, float]]]:
        raise NotImplementedError

    def fetch(self, ids: Sequence[str]) -> dict:
        raise NotImplementedError

    def centered(self, V: np.ndarray) -> np.ndarray:
        return _center_rows(V, self.mu)


class LocalVectorStore(VectorStore):
    """색인 판의 `moments_emb.npy` 를 브루트포스로 찾는다 — **로컬 ANN 대용**이다.

    검색 한 번은 허용된 행 전체와 내적한다(요청 경로에 남는 유일한 O(N), 점검표
    `ai_server/docs/REQUEST-PATH-COMPLEXITY.md`). 대신 그 뒤 단계는 여기서 돌려준 후보만
    다룬다.

    중심화한 행렬을 따로 만들어 두지 않는다. 90만 구간이면 원본만큼(약 5GB) 더 든다.
    `u·q = (z·q − μ·q) / ‖z − μ‖` 라 행마다 반지름 `‖z − μ‖` 하나만 들고 있으면 같은
    값이 나온다 — 예전 `center(Z[pool_rows]) @ q` 와 식이 같다.

    평균 μ 는 **색인 풀 전체**로 한 번 잰다(!285). 요청의 후보로 재면 검색 조건마다
    기준점이 달라진다.
    """

    backend = "local"

    def __init__(self, Z: np.ndarray, row_of: dict, pool_ids: Sequence[str],
                 pool_rows: np.ndarray, embedding_version: str = "",
                 pool_sources: Iterable[str] = ()) -> None:
        self.Z = Z
        self._row_of = row_of
        self._pool_ids = [str(x) for x in pool_ids]
        self._pool_rows = np.asarray(pool_rows, dtype=np.int64)
        self._version = str(embedding_version)
        self._sources = tuple(sorted(str(s) for s in pool_sources))
        self._mu = np.asarray(Z[self._pool_rows], dtype=np.float32).mean(0) \
            if len(self._pool_rows) else np.zeros(int(Z.shape[1]), np.float32)
        self._radius: Optional[np.ndarray] = None
        self._snapshot: Optional[str] = None
        self._all = AllowList(self._pool_ids, self._pool_rows)

    @property
    def dim(self) -> int:
        return int(self.Z.shape[1])

    @property
    def mu(self) -> np.ndarray:
        return self._mu

    @property
    def generation(self) -> str:
        # 판을 새로 올리면 저장소 객체가 새로 생긴다. 내용 지문(`snapshot`)은 풀 전체를
        # 훑어야 해서 캐시 열쇠로는 쓰지 않는다.
        return f"local:{id(self):x}"

    def snapshot(self) -> str:
        if self._snapshot is None:
            self._snapshot = pinecone_store.snapshot_hash(
                self._pool_ids, np.asarray(self.Z[self._pool_rows], dtype=np.float32),
                self._version, self._sources)
        return self._snapshot

    def radius(self) -> np.ndarray:
        """행마다 `‖z − μ‖`. 판마다 한 번, 조각으로 나눠 잰다 (큰 임시 행렬을 만들지 않는다)."""
        if self._radius is None:
            n = int(self.Z.shape[0])
            out = np.empty(n, dtype=np.float32)
            step = 8192
            for a in range(0, n, step):
                W = np.asarray(self.Z[a:a + step], dtype=np.float32) - self._mu
                out[a:a + step] = np.linalg.norm(W, axis=1)
            self._radius = np.clip(out, 1e-9, None)
        return self._radius

    def allow(self, ids: Iterable[str]) -> AllowList:
        keep, rows = [], []
        for i in ids:
            r = self._row_of.get(str(i))
            if r is not None:
                keep.append(str(i))
                rows.append(r)
        return AllowList(keep, np.asarray(rows, dtype=np.int64))

    def search_many(self, queries: np.ndarray, top_k: int,
                    allow: Optional[AllowList] = None) -> list[list[tuple[str, float]]]:
        Q = np.atleast_2d(np.asarray(queries, dtype=np.float32))
        allow = allow if allow is not None else self._all
        if allow.rows is None:                   # id 만 든 목록 — 행 번호로 옮긴다
            allow = self.allow(allow.ids.tolist())
        rows = allow.rows
        if not len(rows) or not len(Q):
            return [[] for _ in range(len(Q))]
        # 허용 행이 판의 일부면 그 행만 꺼내 곱하고, 대부분이면 판 전체와 곱한 뒤
        # 고른다. 앞쪽은 복사가 작고, 뒤쪽은 큰 복사 없이 mmap 을 그대로 읽는다.
        if len(rows) * 4 < int(self.Z.shape[0]):
            dots = np.asarray(self.Z[rows], dtype=np.float32) @ Q.T
        else:
            dots = (np.asarray(self.Z) @ Q.T)[rows]
        sim = (dots - (self._mu @ Q.T)[None, :]) / self.radius()[rows][:, None]
        out = []
        for j in range(len(Q)):
            order = _top_k(sim[:, j], top_k)
            out.append([(str(allow.ids[i]), float(sim[i, j])) for i in order])
        return out

    def fetch(self, ids: Sequence[str]) -> dict:
        keep = [str(i) for i in ids if str(i) in self._row_of]
        if not keep:
            return {}
        V = np.asarray(self.Z[np.asarray([self._row_of[i] for i in keep])], dtype=np.float32)
        return dict(zip(keep, V))


class PineconeVectorStore(VectorStore):
    """예전 Pinecone 갈래를 인터페이스 뒤로 옮긴 것. **459 에서 지운다.**

    동작은 옮기기 전과 같다 — 평균은 control 이 들고 있고(읽을 때마다 상태·세대를
    본다), 검색은 조건 없이 namespace 를 찾은 뒤 허용 목록으로 거른다. 실패하면 로컬로
    내려가지 않고 예외가 그대로 올라간다.
    """

    backend = "pinecone"

    @property
    def dim(self) -> int:
        return pinecone_store.center().dim

    @property
    def mu(self) -> np.ndarray:
        return pinecone_store.serving_center().mu

    @property
    def generation(self) -> str:
        return f"pinecone:{pinecone_store.center().snapshot}"

    def snapshot(self) -> str:
        return pinecone_store.center().snapshot

    def search_many(self, queries: np.ndarray, top_k: int,
                    allow: Optional[AllowList] = None) -> list[list[tuple[str, float]]]:
        out = []
        for q in np.atleast_2d(np.asarray(queries, dtype=np.float32)):
            got = pinecone_store.search(q, top_k)
            out.append(got if allow is None else [(m, s) for m, s in got if m in allow])
        return out

    def fetch(self, ids: Sequence[str]) -> dict:
        return pinecone_store.fetch_raw([str(i) for i in ids])


PINECONE = PineconeVectorStore()
