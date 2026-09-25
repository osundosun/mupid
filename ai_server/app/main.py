"""AI 추천 서비스 진입점. CPU 로만 돈다.

`/health` 와 `/internal` 계약만 노출한다. 데모 UI·학습·시뮬레이션·음원 스트리밍은 없다.

기동할 때 산출물 무결성을 먼저 본다. 색인 네 개는 행 번호로 서로를 가리키기 때문에 버전이
섞이면 **엉뚱한 곡을 추천하면서도 서비스는 정상처럼 뜬다.** 어긋나면 `/internal/ready` 를
실패로 둔다.

**기동에서 Spring 을 부르지 않는다**(연동 계약 2026-09-23 절). 곡·구간 메타는 요청마다
본문으로 온다. 예전에는 기동 때 Spring 카탈로그를 당겨 왔고, 토큰이 만료되면 파드가
Ready 가 되지 못해 배포가 롤백됐다(S15P21A204-423).
"""
from __future__ import annotations

import time
from contextlib import asynccontextmanager

import numpy as np
import pandas as pd
from fastapi import FastAPI

from . import (index_contract, internal_api, labels_store, model_bundle, paths,
               pinecone_store, qdrant_store, recommender)
from .log import log

_STARTED = time.time()
_STATE: dict = {"loaded": False, "error": None, "integrity": None, "load_seconds": None,
                "plate": None}


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
    싣는다 — 배치 도구(`register_sections`·`pull_signals`)가 이 파일을 쓰므로
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
    # 이미지의 모델 자리가 대신하는 판 파일은 없어도 결핍이 아니다 (`model_bundle.SERVING`).
    # 채택된 모델로 구운 이미지에서 판의 옛 가중치를 치우면 "선택 산출물 없음" 이 거짓이 된다.
    for bundle_name, alts in model_bundle.SERVING.items():
        if model_bundle.resolve(bundle_name).source != "image":
            continue
        for alt in alts():
            if alt in files:
                files[alt]["served_from_image_as"] = bundle_name
    missing = sorted(k for k, v in files.items()
                     if not v["exists"] and not v.get("served_from_image_as"))
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
        # `moments_emb.npy` 는 **운영 판에 없다.** 순간 벡터의 정본은 Pinecone 이고
        # 이 파일은 분석 배치의 입력 산출물이다. 있으면 함께 세고, 없어도 실패가
        # 아니다 — 대신 벡터 쪽 정합은 control namespace 의 세대 검증이 본다.
        rows = {
            "moments": len(mom),
            "features_v1": int(np.load(d / "features_v1.npz")["X"].shape[0]),
            "stage1_section": int(np.load(d / "stage1_section.npy", mmap_mode="r").shape[0]),
        }
        emb = d / "moments_emb.npy"
        if emb.exists():
            rows["moments_emb"] = int(np.load(emb, mmap_mode="r").shape[0])
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
    bundle = model_bundle.status()
    if bundle["image_bundle"]["error"]:
        # 판으로 물러나 뜬다. 이미지가 약속한 모델로 돌지 않는다는 사실은 남긴다.
        log(f"이미지의 모델 묶음을 쓰지 않는다 — {bundle['image_bundle']['error']}")
    log(f"서빙 가중치 — {bundle['source'] or '없음'}"
        + (f" · {bundle['name']} {bundle['version']}" if bundle["version"] else ""))

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

    # ② 색인·모델. 후보는 비어 있다 — 요청마다 본문의 `catalog` 로 묶는다.
    try:
        recommender.CAT = recommender.Catalog()
    except Exception as e:
        _STATE["error"] = f"{type(e).__name__}: {e}"
        log(f"색인 적재 실패 — {_STATE['error']}")
        return

    # ③ 벡터 검색 갈래. 모르는 값이면 여기서 멈춘다 — 조용히 local 로 내려가지 않는다.
    try:
        backend = recommender.vector_backend()
    except ValueError as e:
        _STATE["error"] = str(e)
        log(f"벡터 검색 갈래 설정 오류 — {_STATE['error']}")
        return

    #    Pinecone 을 켜 두었으면 **평균·namespace·판이 한 묶음인지** 기동에서 본다.
    #    하나만 갈아 끼운 상태는 오류가 아니라 유사도 저하로만 드러나므로 요청을
    #    받기 전에 잡아야 한다.
    if backend == "qdrant":
        # Qdrant 는 **여기서 멈추지 않는다.** 판정은 `/internal/ready` 가 요청마다(몇 초 캐시)
        # 다시 한다 — Qdrant 가 늦게 뜨거나 잠깐 죽었다 살아나도 재기동 없이 준비 완료로
        # 돌아온다. 기동에서는 판정을 한 번 찍어 둔다.
        why = recommender.vector_store_problem()
        _STATE["vector_search"] = {"backend": "qdrant", **qdrant_store.settings(),
                                   "problem": why}
        if why:
            log(f"벡터 검색 — Qdrant 준비 안 됨 (준비 판정 실패) — {why}")
        else:
            st = recommender.vector_store().status()
            log(f"벡터 검색 — Qdrant · 컬렉션 {st['collection']!r} · {st.get('points')}구간 "
                f"· μ {st['mu_source']}")
    elif backend == "pinecone":
        try:
            got = pinecone_store.center()
            # control 의 세대가 지금 판과 같은가. **저장본이 있을 때만** 다시 잴 수
            # 있다 — 운영 판에는 없으므로 control 이 적은 값을 그대로 믿는다.
            if getattr(recommender.CAT, "Z", None) is not None:
                got.require_snapshot(recommender.current_snapshot())
            _STATE["vector_search"] = {"backend": "pinecone", **got.stats(),
                                       **pinecone_store.stats()}
            log(f"벡터 검색 — Pinecone · namespace {pinecone_store.namespace()!r} · 평균 {got.rows}건")
        except Exception as e:
            _STATE["error"] = f"{type(e).__name__}: {e}"
            log(f"벡터 검색 준비 실패 — {_STATE['error']}")
            return
    else:
        _STATE["vector_search"] = {"backend": "local"}

    _STATE["loaded"] = True
    _STATE["load_seconds"] = round(time.time() - t0, 3)
    log(f"적재 완료 {_STATE['load_seconds']}초 · 색인 {len(recommender.CAT._mom_index)}행 "
        f"(후보는 요청 본문으로 온다)")


