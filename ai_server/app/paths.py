"""산출물 경로를 한 곳에서 계산한다.

경로 계산이 여러 파일로 흩어지면 **일부만 다른 곳을 보게 된다.** 그때 증상이 나쁘다 —
서비스는 정상처럼 뜨고 `/health` 도 200 인데 매핑표만 빈 상태라 모든 요청이 503 이거나,
더 나쁘게는 다른 판의 색인을 섞어 엉뚱한 곡을 추천한다. 원인이 경로라는 것이 응답에
드러나지 않아 추적이 오래 걸린다. 그래서 루트·데이터 디렉터리·매핑표는 여기서만 정한다.

기준은 `MUPID_AI_ARTIFACT_DIR` 하나다. 비어 있으면 `ai_server/artifacts` 를 쓴다.

함수로 내보내는 이유는 **읽는 시점을 호출자가 정할 수 있게** 하기 위해서다. 모듈 상수로
굳히면 import 순서에 따라 환경변수 설정 전의 값이 박힌다(테스트가 실제로 그 순서에
의존하고 있었다).

`labeler`·`features`·`stage1_torch` 도 여기를 쓴다. 예전에는 셋이 각자 셈했고 그게
`MUPID_AI_ARTIFACT_DIR=` (빈 값) 에서 갈렸다 — `os.environ.get(k, default)` 는 빈
문자열을 덮지 않아 `Path('')`, 곧 현재 디렉터리가 된다. 확인은 `check()` 가 한다.
"""
from __future__ import annotations

import os
from pathlib import Path

PKG_ROOT = Path(__file__).resolve().parents[1]          # ai_server/
DEFAULT_ARTIFACT_DIR = PKG_ROOT / "artifacts"


def artifact_root() -> Path:
    """모델·색인 루트."""
    return Path(os.environ.get("MUPID_AI_ARTIFACT_DIR") or DEFAULT_ARTIFACT_DIR)


def data_dir() -> Path:
    return artifact_root() / "data" / "proc" / "mupid"


def moments_path() -> Path:
    return data_dir() / "moments.parquet"


def id_map_path() -> Path:
    """숫자 ID 매핑표.

    `MUPID_ID_MAP` 은 시험용 매핑표를 따로 물리기 위한 것이다. **빈 문자열은 미설정으로
    본다** — `.env` 에 `MUPID_ID_MAP=` 로 남겨 두는 경우가 흔하고, 그대로 `Path("")` 를
    만들면 현재 디렉터리를 가리켜 진단이 불가능한 실패가 된다.
    """
    return Path(os.environ.get("MUPID_ID_MAP") or (data_dir() / "id_map.parquet"))


def check() -> dict:
    """진단용. **경로만** 싣는다 — 환경변수 값 자체(해석기 주소 등)는 내보내지 않는다.

    `/health` 와 `/internal/ready` 가 이걸 그대로 쓴다. "설정한 대로 안 읽는다" 를
    응답만 보고 판별할 수 있어야 한다.
    """
    root, data = artifact_root(), data_dir()
    idm, mom = id_map_path(), moments_path()
    return {
        "artifact_dir": str(root),
        "artifact_dir_from_env": bool(os.environ.get("MUPID_AI_ARTIFACT_DIR")),
        "data_dir": str(data),
        "data_dir_exists": data.is_dir(),
        "moments": {"path": str(mom), "exists": mom.exists()},
        "id_map": {"path": str(idm), "exists": idm.exists(),
                   "overridden": bool(os.environ.get("MUPID_ID_MAP"))},
    }
