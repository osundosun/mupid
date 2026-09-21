"""추천 계산부. `rec_mupid/src/m16_service.py` 의 1~1597행.

**추천 점수·가중치·필터·후보 생성·랭킹을 바꾸지 않았다.** 데모 라우트(UI·학습·시뮬레이션·
음원 스트리밍)만 떼어냈고, 원본과 다른 곳은 세 군데뿐이다.

  1. `import os` 추가
  2. 산출물 경로를 `MUPID_AI_ARTIFACT_DIR` 에서 읽는다
  3. 라벨러·특징·Stage 1 모듈을 `app/` 의 정식 모듈로 가져와 상대 import 로 부른다
"""
from __future__ import annotations

import copy
import json
import os
import subprocess
import sys
import threading
import time
import uuid
import warnings
from urllib.request import Request as UrlRequest, urlopen
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

import numpy as np
import pandas as pd
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import (FileResponse, HTMLResponse, RedirectResponse, Response)
from pydantic import BaseModel

from . import labels_store, metrics, paths, stage1_np
from .log import log

warnings.filterwarnings("ignore")

# 모델·색인 경로는 MUPID_AI_ARTIFACT_DIR 하나로 통일한다. 계산은 `paths` 한 곳에서만 한다.
#
# **상수로 굳히지 않는다.** 예전에는 여기서 `ROOT = paths.artifact_root()` 로 받아
# 두었는데, 그러면 import 시점의 환경변수가 그대로 박힌다. 이 모듈은 다른 모듈이
# 먼저 끌어올 수 있어서 "누가 먼저 import 했는가" 가 경로를 정하게 되고, 값이
# 어긋나도 서비스는 정상처럼 뜬다. 그래서 읽는 시점에 부른다 — 라벨러는 **코드와
# 함께** 배포되므로(`app/labeler.py`) 산출물 디렉터리에는 없다는 점도 그대로다.
#
# 배치가 쓰는 자리(`serve`·`logs`·`labels_service.parquet`)는 `paths.state_dir()`
# 에 붙는다. 나누지 않은 환경에서는 기본값이 색인 디렉터리라 종전과 같다.
# 음원·표지 경로는 두지 않는다. 이 서비스는 스트리밍을 하지 않고(main.py), 그 값을 읽는
# 곳도 없었다. 개발자 홈 디렉터리(Path.home())를 가리키고 있어 컨테이너에서는 존재하지도
# 않는 경로였고, 추천 결과 딕셔너리에 실려 로그로 새어 나갈 수 있었다.
# 자연어 해석기 주소. 게이트(설정 여부 판단)와 호출부가 **같은 변수**를 본다.
# 기본값을 두지 않는다 — 기본값이 있으면 "미설정" 이 존재할 수 없어, 해석기가 없는 배포에서
# 조건을 뽑지 못한 채 일반 추천이 나간다.
NLP_PARSE_URL = os.environ.get("MUPID_AI_NLP_URL", "").strip()

# ---------------------------- 설정 (조정 대상) ----------------------------
POOL_SOURCE = os.environ.get("MUPID_POOL", "flow")
# 후보 풀에 포함할 moments.source 집합. 출처 표기는 그대로 두고 선택만 넓힌다.
# 해당 출처의 행이 없으면 기존 동작과 완전히 같다.
#
# 기본값에서 `own` 을 뺐다. 현재 카탈로그에 그 출처의 행이 0건이라 아무 일도 하지 않으면서
# "자체 음원도 추천된다" 고 읽히기 때문이다. 실제로 넣을 때 환경변수로 다시 켠다.
# 설정한 출처가 카탈로그에 없으면 기동 로그와 /health 가 알려준다 — 오타로 풀이 조용히
# 비는 일이 없어야 한다.
POOL_SOURCES = {POOL_SOURCE} | {
    x.strip() for x in os.environ.get("MUPID_POOL_EXTRA", "jamendo").split(",")
    if x.strip()}
MAX_BPM_LOG2 = 0.40
MIN_MOMENT_SEC = 8.0
W_RULE, W_MODEL = 0.5, 0.5     # 로지스틱 계수가 없을 때만 쓰는 폴백
CLAP_WEIGHT = 0.25             # 자연어-오디오 의미 점수; 명시 필터 통과 후보만 재정렬
MOOD_WEIGHT = 0.25             # filters.mood 부드러운 재정렬; 걸러내지 않는다
P_MIN = 0.32                   # 절대 임계값 (P_MODE="absolute" 일 때만)
P_MODE = "quantile"            # quantile | absolute
P_TOP_FRAC = 0.05              # 사용자별 후보 확률 상위 5% 를 통과시킨다
SELECT_MODE = "logistic"       # logistic | rank (rank 는 예전 랭크 가중합)
NEAR_DUP_COS = 0.95
MMR_LAMBDA = 0.8
EXPLORE_SLOTS = 1
RECENT_EXCLUDE_SEC = 4 * 3600
# ---- 행동 신호 가중치. **검증된 값이 아니라** 조정용 초기값이다.
# 점수는 백분위([0,1])로 정규화한 뒤 더하므로 아래 값은 "백분위 몇 %p" 로 읽으면 된다.
BEH_W_COMPLETE = 0.02          # 정상 완주 1회 가점 (약한 긍정)
BEH_CAP_COMPLETE = 3           # 완주 가점 누적 상한 횟수
BEH_W_REPEAT = 0.03            # 2회째부터 반복 완주 추가 가점
BEH_CAP_REPEAT = 3             # 반복 가점 누적 상한 횟수
BEH_W_LIKE = 0.15              # 명시적 LIKE (강한 긍정)
BEH_W_SKIP = 0.20              # Early Skip 1회 감점(감쇠 전)
BEH_SKIP_FLOOR = 0.40          # 감점 하한 — 누적해도 이보다 더 깎지 않는다
BEH_W_RECENT = 0.30            # 방금 들은 곡의 감점 상한 (4시간에 걸쳐 0 으로 복구)
FLOW_W_BPM = 0.12              # 이어 듣기: BPM 이 튈 때의 감점 상한
FLOW_W_ENERGY = 0.08           # 이어 듣기: 에너지가 튈 때의 감점 상한
FLOW_BPM_TOL = 0.5             # log2 배율 — 0.5 는 약 1.41배까지 허용
BEH_SKIP_HALF_LIFE_DAYS = 7.0  # Early Skip 감점 반감기
BEH_REC_SURFACES = ("autoplay", "recommendation", "daily_recommendation")
# (user, 로그 크기, mtime, 분) -> 집계. 로그가 바뀌면 키가 달라져 자동으로 무효가 된다.
_BEH_CACHE: dict = {}
_BEH_CACHE_MAX = 256
# 4시간 즉시 재추천 방지는 "정상 재생"에만 적용한다. Early Skip 은 영구 제외가 아니라 감점이다.
BEH_PLAYED_REASONS = ("track_done", "user_stop", "replaced")

# ---- 클릭 즉시 반영 ----
# 저장만 하고 재계산하지 않는 이벤트: PLAY_PROGRESS, PLAY_START (아래 두 집합에 없다)
LEARN_TRIGGER_EVENTS = {"LIKE", "UNLIKE", "SEEK_BACK", "TRACK_SKIP", "PLAY_END",
                        "SECTION_MARK", "SECTION_UNMARK", "MOMENT_LIKE", "SEEK", "REPLAY"}
PROFILE_EVENTS = LEARN_TRIGGER_EVENTS | {"DISLIKE", "UNDISLIKE", "SEEK_FORWARD",
                                         "PLAYLIST_ADD", "PLAYLIST_REMOVE"}
LEARN_DEBOUNCE_SEC = 3.0        # 연속 클릭 debounce
# UI 이름 -> 라벨러가 이미 아는 이름. **저장 로그는 원본 이름 그대로 남는다.**
# None 은 라벨러에 대응 개념이 없어 전달하지 않는다는 뜻이다.
UI_TO_LABELER = {
    "SECTION_MARK": ("MOMENT_LIKE", {}),
    "SEEK_BACK": ("SEEK", {}),
    "SEEK_FORWARD": ("SEEK", {}),
    "TRACK_SKIP": ("PLAY_END", {"reason": "skip_next"}),
    "LIKE": ("TRACK_LIKE", {}),          # 최종 상태가 활성일 때만 전달
    "DISLIKE": ("DISLIKE", {}),          # 최종 상태가 활성일 때만 전달
    "UNLIKE": None, "UNDISLIKE": None, "PLAYLIST_REMOVE": None,
    "SECTION_UNMARK": None,          # 구간 해제는 DISMISS override 로 처리한다
    "PLAYLIST_ADD": ("PLAYLIST_ADD", {}),   # 최종 보관 상태일 때만 전달
}
_SEEN_EVENT_IDS: set = set()
_LAST_LEARN: dict = {}      # user -> 마지막 학습 시작 시각 (debounce)
_LEARN_BUSY: set = set()    # 지금 학습 중인 user
_LEARN_DONE: dict = {}      # user -> 마지막 학습 완료 시각
_LEARN_SEEN: dict = {}      # user -> 완료를 응답으로 알린 시각
HAC_TAU = 0.60
DNA_MIN, DNA_MAX = 2, 7
DNA_TOP_N = 40
LAMBDA_USER_RESIDUAL = 0.5
HALF_LIFE_DAYS = 14.0          # 히스토리 반감기(일). 스윕 최적값. 0 = 감쇠 없음
ONBOARD_MIN_MATCH = 12         # 조사 결과로 고른 구간이 이보다 적으면 조건을 단계적으로 완화
ONBOARD_SEEDS = 3              # 조사에서 만드는 씨앗 질의 벡터 개수 상한
# ------------------------------------------------------------------------


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class Catalog:
    def __init__(self) -> None:
        from . import features as feat

        self.mom = pd.read_parquet(paths.data_dir() / "moments.parquet")
        if "source" not in self.mom.columns:
            self.mom["source"] = "fma"
        self.Z = np.load(paths.data_dir() / "moments_emb.npy", mmap_mode="r")
        self.X, self.idx, self.info = feat.load()
        self.content = pd.read_parquet(paths.data_dir() / "content.parquet").set_index("track_id")
        self.meta = (pd.read_parquet(paths.data_dir() / "flow_meta.parquet").set_index("track_id")
                     if (paths.data_dir() / "flow_meta.parquet").exists() else pd.DataFrame())
        self.pool = self.mom[self.mom.source.isin(POOL_SOURCES)].reset_index(drop=True)
        # 출처별 구간 수. 설정했는데 0건인 출처는 오타이거나 아직 안 실린 것이다.
        counts = self.pool.source.value_counts().to_dict()
        self.pool_sources = {src: int(counts.get(src, 0)) for src in sorted(POOL_SOURCES)}
        self.pool_sources_empty = sorted(k for k, v in self.pool_sources.items() if not v)
        if self.pool_sources_empty:
            log(f"설정된 출처인데 구간이 0건이다 — {', '.join(self.pool_sources_empty)}"
                f" (MUPID_POOL / MUPID_POOL_EXTRA 확인)")
        self.pool_rows = self.pool.emb_row.to_numpy()
        self.pool_x = np.asarray([self.idx.get(m, -1) for m in self.pool.moment_id])
        self.by_track = {t: g for t, g in self.pool.groupby("track_id", sort=False)}
        self.clap_idx, self.clap_emb = {}, None
        clap_path = paths.data_dir() / "clap_moments.npz"
        if clap_path.exists():
            d = np.load(clap_path)
            self.clap_emb = np.asarray(d["embeddings"], dtype=np.float32)
            self.clap_idx = {str(mid): i for i, mid in enumerate(d["moment_ids"])}
        self.ZS = None
        self.model_error = None    # 규칙 경로로 내려앉았다면 그 이유
        self.model = None          # torch 판 (있을 때만)
        self.user_enc = None       # NumPy 판 (stage1_weights.npz 가 있을 때)
        self.model_backend = None
        p = paths.data_dir() / "stage1_section.npy"

        # NumPy 가중치가 있으면 그쪽을 먼저 쓴다. 서빙에서 torch 가 하는 일은 enc_user
        # 한 번뿐이라 이것만 있으면 이미지에서 torch 를 걷어낼 수 있다.
        npz = paths.data_dir() / "stage1_weights.npz"
        if p.exists() and npz.exists():
            try:
                enc = stage1_np.load(npz)
                if enc is not None and enc.in_dim == self.X.shape[1]:
                    with np.load(npz) as d:
                        self.hist_max = int(d["hist_max"]) if "hist_max" in d else 32
                        self.ckpt_half_life = (float(d["half_life_days"])
                                               if "half_life_days" in d else None)
                    self.user_enc = enc
                    self.model = enc          # "모델이 있다" 판정에 함께 쓴다
                    self.model_backend = "numpy"
                    self.ZS = np.load(p, mmap_mode="r")
                else:
                    self.model_error = (
                        f"특징 차원 불일치: npz {getattr(enc, 'in_dim', '?')} vs "
                        f"현재 {self.X.shape[1]} — 재학습 필요")
            except Exception as e:
                self.model_error = f"npz 적재 실패: {type(e).__name__}: {e}"

        if self.model is None and p.exists() and (paths.data_dir() / "stage1.pt").exists():
            try:
                import torch

                from . import stage1_torch as s1

                # 체크포인트는 남이 준 파일이다. weights_only=False 로 열면 파일 하나가
                # 임의 코드를 실행할 수 있다. 먼저 안전 모드로 열고, 그 파일이 텐서·
                # 기본형만 담고 있지 않을 때만 예전 방식으로 물러난다 — 그때는 조용히
                # 넘어가지 않고 로그를 남긴다.
                ckpt_path = paths.data_dir() / "stage1.pt"
                try:
                    ck = torch.load(ckpt_path, map_location="cpu", weights_only=True)
                except Exception as _e:
                    log(f"stage1.pt 를 안전 모드로 읽지 못했다 "
                        f"({type(_e).__name__}). 신뢰할 수 있는 파일인지 확인할 것.")
                    ck = torch.load(ckpt_path, map_location="cpu", weights_only=False)
                if ck["in_dim"] == self.X.shape[1]:
                    m = s1.make_model(ck["in_dim"])
                    m.load_state_dict(ck["state"])
                    m.eval()
                    self.model = m
                    self.torch = torch
                    self.model_backend = "torch"
                    self.hist_max = ck["hist_max"]
                    self.ckpt_half_life = ck.get("half_life_days")
                    self.ZS = np.load(p, mmap_mode="r")
                else:
                    self.model_error = (f"특징 차원 불일치: ckpt {ck['in_dim']} vs "
                                        f"현재 {self.X.shape[1]} — 재학습 필요")
            except Exception as e:      # 모델이 없어도 규칙 경로로 동작한다
                self.model_error = f"{type(e).__name__}: {e}"

        # 구간 벡터(stage1_section.npy)가 있다는 것은 **학습 점수를 쓰겠다는 뜻**이다.
        # 그런데 실행기가 없으면 점수가 통째로 빠진 채 규칙 경로로 내려앉는다. 조용히
        # 넘어가면 "추천이 좀 이상하다" 로만 보이므로 여기서 이유를 남긴다.
        if self.model is None and p.exists() and not self.model_error:
            self.model_error = (
                "구간 벡터는 있는데 실행기가 없다 — stage1_weights.npz 를 함께 두거나"
                " (권장, torch 불필요) stage1.pt 와 torch 를 둘 다 둔다."
                " 생성: python -m ai_server.tools.export_stage1_npz")
        if self.model_error:
            log(f"학습 점수 없이 규칙 경로로 동작한다 — {self.model_error}")
        self.comb = None
        import os as _os
        _name = _os.environ.get("MUPID_COMBINER", "logistic_flow_stage1.json")
        cp = paths.data_dir() / _name
        if not cp.exists():
            cp = paths.data_dir() / "logistic_combiner.json"
        if cp.exists():
            d = json.loads(cp.read_text(encoding="utf-8"))
            self.comb = {"features": d["features"],
                         "mean": np.asarray(d["scaler_mean"], dtype=np.float64),
                         "scale": np.asarray(d["scaler_scale"], dtype=np.float64),
                         "coef": np.asarray(d["coef"], dtype=np.float64),
                         "b": float(d["intercept"]),
                         "quantiles": d.get("prob_quantiles", {}),
                         "path": cp.name,
                         "threshold": float(d.get("suggested_threshold", P_MIN)),
                         "eval": d.get("eval", [])}
        self.loaded_at = now_iso()

    def track_info(self, tid: str) -> dict:
        m = self.meta.loc[tid].to_dict() if tid in getattr(self.meta, "index", []) else {}
        c = self.content.loc[tid].to_dict() if tid in self.content.index else {}
        return {
            "track_id": tid, "title": m.get("title", tid),
            "category": _text(m.get("category")),
            "language": _text(m.get("language")),
            "bpm": round(float(c.get("bpm", 0.0)), 1),
            "bpm_requested": [m.get("bpm_lo"), m.get("bpm_hi")],
            "dur_sec": round(float(c.get("dur_sec", 0.0)), 1),
            "sound_raw": m.get("sound_raw", ""),
            **_catalog_tags(m),
            **{k: v for k, v in jamendo_row(tid).items()
               if k in ("source", "artist_name", "audio_url", "image_url",
                        "source_page_url", "license_name", "license_url",
                        "attribution_text")},
        }


