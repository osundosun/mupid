"""서빙 가중치를 어디서 읽는가 — **이미지의 모델 자리 먼저, 없으면 판** (S15P21A204-454, 설계 §6.3).

모델 묶음은 CI 가 `models.lock` 대로 Object Storage 에서 받아 sha256 을 확인하고 이미지에
굽는다(`infra_k8s/ci/package-ai.sh`). 파드는 토큰도 Filer 접근도 없이 뜬다 — 새 가중치가 든
새 이미지로 파드가 바뀌는 것이 곧 반영이다. `models.lock` 에 채택된 버전이 없으면 모델 자리는
비어 있고, 지금처럼 판(`data/proc/mupid/`)에 섞인 가중치를 읽는다.

모델 자리에는 CI 가 `model.json` 을 함께 쓴다 — 이름·버전과 파일별 sha256(`models.lock` 에서
옮긴 값). 기동 때 그 값과 파일을 다시 대조한다. 이미지는 CI 가 확인한 바이트를 담았겠지만,
어긋난 파일로 도는 것보다 판으로 물러나 그 사실을 `/health` 에 적는 편이 낫다.

**묶음 이름과 판 이름이 다르다.** 대응은 여기 `SERVING` 한 곳에만 둔다.

    묶음 (설계 §6.3)        판 (지금)
    user_encoder.npz        stage1_weights.npz      (`tools/export_stage1_npz.py`)
    combiner.json           logistic_flow_stage1.json  (`MUPID_COMBINER` 로 바꿀 수 있다)
                            → logistic_combiner.json   (그것도 없을 때)
    onboarding_seeds.npz    — 판에는 없다. 없으면 요청 사본마다 센다
                              (`tools/export_onboarding_seeds.py`, `recommender.seed_sums`)
    mu.npy                  — 판에는 없다. Qdrant 갈래의 동결 μ (설계 §6.3, S15P21A204-456).
                              없으면 판의 색인 풀 평균을 쓴다 (`recommender._frozen_mu`)
"""
from __future__ import annotations

import hashlib
import json
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

from . import paths

MODEL_NAME = "stage1"
MANIFEST = "model.json"


def _combiner_names() -> tuple[str, ...]:
    return (os.environ.get("MUPID_COMBINER") or "logistic_flow_stage1.json",
            "logistic_combiner.json")


# 묶음 이름 → 판에서 찾을 이름들 (앞의 것부터). 함수인 것은 `MUPID_COMBINER` 를 부를 때 읽으려는 것이다.
SERVING = {
    "user_encoder.npz": lambda: ("stage1_weights.npz",),
    "combiner.json": _combiner_names,
    "onboarding_seeds.npz": lambda: (),
    "mu.npy": lambda: (),
}


def model_dir() -> Path:
    """이미지의 모델 자리. 이미지에서는 `/app/ai_server/models/stage1` 이다.

    저장소의 같은 자리(`ai_server/models/stage1/`)는 `.gitkeep` 만 두고 비워 둔다 — 개발·시험은
    지금처럼 판을 읽는다. 빈 문자열은 미설정으로 본다(`paths` 의 규칙과 같다).
    """
    return Path(os.environ.get("MUPID_AI_MODEL_DIR") or (paths.PKG_ROOT / "models" / MODEL_NAME))


@dataclass(frozen=True)
class Resolved:
    name: str                  # 묶음 이름
    path: Optional[Path]       # 읽을 파일. 어디에도 없으면 None
    source: Optional[str]      # "image" | "artifact" | None


def _sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


_CACHE: dict = {}


def image_bundle() -> dict:
    """모델 자리의 `model.json` 을 읽고 파일을 대조한다. 결과는 자리마다 한 번만 센다.

    돌려주는 것: `{"present", "version", "files": {이름: sha}, "error"}`. `error` 가 있으면
    모델 자리를 쓰지 않는다 — 버전 표식 없는 파일, 체크섬이 어긋난 파일을 믿지 않는다.
    """
    d = model_dir()
    key = str(d)
    got = _CACHE.get(key)
    if got is not None:
        return got
    out: dict = {"dir": str(d), "present": False, "version": None, "files": {}, "error": None}
    manifest = d / MANIFEST
    loose = [p.name for p in d.glob("*") if p.is_file() and p.name in SERVING] if d.is_dir() else []
    if not manifest.exists():
        if loose:
            # CI 는 파일과 표식을 늘 함께 쓴다. 표식이 없으면 누가 어디서 넣었는지 모르는 파일이다.
            out["error"] = f"{MANIFEST} 없이 가중치만 있다: {sorted(loose)} — 쓰지 않는다"
        _CACHE[key] = out
        return out
    try:
        meta = json.loads(manifest.read_text(encoding="utf-8"))
        files = {str(k): str(v).lower() for k, v in (meta.get("files") or {}).items()}
        out.update(present=True, version=meta.get("version"), files=files)
        if meta.get("name", MODEL_NAME) != MODEL_NAME:
            out["error"] = f"다른 모델의 묶음이다: {meta.get('name')!r}"
        for name, want in files.items():
            p = d / name
            if not p.exists():
                out["error"] = f"{MANIFEST} 에 적힌 {name} 이 없다"
                break
            if _sha256(p) != want:
                out["error"] = f"{name} 의 sha256 이 {MANIFEST} 와 다르다"
                break
    except Exception as e:
        out["error"] = f"{MANIFEST} 를 읽지 못했다: {type(e).__name__}: {e}"
    _CACHE[key] = out
    return out


def resolve(name: str) -> Resolved:
    """묶음 이름 하나를 읽을 자리. 모델 자리(표식이 멀쩡하고 그 파일이 적혀 있을 때) → 판."""
    if name not in SERVING:
        raise KeyError(f"서빙 파일이 아니다: {name}")
    b = image_bundle()
    if b["present"] and not b["error"] and name in b["files"]:
        return Resolved(name, model_dir() / name, "image")
    for alt in SERVING[name]():
        p = paths.data_dir() / alt
        if p.exists():
            return Resolved(name, p, "artifact")
    return Resolved(name, None, None)


def status() -> dict:
    """`/health` 의 `model`. 어느 자리에서 읽었는지와 모델 버전.

    `version` 은 모델 자리에서 읽은 파일이 하나라도 있을 때만 있다. 판에 섞인 가중치에는 버전이
    없다 — 그 상태가 `models.lock` 에 채택된 모델이 없는 지금이다.
    """
    b = image_bundle()
    files = {}
    for name in SERVING:
        r = resolve(name)
        files[name] = {"source": r.source, "path": str(r.path) if r.path else None}
    from_image = any(v["source"] == "image" for v in files.values())
    return {
        "name": MODEL_NAME,
        "version": b["version"] if from_image else None,
        "source": ("image" if from_image
                   else "artifact" if any(v["source"] for v in files.values()) else None),
        "model_dir": b["dir"],
        "image_bundle": {"present": b["present"], "version": b["version"], "error": b["error"]},
        "files": files,
    }


def reset() -> None:
    """시험용. 모델 자리 판정을 다시 센다."""
    _CACHE.clear()
