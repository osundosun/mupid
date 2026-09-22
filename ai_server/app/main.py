"""AI 추천 서비스 진입점. CPU 로만 돈다.

`/health` 와 `/internal` 계약만 노출한다. 데모 UI·학습·시뮬레이션·음원 스트리밍은 없다.

기동할 때 산출물 무결성을 먼저 본다. 색인 네 개는 행 번호로 서로를 가리키기 때문에 버전이
섞이면 **엉뚱한 곡을 추천하면서도 서비스는 정상처럼 뜬다.** 어긋나면 `/internal/ready` 를
실패로 둔다.
"""
from __future__ import annotations

import threading
import time
from contextlib import asynccontextmanager

import numpy as np
import pandas as pd
from fastapi import FastAPI

from . import catalog_api, index_contract, internal_api, labels_store, paths, recommender
from .log import log

_STARTED = time.time()
_STATE: dict = {"loaded": False, "error": None, "integrity": None, "load_seconds": None,
                "plate": None, "catalog_match": None}


# 없어도 서비스가 뜨지만 **기능 하나가 조용히 사라지는** 파일들. 행 수 검사 대상이 아니라
# 존재 여부만 본다. 예컨대 labels_service.parquet 이 없으면 오류 없이 모든 사용자가
# 콜드스타트로 처리된다 — 서버는 정상으로 보이고 개인화만 없다.
_OPTIONAL_ARTIFACTS = {
    "logistic_flow_stage1.json": "점수 결합기. 없으면 규칙 점수로 내려간다",
    # 둘 중 **하나**는 있어야 학습 점수가 산다. npz 가 있으면 torch 가 필요 없다.
    "stage1_weights.npz": "랭킹 모델(NumPy 판). 권장 — 이미지에 torch 가 필요 없다",
    "stage1.pt": "랭킹 모델(torch 판). npz 가 있으면 필요 없다",
}


def artifact_status() -> dict:
    """선택 산출물과 매핑표의 적재 상태. `/health` 가 그대로 내보낸다.

    매핑표는 **서빙 산출물이 아니다.** 숫자↔문자열 번역은 Spring 이 하고
    `/internal/ready` 도 더는 보지 않는다(`internal_api.ready`). 그래도 상태는
    싣는다 — 적재 도구(`push_sections`·`register_sections`)가 이 파일을 쓰므로
    운영 중에 "왜 적재가 안 되나" 를 여기서 먼저 본다. 대신 `missing` 에는 넣지
    않는다. 넣으면 기동 로그가 매번 "선택 산출물 없음" 을 찍어, 정작 개인화가
    빠진 배포와 구분이 되지 않는다.
    """
    d = paths.data_dir()
    files = {name: {"exists": (d / name).exists(), "why": why}
             for name, why in _OPTIONAL_ARTIFACTS.items()}
    # 라벨은 파일이 아닐 수 있다. ClickHouse 를 쓰면 그쪽에 있는지로 판단해야
    # 하고, 파일만 보면 **개인화가 멀쩡한 배포를 콜드스타트로 잘못 보고한다.**
    labels_ok = recommender.labels_available()
    files["labels_service.parquet"] = {
        "exists": labels_ok,
        "store": "clickhouse" if labels_store.configured() else "file",
        "why": "개인화. 없으면 모든 사용자가 콜드스타트다"}
    missing = sorted(k for k, v in files.items() if not v["exists"])
    idm = paths.id_map_path()
    files["id_map.parquet"] = {
        "exists": idm.exists(), "path": str(idm),
        "why": "숫자 ID 매핑. 적재 도구 전용 — 서빙도 준비 판정도 보지 않는다"}
    return {"files": files, "missing": missing}


def check_integrity() -> dict:
    """색인 네 개의 행 수와 순서를 본다. 추천을 계산하기 전에 확인한다."""
    d = paths.data_dir()
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