CAT: Optional[Catalog] = None

TEMPO_BUCKETS = [("slow", "느림 (90 미만)", 0.0, 90.0),
                 ("mid", "중간 (90~120)", 90.0, 120.0),
                 ("fast", "빠름 (120 이상)", 120.0, 1e9)]
ENERGY_BUCKETS = [("calm", "차분 (하위 33%)", 0.0, 1 / 3),
                  ("normal", "보통", 1 / 3, 2 / 3),
                  ("intense", "강렬 (상위 33%)", 2 / 3, 1.01)]


_MISSING_TEXT = {"", "unknown", "nan", "none", "null", "<na>", "nat"}
# 값은 `VocalGender`(`M`·`F`·`MIX`)와 `music_analysis.vocal_gender
# ENUM('M','F','MIX')` 를 그대로 쓴다. 이 표는 DB 로 나가지 않고 카탈로그 안에만
# 머물지만(밖으로 나가는 것은 `_vocal_values` 가 만드는 검색 어휘다), 어휘를 서버와
# 어긋나게 두면 같은 곡을 두 이름으로 부르게 된다.
# **`etc`·`other` 는 넣지 않는다.** 예전 `ETC` 자리는 S15P21A204-402 에서 `MIX` 로
# 바뀌었고, `other`("남/여로 판정 못 함")를 `MIX`("혼성으로 판정함")로 옮기는 것은
# 모르는 것을 판정으로 만드는 일이다 — `register_analysis` 가 `unknown` 을 비우는
# 것과 같은 원칙이다. 받지 않은 라벨은 `None`("모른다")으로 남는다.
_VOCAL_GENDER = {
    "m": "M", "male": "M", "f": "F", "female": "F",
    "mix": "MIX", "mixed": "MIX",
}
# 검색 근거로 쓰지 않는 악기 이름. demucs 스템 넷 중 `other` 한 종이다
# (`music_preprocessing/.../instruments.py` 의 `STEMS` 가 정본).
#
# **`code_name` 과 `display_name` 을 둘 다 적는다.** 코드표 정리로 이 행의
# `code_name` 이 `그 외 악기` → `other` 로 바뀌는 중인데, 한쪽만 보면 마이그레이션
# 전후 어느 한쪽에서 필터가 통째로 풀린다 — 실측 9,292행(`music_analysis_instruments`
# 의 31%)이 "구체적인 악기" 로 검색에 노출된다. 오류는 나지 않고 결과만 나빠진다.
#
# 이름이 같은 `_VOCAL_GENDER["other"]` 와는 **다른 축이다.** 저쪽은 보컬 성별이고
# 값이 `ETC` 다. 한 집합으로 합치면 성별 `other` 까지 같이 지워진다.
_NONSPECIFIC_INSTRUMENTS = {"other", "그 외 악기", "그외 악기"}


def _text(v) -> str:
    """누락 표기를 실제 어휘로 노출하지 않는다."""
    if v is None or (isinstance(v, float) and v != v):
        return ""
    value = str(v).strip()
    return "" if value.lower() in _MISSING_TEXT else value


def _tag_list(v) -> list:
    """flow_meta 의 쉼표 구분 태그 문자열을 리스트로."""
    if isinstance(v, (list, tuple, set, np.ndarray)):
        values = v
    elif _text(v):
        values = str(v).split(",")
    else:
        return []
    return [text for x in values if (text := _text(x))]


def _instrument_list(v) -> list:
    """구체적인 악기만 검색 근거로 쓴다.

    `unknown`·`nan`·`null` 같은 누락 표기는 `_tag_list` 가 이미 떨군다(`_MISSING_TEXT`).
    여기서 더 거르는 것은 **뜻이 없는 악기 이름** 하나뿐이다.
    """
    return list(dict.fromkeys(
        x for x in _tag_list(v)
        if x.casefold() not in _NONSPECIFIC_INSTRUMENTS))


def short_genre(g: str) -> str:
    """'Rock---Punk' -> 'Punk'."""
    g = _text(g)
    return g.split("---")[-1].strip() if g else ""


def _catalog_tags(m: dict) -> dict:
    """카탈로그 메타를 서비스의 보컬·악기 자료형으로 정규화한다."""
    voice = _text(m.get("voice_label")).lower()
    raw_vocal = _text(m.get("vocal_type")).lower()
    raw_has_vocal = m.get("has_vocal")
    if isinstance(raw_has_vocal, (bool, np.bool_)):
        has_vocal = bool(raw_has_vocal)
    elif str(raw_has_vocal).strip().lower() in ("1", "true"):
        has_vocal = True
    elif str(raw_has_vocal).strip().lower() in ("0", "false"):
        has_vocal = False
    elif voice == "instrumental" or raw_vocal == "instrumental":
        has_vocal = False
    elif voice == "voice" or raw_vocal in ("voice", "male", "female", "mixed", "other"):
        has_vocal = True
    else:
        has_vocal = None

    raw_gender = next((_text(v).lower() for v in
                       (m.get("vocal_gender"), m.get("gender_label"), raw_vocal)
                       if _text(v)), "")
    vocal_gender = None if has_vocal is not True else _VOCAL_GENDER.get(raw_gender)
    return {
        "genre": short_genre(m.get("genre_top1")),
        "genre_full": _text(m.get("genre_top1")),
        "moods": _tag_list(m.get("mood_tags")),
        "instruments": _instrument_list(m.get("instruments", m.get("instrument_tags"))),
        "has_vocal": has_vocal,
        "vocal_gender": vocal_gender,
    }


def _vocal_values(has_vocal, vocal_gender) -> list[str]:
    """실제 두 컬럼을 자연어 검색 어휘로 투영한다."""
    if has_vocal is None:
        return []
    if not has_vocal:
        return ["instrumental"]
    gender = {"M": "male", "F": "female", "MIX": "mixed"}.get(vocal_gender)
    return ["voice"] + ([gender] if gender else [])


def parse_style(cat: str, vocal_values: list[str], lang: str) -> str:
    """category 문자열에서 보컬·언어 접두어를 떼어 스타일만 남긴다."""
    parts = [x for x in str(cat).split("_") if x]
    # 예전에는 혼성이 `other` 로 투영돼서 `mixed_korean_…` 의 접두어를 떼지 못했고,
    # 그래서 `other` 일 때만 `mixed` 를 덧붙였다. 이제 `_vocal_values` 가 `mixed` 를
    # 그대로 내므로 category 의 접두어와 어휘가 같다 — 보정이 필요 없다.
    prefixes = set(vocal_values)
    if parts and parts[0] in prefixes:
        parts = parts[1:]
    if parts and parts[0] == str(lang):
        parts = parts[1:]
    return "_".join(parts)


def catalog_facets() -> dict:
    """조사 선택지를 **카탈로그에서 직접** 만든다 (하드코딩하지 않는다).

    카탈로그가 바뀌지 않는 한 결과가 같으므로 한 번만 만든다. 캐시를 **카탈로그
    객체에 붙여** 두어, `CAT` 을 새로 만들면 캐시도 함께 사라진다(`_pool_mean` 과 같은
    방식). 전역 dict 에 두면 산출물을 교체했을 때 옛 어휘가 남는다.
    """
    c = CAT
    hit = getattr(c, "_facets_cache", None)
    if hit is not None:
        return hit
    moment_instruments = {}
    if "instruments" in c.mom.columns:
        for track_id, group in c.mom.groupby("track_id", sort=False):
            moment_instruments[track_id] = list(dict.fromkeys(
                instrument
                for value in group.instruments
                for instrument in _instrument_list(value)))
    rows = []
    for t in sorted(c.by_track):
        ti = c.track_info(t)
        lo, hi = (ti.get("bpm_requested") or [None, None])[:2]
        # 측정 BPM(자기상관)은 옥타브 오류가 잦다. 생성 시 요청한 범위가 있으면 그것을 쓴다.
        if lo is not None and hi is not None and float(lo) > 0:
            bpm_use, bpm_src = (float(lo) + float(hi)) / 2, "requested"
        else:
            bpm_use, bpm_src = float(ti["bpm"] or 0.0), "measured"
        rows.append({
            "track_id": t, "has_vocal": ti["has_vocal"],
            "vocal_gender": ti["vocal_gender"],
            "language": ti["language"],
            "style": parse_style(ti["category"],
                                 _vocal_values(ti["has_vocal"], ti["vocal_gender"]),
                                 ti["language"]),
            "genre": ti.get("genre") or "",
            "moods": ti.get("moods") or [],
            "instruments": moment_instruments.get(t) or ti.get("instruments") or [],
            "bpm": bpm_use, "bpm_source": bpm_src, "bpm_measured": float(ti["bpm"] or 0.0),
        })
    df = pd.DataFrame(rows)

    def cnt(col):
        return [{"value": k, "label": k, "tracks": int(v)}
                for k, v in df[col].value_counts().items() if str(k).strip()]

    def cnt_multi(col, minimum: int = 2):
        """리스트 컬럼(무드·악기)의 태그별 곡 수. 너무 드문 태그는 선택지에서 뺀다."""
        acc: dict = {}
        for xs in df[col]:
            for x in xs or []:
                acc[x] = acc.get(x, 0) + 1
        return [{"value": k, "label": k, "tracks": v}
                for k, v in sorted(acc.items(), key=lambda kv: -kv[1]) if v >= minimum]

    tempo = [{"value": k, "label": lb,
              "tracks": int(((df.bpm >= lo) & (df.bpm < hi)).sum())}
             for k, lb, lo, hi in TEMPO_BUCKETS]
    secs = [{"value": k, "label": k, "moments": int(v)}
            for k, v in c.pool.section_label.value_counts().items()]
    # 에너지(rms 백분위)는 선택지에서 뺐다 — 체감과 어긋나 부정확했다.
    # 대신 모델이 뽑은 장르·무드·악기를 선택지로 쓴다.
    vocal_counts: dict[str, int] = {}
    for row in df.itertuples(index=False):
        for value in _vocal_values(row.has_vocal, row.vocal_gender):
            vocal_counts[value] = vocal_counts.get(value, 0) + 1
    vocal = [{"value": k, "label": k, "tracks": v}
             for k, v in sorted(vocal_counts.items(), key=lambda kv: -kv[1])]
    out = {"language": cnt("language"), "vocal": vocal,
           "style": cnt("style"), "genre": cnt("genre"),
           "mood": cnt_multi("moods"), "instrument": cnt_multi("instruments"),
           "tempo": tempo, "section_label": secs, "_df": df}
    # 인덱스를 건 사본도 함께 들고 있는다. `set_index` 도 호출마다 복사가 일어난다.
    c._facets_cache = out
    c._facets_by_track = df.set_index("track_id").to_dict("index")
    return out


