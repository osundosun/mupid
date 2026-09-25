"""시험용 가짜 Qdrant — `qdrant_store.request` 자리에 끼운다 (S15P21A204-456).

서빙이 부르는 REST 넷만 흉내 낸다 — 컬렉션 정보, 검색(`/points/query/batch`), 조회
(`/points`), 개수(`/points/count`). 검색은 **정확한 코사인**이라 로컬 갈래의 브루트포스와
같은 순서가 나와야 한다. 쓰기 경로는 없다 — 서빙이 쓰면 여기서 곧바로 실패한다.
"""
from __future__ import annotations

import json

import numpy as np

from ai_server.app import qdrant_schema, qdrant_store


class FakeQdrant:
    def __init__(self, collection: str | None = None) -> None:
        self.collection = collection or qdrant_schema.collection_name()
        self.exists = True
        self.down = False
        self.vectors = qdrant_schema.vectors()
        self.points: dict[str, dict] = {}         # point id -> {"vector": {...}, "payload": {...}}
        self.calls: list[tuple[str, str, dict]] = []

    # ------------------------------------------------------------ 채우기 (쓰는 쪽 흉내)
    def load(self, ids, Z, mu, source="flow", stage1=None, model_version=None) -> None:
        """쓰는 쪽(Spring·457)이 하듯 `u = (Z-μ)/‖Z-μ‖`, `r = ‖Z-μ‖` 로 넣는다."""
        W = np.asarray(Z, dtype=np.float32) - np.asarray(mu, dtype=np.float32)
        r = np.linalg.norm(W, axis=1)
        U = W / np.clip(r, 1e-9, None)[:, None]
        if model_version:
            self.vectors[qdrant_schema.model_vector(model_version)] = \
                qdrant_schema.model_vector_params()
        for i, (mid, u, rr) in enumerate(zip(ids, U, r)):
            vec = {"mert": u.tolist()}
            if model_version and stage1 is not None and i < len(stage1):
                vec[qdrant_schema.model_vector(model_version)] = list(map(float, stage1[i]))
            self.points[qdrant_schema.point_id(mid)] = {
                "vector": vec, "payload": {"external_id": str(mid), "r": float(rr),
                                           "source": source}}

    # ------------------------------------------------------------ REST
    def __call__(self, method, path, body=None, timeout_s=None):
        if isinstance(body, (bytes, bytearray)):
            body = json.loads(body)
        self.calls.append((method, path, body))
        if self.down:
            raise qdrant_store.QdrantError(f"URLError {method} {path}: connection refused")
        base = f"/collections/{self.collection}"
        if not path.startswith(base) or not self.exists:
            raise qdrant_store.QdrantError(f"HTTP 404 {method} {path}: Not found")
        rest = path[len(base):]
        if method == "GET" and rest == "":
            return {"status": "green", "points_count": len(self.points),
                    "config": {"params": {"vectors": self.vectors}}}
        if method == "POST" and rest == "/points/query/batch":
            return [{"points": self._query(q)} for q in body["searches"]]
        if method == "POST" and rest == "/points":
            out = []
            for pid in body["ids"]:
                p = self.points.get(pid)
                if p is None:
                    continue
                out.append({"id": pid,
                            "vector": {k: v for k, v in p["vector"].items()
                                       if k in body.get("with_vector", [])},
                            "payload": {k: v for k, v in p["payload"].items()
                                        if k in body.get("with_payload", [])}})
            return out
        if method == "POST" and rest == "/points/count":
            return {"count": sum(1 for pid, p in self.points.items()
                                 if self._match(pid, p, body.get("filter")))}
        raise AssertionError(f"서빙이 부르지 않아야 할 호출: {method} {path}")

    def _match(self, pid, p, filt) -> bool:
        for cond in (filt or {}).get("must", []):
            if "has_id" in cond and pid not in set(cond["has_id"]):
                return False
            if "has_vector" in cond and cond["has_vector"] not in p["vector"]:
                return False
            if cond.get("key") == "source" and p["payload"].get("source") not in cond["match"]["any"]:
                return False
        return True

    def _query(self, q) -> list:
        vec = np.asarray(q["query"], dtype=np.float32)
        vec = vec / max(float(np.linalg.norm(vec)), 1e-9)
        hits = []
        for pid, p in self.points.items():
            if q.get("using", "mert") not in p["vector"] or not self._match(pid, p, q.get("filter")):
                continue
            hits.append((float(np.asarray(p["vector"][q["using"]], np.float32) @ vec), pid))
        hits.sort(key=lambda h: -h[0])
        return [{"id": pid, "score": s,
                 "payload": {"external_id": self.points[pid]["payload"]["external_id"]}}
                for s, pid in hits[:int(q["limit"])]]