def _startup() -> None:
    t0 = time.time()
    miss = artifact_status()["missing"]
    if miss:
        # 죽이지 않는다. "정상처럼 뜬 채 기능만 없는" 상태를 로그와 /health 에 남긴다.
        # 무결성 검사보다 **먼저** 찍는다 — 무결성이 깨지면 아래에서 돌아 나가기 때문이다.
        log(f"선택 산출물 없음 — {', '.join(miss)}")

    # ① 판의 계약 표식. 옛 판(장르·무드를 색인 안에 들고 있던 판)과 새 코드가 섞이면
    #    오류 없이 그 축이 통째로 빈다. 값을 읽기 전에 막는다.
    plate = index_contract.check_plate()
    _STATE["plate"] = plate
    for line in plate["warnings"]:
        log(f"색인 주의 — {line}")
    if not plate["ok"]:
        _STATE["error"] = "; ".join(plate["errors"])
        log(f"색인 계약 실패 — {_STATE['error']}")
        return

    integrity = check_integrity()
    _STATE["integrity"] = integrity
    if not integrity["ok"]:
        # 여기서 죽이지 않는다. /health 로 원인을 볼 수 있어야 하고 ready 만 막으면 된다.
        _STATE["error"] = "; ".join(integrity["errors"])
        log(f"산출물 무결성 실패 — {_STATE['error']}")
        return

    # ② 카탈로그. **최초 적재 실패는 실패다** — 없이 뜨면 장르·무드·보컬·악기
    #    조건이 통째로 빈 채 추천이 나가고, 그것은 응답만 봐서는 정상과 구분되지
    #    않는다. 갱신 실패는 다르다(`catalog_api.refresh` 가 옛 것을 지킨다).
    try:
        catalog_api.load()
    except Exception as e:
        _STATE["error"] = f"{type(e).__name__}: {e}"
        log(f"카탈로그 적재 실패 — {_STATE['error']}")
        return

    try:
        recommender.CAT = recommender.Catalog()
    except Exception as e:
        _STATE["error"] = f"{type(e).__name__}: {e}"
        log(f"색인 적재 실패 — {_STATE['error']}")
        return

    # ③ 판과 카탈로그가 짝인가. 표식만으로는 "이 카탈로그와 짝인가" 를 알 수 없다.
    match = index_contract.check_catalog(
        recommender.CAT.pool.moment_id.astype(str).tolist(), catalog_api.snapshot())
    _STATE["catalog_match"] = match
    if not match["ok"]:
        _STATE["error"] = match["reason"]
        log(f"색인-카탈로그 불일치 — {match['reason']}")
        return
    if match["moments_unmatched"]:
        # 정상일 수 있다(지워진 곡). 그래도 수를 남긴다 — 조용히 줄어드는 것이 제일 나쁘다.
        log(f"카탈로그에 없는 색인 구간 {match['moments_unmatched']}건 — 후보에서 뺐다 "
            f"(예: {', '.join(match['sample_unmatched'])})")

    _STATE["loaded"] = True
    _STATE["load_seconds"] = round(time.time() - t0, 3)
    log(f"적재 완료 {_STATE['load_seconds']}초 · "
        f"{recommender.CAT.pool.track_id.nunique()}곡 "
        f"{len(recommender.CAT.pool)}구간")


def _refresh_loop(stop: threading.Event) -> None:
    """TTL 이 지나면 카탈로그를 다시 읽는다. **요청 경로에서 하지 않는다.**

    요청 안에서 갱신하면 그 사용자 한 명이 카탈로그 전체를 받아오는 동안 기다린다.
    여기서 하면 느려지는 것은 아무도 아니고, 실패해도 옛 카탈로그로 계속 돈다.

    깨어나는 간격을 TTL 보다 짧게 두는 것은 **종료 때문**이다. TTL 이 한 시간이면
    파드가 내려갈 때 그만큼 기다리게 된다.
    """
    while not stop.is_set():
        ttl = catalog_api.ttl_seconds()
        if not ttl:
            return
        if stop.wait(min(ttl, 60.0)):
            return
        snap = catalog_api.snapshot()
        if snap is None or time.time() - snap.loaded_at < ttl:
            continue
        try:
            internal_api.refresh_catalog()
        except Exception as e:                   # 주기 작업이 프로세스를 죽이지 않게
            log(f"카탈로그 주기 갱신이 예외로 끝났다 — {type(e).__name__}: {e}")


@asynccontextmanager
async def lifespan(_app: FastAPI):
    _startup()
    stop = threading.Event()
    thread = None
    if _STATE["loaded"] and catalog_api.ttl_seconds():
        thread = threading.Thread(target=_refresh_loop, args=(stop,),
                                  name="catalog-refresh", daemon=True)
        thread.start()
        log(f"카탈로그 주기 갱신 {catalog_api.ttl_seconds():.0f}초")
    try:
        yield
    finally:
        stop.set()
        if thread is not None:
            thread.join(timeout=5)


app = FastAPI(title="MUPID AI 추천 서비스", version="1.0.0", lifespan=lifespan)


@app.get("/health", summary="프로세스가 떠 있는가")
def health() -> dict:
    """뜨기만 하면 200 이다. 요청을 받을 수 있는지는 /internal/ready 가 답한다."""
    cat = recommender.CAT
    return {
        "status": "ok",
        "uptime_seconds": round(time.time() - _STARTED, 1),
        "artifact_dir": str(paths.artifact_root()),
        "paths": paths.check(),
        "catalog_loaded": _STATE["loaded"],
        # torch 판인지 NumPy 판인지. 이미지에서 torch 를 걷어낸 뒤 실제로 어느 쪽이
        # 돌고 있는지 응답으로 확인할 수 있어야 한다.
        "model_backend": getattr(recommender.CAT, "model_backend", None),
        # 규칙 경로로 내려앉았다면 이유가 여기 있다. 비어 있으면 학습 점수가 살아 있다.
        "model_error": getattr(recommender.CAT, "model_error", None),
        "load_seconds": _STATE["load_seconds"],
        "error": _STATE["error"],
        "catalog": ({"tracks": int(cat.pool.track_id.nunique()),
                     "moments": len(cat.pool),
                     # 설정한 출처가 실제로 몇 건 실렸는지. 0 이면 오타이거나 미적재다.
                     "by_source": cat.pool_sources,
                     "empty_sources": cat.pool_sources_empty,
                     # 색인에는 있는데 MySQL 에 없는 구간. 지워진 곡이면 정상이다.
                     "moments_outside_catalog": cat.moments_outside_catalog}
                    if cat is not None else None),
        # 곡·순간 메타데이터의 출처. **여기가 비면 장르·무드 축이 통째로 빈다.**
        "metadata_source": catalog_api.status(),
        "index_contract": _STATE["plate"],
        "catalog_match": _STATE["catalog_match"],
        "integrity": (_STATE["integrity"] or {}).get("checks"),
        "artifacts": artifact_status(),
    }


internal_api.install(app, recommender)
