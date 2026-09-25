"""전체 색인의 벡터를 Spring 벡터 등록 API 로 보낸다 (S15P21A204-457, 설계 §6.3·§6.6·§7.1).

Qdrant 에 쓰는 길은 Spring API 하나다(원칙 1). 이 모듈은 JupyterHub 의 **전체 색인**(벡터의 원본)을
읽어 곡마다 본문을 만들고 `PUT /api/v1/music/{id}/vectors` 로 보낸다. Qdrant 를 모른다.

    freeze_mu      중심화 μ 를 한 번 재어 동결한다 → mu.npy (+ mu.json)  모델 묶음 v1 에 넣는다
    plan           색인 + registered.json + μ → 곡별 본문
    send           곡마다 PUT. 429·5xx 는 기다렸다 다시, 4xx 는 세고 다음 곡
    send_stage1    모델 재계산 — stage1_v{N} 만 1,000구간씩 PUT /system/vectors/stage1/{N}

## 색인에서 무엇을 읽나 (ai_server `Catalog` 가 읽는 것과 같은 행 맞춤)

| 벡터 | 파일 | 행 |
|---|---|---|
| `mert` (u)·`r` | `moments_emb.npy` (Z) | `moments.parquet` 의 `emb_row` |
| `feat_v1` | `features_v1.npz` 의 `X` | 같은 파일의 `moment_id` 순서 |
| `stage1_v{N}` | `stage1_section.npy` | `X` 와 같은 행 |
| `clap` | `clap_moments.npz` (fp16) | 같은 파일의 `moment_ids`. 없는 구간은 비운다 |

## μ — 쓰는 쪽과 읽는 쪽이 같아야 한다 (설계 §6.3, ai_server/docs/QDRANT.md §3)

`mert = (Z-μ)/‖Z-μ‖` 라 다른 μ 로 읽으면 **오류 없이** 유사도만 틀어진다. 그래서 μ 는 계산하지
않고 **파일에서 읽는다** — `freeze_mu` 가 한 번 쓴 `mu.npy` 다. 기준은 `registered.json` 에 번호가 있는
곡(1,060곡, 삭제된 26곡 포함)의 구간 중 임베딩이 있는 것이다. 제목 ID 로 된 옛 행과 Jamendo 는
번호가 없어 저절로 빠진다. 새 곡은 이 μ 로 upsert 만 한다 — 전체 재적재는 임베딩 모델이 바뀔 때와
드리프트가 기준을 넘을 때뿐이다(§10.2-2).

**AI 쪽 μ**: 모델 묶음(`models.lock`)에 `mu.npy` 가 서빙 파일로 들어가기 전까지 ai_server 는 판의
색인 풀 평균을 쓴다(`recommender._frozen_mu`). 운영 판의 풀(flow)과 동결 기준(등록 곡)이 같으면 두
값이 같다 — `freeze_mu` 가 그 차이를 `mu.json` 에 적는다. 차이가 있으면 `mu.npy` 를 묶음에 먼저 싣는다.
"""
from __future__ import annotations

import base64
import hashlib
import json
import logging
import time
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Iterable, Optional

logger = logging.getLogger(__name__)

EMB_VERSION = "mert-v1-95m"      # ai_server/app/qdrant_collection.json 의 embedding_version
FEAT_VERSION = "v1"
MERT_DIM, FEAT_DIM, STAGE1_DIM, CLAP_DIM = 1536, 285, 128, 512
STAGE1_BATCH = 1_000             # Spring Stage1VectorsRequest.MAX_SECTIONS
# 429(쓰기 줄이 찼다)·5xx 를 몇 번 다시 보낼지. 클라이언트 자체도 5xx 를 3번 다시 보낸다.
BUSY_RETRIES = 6


class VectorError(RuntimeError):
    """보낼 수 없다. 무엇이 없는지 말하고 멈춘다."""


def b64(values) -> str:
    """float32 리틀엔디언 바이트의 base64 — Spring `VectorCodec` 이 푸는 형식(설계 §11 미결 5)."""
    import numpy as np

    return base64.b64encode(np.ascontiguousarray(values, dtype="<f4").tobytes()).decode("ascii")


# ───────────────────────────────────────────────────────────── 색인 읽기