def _generation() -> dict:
    """서버가 지금 들고 있는 control 세대. **비밀값은 없다.**

    갱신 배치가 이 값을 읽어 "새 μ 가 서버 메모리에 반영됐는가" 를 판정한다.
    읽는 김에 캐시도 갱신되므로, 배치가 물어보는 행위 자체가 반영을 앞당긴다.
    """
    try:
        backend = recommender.vector_backend()
    except ValueError as e:
        return {"backend": None, "error": str(e)}
    if backend == "qdrant":
        if recommender.CAT is None:
            return {"backend": "qdrant"}
        try:
            store = recommender.vector_store()
            # 지난 판정만 싣는다 — /health 는 liveness 라 Qdrant 를 기다리면 안 된다.
            return {"backend": "qdrant", "generation": store.generation,
                    "status": store.cached_status()}
        except Exception as e:                   # μ 가 없는 판 등 — /health 는 늘 답한다
            return {"backend": "qdrant", "error": f"{type(e).__name__}: {e}"}
    if backend != "pinecone":
        return {"backend": "local"}
    try:
        got = pinecone_store.center(reload=True)
        return {"backend": "pinecone", **got.stats()}
    except Exception as e:
        return {"backend": "pinecone", "error": f"{type(e).__name__}: {e}"}


def _vector_settings() -> dict:
    """갈래와 그 설정. **키는 있는지 여부만** 싣는다. 갈래 설정이 틀려도 /health 는 답한다."""
    try:
        backend = recommender.vector_backend()
    except ValueError as e:
        return {"backend": None, "error": str(e)}
    out = {"backend": backend, **pinecone_store.settings()}
    if backend == "qdrant":
        out["qdrant"] = qdrant_store.settings()
    return out


def _model_status() -> dict:
    """모델 자리 판정 + 이 프로세스가 실제로 적재한 자리. 둘이 다르면 기동 뒤 파일이 바뀐 것이다."""
    out = model_bundle.status()
    out["loaded_from"] = dict(getattr(recommender.CAT, "model_sources", None) or {})
    out["onboarding_seeds"] = recommender.seed_table_status()
    return out


@asynccontextmanager
async def lifespan(_app: FastAPI):
    _startup()
    yield


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
        "index_loaded": _STATE["loaded"],
        # torch 판인지 NumPy 판인지. 이미지에서 torch 를 걷어낸 뒤 실제로 어느 쪽이
        # 돌고 있는지 응답으로 확인할 수 있어야 한다.
        "model_backend": getattr(recommender.CAT, "model_backend", None),
        # 규칙 경로로 내려앉았다면 이유가 여기 있다. 비어 있으면 학습 점수가 살아 있다.
        "model_error": getattr(recommender.CAT, "model_error", None),
        # 서빙 가중치를 어디서 읽었나 — 이미지의 모델 자리(`models.lock` 이 채택한 버전)인가,
        # 판에 섞인 가중치인가. 배포가 새 모델로 바뀌었는지 이 값으로 확인한다 (S15P21A204-454).
        "model": _model_status(),
        "load_seconds": _STATE["load_seconds"],
        "error": _STATE["error"],
        "index": ({"moments": int(len(cat._mom_index)),
                   "pool_sources": sorted(recommender.POOL_SOURCES)}
                  if cat is not None else None),
        # 곡·순간 메타데이터의 출처. 요청 본문이다 — 기동 때 준비할 것이 없다.
        "metadata_source": "request",
        # 순간 벡터를 어디서 읽는가. Pinecone 을 켜 두었는데 값이 비면 검색이
        # 통째로 실패하므로, 설정 여부가 준비 판정과 같은 화면에 있어야 한다.
        "vector_search": _vector_settings(),
        # 갱신 중인지, 어느 세대를 들고 있는지. 배치가 "서버가 새 세대를 읽었나" 를
        # 이 값으로 확인하고 그 뒤에 READY 로 넘어간다.
        "vector_generation": _generation(),
        "index_contract": _STATE["plate"],
        "integrity": (_STATE["integrity"] or {}).get("checks"),
        "artifacts": artifact_status(),
    }


internal_api.install(app, recommender)
