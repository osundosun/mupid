"""**개발·시험 전용** — 판의 벡터를 로컬 Qdrant 에 넣는다 (S15P21A204-456).

**운영 경로가 아니다.** 운영 Qdrant 에 벡터를 쓰는 것은 Spring 벡터 등록 API(457) 하나다(설계
원칙 1 — 쓰기 관문은 Spring API 하나). 그래서 이 도구는 주소가 이 기계(localhost·127.0.0.1)가
아니면 거절한다 — 클러스터 안의 `qdrant` 이름으로는 돌지 않는다.

쓰는 모양은 Spring 이 쓸 모양과 같다(`app/qdrant_collection.json`): 포인트 id = UUIDv5(external_id),
`mert` = `(Z-μ)/‖Z-μ‖`, payload `r`·`external_id`·`track_id`·`source`·`emb_version`. μ 는 서빙의
판 폴백과 같은 **색인 풀 평균**이다 — 그래야 서빙이 같은 μ 로 읽는다.

    MUPID_AI_ARTIFACT_DIR=<판> python -m ai_server.tools.qdrant_dev_load \\
        --url http://127.0.0.1:6333 --api-key-stdin < key.txt

부하 측정(`tools/bench_request_path.py --backend qdrant`)이 이것을 부른다.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import time
import urllib.parse
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

from ai_server.app import qdrant_schema, qdrant_store

BATCH = 256
LOCAL = {"localhost", "127.0.0.1", "::1"}


def refuse_remote(url: str) -> None:
    host = urllib.parse.urlparse(url).hostname or ""
    if host not in LOCAL:
        raise SystemExit(f"개발 전용이다 — {host!r} 에는 쓰지 않는다 (운영 쓰기는 Spring 벡터 등록 API)")


def _track_number(tid: str) -> int:
    """payload `track_id` 는 정수(music_id)다. 판의 `T000123` 은 숫자만 떼어 쓴다 (개발용)."""
    digits = re.sub(r"\D", "", str(tid))
    return int(digits) if digits else 0


def load(url: str, key: str, data_dir: Path, sources=("flow",), model_version: str | None = None,
         collection: str | None = None, workers: int = 4, log=print) -> dict:
    refuse_remote(url)
    from ai_server.tools.qdrant_collection import ensure        # noqa: PLC0415

    name = collection or qdrant_schema.collection_name()

    def call(method, path, body):
        return qdrant_store.request(method, path, body, timeout_s=120, api_key=key, base=url)

    mom = pd.read_parquet(data_dir / "moments.parquet")
    pool = mom[mom.source.isin(list(sources))].reset_index(drop=True)
    Z = np.load(data_dir / "moments_emb.npy", mmap_mode="r")
    rows = pool.emb_row.to_numpy()
    mu = np.asarray(Z[rows], dtype=np.float32).mean(0)
    s1 = None
    if model_version:
        s1 = np.load(data_dir / "stage1_section.npy", mmap_mode="r")
    ensure(call, name, [model_version] if model_version else [], log=log)
    info = call("GET", f"/collections/{name}", None)
    if int(info.get("points_count") or 0) == len(pool):
        log(f"이미 {len(pool)}구간이 있다 — 건너뛴다")
        return {"collection": name, "points": len(pool), "skipped": True, "mu": mu}

    emb = qdrant_schema.embedding_version()
    vname = qdrant_schema.model_vector(model_version) if model_version else None

    def one(a: int) -> int:
        b = min(a + BATCH, len(pool))
        W = np.asarray(Z[rows[a:b]], dtype=np.float32) - mu
        r = np.linalg.norm(W, axis=1)
        U = W / np.clip(r, 1e-9, None)[:, None]
        pts = []
        for i in range(b - a):
            m = pool.iloc[a + i]
            vec = {"mert": U[i].tolist()}
            if vname is not None:
                vec[vname] = np.asarray(s1[int(m.emb_row)], dtype=np.float32).tolist()
            pts.append({"id": qdrant_schema.point_id(m.moment_id), "vector": vec,
                        "payload": {"external_id": str(m.moment_id),
                                    "track_id": _track_number(m.track_id), "r": float(r[i]),
                                    "source": str(m.source), "emb_version": emb}})
        call("PUT", f"/collections/{name}/points?wait=true", {"points": pts})
        return b - a

    t0 = time.perf_counter()
    with ThreadPoolExecutor(max_workers=workers) as ex:
        done = sum(ex.map(one, range(0, len(pool), BATCH)))
    secs = time.perf_counter() - t0
    log(f"{done}구간을 넣었다 — {secs:.1f}초")
    return {"collection": name, "points": done, "seconds": round(secs, 1), "mu": mu}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--url", default="http://127.0.0.1:6333")
    ap.add_argument("--api-key-stdin", action="store_true")
    ap.add_argument("--model-version", default=None)
    ap.add_argument("--sources", default="flow")
    a = ap.parse_args(argv)
    refuse_remote(a.url)
    key = sys.stdin.readline().strip() if a.api_key_stdin else os.environ.get("MUPID_QDRANT_API_KEY", "")
    from ai_server.app import paths                             # noqa: PLC0415
    got = load(a.url, key, paths.data_dir(), a.sources.split(","), a.model_version)
    got.pop("mu", None)
    print(json.dumps(got, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
