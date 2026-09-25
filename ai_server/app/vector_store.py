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

구현은 둘이고, 고르는 것은 `MUPID_VECTOR_BACKEND` 다(`backend_name`, 기본 local).

    local     색인 판의 `moments_emb.npy`. 운영이 지금 이것이다 (2026-09-24~)
    qdrant    node-b 의 Qdrant 컬렉션 (S15P21A204-456). 읽기만 한다 — 쓰는 것은 Spring(457)

예전 Pinecone 갈래는 S15P21A204-459 에서 지웠다(무료 요금제 이그레스 소진, 되살리지 않는다).

**점수는 저장소가 매기지 않는다.** 저장소는 후보를 좁히고 원본을 돌려줄 뿐이고, 순위는
언제나 `recommender` 가 원본에서 다시 센다. 그래야 갈래가 바뀌어도 같은 값이 나온다.
"""
from __future__ import annotations

import hashlib
import json
import os
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Callable, Iterable, Optional, Sequence

import numpy as np

from . import qdrant_schema, qdrant_store

BACKENDS = ("local", "qdrant")
# 지운 갈래의 옛 설정. 운영 Secret(`MUPID_AI_SERVER_ENV`)에 아직 남아 있다 — 지워질 때까지
# 기동을 막지 않고 **읽지도 않는다**(`ignored_env` 가 이름만 알린다).
LEGACY_ENV_PREFIX = "MUPID_PINECONE_"


def backend_name() -> str:
    """`MUPID_VECTOR_BACKEND` 로 갈래를 고른다. **기본은 local** 이다.

    Qdrant 에 벡터가 채워지기(457) 전에 기본을 qdrant 로 두면 배포가 곧 추천 중단이다.
    예전 스위치(`MUPID_PINECONE_ENABLED`)는 더 보지 않는다 — 켜져 있어도 local 이다.

    모르는 값은 오류다. 조용히 local 로 내려가면 "qdrant 로 돌고 있다" 고 믿는 배포가 옛
    판의 벡터로 추천한다. `pinecone` 은 지운 갈래라 따로 알린다 — 준비 판정이 이 문구로 실패한다.
    """
    raw = (os.environ.get("MUPID_VECTOR_BACKEND") or "").strip().lower()
    if not raw:
        return "local"
    if raw == "pinecone":
        raise ValueError("MUPID_VECTOR_BACKEND=pinecone — Pinecone 갈래는 지웠다(S15P21A204-459). "
                         f"{'|'.join(BACKENDS)} 중 하나를 고른다")
    if raw not in BACKENDS:
        raise ValueError(f"MUPID_VECTOR_BACKEND 는 {'|'.join(BACKENDS)} 중 하나다: {raw!r}")
    return raw


def ignored_env() -> list[str]:
    """남아 있지만 읽지 않는 옛 설정의 **이름**. 값은 싣지 않는다(키가 섞여 있다)."""
    return sorted(k for k in os.environ if k.startswith(LEGACY_ENV_PREFIX))


def snapshot_hash(moment_ids, vectors, embedding_version: str,
                  pool_sources) -> str:
    """이 적재본의 지문. **id 목록만으로는 모자란다.**

    재분석으로 같은 `external_id` 의 **벡터만** 바뀌는 경우가 흔하다. id 만 세면
    그때 지문이 같게 나와, 옛 평균과 새 벡터가 섞인 상태를 아무도 잡지 못한다 —
    오류 없이 유사도만 내려앉는다. 그래서 값까지 넣는다.

        정렬된 external_id   구성이 같으면 차례가 달라도 같아야 한다
        벡터 값              float32 바이트. 한 구간만 바뀌어도 달라진다
        임베딩 버전          모델·층·풀링이 바뀌면 같은 값이라도 뜻이 다르다
        추천 풀 설정         MUPID_POOL / MUPID_POOL_EXTRA. 대상이 달라진다

    모델 묶음의 씨앗 표(`onboarding_seeds.npz`)가 이 값으로 판에 묶인다.
    """
    V = np.atleast_2d(np.asarray(vectors, dtype=np.float32))
    ids = [str(m) for m in moment_ids]
    if len(ids) != len(V):
        raise ValueError(f"id {len(ids)}개와 벡터 {len(V)}개가 다르다")

    h = hashlib.sha256()
    h.update(b"snapshot-v1\x00")
    h.update(str(embedding_version).encode("utf-8") + b"\x00")
    for src in sorted(str(s) for s in pool_sources):
        h.update(src.encode("utf-8") + b"\x00")
    h.update(b"--\x00")
    for position in sorted(range(len(ids)), key=lambda i: ids[i]):
        # 구분자를 넣는다. 넣지 않으면 `A`+`BC` 와 `AB`+`C` 가 같은 지문이 된다.
        h.update(ids[position].encode("utf-8") + b"\x00")
        h.update(np.ascontiguousarray(V[position], dtype=np.float32).tobytes())
    return h.hexdigest()


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
        # Qdrant 가 쓰는 `HasId` 조각(직렬화한 UUID 배열). 목록마다 한 번만 만든다.
        self.qdrant_has_id: Optional[bytes] = None

    def __len__(self) -> int:
        return int(len(self.ids))

    def __contains__(self, mid) -> bool:
        if self._set is None:
            self._set = set(self.ids.tolist())
        return str(mid) in self._set


def _center_rows(V: np.ndarray, mu: np.ndarray) -> np.ndarray:
    """`(V - mu) / ‖V - mu‖`. Qdrant 컬렉션의 `mert` 를 쓴 쪽(Spring·배치)과 **같은 식**이다."""
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
    """인터페이스. 구현은 아래 둘이다 (local · qdrant)."""

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
            self._snapshot = snapshot_hash(
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


class QdrantNotReady(Exception):
    """Qdrant 갈래를 골랐는데 컬렉션이 준비되지 않았다. 준비 판정이 이 문구로 실패한다."""


class QdrantVectorStore(VectorStore):
    """node-b 의 Qdrant 컬렉션에서 찾고 읽는다 (S15P21A204-456, 설계 §6.1·§9.3).

    **컬렉션에 든 것은 원본이 아니라 중심화 방향이다.** `mert` = `u = (Z-μ)/‖Z-μ‖`, payload
    `r = ‖Z-μ‖`. 그래서 검색 점수가 곧 로컬 갈래의 `sim` 이고(같은 μ 로 중심화한 질의의
    코사인), 원본은 `μ + r·u` 로 되돌린다 — 정확한 복원이다(부동소수점 반올림만 남는다).

    μ 는 **쓴 쪽과 같아야 한다.** 동결 μ(모델 묶음 `mu.npy`, 설계 §6.3)가 이미지에 있으면 그것을,
    없으면 지금처럼 판의 색인 풀 평균을 쓴다(`recommender._frozen_mu`). 다른 μ 로 쓴 컬렉션을
    읽으면 오류 없이 유사도만 틀어진다 — 그래서 어느 μ 인지 `/health` 에 싣는다.

    허용 목록(설계 §9.3-1):
      · 컬렉션 대부분이면(`POST_FILTER_FRACTION` 이상) 걸지 않고 넉넉히 뽑아 거른다 — 수천 개의
        UUID 를 검색마다 보내는 것보다 싸고, 대부분 통과하니 재현율도 그대로다.
      · `HAS_ID_MAX` 이하면 `HasId` 사전 필터. 재현율이 줄지 않는다. 기준은 설정이다
        (`MUPID_PREFILTER_MAX`, 기본 1만 — 설계 §11 미결 2). 계약 v3(458)부터 조건 검색의 허용
        목록도 이 길로 온다(조건을 통과한 구간 = 요청 사본의 풀).
      · 그 사이는 넉넉히 뽑아 거르고, 모자라면 한 번 더 넓힌다.
    `source` 는 언제나 payload 필터로 건다 — 추천 풀(flow) 밖의 구간이 상위를 차지하지 않게.
    """

    backend = "qdrant"
    HAS_ID_MAX = int(os.environ.get("MUPID_PREFILTER_MAX") or 10_000)
    POST_FILTER_FRACTION = 0.5
    OVERSAMPLE = 3
    FETCH_BATCH = 256
    FETCH_WORKERS = 4
    STATUS_TTL = 10.0

    def __init__(self, mu: np.ndarray, mu_source: str, pool_sources: Iterable[str] = (),
                 model_version: Optional[str] = None,
                 http: Optional[Callable] = None, collection: Optional[str] = None) -> None:
        self._mu = np.asarray(mu, dtype=np.float32).reshape(-1)
        self.mu_source = str(mu_source)
        self._sources = sorted(str(s) for s in pool_sources)
        self.model_version = model_version
        self._http = http or qdrant_store.request
        self.collection = collection or qdrant_store.collection()
        self._lock = threading.Lock()
        self._status: Optional[dict] = None
        self._status_at = 0.0
        self._filter_head = (json.dumps({"key": "source", "match": {"any": self._sources}},
                                        separators=(",", ":")).encode()
                             if self._sources else None)

    # ---------------------------------------------------------------- 성질
    @property
    def dim(self) -> int:
        return int(self._mu.shape[0])

    @property
    def mu(self) -> np.ndarray:
        return self._mu

    @property
    def generation(self) -> str:
        # 저장소 객체는 판(`Catalog`)마다 하나다. 컬렉션 내용은 곡 단위 upsert 로 조금씩 바뀌므로
        # 세대로 삼지 않는다 — 사본 캐시에 드는 것은 id·게이트뿐이고 벡터는 요청마다 다시 읽는다.
        return f"qdrant:{self.collection}:{id(self):x}"

    def snapshot(self) -> str:
        # 판 지문(`snapshot_hash`)은 전 구간의 원본을 훑어야 잰다. Qdrant 에서 그러면 풀 전체
        # 전송이다. 그래서 판 지문과 **맞지 않는 값**을 돌려준다 — 판 지문에 묶인 온보딩 씨앗
        # 표는 쓰지 않고 지금처럼 센다(`recommender._seed_table` 이 이유를 남긴다).
        return f"qdrant:{self.collection}"

    # ---------------------------------------------------------------- 준비 판정
    def status(self, refresh: bool = False) -> dict:
        """컬렉션이 서빙할 수 있는가. `STATUS_TTL` 초 동안은 지난 판정을 다시 쓴다.

        준비 판정(`/internal/ready`)과 추천 요청 입구(`internal_api._svc`)가 부른다 — 요청마다
        Qdrant 를 두드리지 않으면서, Qdrant 가 죽으면 늦어도 TTL 뒤에는 준비 판정이 실패한다
        (설계 §10.1 장애 표: Qdrant 가 죽으면 추천이 멈춘다).
        """
        now = time.monotonic()
        with self._lock:
            if not refresh and self._status is not None and now - self._status_at < self.STATUS_TTL:
                return self._status
        got = self._check()
        with self._lock:
            self._status, self._status_at = got, time.monotonic()
        return got

    def cached_status(self) -> Optional[dict]:
        """지난 판정. **Qdrant 를 부르지 않는다** — `/health`(liveness)용이다. Qdrant 가 멈춘 채
        응답하지 않을 때 liveness 가 그 시간만큼 걸리면 kubelet 이 멀쩡한 ai 를 죽인다."""
        with self._lock:
            return self._status

    def _check(self) -> dict:
        out: dict = {"ok": False, "collection": self.collection, "mu_source": self.mu_source,
                     "model_version": self.model_version, "errors": []}
        try:
            info = self._http("GET", f"/collections/{self.collection}")
        except qdrant_store.QdrantError as e:
            missing = str(e).startswith("HTTP 404")
            out["errors"].append(f"컬렉션 {self.collection} 이 없다" if missing
                                 else f"Qdrant 에 닿지 못했다: {e}")
            return out
        vectors = ((info or {}).get("config") or {}).get("params", {}).get("vectors") or {}
        out["points"] = int((info or {}).get("points_count") or 0)
        out["vectors"] = sorted(vectors)
        mert = vectors.get("mert") or {}
        if int(mert.get("size") or 0) != self.dim:
            out["errors"].append(f"mert 차원이 {mert.get('size')} 이다 — μ 는 {self.dim}")
        if out["points"] <= 0:
            out["errors"].append("컬렉션이 비었다 — 벡터 적재(457) 전이다")
        # 이미지의 모델 버전(설계 §10.2-1·5). 그 버전이 만든 구간 벡터가 **전 구간에** 없으면 새
        # 사용자 인코더와 옛 구간 벡터가 짝을 이룬다 — 오류 없이 순위만 틀어진다. 모델 버전이
        # 없으면(`models.lock` 이 null, 판의 가중치로 돈다) 이 검사는 건너뛴다.
        if self.model_version and not out["errors"]:
            name = qdrant_schema.model_vector(self.model_version)
            if name not in vectors:
                out["errors"].append(f"{name} 벡터가 컬렉션에 없다 — 모델 {self.model_version} 의 "
                                     f"재계산이 반영되지 않았다")
            else:
                try:
                    have = self._count({"must": [{"has_vector": name}]})
                    base = self._count({"must": [{"has_vector": "mert"}]})
                except qdrant_store.QdrantError as e:
                    out["errors"].append(f"Qdrant 에 닿지 못했다: {e}")
                    return out
                out["model_vector"] = {"name": name, "points": have, "of": base}
                if have < base:
                    out["errors"].append(f"{name} 이 {have}/{base} 구간뿐이다 — 재계산이 덜 끝났다")
        out["ok"] = not out["errors"]
        return out

    def _count(self, filter_: dict) -> int:
        got = self._http("POST", f"/collections/{self.collection}/points/count",
                         {"exact": True, "filter": filter_})
        return int(got["count"])

    def require_ready(self) -> None:
        st = self.status()
        if not st["ok"]:
            raise QdrantNotReady("; ".join(st["errors"]))

    # ---------------------------------------------------------------- 검색
    def allow(self, ids: Iterable[str]) -> AllowList:
        return AllowList([str(i) for i in ids])

    @staticmethod
    def _has_id(allow: AllowList) -> bytes:
        if allow.qdrant_has_id is None:
            allow.qdrant_has_id = json.dumps(
                [qdrant_schema.point_id(i) for i in allow.ids.tolist()],
                separators=(",", ":")).encode()
        return allow.qdrant_has_id

    def _query_batch(self, Q: np.ndarray, limit: int, has_id: Optional[bytes]) -> list:
        """질의 여럿을 한 번에(`/points/query/batch`). 본문은 조각을 이어 붙여 만든다."""
        must = [self._filter_head] if self._filter_head else []
        if has_id is not None:
            must.append(b'{"has_id":' + has_id + b"}")
        filt = (b',"filter":{"must":[' + b",".join(must) + b"]}") if must else b""
        tail = (b',"using":"mert","limit":' + str(int(limit)).encode()
                + b',"with_payload":["external_id"]' + filt + b"}")
        parts = [b'{"query":' + json.dumps(q.tolist()).encode() + tail for q in Q]
        body = b'{"searches":[' + b",".join(parts) + b"]}"
        got = self._http("POST", f"/collections/{self.collection}/points/query/batch", body)
        return [[(str(p["payload"]["external_id"]), float(p["score"])) for p in r["points"]]
                for r in got]

    def search_many(self, queries: np.ndarray, top_k: int,
                    allow: Optional[AllowList] = None) -> list[list[tuple[str, float]]]:
        Q = np.atleast_2d(np.asarray(queries, dtype=np.float32))
        k = int(top_k)
        if not len(Q) or k <= 0:
            return [[] for _ in range(len(Q))]
        if allow is None:
            return self._query_batch(Q, k, None)
        if not len(allow):
            return [[] for _ in range(len(Q))]
        points = int(self.status().get("points") or 0)
        wide = bool(points) and len(allow) >= self.POST_FILTER_FRACTION * points
        if not wide and len(allow) <= self.HAS_ID_MAX:
            return self._query_batch(Q, min(k, len(allow)), self._has_id(allow))
        # 넉넉히 뽑아 거른다. 허용 비율의 역수만큼 더 뽑고, 모자라면 한 번 더 넓힌다.
        ratio = len(allow) / points if points else 1.0
        limit = int(k * self.OVERSAMPLE / max(ratio, 1e-3)) + k
        out = [[(m, s) for m, s in got if m in allow] for got in self._query_batch(Q, limit, None)]
        short = [j for j, got in enumerate(out) if len(got) < k]
        if short and limit < points:
            again = self._query_batch(Q[short], min(limit * 4, points), None)
            for j, got in zip(short, again):
                out[j] = [(m, s) for m, s in got if m in allow]
        return [got[:k] for got in out]

    # ---------------------------------------------------------------- 원본
    def fetch(self, ids: Sequence[str]) -> dict:
        return self.fetch_versioned(ids)[0]

    def fetch_versioned(self, ids: Sequence[str]) -> tuple[dict, dict]:
        """(원본, 구간 지문). 지문은 payload `analysis_version` — 쓴 쪽(Spring)이 그 구간을 분석한
        버전이다. 부르는 쪽이 피처 저장소의 버전과 대조해 다르면 버린다(설계 §9.3-3)."""
        want = list(dict.fromkeys(str(i) for i in ids))
        if not want:
            return {}, {}
        chunks = [want[a:a + self.FETCH_BATCH] for a in range(0, len(want), self.FETCH_BATCH)]

        def one(chunk):
            return self._http("POST", f"/collections/{self.collection}/points", {
                "ids": [qdrant_schema.point_id(i) for i in chunk],
                "with_vector": ["mert"], "with_payload": ["external_id", "r", "analysis_version"]})

        if len(chunks) == 1:
            rows = one(chunks[0])
        else:
            # 온보딩 씨앗처럼 수천 개를 부르는 경우가 있다. 조각을 나란히 보낸다.
            with ThreadPoolExecutor(max_workers=min(self.FETCH_WORKERS, len(chunks))) as ex:
                rows = [p for got in ex.map(one, chunks) for p in got]
        out, versions = {}, {}
        for p in rows:
            vec = (p.get("vector") or {}).get("mert")
            pay = p.get("payload") or {}
            if vec is None or "r" not in pay:
                continue                              # 반쯤 들어간 포인트는 없는 것으로 본다
            u = np.asarray(vec, dtype=np.float32)
            mid = str(pay["external_id"])
            out[mid] = self._mu + np.float32(pay["r"]) * u
            if pay.get("analysis_version") is not None:
                versions[mid] = str(pay["analysis_version"])
        return out, versions