def catalog_facet_rows() -> dict:
    """track_id -> 메타 dict. `fac.loc[t]` 를 대신한다.

    `DataFrame.loc` 은 한 번에 Series 를 새로 만든다. 후보마다 부르면 그 비용이
    행 수만큼 쌓인다 — 게이트 다섯 개면 곡 수의 다섯 배다. 값은 같고 만드는 방식만
    다르다.
    """
    c = CAT
    if getattr(c, "_facets_by_track", None) is None:
        catalog_facets()
    return c._facets_by_track


ONB_LIST_FIELDS = ("language", "vocal_type", "style", "genre", "mood", "instrument",
                   "tempo", "energy", "section_label", "exclude_style")


def _onb_list(v) -> list:
    """조사 답변을 항상 list 로 정규화한다.

    필드가 늘어난 뒤 저장된 옛 행은 concat 으로 NaN 이 된다. NaN 은 truthy 라
    `v or []` 를 통과해 `x not in NaN` 에서 TypeError 가 났다(2026-09-07).
    """
    if isinstance(v, str):
        t = v.strip()
        if t.startswith("["):
            try:
                return [x for x in json.loads(t) if isinstance(x, str)]
            except Exception:
                return []
        return [t] if t else []
    if isinstance(v, (list, tuple, set, np.ndarray)):
        return [x for x in v if isinstance(x, str)]
    return []


def onboarding_of(user: str, injected: dict | None = None) -> dict | None:
    """온보딩 답변. `injected` 를 주면 그것을 쓴다(요청에 실려 온 경우).

    주지 않으면 종전대로 `serve/onboarding.parquet` 를 읽는다 — 배치와 오프라인
    테스트가 그 경로로 돈다.
    """
    if injected is not None:
        return injected
    if not paths.onboarding_path().exists():
        return None
    d = pd.read_parquet(paths.onboarding_path())
    m = d[d.user_ref == user]
    if m.empty:
        return None
    r = m.iloc[-1].to_dict()
    return {k: (_onb_list(v) if k in ONB_LIST_FIELDS else v) for k, v in r.items()}


def onboarding_seeds(user: str,
                     injected: dict | None = None) -> tuple[np.ndarray, np.ndarray, list[dict]]:
    """조사 답변으로 씨앗 질의 벡터를 만든다. 라벨이 없어도 개인화가 된다."""
    c = CAT
    ans = onboarding_of(user, injected)
    if not ans:
        return np.zeros((0, c.Z.shape[1]), np.float32), np.zeros(0), []
    fac = catalog_facets()
    df = fac["_df"].set_index("track_id")

    def track_ok(tid: str, relax: int) -> bool:
        if tid not in df.index:
            return False
        r = df.loc[tid]
        want_lang = ans.get("language") or []
        want_voc = ans.get("vocal_type") or []
        want_sty = ans.get("style") or []
        want_tempo = ans.get("tempo") or []
        want_genre = ans.get("genre") or []
        want_mood = ans.get("mood") or []
        want_inst = ans.get("instrument") or []
        if relax < 3 and want_lang and r.language not in want_lang:
            return False
        if relax < 3 and want_voc and not (
                set(_vocal_values(r.has_vocal, r.vocal_gender)) & set(want_voc)):
            return False
        if relax < 2 and want_sty and r.style not in want_sty:
            return False
        if relax < 2 and want_genre and r.genre not in want_genre:
            return False
        if relax < 1 and want_tempo:
            ok = any(lo <= r.bpm < hi for k, _l, lo, hi in TEMPO_BUCKETS if k in want_tempo)
            if not ok:
                return False
        if relax < 1 and want_mood and not (set(r.moods or []) & set(want_mood)):
            return False
        if relax < 1 and want_inst and not (set(r.instruments or []) & set(want_inst)):
            return False
        return True

    want_secs = ans.get("section_label") or []
    for relax in range(4):
        pool = c.pool[c.pool.track_id.map(lambda t: track_ok(t, relax))]
        if want_secs:
            pool = pool[pool.section_label.isin(want_secs)]
        pool = pool[pool.dur_sec >= MIN_MOMENT_SEC]
        if len(pool) >= ONBOARD_MIN_MATCH:
            break
    if pool.empty:
        return np.zeros((0, c.Z.shape[1]), np.float32), np.zeros(0), []

    V = center(np.asarray(c.Z[pool.emb_row.to_numpy()], dtype=np.float32))
    # 구간 라벨별로 씨앗을 나눈다 (하나로 평균 내지 않는다)
    seeds, W, meta = [], [], []
    groups = list(pool.groupby("section_label", sort=False))
    groups.sort(key=lambda kv: -len(kv[1]))
    for lab, g in groups[:ONBOARD_SEEDS]:
        idx = [pool.index.get_loc(i) for i in g.index]
        v = V[idx].mean(0)
        v /= max(np.linalg.norm(v), 1e-9)
        seeds.append(v)
        W.append(float(len(g)) ** 0.5)
        meta.append({"dna_id": f"{user}#seed_{lab}", "n_members": int(len(g)),
                     "cohesion": float((V[idx] @ v).mean()),
                     "rep_moment_id": str(g.iloc[0].moment_id),
                     "source": "onboarding", "relax_level": relax})
    W = np.asarray(W, dtype=np.float32)
    W /= max(W.sum(), 1e-9)
    return np.asarray(seeds, dtype=np.float32), W, meta


# --------------------------------------------------------------- 이벤트/라벨
def append_event(ev: dict) -> None:
    paths.events_path().parent.mkdir(parents=True, exist_ok=True)
    ev.setdefault("event_id", f"evt_{uuid.uuid4().hex[:22]}")
    ev.setdefault("occurred_at", now_iso())
    with paths.events_path().open("a", encoding="utf-8") as f:
        f.write(json.dumps(ev, ensure_ascii=False) + "\n")


def normalized_events() -> Path:
    """라벨러에 넘길 정규화 사본을 만든다. 원본 로그는 그대로 둔다.

    LIKE/DISLIKE 는 토글이라 **최종 상태가 활성인 것만** TRACK_LIKE/DISLIKE 로 넘긴다.
    (UNLIKE/UNDISLIKE 로 취소된 것은 라벨에 남지 않아야 한다.)
    """
    out = paths.events_path().parent / "events_service_normalized.jsonl"
    if not paths.events_path().exists():
        return paths.events_path()
    raw = [ln for ln in paths.events_path().read_text(encoding="utf-8").splitlines() if ln.strip()]
    state: dict = {}                       # (user, track) -> {"like": bool, "dislike": bool}
    for ln in raw:
        try:
            e = json.loads(ln)
        except Exception:
            continue
        et = str(e.get("event_type"))
        if et in ("PLAYLIST_ADD", "PLAYLIST_REMOVE"):
            # 플레이리스트별로 센다. 다른 플레이리스트에 남아 있으면 긍정 상태를 유지한다.
            k = (e.get("user_ref"), str(e.get("track_id")))
            pl = str(e.get("playlist_id") or "default")
            d = state.setdefault(k, {"like": False, "dislike": False, "pl": set()})
            d.setdefault("pl", set())
            if et == "PLAYLIST_ADD":
                d["pl"].add(pl)
            else:
                d["pl"].discard(pl)         # 삭제는 가점 회수일 뿐 부정 신호가 아니다
            continue
        if et in ("LIKE", "UNLIKE", "DISLIKE", "UNDISLIKE"):
            k = (e.get("user_ref"), str(e.get("track_id")))
            d = state.setdefault(k, {"like": False, "dislike": False, "pl": set()})
            d["like"] = True if et == "LIKE" else (False if et == "UNLIKE" else d["like"])
            d["dislike"] = (True if et == "DISLIKE"
                            else (False if et == "UNDISLIKE" else d["dislike"]))
    lines = []
    for ln in raw:
        try:
            e = json.loads(ln)
        except Exception:
            continue
        et = str(e.get("event_type"))
        m = UI_TO_LABELER.get(et, "keep")
        if m == "keep":
            lines.append(ln)
            continue
        if m is None:
            continue
        if et in ("LIKE", "DISLIKE"):
            k = (e.get("user_ref"), str(e.get("track_id")))
            if not state.get(k, {}).get("like" if et == "LIKE" else "dislike"):
                continue
        if et == "PLAYLIST_ADD":
            k = (e.get("user_ref"), str(e.get("track_id")))
            if not state.get(k, {}).get("pl"):     # 전부 빠졌으면 가점을 넘기지 않는다
                continue
        if m[0] == "SEEK" and ("from_sec" not in e or "to_sec" not in e):
            # 라벨러의 SEEK 는 `from_sec`·`to_sec` 를 읽어 방향을 가린다. ClickHouse 에서
            # 온 신호에는 그 둘이 없다 — `user_section_signals` 는 **닿은 구간**만 알고
            # 어디서 왔는지는 들고 있지 않다. 없는 채로 넘기면 `float(r.from_sec)` 에서
            # AttributeError 가 나고 **라벨 배치가 통째로 죽는다.**
            #
            # 구간 경계로 같은 판정을 만든다. 되감기는 이 구간으로 들어온 것이므로
            # 도착을 구간 시작에 두어 to < from 이 되게 하고, 건너뛰기는 이 구간에서
            # 빠져나간 것이므로 출발을 구간 시작에 둔다. 라벨러는 되감기에서 to 가,
            # 건너뛰기에서 from 이 든 구간을 집으므로 어느 쪽이든 이 구간이 잡힌다.
            start, end = float(e.get("start_sec", 0.0)), float(e.get("end_sec", 0.0))
            if end <= start:
                end = start + 1.0
            if et == "SEEK_BACK":
                e["from_sec"], e["to_sec"] = end, start
            else:
                e["from_sec"], e["to_sec"] = start, end
        e["event_type"] = m[0]
        e.update(m[1])
        lines.append(json.dumps(e, ensure_ascii=False))
    # 빈 줄을 남기지 않는다. 라벨러가 줄마다 json.loads 하므로 빈 줄이면 깨진다.
    out.write_text("".join(x + "\n" for x in lines), encoding="utf-8")
    return out