@dataclass
class Board:
    """전체 색인 한 판. 파일은 mmap 으로 연다 — 57MB 를 곡마다 다시 읽지 않는다."""

    data_dir: Path
    moments: "object"                 # DataFrame: moment_id, track_id, emb_row, source
    Z: "object"
    X: "object"
    x_row: dict
    S1: "object"
    clap: "object" = None
    clap_row: dict = field(default_factory=dict)

    @classmethod
    def open(cls, data_dir: Path) -> "Board":
        import numpy as np
        import pandas as pd

        d = Path(data_dir)
        for name in ("moments.parquet", "moments_emb.npy", "features_v1.npz", "stage1_section.npy"):
            if not (d / name).exists():
                raise VectorError(f"색인에 {name} 이 없다: {d} — 전체 색인(<artifact>/data/proc/mupid)을 가리킨다")
        mom = pd.read_parquet(d / "moments.parquet")
        if "source" not in mom.columns:
            mom["source"] = "flow"
        feats = np.load(d / "features_v1.npz", allow_pickle=False)
        x_row = {str(m): i for i, m in enumerate(feats["moment_id"])}
        clap, clap_row = None, {}
        if (d / "clap_moments.npz").exists():
            c = np.load(d / "clap_moments.npz", allow_pickle=False)
            clap = np.asarray(c["embeddings"], dtype=np.float32)
            clap_row = {str(m): i for i, m in enumerate(c["moment_ids"])}
        return cls(d, mom, np.load(d / "moments_emb.npy", mmap_mode="r"), feats["X"], x_row,
                   np.load(d / "stage1_section.npy", mmap_mode="r"), clap, clap_row)

    def rows_of(self, track_ids: Iterable[str]):
        want = {str(t) for t in track_ids}
        return self.moments[self.moments.track_id.astype(str).isin(want)]


def _section_index(moment_id: str) -> int:
    tail = str(moment_id).rsplit("#", 1)
    return int(tail[1]) if len(tail) == 2 and tail[1].isdigit() else -1


# ───────────────────────────────────────────────────────────── μ 동결

def freeze_mu(board: Board, music_id_of: Callable[[str], Optional[int]], out: Path) -> dict:
    """등록 곡의 구간 임베딩 평균을 `out`(mu.npy) 에 쓰고, 옆에 `mu.json` 으로 근거를 남긴다.

    **이미 있으면 덮지 않는다.** 동결 값이 바뀌면 Qdrant 전체를 다시 넣어야 한다(§6.3) — 조용히
    바뀌지 않게, 다시 재려면 사람이 파일을 지운다.
    """
    import numpy as np

    out = Path(out)
    if out.exists():
        raise VectorError(f"{out} 가 이미 있다 — μ 는 동결 값이다. 다시 재려면 지우고 전체 재적재를 계획한다")
    mom = board.moments
    tids = mom.track_id.astype(str)
    registered = tids.map(lambda t: music_id_of(t) is not None).to_numpy(dtype=bool)
    rows = mom.emb_row.to_numpy()[registered]
    if not len(rows):
        raise VectorError("registered.json 에 번호가 있는 곡의 구간이 색인에 하나도 없다")
    Z = np.asarray(board.Z[np.sort(rows)], dtype=np.float64)
    mu = Z.mean(0).astype(np.float32)
    pool = mom[mom.source.astype(str) == "flow"].emb_row.to_numpy()
    pool_mu = np.asarray(board.Z[np.sort(pool)], dtype=np.float64).mean(0) if len(pool) else mu
    out.parent.mkdir(parents=True, exist_ok=True)
    np.save(out, mu)
    meta = {
        "embedding_version": EMB_VERSION,
        "dim": int(mu.shape[0]),
        "sections": int(len(rows)),
        "tracks": int(tids[registered].nunique()),
        "basis": "registered.json 의 곡(삭제 포함) 구간 중 임베딩이 있는 것",
        "sha256": hashlib.sha256(out.read_bytes()).hexdigest(),
        "board": str(board.data_dir),
        # ai_server 가 mu.npy 없이 쓰는 판의 풀 평균과의 거리. 0 에 가까우면 묶음 전에도 같은 μ 로 읽는다.
        "pool_mean_l2_diff": float(np.linalg.norm(pool_mu - mu)),
        "frozen_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
    }
    out.with_suffix(".json").write_text(json.dumps(meta, ensure_ascii=False, indent=2) + "\n",
                                        encoding="utf-8", newline="\n")
    return meta


def load_mu(path: Path):
    import numpy as np

    p = Path(path)
    if not p.exists():
        raise VectorError(f"동결 μ 가 없다: {p} — 먼저 `vectors freeze-mu` 로 한 번 잰다")
    mu = np.asarray(np.load(p), dtype=np.float32).reshape(-1)
    if mu.shape[0] != MERT_DIM:
        raise VectorError(f"μ 차원이 {mu.shape[0]} 이다 — {MERT_DIM} 이어야 한다")
    return mu


# ───────────────────────────────────────────────────────────── 본문

