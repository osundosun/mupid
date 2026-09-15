"""AI 추천 서비스 진입점. CPU 로만 돈다.

`/health` 와 `/internal` 계약만 노출한다. 데모 UI·학습·시뮬레이션·음원 스트리밍은 없다.

기동할 때 산출물 무결성을 먼저 본다. 색인 네 개는 행 번호로 서로를 가리키기 때문에 버전이
섞이면 **엉뚱한 곡을 추천하면서도 서비스는 정상처럼 뜬다.** 어긋나면 `/internal/ready` 를
실패로 둔다.
"""
from __future__ import annotations

import time

import numpy as np
import pandas as pd
from fastapi import FastAPI

from . import internal_api, paths, recommender

app = FastAPI(title="MUPID AI 추천 서비스", version="1.0.0")

_STARTED = time.time()
_STATE: dict = {"loaded": False, "error": None, "integrity": None, "load_seconds": None}


def check_integrity() -> dict:
    """색인 네 개의 행 수와 순서를 본다. 추천을 계산하기 전에 확인한다."""
    d = recommender.OUT_DIR
    out: dict = {"ok": False, "checks": {}, "errors": []}
    try:
        mom = pd.read_parquet(d / "moments.parquet", columns=["moment_id", "emb_row"])
        rows = {
            "moments": len(mom),
            "moments_emb": int(np.load(d / "moments_emb.npy", mmap_mode="r").shape[0]),
            "features_v1": int(np.load(d / "features_v1.npz")["X"].shape[0]),
            "stage1_section": int(np.load(d / "stage1_section.npy", mmap_mode="r").shape[0]),
        }
        out["checks"]["rows"] = rows
        if len(set(rows.values())) != 1:
            out["errors"].append(f"색인 행 수가 어긋난다: {rows}")

        contiguous = bool((mom.emb_row.to_numpy() == np.arange(len(mom))).all())
        out["checks"]["emb_row_contiguous"] = contiguous
        if not contiguous:
            out["errors"].append("emb_row 가 0부터 연속이 아니다 — 행 순서가 어긋났다")

        dup = int(mom.moment_id.duplicated().sum())
        out["checks"]["duplicate_moment_id"] = dup
        if dup:
            out["errors"].append(f"moment_id 가 {dup}건 중복이다")
    except Exception as e:                       # 파일이 없거나 깨진 경우
        out["errors"].append(f"{type(e).__name__}: {e}")
    out["ok"] = not out["errors"]
    return out


@app.on_event("startup")
def _startup() -> None:
    t0 = time.time()
    integrity = check_integrity()
    _STATE["integrity"] = integrity
    if not integrity["ok"]:
        # 여기서 죽이지 않는다. /health 로 원인을 볼 수 있어야 하고 ready 만 막으면 된다.
        _STATE["error"] = "; ".join(integrity["errors"])
        print(f"[ai_server] 산출물 무결성 실패 — {_STATE['error']}", flush=True)
        return
    try:
        recommender.CAT = recommender.Catalog()
        _STATE["loaded"] = True
        _STATE["load_seconds"] = round(time.time() - t0, 3)
        print(f"[ai_server] 적재 완료 {_STATE['load_seconds']}초 · "
              f"{recommender.CAT.pool.track_id.nunique()}곡 "
              f"{len(recommender.CAT.pool)}구간", flush=True)
    except Exception as e:
        _STATE["error"] = f"{type(e).__name__}: {e}"
        print(f"[ai_server] 카탈로그 적재 실패 — {_STATE['error']}", flush=True)


@app.get("/health", summary="프로세스가 떠 있는가")
def health() -> dict:
    """뜨기만 하면 200 이다. 요청을 받을 수 있는지는 /internal/ready 가 답한다."""
    cat = recommender.CAT
    return {
        "status": "ok",
        "uptime_seconds": round(time.time() - _STARTED, 1),
        "artifact_dir": str(recommender.ROOT),
        "paths": paths.check(),
        "catalog_loaded": _STATE["loaded"],
        "load_seconds": _STATE["load_seconds"],
        "error": _STATE["error"],
        "catalog": ({"tracks": int(cat.pool.track_id.nunique()),
                     "moments": len(cat.pool)} if cat is not None else None),
        "integrity": (_STATE["integrity"] or {}).get("checks"),
    }


internal_api.install(app, recommender)