def run_labeler() -> dict:
    if not paths.events_path().exists():
        return {"ok": False, "reason": "이벤트 로그가 없다"}
    src_events = normalized_events()
    # 출력 인코딩을 못 박는다. 지정하지 않으면 파이썬이 **콘솔 기본 인코딩**으로
    # 디코드하는데, Windows 는 cp949 라서 자식이 찍은 한국어 한 줄에 UnicodeDecodeError
    # 로 죽는다. 라벨은 제대로 만들어진 뒤에 출력을 읽다가 터지는 것이라, 증상이
    # "라벨 생성 실패" 로만 보여 원인이 인코딩이라는 것이 드러나지 않는다.
    r = subprocess.run(
        [sys.executable, "-m", "ai_server.app.labeler", "--events", str(src_events),
         "--out", str(paths.labels_path()), "--pool", "service"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        cwd=paths.PKG_ROOT.parent)
    out = {"ok": r.returncode == 0 and paths.labels_path().exists(),
           "stdout": r.stdout[-1500:], "stderr": r.stderr[-800:]}
    if out["ok"] and labels_store.configured():
        # 라벨러는 parquet 으로 내놓는다(중간 산출물). 서빙이 읽는 정본은
        # ClickHouse 이므로 여기서 한 판을 통째로 올린다.
        #
        # **적재에 실패하면 실패로 돌려준다.** 성공으로 처리하면 배치는 초록인데
        # 서빙은 어제 라벨을 계속 보게 되고, 그 차이는 아무 데도 드러나지 않는다.
        run_id = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S")
        try:
            out["loaded"] = labels_store.write(pd.read_parquet(paths.labels_path()), run_id)
            out["run_id"] = run_id
        except Exception as e:
            out["ok"] = False
            out["reason"] = f"라벨 적재 실패: {type(e).__name__}: {e}"
            log(out["reason"])
    return out


# ------------------------- 라벨 읽기 (ClickHouse / 파일) -------------------------
# 다섯 곳이 각자 `pd.read_parquet(paths.labels_path())` 를 부르고 있었다. 저장소가 둘이 되면
# 그 다섯 곳이 따로 갈라지므로 여기 하나로 모은다. **판단 기준은 하나다** —
# `labels_store.configured()`. 읽기와 쓰기가 서로 다른 곳을 보는 상태를 만들지
# 않기 위해서다.
def labels_available() -> bool:
    if labels_store.configured():
        try:
            return labels_store.has_any()
        except Exception as e:
            # 추천을 죽이지 않는다. 개인화만 빠지고 그 사실이 로그에 남는다.
            log(f"라벨 저장소 조회 실패 — {type(e).__name__}: {e}")
            return False
    return paths.labels_path().exists()


def labels_for(user: str) -> pd.DataFrame:
    """사용자 한 명의 라벨. 없으면 빈 표(호출부가 `.empty` 로 본다)."""
    if labels_store.configured():
        try:
            return labels_store.user_labels(user)
        except Exception as e:
            log(f"라벨 조회 실패 ({user}) — {type(e).__name__}: {e}")
            return pd.DataFrame()
    if not paths.labels_path().exists():
        return pd.DataFrame()
    lab = pd.read_parquet(paths.labels_path())
    return lab[lab.user_ref == user]


def labels_all() -> pd.DataFrame:
    """전체. 배치(`learn`)만 쓴다 — 요청 경로에서 부르지 않는다."""
    if labels_store.configured():
        return labels_store.all_labels()
    return pd.read_parquet(paths.labels_path()) if paths.labels_path().exists() else pd.DataFrame()


def other_positives(user: str) -> pd.DataFrame:
    """남들의 L1 라벨. 질의 측 개인화 보정(`build_dna`)이 쓴다."""
    if labels_store.configured():
        try:
            return labels_store.other_user_positives(user)
        except Exception as e:
            log(f"타 사용자 라벨 조회 실패 — {type(e).__name__}: {e}")
            return pd.DataFrame()
    if not paths.labels_path().exists():
        return pd.DataFrame()
    lab = pd.read_parquet(paths.labels_path())
    return lab[(lab.user_ref != user) & (lab.grade == "L1")]


def decay_weights(df: pd.DataFrame) -> np.ndarray:
    """occurred_at 기준 반감기 감쇠. 최신 라벨 시각을 기준점으로 삼는다."""
    n = len(df)
    if HALF_LIFE_DAYS <= 0 or "occurred_at" not in df.columns or n == 0:
        return np.ones(n, dtype=np.float64)
    ts = pd.to_datetime(df.occurred_at, errors="coerce", utc=True)
    if ts.isna().all():
        return np.ones(n, dtype=np.float64)
    age = (ts.max() - ts).dt.total_seconds().to_numpy() / 86400.0
    age = np.nan_to_num(age, nan=0.0)
    return np.maximum(np.power(0.5, np.clip(age, 0, None) / HALF_LIFE_DAYS), 1e-3)


# --------------------------- §16 선호 구간 (조회/수정/삭제) ---------------------------
# 사용자 수정·삭제는 원본 라벨을 덮어쓰지 않고 override 로그에만 쌓는다.
# override 로그·체크포인트·Jamendo 카탈로그의 자리도 `paths` 가 정한다.
JAMENDO_PREFIX = "JAMENDO_"
_JAM_CACHE: dict = {"mtime": None, "rows": {}}


def jamendo_row(track_id: str) -> dict:
    """Jamendo 카탈로그 1행. 파일이 없으면 항상 빈 dict (기존 동작과 동일)."""
    if not str(track_id).startswith(JAMENDO_PREFIX) or not paths.jamendo_catalog_path().exists():
        return {}
    mt = paths.jamendo_catalog_path().stat().st_mtime
    if _JAM_CACHE["mtime"] != mt:
        try:
            df = pd.read_parquet(paths.jamendo_catalog_path())
            _JAM_CACHE["rows"] = {str(r.track_id): {k: ("" if pd.isna(v) else v)
                                                    for k, v in r._asdict().items()
                                                    if k != "Index"}
                                  for r in df.itertuples()}
            _JAM_CACHE["mtime"] = mt
        except Exception:
            _JAM_CACHE["rows"] = {}
    return _JAM_CACHE["rows"].get(str(track_id), {})
EVIDENCE_KO = {"moment_like": "구간 직접 지정", "replay": "반복 재생", "seek_back": "되감기",
               "seek_back_wf": "되감기(파형)", "seek_forward": "건너뛰기",
               "fast_skip": "빠른 스킵", "track_like": "곡 좋아요",
               "playlist_add": "플레이리스트 추가", "dislike": "관심 없음"}
REACTION_EVENTS = {"LIKE": "LIKED", "UNLIKE": "NEUTRAL",
                   "DISLIKE": "DISLIKED", "UNDISLIKE": "NEUTRAL"}


def load_checkpoint() -> dict:
    if paths.checkpoint_path().exists():
        try:
            return json.loads(paths.checkpoint_path().read_text(encoding="utf-8"))
        except Exception:
            return {}
    return {}


def save_checkpoint(ck: dict) -> None:
    """원자적으로 쓴다. 중간에 죽어도 checkpoint 가 깨지지 않는다."""
    paths.test_pipeline_dir().mkdir(parents=True, exist_ok=True)
    tmp = paths.checkpoint_path().with_suffix(".json.tmp")
    tmp.write_text(json.dumps(ck, ensure_ascii=False, indent=1), encoding="utf-8")
    tmp.replace(paths.checkpoint_path())


def user_event_state(user: str) -> dict:
    """사용자 로그를 한 번 훑어 현재 상태를 만든다 (건수 + 마지막 event_id/시각)."""
    n, last_id, last_at = 0, "", ""
    if paths.events_path().exists():
        for ln in paths.events_path().open(encoding="utf-8"):
            try:
                e = json.loads(ln)
            except Exception:
                continue
            if e.get("user_ref") != user:
                continue
            n += 1
            last_id = str(e.get("event_id") or last_id)
            last_at = str(e.get("occurred_at") or last_at)
    return {"n_events": n, "last_event_id": last_id, "last_occurred_at": last_at}


def learn(req: "LearnReq") -> dict:
    """라벨을 다시 만들고 사용자별 선호 구간을 `prefs.parquet` 으로 굳힌다.

    데모의 `POST /learn` 본문을 그대로 옮긴 것이다. 이식 때 라우트만 걷어 내면서 함수까지
    빠져 `learn_incremental()` 이 NameError 로 죽어 있었다. 이 저장소에는 라우트가 없으므로
    HTTP 예외 대신 평범한 예외를 던진다 — 배치에서 불릴 수도 있다.
    """
    r = run_labeler()
    if not r["ok"]:
        raise RuntimeError(f"라벨 생성 실패: {r.get('reason') or r.get('stderr', '')[-300:]}")
    lab = labels_all()
    c = CAT
    minfo = c.mom.set_index("moment_id")
    rows = []
    users = [req.user_ref] if req.user_ref else sorted(lab.user_ref.unique())
    for u in users:
        C, W, meta = build_dna(u)
        p = lab[(lab.user_ref == u) & (lab.grade == "L1")]
        for r_ in p.itertuples():
            if r_.section_key not in minfo.index:
                continue
            mi = minfo.loc[r_.section_key]
            rows.append({
                "user_ref": u, "moment_id": r_.section_key, "track_id": r_.track_id,
                "title": c.track_info(r_.track_id)["title"],
                "section_label": mi.section_label,
                "start_sec": round(float(mi.start_sec), 2),
                "end_sec": round(float(mi.end_sec), 2),
                "grade": r_.grade, "evidence": r_.evidence,
                "is_merged": bool(r_.is_merged),
                "dna_clusters": len(C), "created_at": now_iso(),
                "source": "auto",
            })
    paths.serve_dir().mkdir(parents=True, exist_ok=True)
    df = pd.DataFrame(rows)
    if paths.prefs_path().exists() and len(df):
        old = pd.read_parquet(paths.prefs_path())
        old = old[~old.user_ref.isin(df.user_ref.unique())]
        df = pd.concat([old, df], ignore_index=True)
    if len(df):
        df.to_parquet(paths.prefs_path(), index=False)
    return {"ok": True, "users": users, "labels": int(len(lab)),
            "preferences_saved": int(len(rows)), "path": str(paths.prefs_path())}


def learn_incremental(user: str, force: bool = False) -> dict:
    """새 이벤트가 있을 때만 학습한다. 실패하면 checkpoint 를 전진시키지 않는다."""
    t0 = time.time()
    ck = load_checkpoint()
    cur = user_event_state(user)
    prev = ck.get(user, {})
    new_events = cur["n_events"] - int(prev.get("n_events", 0))
    if not force and prev.get("last_event_id") == cur["last_event_id"] \
            and new_events <= 0:
        return {"ok": True, "processed": 0, "skipped": True, "reason": "새 이벤트 없음",
                "elapsed_sec": round(time.time() - t0, 3)}
    r = learn(LearnReq(user_ref=user))            # 기존 학습 로직 그대로
    ck[user] = {**cur, "updated_at": now_iso(), "labels": int(r.get("labels", 0)),
                "preferences_saved": int(r.get("preferences_saved", 0))}
    save_checkpoint(ck)                            # 성공했을 때만 전진
    return {"ok": True, "processed": max(new_events, 0), "skipped": False,
            "labels": r.get("labels"), "preferences_saved": r.get("preferences_saved"),
            "elapsed_sec": round(time.time() - t0, 3)}


def pref_id(user: str, section_key: str) -> str:
    import hashlib

    return "PREF_" + hashlib.md5(f"{user}|{section_key}".encode()).hexdigest()[:10]


def load_overrides() -> dict:
    """preference_overrides.jsonl 을 순서대로 접어 현재 상태를 만든다 (마지막이 이긴다)."""
    out: dict = {}
    if not paths.overrides_path().exists():
        return out
    for ln in paths.overrides_path().open(encoding="utf-8"):
        ln = ln.strip()
        if not ln:
            continue
        try:
            e = json.loads(ln)
        except Exception:
            continue
        pid = e.get("preference_id")
        if not pid:
            continue
        cur = out.setdefault(pid, {})
        if e.get("action") == "DISMISS":
            cur["status"] = "DISMISSED"
            cur["dismissed_at"] = e.get("occurred_at")
        elif e.get("action") == "UPDATE":
            cur["status"] = "ACTIVE"
            cur["final_start_ms"] = int(e["final_start_ms"])
            cur["final_end_ms"] = int(e["final_end_ms"])
            cur["updated_at"] = e.get("occurred_at")
        elif e.get("action") in ("RESET", "RESTORE"):
            out[pid] = {"status": "ACTIVE"}      # 수정·삭제를 모두 되돌린다
    return out


def append_override(rec: dict) -> None:
    paths.overrides_path().parent.mkdir(parents=True, exist_ok=True)
    rec.setdefault("occurred_at", now_iso())
    with paths.overrides_path().open("a", encoding="utf-8") as f:
        f.write(json.dumps(rec, ensure_ascii=False) + "\n")


def track_reactions(user: str) -> dict:
    """LIKE/UNLIKE/DISLIKE/UNDISLIKE 이벤트를 접어 곡별 현재 상태를 만든다."""
    out: dict = {}
    if not paths.events_path().exists():
        return out
    for ln in paths.events_path().open(encoding="utf-8"):
        try:
            e = json.loads(ln)
        except Exception:
            continue
        if e.get("user_ref") != user:
            continue
        st = REACTION_EVENTS.get(str(e.get("event_type")))
        if st:
            out[str(e.get("track_id"))] = st
    return {k: v for k, v in out.items() if v != "NEUTRAL"}


def _seed_rows(user: str) -> list[dict]:
    """조사 답변으로 만들어진 씨앗을 화면용으로 정리한다.

    씨앗은 저장된 레코드가 아니라 답변에서 매번 계산되는 질의 벡터다.
    그래서 경계 편집 개념이 없고, 대표 구간 재생과 '조사에서 제외'만 가능하다.
    """
    try:
        C, W, meta = onboarding_seeds(user)
    except Exception:
        return []
    if not len(C):
        return []
    c = CAT
    minfo = c.mom.set_index("moment_id")
    out = []
    for m, w in zip(meta, W):
        rep_id = str(m.get("rep_moment_id") or "")
        row = {"seed_id": m.get("dna_id"), "section_label": str(m.get("dna_id", "")).split("_")[-1],
               "n_members": int(m.get("n_members", 0)),
               "cohesion": round(float(m.get("cohesion", 0.0)), 3),
               "weight": round(float(w), 4), "relax_level": m.get("relax_level"),
               "rep_moment_id": rep_id, "source": "SEED"}
        if rep_id in minfo.index:
            mi = minfo.loc[rep_id]
            row.update(track_id=str(mi.track_id),
                       title=c.track_info(str(mi.track_id))["title"],
                       start_ms=int(round(float(mi.start_sec) * 1000)),
                       end_ms=int(round(float(mi.end_sec) * 1000)))
        out.append(row)
    return out


def _pref_sections(user: str, include_dismissed: bool = False) -> list[dict]:
    """라벨(L1/L2) + override 를 합쳐 화면용 선호 구간 목록을 만든다."""
    lab = labels_for(user)
    if lab.empty:
        return []
    mine = lab[lab.grade.isin(["L1", "L2"])]
    if mine.empty:
        return []
    c = CAT
    minfo = c.mom.set_index("moment_id")
    ov = load_overrides()
    rows = []
    for r in mine.itertuples():
        if r.section_key not in minfo.index:
            continue
        mi = minfo.loc[r.section_key]
        try:
            ev = json.loads(r.evidence) if isinstance(r.evidence, str) else dict(r.evidence or {})
        except Exception:
            ev = {}
        codes = [k for k, v in ev.items() if v]
        manual = bool(ev.get("moment_like", 0))
        pid = pref_id(user, r.section_key)
        o = ov.get(pid, {})
        status = o.get("status", "ACTIVE")
        if status == "DISMISSED" and not include_dismissed:
            continue
        os_ms = int(round(float(mi.start_sec) * 1000))
        oe_ms = int(round(float(mi.end_sec) * 1000))
        edited = "final_start_ms" in o
        rows.append({
            "preference_id": pid, "track_id": r.track_id,
            "title": c.track_info(r.track_id)["title"],
            "moment_id": r.section_key,
            "source": ("USER_EDITED_AUTO" if edited else ("MANUAL" if manual else "AUTO")),
            "origin_source": "MANUAL" if manual else "AUTO",
            "section_label": str(mi.section_label),
            "original_start_ms": os_ms, "original_end_ms": oe_ms,
            "final_start_ms": o.get("final_start_ms", os_ms),
            "final_end_ms": o.get("final_end_ms", oe_ms),
            "start_ms": o.get("final_start_ms", os_ms),
            "end_ms": o.get("final_end_ms", oe_ms),
            "track_duration_ms": int(round(float(mi.track_dur_sec) * 1000)),
            "grade": str(r.grade),
            "evidence": codes,
            "evidence_ko": [EVIDENCE_KO.get(k, k) for k in codes],
            "status": status,
            # 경계를 고치면 이 구간의 임베딩은 낡는다. 서비스는 MERT 를 돌리지 않으므로
            # 재추출이 필요하다는 사실만 표시한다(t01 파이프라인이 실제 재추출을 한다).
            "needs_reextract": bool(edited),
            "updated_at": o.get("updated_at") or o.get("dismissed_at"),
        })
    rows.sort(key=lambda x: (x["track_id"], x["start_ms"]))
    return rows


def dismissed_moments(user: str) -> set:
    ov = load_overrides()
    if not ov:
        return set()
    out = set()
    lab = labels_for(user)
    if lab.empty:
        return out
    for k in lab["section_key"].unique():
        if ov.get(pref_id(user, str(k)), {}).get("status") == "DISMISSED":
            out.add(str(k))
    return out


def _file_events(user: str):
    """파일 로그 → 정규화된 이벤트. ClickHouse 판(`app/clickhouse.py`)과 같은 키를 쓴다.

    두 출처가 같은 모양으로 들어와야 집계를 한 벌로 둘 수 있다. 두 벌이 되면 한쪽만
    고쳐져 같은 사용자에게 다른 점수가 나간다.
    """
    if not paths.events_path().exists():
        return
    for ln in paths.events_path().open(encoding="utf-8"):
        try:
            e = json.loads(ln)
        except Exception:
            continue
        if e.get("user_ref") != user:
            continue
        try:
            ts = datetime.fromisoformat(
                str(e["occurred_at"]).replace("Z", "+00:00")).timestamp()
        except Exception:
            continue
        yield {
            "track_id": str(e.get("track_id")),
            "event_type": str(e.get("event_type")),
            "ts": ts,
            "reason": str(e.get("reason") or ""),
            # 값이 없으면 Early Skip 으로 보지 않는다. ClickHouse 쪽은 position_ms 가
            # 항상 있어 0 이 "바로 넘겼다" 라는 뜻이지만, 파일 로그의 누락은 모름이다.
            "position_sec": float(e.get("position_sec") or 1e9),
            "surface": str((e.get("context") or {}).get("surface") or ""),
            "play_id": str(e.get("play_id") or e.get("session_id") or ""),
        }


def _aggregate_behavior(events, now: float) -> dict:
    """정규화된 이벤트를 **한 번만** 훑어 하드 필터와 점수 보정을 함께 만든다.

    출처가 파일이든 ClickHouse 든 여기 하나로 모인다.
    """
    try:
        from .labeler import FAST_SKIP_SEC
    except Exception:
        FAST_SKIP_SEC = 5.0

    out = {"recent_play": set(), "dislike": set(), "like": set(),
           "complete": {}, "skip_pen": {}, "delta": {}, "recent_pen": {}, "events": 0}
    cut = now - RECENT_EXCLUDE_SEC
    recent_ts: dict[str, float] = {}    # track -> 최근 PLAY_START 시각
    toggles: dict[str, list] = {}       # track -> [(ts, event_type)]
    skips: dict[str, dict] = {}         # track -> {play_key: ts}  (중복 집계 방지)

    for e in events:
        out["events"] += 1
        tid = e["track_id"]
        et = e["event_type"]
        ts = e["ts"]
        if et in ("LIKE", "UNLIKE", "DISLIKE", "UNDISLIKE"):
            toggles.setdefault(tid, []).append((ts, et))
            continue
        if et == "PLAY_START":
            if ts >= cut:
                out["recent_play"].add(tid)
                # 가장 최근 재생 시각만 남긴다. 여러 번 들었다고 더 깎지는 않는다.
                recent_ts[tid] = max(recent_ts.get(tid, 0.0), ts)
            continue
        if et != "PLAY_END":
            continue
        reason = e["reason"]
        if reason == "skip_next":
            if e["position_sec"] > FAST_SKIP_SEC:
                continue
            if e["surface"] not in BEH_REC_SURFACES:  # 추천 경로에서 난 것만 부정 신호
                continue
            # 같은 재생(play)에서 나온 같은 부정 신호는 한 번만 센다
            skips.setdefault(tid, {})[e["play_id"] or f"{int(ts)}"] = ts
            continue
        if reason in BEH_PLAYED_REASONS:
            if ts >= cut:
                out["recent_play"].add(tid)          # 4시간 즉시 재추천 방지
            if reason == "track_done":
                out["complete"][tid] = out["complete"].get(tid, 0) + 1

    # LIKE/DISLIKE 는 occurred_at 순서로 접어 마지막 상태만 남긴다
    for tid, evs in toggles.items():
        like = dis = False
        for _, et in sorted(evs, key=lambda x: x[0]):
            if et == "LIKE":
                like = True
            elif et == "UNLIKE":
                like = False
            elif et == "DISLIKE":
                dis = True
            elif et == "UNDISLIKE":
                dis = False
        if like:
            out["like"].add(tid)
        if dis:
            out["dislike"].add(tid)

    # 최근 재생 감점: 방금 들었으면 BEH_W_RECENT, 4시간이 지나면 0.
    for tid, ts in recent_ts.items():
        frac = 1.0 - min(max(now - ts, 0.0) / RECENT_EXCLUDE_SEC, 1.0)
        if frac > 0:
            out["recent_pen"][tid] = BEH_W_RECENT * frac

    for tid, plays in skips.items():
        pen = sum(BEH_W_SKIP * (0.5 ** (max(now - ts, 0) / 86400.0 / BEH_SKIP_HALF_LIFE_DAYS))
                  for ts in plays.values())
        out["skip_pen"][tid] = min(pen, BEH_SKIP_FLOOR)

    for tid in set(out["complete"]) | set(out["skip_pen"]) | out["like"]:
        if tid in out["dislike"]:
            continue                                  # 제외되므로 감점을 겹쳐 매기지 않는다
        n = out["complete"].get(tid, 0)
        d = BEH_W_COMPLETE * min(n, BEH_CAP_COMPLETE)
        d += BEH_W_REPEAT * min(max(n - 1, 0), BEH_CAP_REPEAT)
        if tid in out["like"]:
            d += BEH_W_LIKE
        d -= out["skip_pen"].get(tid, 0.0)
        if tid in out["like"]:
            # 명시 LIKE 는 암묵 negative 에 지지 않는다 (라벨러의 L1 > neg_strong 원칙과 동일).
            # 스킵 감점이 가점을 깎을 수는 있어도 음수로 끌어내리지는 못한다.
            d = max(d, 0.0)
        out["delta"][tid] = d
    return out


def user_behavior(user: str, now: float | None = None,
                  events: list | None = None) -> dict:
    """사용자 행동을 집계해 하드 필터와 점수 보정을 만든다.

    반환
      recent_play  최근 RECENT_EXCLUDE_SEC 안에 정상 재생된 곡
      dislike      활성 DISLIKE 곡                                  (하드 필터)
      like         활성 LIKE 곡
      complete     곡별 정상 완주 횟수
      skip_pen     곡별 시간 감쇠된 Early Skip 감점 (양수)
      delta        곡별 최종 점수 보정값 (가점 - 감점)

    `events` 를 주면 그것을 쓴다(ClickHouse 직접 조회 경로). 주지 않으면 종전대로
    `events_service.jsonl` 을 읽는다 — 배치와 오프라인 테스트가 그 경로로 돈다.

    Early Skip 판정은 라벨러(app/labeler.py)의 기준을 그대로 쓴다:
    reason='skip_next' · position_sec <= FAST_SKIP_SEC, 추천 경로에서 발생한 것만.
    """
    now = time.time() if now is None else now

    if events is not None:
        # 요청마다 새로 읽어 온 것이다. 캐시하면 최신성을 버리는 셈이라 그대로 집계한다.
        return _aggregate_behavior(events, now)

    if not paths.events_path().exists():
        return {"recent_play": set(), "dislike": set(), "like": set(),
                "complete": {}, "skip_pen": {}, "delta": {}, "recent_pen": {},
                "events": 0}

    # 로그가 그대로면 다시 읽지 않는다. 추천은 사용자가 기다리는 경로라 요청마다
    # 전체를 훑으면 로그 크기에 비례해 느려진다. 파일이 자라거나 바뀌면 캐시를 버린다.
    # (시간이 흐르면 감쇠 값이 달라지므로 `now` 도 키에 넣는다 — 분 단위로 끊는다.)
    st = paths.events_path().stat()
    ck = (user, st.st_size, st.st_mtime_ns, int(now // 60))
    hit = _BEH_CACHE.get(ck)
    if hit is not None:
        return copy.deepcopy(hit)

    out = _aggregate_behavior(_file_events(user), now)
    if len(_BEH_CACHE) > _BEH_CACHE_MAX:
        _BEH_CACHE.clear()          # 사용자가 늘면 통째로 비운다. LRU 까지는 필요 없다
    _BEH_CACHE[ck] = copy.deepcopy(out)
    return out


def user_positives(user: str) -> pd.DataFrame:
    lab = labels_for(user)
    if lab.empty:
        return pd.DataFrame()
    pos = lab[(lab.grade == "L1") & (~lab.is_merged)]
    drop = dismissed_moments(user)          # 화면에서 지운 구간은 Moment DNA 에서도 뺀다
    if drop:
        pos = pos[~pos.section_key.isin(drop)]
    return pos


def _pool_mean() -> np.ndarray:
    c = CAT
    if getattr(c, "_mu_g", None) is None:
        c._mu_g = np.asarray(c.Z[c.pool_rows], dtype=np.float32).mean(0)
    return c._mu_g


def center(V: np.ndarray) -> np.ndarray:
    """카탈로그 평균 제거 + L2. 질의와 후보에 **동일하게** 적용해야 한다."""
    W = np.atleast_2d(np.asarray(V, dtype=np.float32)) - _pool_mean()
    W /= np.clip(np.linalg.norm(W, axis=1, keepdims=True), 1e-9, None)
    return W


def similar_moments(moment_id: str, n: int, exclude_tracks: set[str] | None = None,
                    same_track: bool = False) -> list[dict]:
    """기준 구간과 닮은 다른 곡의 구간. **개인화는 하지 않는다.**

    사용자 취향을 섞지 않는 것이 요점이다 — "이 구간과 비슷한 구간" 은 누가 물어도 같은
    답이어야 캐시도 되고 설명도 된다. 개인화가 필요하면 `recommend()` 를 쓴다.

    질의와 후보에 `center()` 를 **둘 다** 적용한다. 한쪽만 하면 유사도가 통째로
    내려앉는다 — 이 프로젝트에서 실제로 겪은 회귀다.

    한 곡당 가장 닮은 구간 하나만 남긴다. 같은 곡의 인접 구간이 목록을 채우면 "비슷한
    곡" 으로서 쓸모가 없다.
    """
    c = CAT
    hit = c.pool.index[c.pool.moment_id == moment_id]
    if not len(hit):
        return []
    i0 = int(hit[0])
    src_track = str(c.pool.track_id.iloc[i0])

    q = center(np.asarray(c.Z[int(c.pool.emb_row.iloc[i0])], dtype=np.float32))[0]
    V = center(np.asarray(c.Z[c.pool_rows], dtype=np.float32))
    sim = V @ q

    drop = set(exclude_tracks or ())
    if not same_track:
        drop.add(src_track)

    out: list[dict] = []
    seen: set[str] = set()
    for i in np.argsort(-sim):
        i = int(i)
        if i == i0:
            continue
        r = c.pool.iloc[i]
        t = str(r.track_id)
        if t in drop or t in seen:
            continue
        seen.add(t)
        out.append({"rank": len(out) + 1, "track_id": t, "moment_id": str(r.moment_id),
                    "section_label": str(r.section_label),
                    "start_sec": round(float(r.start_sec), 2),
                    "end_sec": round(float(r.end_sec), 2),
                    "similarity": round(float(sim[i]), 6)})
        if len(out) >= n:
            break
    return out


def build_dna(user: str, query_plan: Optional[dict] = None,
              current_track_id: Optional[str] = None,
              current_moment_id: Optional[str] = None) -> tuple[np.ndarray, np.ndarray, list[dict]]:
    """규칙 경로. 군집은 **원공간**에서(구조가 살아 있음), 점수용 변환은 center() 로 통일."""
    from scipy.cluster.hierarchy import fcluster, linkage

    c = CAT
    pos = user_positives(user)
    if query_plan is not None and not pos.empty:
        plan = copy.deepcopy(query_plan)
        matched = c.mom[c.mom.moment_id.isin(pos.section_key)]
        matched, qdiag = filter_query_pool(matched, plan, current_track_id, current_moment_id)
        want_mood = _qvals((plan.get("filters") or {}).get("mood"))
        if want_mood:
            meta = catalog_facet_rows()
            matched = matched.loc[np.asarray([
                _qmatch((meta.get(t) or {}).get("moods", []), want_mood)
                for t in matched.track_id
            ], dtype=bool)]
            qdiag["applied"].append("filters.mood")
        # 판정할 수 없는 조건이 하나라도 있으면 DNA 전체를 섞지 않는다.
        if qdiag.get("ignored") or not qdiag.get("applied"):
            matched = matched.iloc[0:0]
        pos = pos[pos.section_key.isin(matched.moment_id)]
    if pos.empty:
        return np.zeros((0, c.Z.shape[1]), np.float32), np.zeros(0), []
    rowmap = c.mom.set_index("moment_id").emb_row
    keys = [k for k in pos.section_key if k in rowmap.index][:DNA_TOP_N]
    if not keys:
        return np.zeros((0, c.Z.shape[1]), np.float32), np.zeros(0), []
    rows = rowmap.loc[keys].to_numpy()
    V0 = np.asarray(c.Z[rows], dtype=np.float32)          # 원공간 (이미 L2)
    dw = decay_weights(pos[pos.section_key.isin(keys)].set_index("section_key").loc[keys]
                       .reset_index())

    # 1) 군집은 원공간에서
    if len(V0) >= DNA_MIN:
        lk = linkage(V0, method="average", metric="cosine")
        cl = fcluster(lk, t=1 - HAC_TAU, criterion="distance")
    else:
        cl = np.ones(len(V0), dtype=int)
    ids = [i for i, _ in sorted(
        ((i, int((cl == i).sum())) for i in set(cl)), key=lambda x: -x[1])][:DNA_MAX]

    # 2) 다른 사용자 평균 (질의 측 개인화 보정용)
    OV = None
    if query_plan is None:
        o = other_positives(user)
        if not o.empty:
            ok = [k for k in o.section_key if k in rowmap.index]
            if ok:
                OV = center(np.asarray(c.Z[rowmap.loc[ok].to_numpy()], dtype=np.float32)).mean(0)

    C, W, meta = [], [], []
    for cid in ids:
        m = cl == cid
        wm = dw[m]
        v0 = (V0[m] * wm[:, None]).sum(0) / max(wm.sum(), 1e-9)   # 감쇠 가중 중심
        coh = float((V0[m] @ (v0 / max(np.linalg.norm(v0), 1e-9))).mean())
        v = center(v0)[0]
        if OV is not None:
            v = v - LAMBDA_USER_RESIDUAL * OV
            v /= max(np.linalg.norm(v), 1e-9)
        rep = keys[int(np.argmax(np.where(m, V0 @ (v0 / max(np.linalg.norm(v0), 1e-9)), -1e9)))]
        C.append(v)
        W.append(max(coh, 0.05) * float(np.sqrt(wm.sum())))   # 감쇠 가중 크기의 sqrt
        meta.append({"dna_id": f"{user}#dna{int(cid)}", "n_members": int(m.sum()),
                     "decayed_size": round(float(wm.sum()), 3),
                     "cohesion": round(coh, 4), "rep_moment_id": rep})
    W = np.asarray(W, dtype=np.float32)
    W /= max(W.sum(), 1e-9)
    return np.asarray(C, dtype=np.float32), W, meta


def model_user_vec(user: str) -> Optional[np.ndarray]:
    c = CAT
    if c.model is None:
        return None
    pos = user_positives(user)
    if pos.empty:
        return None
    # 학습과 동일하게 **최근순**으로 자른다
    if "occurred_at" in pos.columns:
        pos = pos.assign(_t=pd.to_datetime(pos.occurred_at, errors="coerce", utc=True)) \
                 .sort_values("_t", ascending=False)
    keys = [k for k in pos.section_key if k in c.idx][:c.hist_max]
    if not keys:
        return None
    rows = [c.idx[k] for k in keys]
    dw = decay_weights(pos.set_index("section_key").loc[keys].reset_index())
    pad = c.hist_max - len(rows)
    idx = np.asarray(rows + [0] * pad)
    mask = np.asarray([True] * len(rows) + [False] * pad)
    logw = np.log(np.concatenate([dw, np.full(pad, 1e-3)])).astype(np.float32)

    if c.user_enc is not None:                      # NumPy 판
        return c.user_enc.encode(np.asarray(c.X)[idx], mask, logw)

    t = c.torch
    Ht, Mt = t.tensor(idx[None]), t.tensor(mask[None])
    LW = t.tensor(logw[None])
    with t.no_grad():
        try:
            z = c.model.enc_user(t.tensor(c.X)[Ht], Mt, logw=LW).numpy()[0]
        except TypeError:          # 감쇠 미지원 구버전 체크포인트
            z = c.model.enc_user(t.tensor(c.X)[Ht], Mt).numpy()[0]
    return z


LABELS8 = ["intro", "verse", "chorus", "bridge", "inst", "solo", "outro", "other"]


FEATS_ALL = (["rule_sim", "model_sim", "log_dur", "rel_center", "rms_pct", "bpm_log2_ratio"]
             + [f"lab_{L}" for L in LABELS8])


def serve_features(pool: pd.DataFrame, rule_sim: np.ndarray, model_sim: np.ndarray,
                   bpm_pref: float) -> np.ndarray:
    """전체 특징을 만든 뒤 결합기가 선언한 이름 순서로 고른다."""
    c = CAT
    n = len(pool)
    F = np.zeros((n, len(FEATS_ALL)), dtype=np.float64)
    F[:, 0] = rule_sim
    F[:, 1] = model_sim
    F[:, 2] = np.log1p(pool.dur_sec.to_numpy(dtype=np.float64))
    F[:, 3] = ((pool.rel_start + pool.rel_end) / 2).to_numpy(dtype=np.float64)
    rms = pool.rms.to_numpy(dtype=np.float64) if "rms" in pool.columns else np.zeros(n)
    F[:, 4] = np.nan_to_num(
        pool.assign(_r=rms).groupby("track_id")._r.rank(pct=True).to_numpy(), nan=0.5)
    if bpm_pref > 0:
        bpm = np.array([float(c.content.bpm.get(t, 0.0)) for t in pool.track_id])
        F[:, 5] = np.abs(np.log2(np.clip(bpm, 1e-6, None) / bpm_pref))
    li = {L: i for i, L in enumerate(LABELS8)}
    for k, L in enumerate(pool.section_label.astype(str)):
        F[k, 6 + li.get(L, li["other"])] = 1.0
    F = np.nan_to_num(F)
    if c.comb is not None:
        sel = [FEATS_ALL.index(f) for f in c.comb["features"] if f in FEATS_ALL]
        if len(sel) != len(c.comb["features"]):
            raise HTTPException(500, "결합기 특징 이름이 서비스와 맞지 않는다")
        F = F[:, sel]
    return F


def logistic_prob(F: np.ndarray) -> np.ndarray:
    cb = CAT.comb
    Z = (F - cb["mean"]) / np.where(cb["scale"] == 0, 1.0, cb["scale"])
    return 1.0 / (1.0 + np.exp(-(Z @ cb["coef"] + cb["b"])))


def pct(v: np.ndarray) -> np.ndarray:
    if len(v) == 0:
        return v
    o = np.argsort(np.argsort(v))
    return o / max(len(v) - 1, 1)


def soft_rerank(v: np.ndarray, clap_active: bool, clap_pct: np.ndarray,
                mood_match: Optional[np.ndarray]) -> np.ndarray:
    """명시 게이트를 통과한 뒤의 **부드러운 재정렬**. 걸러내지 않고 순서만 바꾼다.

    `mood_match` 가 `None` 이면 종전 식과 연산이 그대로 같다 — mood 조건이 없는 요청의
    순위는 이 함수가 생기기 전과 비트 단위로 같아야 한다.

    mood 는 백분위(`pct`)로 바꾸지 않고 **일치 비율(0~1) 그대로** 섞는다. 대부분의 후보가
    0 으로 동점인데 `pct` 를 씌우면 동점자 사이에 원래 행 순서가 순위로 새어 들어간다.
    """
    out = pct(v)
    if clap_active:
        out = (1.0 - CLAP_WEIGHT) * out + CLAP_WEIGHT * clap_pct
    if mood_match is not None:
        out = (1.0 - MOOD_WEIGHT) * out + MOOD_WEIGHT * mood_match
    return out


def mood_match_ratio(pool: pd.DataFrame, wanted: list, fac: pd.DataFrame) -> np.ndarray:
    """요청한 무드 중 그 곡이 실제로 가진 비율. 게이트가 아니라 가점 재료다."""
    want = set(_qvals(wanted))
    if not want:
        return np.zeros(len(pool), dtype=np.float32)
    per_track = {t: len(want & set(_qvals(fac.loc[t].get("moods", []))))
                 for t in pool.track_id.unique() if t in fac.index}
    return np.asarray([per_track.get(t, 0) / len(want) for t in pool.track_id],
                      dtype=np.float32)


def parse_nlp_query(text: str) -> tuple[Optional[dict], Optional[tuple[str, str]]]:
    """QueryPlan v1 해석기 호출. 실패를 **삼키지 않는다.**

    반환은 `(plan, error)` 이고 `error` 는 `None` 또는 `(error_code, message)` 다.
    호출부가 그대로 응답 오류로 쓴다.

    실패했는데 일반 추천으로 넘어가면 사용자가 말한 조건("조용한 피아노")이 소리 없이
    사라진 채 200 이 나간다. 조건을 몰래 푸는 것과 같아서, 조건을 못 뽑았으면 실패로 답한다.
    """
    if not NLP_PARSE_URL:
        return None, ("SEARCH_NOT_CONFIGURED",
                      "자연어 해석기가 설정되지 않았습니다. MUPID_AI_NLP_URL 을 지정하세요.")
    try:
        body = json.dumps({"text": text}, ensure_ascii=False).encode("utf-8")
        req = UrlRequest(NLP_PARSE_URL, data=body, headers={"Content-Type": "application/json"})
        with urlopen(req, timeout=10) as res:
            data = json.loads(res.read().decode("utf-8"))
        plan = data.get("plan") if data.get("ok") else None
        if not isinstance(plan, dict):
            # 해석기 주소는 내부 정보다. 메시지에 싣지 않는다. 해석기가 돌려준 문자열은
            # 남의 출력이므로 길이를 잘라서 넣는다(스택 추적·경로가 통째로 실릴 수 있다).
            why = str(data.get("error") or "QueryPlan 없음")[:200]
            return None, ("INTERNAL_ERROR", f"자연어 해석에 실패했습니다: {why}")
        emb = data.get("clap_embedding")
        if isinstance(emb, list):
            plan["_clap_embedding"] = emb
        return plan, None
    except Exception as e:
        return None, ("INTERNAL_ERROR", f"자연어 해석기 호출 실패: {type(e).__name__}")


def _qvals(v) -> list[str]:
    if v is None:
        return []
    if isinstance(v, np.ndarray):
        v = v.tolist()
    elif not isinstance(v, (list, tuple, set)):
        v = [v]
    return [str(x).strip().lower().replace("-", "_").replace(" ", "_")
            for x in v if str(x).strip()]


_TAG_ALIASES = {
    "mood": {
        "calm": ("relaxing", "meditative"),
        "quiet": ("relaxing", "meditative"),
        "peaceful": ("relaxing", "meditative"),
        "serene": ("relaxing", "meditative"),
        "chill": ("relaxing",),
        "soft": ("relaxing",),
        "잔잔한": ("relaxing", "meditative"),
        "차분한": ("relaxing", "meditative"),
        "편안한": ("relaxing",),
    }
}


def _canonicalize_query_tags(data: dict, fac: pd.DataFrame,
                             pool: pd.DataFrame) -> dict:
    """QueryPlan 태그를 현재 카탈로그 어휘로만 제한한다."""
    out = dict(data or {})

    def collect(*columns: str) -> set[str]:
        values: set[str] = set()
        for column in columns:
            if column in fac:
                for value in fac[column]:
                    values.update(_qvals(value))
        return values

    allowed = {
        "genre": collect("genre", "style"),
        "language": collect("language"),
        "mood": collect("moods"),
        "instrument": collect("instruments"),
        "vocal": {value for row in fac.itertuples()
                  for value in _vocal_values(row.has_vocal, row.vocal_gender)},
        "flow": set(_qvals(pool["section_label"].tolist())),
    }
    for name, vocab in allowed.items():
        normalized = []
        for value in _qvals(out.get(name)):
            for candidate in _TAG_ALIASES.get(name, {}).get(value, (value,)):
                if candidate in vocab and candidate not in normalized:
                    normalized.append(candidate)
        out[name] = normalized if name != "flow" else (normalized[0] if normalized else None)
    return out


def _qmatch(values, wanted) -> bool:
    have = _qvals(values)
    return any(w == h or f"_{w}_" in f"_{h}_"
               for w in _qvals(wanted) for h in have)


def filter_query_pool(pool: pd.DataFrame, plan: dict, current_track_id: Optional[str],
                      current_moment_id: Optional[str]) -> tuple[pd.DataFrame, dict]:
    """QueryPlan 의 명시 조건만 후보 게이트로 적용한다."""
    c = CAT
    facets = catalog_facets()
    fac = facets["_df"].set_index("track_id")
    meta = catalog_facet_rows()          # track_id -> dict. .loc 대신 쓴다
    raw_f = plan.get("filters") or {}
    f = _canonicalize_query_tags(raw_f, fac, pool)
    ex = _canonicalize_query_tags(plan.get("exclude") or {}, fac, pool)
    available = {
        "instrument": any(_qvals(v) for v in fac.instruments),
        "vocal": any(_vocal_values(r.has_vocal, r.vocal_gender)
                     for r in fac.itertuples()),
    }
    ignored = []
    for name, ok in available.items():
        if not ok and (_qvals(raw_f.get(name)) or _qvals((plan.get("exclude") or {}).get(name))):
            ignored.append(name + "(카탈로그 데이터 없음)")
            f[name], ex[name] = [], []
    plan["filters"], plan["exclude"] = f, ex

    # 사전에 없어 통째로 사라진 필수 조건을 찾는다. 조용히 넘기면 조건을 푼 것이 된다.
    unsat = []
    for name in ("genre", "language", "vocal", "instrument"):
        asked = _qvals(raw_f.get(name))
        if asked and available.get(name, True) and not _qvals(f.get(name)):
            unsat.append({"field": name, "requested": asked})
    if unsat:
        return pool.iloc[0:0].copy(), {
            "applied": [], "ignored": [], "dropped": len(pool),
            "unsatisfiable": unsat,
            "reason": "요청한 조건이 현재 카탈로그 어휘에 없다",
            "plan": {k: v for k, v in plan.items() if not k.startswith("_")}}
    keep = np.ones(len(pool), dtype=bool)
    applied = []

    def gate(name: str, values, negate: bool = False) -> None:
        nonlocal keep
        wanted = (ex if negate else f).get(name)
        if not _qvals(wanted):
            return
        rows = (values(t) for t in pool.track_id) if callable(values) else values
        hit = np.asarray([_qmatch(value, wanted) for value in rows])
        keep &= ~hit if negate else hit
        applied.append(("exclude." if negate else "filters.") + name)

    _m = lambda t: meta.get(t) or {}          # noqa: E731  (fac.loc[t] 과 같은 값)
    gate("genre", lambda t: [_m(t).get("genre", ""), _m(t).get("style", "")])
    gate("language", lambda t: _m(t).get("language", ""))
    instruments = ([_instrument_list(value) or _m(track_id).get("instruments", [])
                    for track_id, value in zip(pool.track_id, pool.instruments)]
                   if "instruments" in pool.columns
                   else [_m(t).get("instruments", []) for t in pool.track_id])
    gate("instrument", instruments)
    gate("vocal", lambda t: _vocal_values(_m(t).get("has_vocal"),
                                           _m(t).get("vocal_gender")))
    # mood/activity/goal은 추론 가능성이 커서 CLAP 점수로만 반영한다.
    gate("genre", lambda t: [_m(t).get("genre", ""), _m(t).get("style", "")], True)
    gate("language", lambda t: _m(t).get("language", ""), True)
    gate("mood", lambda t: _m(t).get("moods", []), True)
    gate("instrument", instruments, True)
    gate("vocal", lambda t: _vocal_values(_m(t).get("has_vocal"),
                                           _m(t).get("vocal_gender")), True)

    flow = f.get("flow")
    if _qvals(flow):
        keep &= np.asarray([_qmatch(v, flow) for v in pool.section_label])
        applied.append("filters.flow")
    flow_ex = ex.get("flow")
    if _qvals(flow_ex):
        keep &= ~np.asarray([_qmatch(v, flow_ex) for v in pool.section_label])
        applied.append("exclude.flow")

    bpms = np.asarray([float(_m(t).get("bpm", 0) or 0) for t in pool.track_id])
    if f.get("bpm_min") is not None:
        keep &= bpms >= float(f["bpm_min"]); applied.append("filters.bpm_min")
    if f.get("bpm_max") is not None:
        keep &= bpms <= float(f["bpm_max"]); applied.append("filters.bpm_max")

    tempo_dir = str(f.get("tempo_direction") or "").lower()
    ref_bpm = (float(_m(current_track_id).get("bpm", 0) or 0)
               if current_track_id in meta else 0.0)
    if tempo_dir in ("up", "faster", "fast") and ref_bpm:
        keep &= bpms > ref_bpm; applied.append("filters.tempo_direction")
    elif tempo_dir in ("down", "slower", "slow") and ref_bpm:
        keep &= (bpms > 0) & (bpms < ref_bpm); applied.append("filters.tempo_direction")
    elif tempo_dir:
        ignored.append("filters.tempo_direction(기준곡 없음)")

    energy_dir = str(f.get("energy_direction") or "").lower()
    ref = c.mom[c.mom.moment_id == current_moment_id] if current_moment_id else c.mom.iloc[0:0]
    if ref.empty and current_track_id:
        ref = c.mom[c.mom.track_id == current_track_id]
    ref_rms = float(ref.rms.mean()) if not ref.empty else 0.0
    if energy_dir in ("up", "higher", "energetic") and ref_rms:
        keep &= pool.rms.to_numpy() > ref_rms; applied.append("filters.energy_direction")
    elif energy_dir in ("down", "lower", "calm") and ref_rms:
        keep &= pool.rms.to_numpy() < ref_rms; applied.append("filters.energy_direction")
    elif energy_dir:
        ignored.append("filters.energy_direction(기준곡 없음)")

    for name in ("activity", "goal"):
        if _qvals(f.get(name)) or _qvals(ex.get(name)):
            ignored.append(name + "(카탈로그 필드 없음)")
    return pool[keep].copy(), {"applied": applied, "ignored": ignored,
                               "dropped": int((~keep).sum()),
                               "plan": {k: v for k, v in plan.items() if not k.startswith("_")}}


# -------------------------------------------------------------------- 추천

def _flow_penalty(pool: pd.DataFrame, current_track_id: Optional[str],
                  current_moment_id: Optional[str]):
    """이어 듣기에서 BPM·에너지가 크게 튀는 후보를 약하게 누른다.

    제외가 아니라 감점이다 — 다른 후보가 없으면 여전히 나온다. 기준 곡의 BPM 이나
    에너지를 모르면 해당 항은 0 이고, 둘 다 모르면 None 을 돌려 아무것도 하지 않는다.
    """
    if not current_track_id:
        return None
    c = CAT
    ref_bpm = float(c.content.bpm.get(current_track_id, 0.0) or 0.0)
    ref = c.mom[c.mom.moment_id == current_moment_id] if current_moment_id else c.mom.iloc[0:0]
    if ref.empty:
        ref = c.mom[c.mom.track_id == current_track_id]
    ref_rms = float(ref.rms.mean()) if not ref.empty else 0.0
    if ref_bpm <= 0 and ref_rms <= 0:
        return None

    pen = np.zeros(len(pool), dtype=np.float64)
    if ref_bpm > 0:
        bpm = np.array([float(c.content.bpm.get(t, 0.0) or 0.0) for t in pool.track_id])
        known = bpm > 0
        lr = np.zeros(len(pool))
        lr[known] = np.abs(np.log2(bpm[known] / ref_bpm))
        # 허용 범위를 넘어선 만큼만, 상한까지 선형으로 깎는다.
        over = np.clip((lr - FLOW_BPM_TOL) / FLOW_BPM_TOL, 0.0, 1.0)
        pen += FLOW_W_BPM * over * known           # BPM 을 모르는 곡은 깎지 않는다
    if ref_rms > 0:
        rel = np.abs(pool.rms.to_numpy() - ref_rms) / max(ref_rms, 1e-9)
        pen += FLOW_W_ENERGY * np.clip((rel - 1.0), 0.0, 1.0)
    return pen


def recommend(user: str, n: int, exclude: set[str], explore: bool = True, query_plan: Optional[dict] = None,
              current_track_id: Optional[str] = None, current_moment_id: Optional[str] = None,
              beh_events: Optional[list] = None,
              onboarding: Optional[dict] = None) -> dict:
    c = CAT
    C, W, dna_meta = build_dna(user, query_plan, current_track_id, current_moment_id)
    query_source = "labels" if len(C) else "none"
    if query_plan is None and not len(C):
        C, W, dna_meta = onboarding_seeds(user, onboarding)
        if len(C):
            query_source = "onboarding"
    zu = model_user_vec(user) if query_plan is None else None
    if query_plan is None:
        _t_beh = time.perf_counter()
        # `beh_events` 는 요청 경계에서 ClickHouse 로 읽어 온 것이다. 없으면 종전대로
        # 파일 로그를 읽는다 — 배치와 오프라인 테스트가 그 경로로 돈다.
        beh = user_behavior(user, events=beh_events)
        metrics.observe_behavior(time.perf_counter() - _t_beh)
    else:
        beh = {"dislike": set(), "delta": {}, "recent_pen": {}, "skip_pen": {}, "events": 0}
    # 하드 필터 = 현재 목록/세션 재생곡 + 활성 DISLIKE
    # 최근 4시간 재생곡은 **제외하지 않는다** — 감점만 주고 4시간에 걸쳐 복구한다.
    # 통째로 빼면 많이 들은 사용자일수록 후보가 말라 추천이 빈약해진다.
    hard = set(exclude) | beh["dislike"]
    diag: dict[str, Any] = {"dna": len(C), "model_vector": zu is not None,
                            "query_source": query_source,
                            "behavior_source": ("disabled_for_query" if query_plan is not None else
                                                "clickhouse" if beh_events is not None else "file"),
                            "onboarding_source": ("disabled_for_query" if query_plan is not None else
                                                  "request" if onboarding is not None else "file"),
                            "behavior_events": beh["events"],
                            "dropped_bpm": 0, "dropped_short": 0, "excluded": len(hard),
                            "excluded_session": len(set(exclude)),
                            "excluded_recent_play": 0,   # 하드 제외 폐지 (감점으로 전환)
                            "penalized_recent_play": len(beh["recent_pen"]),
                            "excluded_dislike": len(beh["dislike"] - set(exclude)),
                            "skip_penalty_applied": 0}

    pool = c.pool[~c.pool.track_id.isin(hard)]
    pool = pool[pool.dur_sec >= MIN_MOMENT_SEC]
    diag["dropped_short"] = int((c.pool.dur_sec < MIN_MOMENT_SEC).sum())
    if query_plan is not None:
        pool, qdiag = filter_query_pool(pool, query_plan, current_track_id, current_moment_id)
        diag["nlp"] = qdiag
    diag["candidates"] = len(pool)
    if pool.empty:
        why = (diag.get("nlp") or {}).get("reason") or "후보 없음"
        return {"results": [], "diagnostics": {**diag, "reason": why}}

    clap_score = np.zeros(len(pool), dtype=np.float32)
    clap_pct = np.full(len(pool), 0.5, dtype=np.float32)
    clap_active = False
    text_vec = np.asarray((query_plan or {}).get("_clap_embedding", []), dtype=np.float32)
    if text_vec.size and c.clap_emb is not None:
        ci = np.asarray([c.clap_idx.get(str(mid), -1) for mid in pool.moment_id])
        cov = ci >= 0
        if cov.any() and text_vec.size == c.clap_emb.shape[1]:
            text_vec /= max(float(np.linalg.norm(text_vec)), 1e-9)
            clap_score[cov] = c.clap_emb[ci[cov]] @ text_vec
            clap_pct[cov] = pct(clap_score[cov])
            clap_active = True
            diag["clap"] = {"active": True, "covered": int(cov.sum()),
                            "candidates": len(pool), "weight": CLAP_WEIGHT}

    # filters.mood 는 게이트가 아니다 — 무드는 추론 폭이 커서 걸러내면 후보가 과하게 마른다.
    # 대신 일치하는 곡을 위로 올린다. CLAP 이 없는 경로(호출자가 QueryPlan 을 직접 보낸
    # 경우)에서 mood 가 아무 일도 하지 않던 것을 메운다.
    mood_match = None
    want_mood = _qvals(((query_plan or {}).get("filters") or {}).get("mood"))
    if want_mood:
        mood_match = mood_match_ratio(pool, want_mood, catalog_facets()["_df"]
                                      .set_index("track_id"))
        diag["mood_rerank"] = {"requested": want_mood, "weight": MOOD_WEIGHT,
                               "matched": int((mood_match > 0).sum()),
                               "candidates": len(pool)}

    rows = pool.emb_row.to_numpy()
    V = center(np.asarray(c.Z[rows], dtype=np.float32))   # 질의와 동일 변환
    n_pairs = 0
    if len(C):
        S = V @ C.T * W[None, :]
        best_j = np.argmax(S, axis=1)
        s_rule = S[np.arange(len(S)), best_j]
        n_pairs = int(S.size)
    else:
        # 조사도 라벨도 없을 때의 최후 폴백. 근거가 약하므로 그 사실을 함께 알린다.
        s_rule = pool.rms.rank(pct=True).to_numpy() * 0.5 + \
            (1.0 - (((pool.rel_start + pool.rel_end) / 2 - 0.4).abs() / 0.6).clip(0, 1)).to_numpy()
        best_j = np.zeros(len(pool), dtype=int)
        diag["cold_start"] = True
        diag["cold_start_note"] = ("초기 선호 조사(POST /onboarding)가 없어 위치(곡 40% 부근)와 "
                                   "에너지만으로 정렬했다. 근거가 약한 폴백이다.")

    if zu is not None and c.ZS is not None:
        xr = np.asarray([c.idx.get(m, -1) for m in pool.moment_id])
        ok = xr >= 0
        s_model = np.zeros(len(pool), dtype=np.float32)
        s_model[ok] = np.asarray(c.ZS[xr[ok]], dtype=np.float32) @ zu
    else:
        s_model = np.zeros(len(pool), dtype=np.float32)

    # 로지스틱 특징용 사용자 평균 질의 — 학습 때와 같은 방식이어야 한다
    rule_mean = np.zeros(len(pool), dtype=np.float64)
    if len(C):
        q = C.mean(0)
        q /= max(np.linalg.norm(q), 1e-9)
        rule_mean = V @ q

    bpm_pref = 0.0
    if query_plan is None:
        pos = user_positives(user)
        if not pos.empty:
            bs = [float(c.content.bpm.get(t, 0.0)) for t in pos.track_id.unique()]
            bs = [x for x in bs if x > 0]
            bpm_pref = float(np.median(bs)) if bs else 0.0

    needs_model = bool(c.comb) and ("model_sim" in c.comb["features"])
    can_logistic = bool(c.comb) and len(C) and (zu is not None or not needs_model)
    mode = SELECT_MODE if can_logistic else "rank"
    diag["combiner"] = (c.comb or {}).get("path")
    diag["combiner_needs_model_vector"] = needs_model
    p_min = float(c.comb["threshold"]) if (mode == "logistic" and "threshold" in c.comb) else P_MIN
    p_rule = "absolute"
    # 행동 보정값 (후보마다 로그를 다시 읽지 않고 위에서 만든 집계를 재사용한다)
    # to_numpy() 는 읽기 전용 뷰를 줄 수 있어 제자리 연산이 터진다. 새 배열로 받는다.
    bdelta = np.array(pool.track_id.map(beh["delta"]).fillna(0.0).to_numpy(),
                      dtype=np.float64)
    # 최근 재생 감점 (하드 제외 대체)
    bdelta = bdelta - np.asarray(
        pool.track_id.map(beh["recent_pen"]).fillna(0.0).to_numpy(), dtype=np.float64)
    # 이어 듣기 흐름 보정: 기준 곡이 있을 때만 적용한다.
    flow_pen = _flow_penalty(pool, current_track_id, current_moment_id)
    if flow_pen is not None:
        bdelta = bdelta - flow_pen
        diag["flow_penalty"] = {"applied": int((flow_pen > 0).sum()),
                                "max": round(float(flow_pen.max()), 4)}
    diag["skip_penalty_applied"] = int(pool.track_id.isin(beh["skip_pen"]).sum())
    if mode == "logistic":
        prob = logistic_prob(serve_features(pool, rule_mean, s_model, bpm_pref))
        final = prob
    else:
        prob = np.zeros(len(pool))
        final = W_RULE * pct(s_rule) + (W_MODEL * pct(s_model) if zu is not None else 0.0)
        if zu is None:
            final = pct(s_rule)

    final = soft_rerank(final, clap_active, clap_pct, mood_match) + bdelta

    # BPM 게이트 (사용자 선호 BPM 중앙값 기준)
    keep = np.ones(len(pool), dtype=bool)
    # ---- 명시 선호 게이트: 청취 이력 유무와 무관하게 항상 적용 (게이트 인계) ----
    ans = (onboarding_of(user, onboarding) or {}) if query_plan is None else {}
    if ans:
        fac = catalog_facets()["_df"].set_index("track_id")   # 온보딩 게이트. 캐시된 df 다
        wl = ans.get("language") or []
        wv = ans.get("vocal_type") or []
        xs = ans.get("exclude_style") or []
        if wl or wv or xs:
            okm = []
            for t in pool.track_id:
                if t not in fac.index:
                    okm.append(False)
                    continue
                r = fac.loc[t]
                okm.append((not wl or r.language in wl)
                           and (not wv or set(_vocal_values(
                               r.has_vocal, r.vocal_gender)) & set(wv))
                           and (not xs or r.style not in xs))
            okm = np.asarray(okm)
            diag["dropped_onboarding_filter"] = int((keep & ~okm).sum())
            keep &= okm
        diag["persistent_gates"] = {"language": wl, "vocal_type": wv, "exclude_style": xs}
        # 구간 유형은 **추정 대상**이다. 이력이 없을 때만 게이트로 걸고,
        # 이력이 생기면 학습된 점수가 담당한다.
        ws = ans.get("section_label") or []
        if ws and query_source == "onboarding":
            okl = pool.section_label.isin(ws).to_numpy()
            diag["dropped_onboarding_section"] = int((keep & ~okl).sum())
            keep &= okl
            diag["section_gate"] = "active (이력 없음)"
        elif ws:
            diag["section_gate"] = "released (이력 기반 점수로 전환)"
    if bpm_pref > 0:
        bpm = np.array([float(c.content.bpm.get(t, 0.0)) for t in pool.track_id])
        lr = np.abs(np.log2(np.clip(bpm, 1e-6, None) / bpm_pref))
        keep &= lr <= MAX_BPM_LOG2
        diag["dropped_bpm"] = int((~(lr <= MAX_BPM_LOG2)).sum())
    if mode == "logistic":
        if P_MODE == "quantile":
            # 사용자별 분위. 게이트를 통과한 후보 안에서 계산해야 의미가 있다.
            # 게이트가 좁으면 상위 5% 가 요청 수보다 적어진다 → 요청 n 의 3배는 통과하게
            # 분위를 넓힌다(임계값을 낮추는 것이 아니라 분위 정의가 후보 수에 비례).
            base = prob[keep] if keep.any() else prob
            frac = max(P_TOP_FRAC, min(0.5, (3.0 * n) / max(len(base), 1)))
            p_min = float(np.quantile(base, 1.0 - frac)) if len(base) else 0.0
            p_rule = f"quantile top {frac:.1%} (기본 {P_TOP_FRAC:.0%}, 후보 {len(base)})"
        below = prob < p_min
        diag["dropped_prob_below_threshold"] = int((keep & below).sum())
        keep &= ~below

    # 묶음별 라운드로빈: 각 DNA 묶음이 슬롯을 나눠 갖는다.
    # max over clusters 만 쓰면 가중치가 큰 묶음이 top-k 를 독점해서
    # 새로 생긴 취향(작은 묶음)이 영원히 안 나온다.
    picked, taken_tracks, vecs = [], set(), []
    if len(C):
        per = []
        for j in range(len(C)):
            if mode == "logistic":
                sj = logistic_prob(serve_features(
                    pool, S[:, j] / max(float(W[j]), 1e-9), s_model, bpm_pref))
            else:
                sj = W_RULE * pct(S[:, j]) + (W_MODEL * pct(s_model) if zu is not None else 0.0)
            sj = soft_rerank(sj, clap_active, clap_pct, mood_match) + bdelta
            per.append(np.argsort(-np.where(keep, sj, -1e9)))
        cl_order = list(np.argsort(-W))
        ptr = {j: 0 for j in range(len(C))}
        guard = 0
        while len(picked) < n and guard < 20000:
            progressed = False
            for j in cl_order:
                if len(picked) >= n:
                    break
                while ptr[j] < len(per[j]):
                    i = int(per[j][ptr[j]])
                    ptr[j] += 1
                    guard += 1
                    r = pool.iloc[i]
                    if not keep[i] or r.track_id in taken_tracks:
                        continue
                    v = V[i]
                    if vecs and max(float(v @ q) for q in vecs) >= NEAR_DUP_COS:
                        continue
                    picked.append((i, float(final[i]), j))
                    taken_tracks.add(r.track_id)
                    vecs.append(v)
                    progressed = True
                    break
            if not progressed:
                break
    else:
        order = np.argsort(-np.where(keep, final, -1e9))
        for i in order:
            if len(picked) >= n:
                break
            r = pool.iloc[int(i)]
            if not keep[i] or r.track_id in taken_tracks:
                continue
            if query_plan is not None and vecs and max(
                    float(V[int(i)] @ q) for q in vecs) >= NEAR_DUP_COS:
                continue
            picked.append((int(i), float(final[int(i)]), 0))
            taken_tracks.add(r.track_id)
            vecs.append(V[int(i)])

    if query_plan is None and explore and EXPLORE_SLOTS and len(picked) >= 3:
        rng = np.random.default_rng(int(time.time()) // 60)
        gorder = np.argsort(-np.where(keep, final, -1e9))
        cands = [int(i) for i in gorder[n:n * 6]
                 if pool.iloc[int(i)].track_id not in taken_tracks]
        if cands:
            j = int(rng.choice(cands))
            picked[-1] = (j, float(final[j]), -1)
            taken_tracks.add(pool.iloc[j].track_id)

    out = []
    for rank, (i, fs, cj) in enumerate(picked, 1):
        r = pool.iloc[i]
        ti = c.track_info(r.track_id)
        jj = cj if (cj is not None and cj >= 0) else int(best_j[i]) if len(C) else -1
        dm = dna_meta[jj] if (len(C) and 0 <= jj < len(dna_meta)) else {}
        out.append({
            "rank": rank, **ti,
            "moment_id": r.moment_id, "section_idx": int(r.section_idx),
            "section_label": r.section_label, "label_source": r.label_source,
            "start_sec": round(float(r.start_sec), 2), "end_sec": round(float(r.end_sec), 2),
            "moment_similarity": round(float(S[i, jj]) if (len(C) and jj >= 0) else float(s_rule[i]), 6),
            "slot_source": ("explore" if cj == -1 else f"dna{jj}"),
            "model_score": round(float(s_model[i]), 6),
            "clap_score": (round(float(clap_score[i]), 6) if clap_active else None),
            "prob": (round(float(prob[i]), 6) if mode == "logistic" else None),
            "final_score": round(fs, 6),
            "matched_dna": dm.get("dna_id"), "matched_dna_rep": dm.get("rep_moment_id"),
            "reason": (
                f"내 선호 구간 묶음 {dm.get('dna_id','(콜드스타트)')}"
                f"(대표 {dm.get('rep_moment_id','-')})와 이 곡 {int(r.section_idx)}번 "
                f"{r.section_label} 구간 {float(r.start_sec):.0f}~{float(r.end_sec):.0f}초가 "
                f"유사도 {float(s_rule[i]):.3f}"
                + (f" · 선호 확률 {float(prob[i]):.3f}" if mode == "logistic" else "")
                + (f" · 학습모델 점수 {float(s_model[i]):.3f}" if zu is not None else "")
                + (f" · BPM {ti['bpm']:.0f}(선호 {bpm_pref:.0f})" if bpm_pref else "")
                + (f" · 자연어 의미 유사도 {float(clap_score[i]):.3f}" if clap_active else "")
            ),
        })
    diag.update({"n_pairs_compared": n_pairs, "candidates": len(pool),
                 "bpm_pref": round(bpm_pref, 1), "returned": len(out),
                 "select_mode": mode, "requested": n, "shortfall": max(0, n - len(out)),
                 "prob_threshold": (round(p_min, 6) if mode == "logistic" else None),
                 "prob_threshold_rule": (p_rule if mode == "logistic" else None),
                 "passed_threshold": int(keep.sum()),
                 "prob_quantiles": ({k: round(float(np.percentile(prob, int(k[1:]))), 4)
                                     for k in ("p50", "p75", "p90", "p95")}
                                    if mode == "logistic" else None),
                 "weights": ({"logistic": True} if mode == "logistic"
                             else {"rule": W_RULE, "model": W_MODEL if zu is not None else 0.0})})
    return {"results": out, "diagnostics": diag, "dna": dna_meta}


# ------------------------------------------------------------------- 스키마
class LearnReq(BaseModel):
    user_ref: Optional[str] = None
    force: bool = False        # 전체 재계산(복구·검증용)


