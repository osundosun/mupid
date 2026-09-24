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


def state_dir() -> Path:
    """배치가 **쓰는** 자리. 색인과 나눈다.

    색인은 여러 파드가 함께 읽는 것이라 마운트가 읽기 전용이다. 그런데 배치는
    같은 디렉터리에 워터마크·이벤트 로그·라벨·선호 구간을 쓴다 — 그대로 두면
    쿠버네티스에서 배치가 아예 돌지 못한다.

    기본값은 종전과 같은 자리다(`data_dir()`). 나누지 않은 환경에서는 동작이
    바뀌지 않는다.

    나눌 때 **색인 디렉터리를 쓰면 안 되는 이유**가 하나 더 있다. 색인은 판마다
    새 디렉터리로 올라오고 `current` 링크가 갈아 끼워진다. 상태를 거기 두면
    색인을 교체할 때마다 워터마크가 사라져 신호를 처음부터 다시 당긴다.
    """
    return Path(os.environ.get("MUPID_AI_STATE_DIR") or data_dir())


def moments_path() -> Path:
    return data_dir() / "moments.parquet"


def track_features_path() -> Path:
    """곡 수준 오디오·생성 파생값. **MySQL 이 들고 있지 않은 것만** 담는다.

    `content.parquet` + `flow_meta.parquet` 이 서빙에서 하던 일을 대신하는데, 둘과
    달리 제목·장르·무드·보컬을 담지 않는다 — 그것들은 MySQL 이 정본이고
    Spring 이 요청 본문으로 준다. 여기 남는 것은 DB 에 컬럼 자체가 없는 축이다.

        bpm · bpm_lo · bpm_hi · dur_sec · language · category · sound_raw

    `bpm` 은 오디오에서 잰 값이고, `language`·`category` 는 곡을 만들 때의 생성
    조건이다. ERD 에 자리가 없어 API 로 옮길 수 없다 — 스키마가 생기면 그때 옮긴다
    (`infra/AI-API-BLOCKERS.md`).
    """
    return data_dir() / "track_features.parquet"


def index_meta_path() -> Path:
    """색인이 **어떤 계약으로 구워졌는지** 적어 둔 표식.

    이것이 없으면 옛 판과 새 코드가 섞인다. 옛 판은 장르·무드를
    `flow_meta.parquet` 안에 들고 있는데 새 코드는 그 파일을 읽지 않으므로,
    **오류 없이 장르·무드 축이 통째로 빈 채** 추천이 돈다. 그 상태는 응답만
    봐서는 정상과 구분되지 않는다. 그래서 표식을 보고 거절한다.
    """
    return data_dir() / "index_meta.json"


# ── 배치가 **쓰는** 자리. 전부 `state_dir()` 에 붙는다. 하나라도 `data_dir()` 로
#    돌아가면 읽기 전용 색인 마운트에서 그 순간 터진다.

def serve_dir() -> Path:
    return state_dir() / "serve"


def events_path() -> Path:
    return state_dir() / "logs" / "events_service.jsonl"


def labels_path() -> Path:
    """라벨의 정본은 ClickHouse 다(`labels_store`). 이 파일은 ClickHouse 가 없는
    개발·오프라인에서만 쓰이고, 배치가 만든 중간 산출물이기도 하다."""
    return state_dir() / "labels_service.parquet"


def prefs_path() -> Path:
    return serve_dir() / "prefs.parquet"


def onboarding_path() -> Path:
    return serve_dir() / "onboarding.parquet"


# ── 시험 파이프라인 산출물. 색인 옆에 있지만 색인 갱신 대상은 아니다.

def test_pipeline_dir() -> Path:
    return artifact_root() / "data" / "test_pipeline"


def overrides_path() -> Path:
    return test_pipeline_dir() / "preference_overrides.jsonl"


def checkpoint_path() -> Path:
    return test_pipeline_dir() / "learn_checkpoint.json"


def jamendo_catalog_path() -> Path:
    return test_pipeline_dir() / "jamendo_catalog.parquet"


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
    meta = index_meta_path()
    return {
        "artifact_dir": str(root),
        "artifact_dir_from_env": bool(os.environ.get("MUPID_AI_ARTIFACT_DIR")),
        "data_dir": str(data),
        "state_dir": str(state_dir()),
        "state_dir_split": bool(os.environ.get("MUPID_AI_STATE_DIR")),
        "data_dir_exists": data.is_dir(),
        "moments": {"path": str(mom), "exists": mom.exists()},
        "index_meta": {"path": str(meta), "exists": meta.exists()},
        "id_map": {"path": str(idm), "exists": idm.exists(),
                   "overridden": bool(os.environ.get("MUPID_ID_MAP"))},
    }