def track_body(board: Board, track_rows, mu, analysis_version: str, model_version: str) -> dict:
    """곡 하나의 `PUT …/vectors` 본문. 구간은 순번 오름차순 — MySQL 에 등록한 순서다."""
    import numpy as np

    rows = track_rows.assign(_i=track_rows.moment_id.map(_section_index)).sort_values("_i")
    sections = []
    for m in rows.itertuples(index=False):
        mid = str(m.moment_id)
        if mid not in board.x_row:
            raise VectorError(f"{mid}: features_v1.npz 에 없다 — 색인이 어긋났다")
        W = np.asarray(board.Z[int(m.emb_row)], dtype=np.float32) - mu
        r = float(np.linalg.norm(W))
        if not r > 0:
            raise VectorError(f"{mid}: ‖Z-μ‖ 가 0 이다")
        xr = board.x_row[mid]
        line = {
            "external_id": mid,
            "r": r,
            "mert": b64(W / r),
            "feat_v1": b64(board.X[xr]),
            "stage1": {model_version: b64(board.S1[xr])},
        }
        if mid in board.clap_row:
            line["clap"] = b64(board.clap[board.clap_row[mid]])
        sections.append(line)
    source = str(rows.source.iloc[0]) if len(rows) else "flow"
    return {"analysis_version": analysis_version, "emb_version": EMB_VERSION,
            "feat_version": FEAT_VERSION, "source": source, "sections": sections}


@dataclass
class Plan:
    """곡 번호 → 원본 track_id. 보내기 전에 센다."""

    tracks: dict[int, str] = field(default_factory=dict)
    unregistered: int = 0


def plan(board: Board, music_id_of: Callable[[str], Optional[int]],
         only: Optional[set[int]] = None) -> Plan:
    out = Plan()
    for tid in sorted(board.moments.track_id.astype(str).unique()):
        mid = music_id_of(tid)
        if mid is None:
            out.unregistered += 1
            continue
        if only is not None and int(mid) not in only:
            continue
        out.tracks[int(mid)] = tid
    return out


# ───────────────────────────────────────────────────────────── 보내기

def _busy(error) -> bool:
    status = getattr(error, "status", None)
    return status == 429 or (isinstance(status, int) and status >= 500)


def _with_retry(call, sleep=time.sleep):
    for attempt in range(BUSY_RETRIES):
        try:
            return call()
        except Exception as e:                      # noqa: BLE001 — 아래에서 가른다
            if not _busy(e) or attempt == BUSY_RETRIES - 1:
                raise
            sleep(min(30, 2 ** attempt))
    raise AssertionError("unreachable")


def send(client, board: Board, p: Plan, mu, analysis_version: str = "v1",
         model_version: str = "v1", sleep=time.sleep, progress=None) -> dict:
    """곡마다 `PUT /api/v1/music/{id}/vectors`. 한 곡의 4xx 는 세고 다음 곡으로 간다.

    - 409 (구간 수·순서·분석 버전 불일치) — 그 곡의 구간을 먼저 다시 등록해야 한다
    - 410 (삭제된 곡) — 건너뛴다. 대조가 Qdrant 에서 그 곡을 지운다
    - 429·5xx — 기다렸다 같은 본문을 다시 보낸다(멱등)
    """
    from .api_client import is_music_gone

    sent, gone, failed = 0, [], []
    for mid, tid in sorted(p.tracks.items()):
        try:
            body = track_body(board, board.rows_of([tid]), mu, analysis_version, model_version)
            _with_retry(lambda: client.put_vectors(mid, body), sleep=sleep)
            sent += 1
        except Exception as e:                      # noqa: BLE001 — 곡 하나 때문에 멈추지 않는다
            if is_music_gone(e):
                gone.append(mid)
            else:
                failed.append({"music_id": mid, "track_id": tid,
                               "error": f"{type(e).__name__}: {e}"[:300]})
        if progress:
            progress(mid)
    return {"sent": sent, "gone": gone, "failed": failed}


def send_stage1(client, board: Board, music_id_of: Callable[[str], Optional[int]], version: str,
                vectors=None, sleep=time.sleep) -> dict:
    """모델 재계산 — 등록 곡 전 구간의 `stage1_{version}` 을 1,000구간씩 보낸다.

    `vectors` 는 새 모델이 만든 구간 벡터(`X` 와 같은 행, 기본은 판의 `stage1_section.npy`). 채택 전
    버전은 JupyterHub 의 학습 산출물을 직접 읽는다(설계 §6.3 "아직 채택되지 않은 v{N+1} 의 재계산").
    """
    S = board.S1 if vectors is None else vectors
    ids = [str(m) for m, t in zip(board.moments.moment_id, board.moments.track_id)
           if music_id_of(str(t)) is not None and str(m) in board.x_row]
    sent = 0
    for a in range(0, len(ids), STAGE1_BATCH):
        chunk = ids[a:a + STAGE1_BATCH]
        lines = [{"external_id": mid, "vector": b64(S[board.x_row[mid]])} for mid in chunk]
        _with_retry(lambda: client.put_stage1_vectors(version, lines), sleep=sleep)
        sent += len(chunk)
    return {"sent": sent, "batches": (len(ids) + STAGE1_BATCH - 1) // STAGE1_BATCH}
