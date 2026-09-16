"""MERT 구간 임베딩 — `moments_emb.npy` 를 만든 규약 그대로.

정본은 GPU 박스의 `~/rec_mupid/src/s24_mert.py` 와 `m14_local_tracks.py` 의 `Mert` 다.
값의 근거와 재현 실측은 `ai_server/docs/EMBEDDING-RECIPE.md` 에 있다.

**해석하지 말고 값을 옮긴다.** 층을 하나만 고르거나, `std` 를 분산으로 바꾸거나,
정규화 순서를 뒤집으면 신곡만 다른 공간에 놓인다. 오류는 나지 않고 추천 품질만 떨어진다.

    앞 768 = H[a:b].mean(0)     구간 동안의 평균
    뒤 768 = H[a:b].std(0)      구간 동안의 표준편차
    Zn = (V - mu) / sd  →  1536차 전체 행 L2
"""
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Sequence

import numpy as np

MODEL = "m-a-p/MERT-v1-95M"

# 허브의 최신을 그때그때 받으면 같은 코드로도 다른 임베딩이 나온다. 그래서 박는다.
#
# **이 값은 GPU 박스 허브 캐시(refs/main)에서 역추정한 것**이지 생성 당시 기록이 아니다.
# 재현 시험(tests/test_analysis_pipeline.py)을 통과하면 그때 확정된 것으로 본다.
REVISION = "12af15fef9d0ac838c3f475bfbbf26d2060dd4f5"

SR = 24_000              # librosa.load(sr=24000, mono=True)
CHUNK = 20 * SR          # 20초씩 forward 하고 이어 붙인다
HZ = 75.0                # 24kHz 기준 MERT-v1 프레임레이트
MIN_SEC_S = 3.0          # 이보다 짧은 구간은 버린다
EMB_DIM = 1536           # 768(mean) + 768(std)

ZSTATS_NAME = "moment_zstats.npz"


class ArtifactMissing(RuntimeError):
    """정규화 통계가 없다. **새로 계산하지 않는다.**"""


@dataclass(frozen=True)
class Segment:
    """구간 하나. 초 단위 경계와 라벨."""

    label: str
    start: float
    end: float


def keep_segments(segs: Sequence[Segment]) -> list[Segment]:
    """분할기 출력에서 쓸 구간만 남긴다.

    `start`·`end` 라벨은 곡의 양끝 표시라 구간이 아니고, 3초 미만은 버린다.
    **이 규칙은 분할·임베딩·특징이 같은 값을 써야 한다** — 한 곳만 다르면 구간 수가
    어긋나 색인 정렬이 깨진다.
    """
    return [s for s in segs
            if str(s.label) not in ("start", "end") and s.end - s.start >= MIN_SEC_S]


class Embedder:
    """모델을 **한 번만** 올리고 재사용한다.

    곡마다 다시 올리면 적재(6초)가 추론(CPU 5초)보다 비싸진다. 배치가 곡 여러 개를
    도는 전제이고, K8s CronJob 도 한 프로세스에서 전부 처리한다.
    """

    def __init__(self, data_dir: Path, *, device: Optional[str] = None) -> None:
        self.mu, self.sd = _load_zstats(Path(data_dir))
        self._device = device
        self._model = None
        self._torch = None

    # ------------------------------------------------------------------ 적재

    def _load(self):
        if self._model is not None:
            return
        import torch
        from transformers import AutoModel

        self._torch = torch
        want = self._device or ("cuda:0" if torch.cuda.is_available() else "cpu")
        dev = torch.device(want)
        m = AutoModel.from_pretrained(
            MODEL, revision=REVISION, trust_remote_code=True).to(dev).eval()
        if dev.type == "cuda":
            # 원본이 GPU 에서 half 로 돌렸다. 값을 맞추려면 여기도 half 다.
            m = m.half()
        self._model, self.device = m, dev

    # ---------------------------------------------------------------- 프레임

    def frames(self, y: np.ndarray) -> np.ndarray:
        """(T,) 파형 → (n_frames, 768). **13벌 hidden_state 를 통째로 평균낸다.**"""
        self._load()
        torch = self._torch
        dt = torch.float16 if self.device.type == "cuda" else torch.float32
        outs = []
        with torch.no_grad():
            for i in range(0, len(y), CHUNK):
                seg = np.asarray(y[i:i + CHUNK], dtype=np.float32)
                if len(seg) < SR:                       # 꼬리가 1초 미만이면 채운다
                    seg = np.pad(seg, (0, SR - len(seg)))
                x = torch.tensor(seg, dtype=dt, device=self.device).unsqueeze(0)
                o = self._model(x, output_hidden_states=True)
                h = torch.stack(o.hidden_states, 0).mean(0).squeeze(0)
                outs.append(h.float().cpu())
        return torch.cat(outs, 0).numpy()

    # ------------------------------------------------------------------ 풀링

    @staticmethod
    def pool(H: np.ndarray, start: float, end: float) -> np.ndarray:
        """구간 하나 → 1536차. 인덱스 계산까지 원본과 같아야 한다."""
        a = int(round(start * HZ))
        b = int(round(end * HZ))
        b = min(max(b, a + 1), len(H))
        a = min(a, len(H) - 1)
        blk = H[a:b]
        return np.concatenate([blk.mean(0), blk.std(0)])

    # ---------------------------------------------------------------- 정규화

    def normalize(self, V: np.ndarray) -> np.ndarray:
        """`(V - mu) / sd` 뒤 1536차 전체 행 L2. **순서를 뒤집지 않는다.**"""
        A = np.atleast_2d(np.asarray(V, dtype=np.float32))
        if A.shape[1] != EMB_DIM:
            raise ValueError(f"임베딩 차원이 다르다: {A.shape[1]} vs {EMB_DIM}")
        Z = (A - self.mu) / self.sd
        return (Z / np.clip(np.linalg.norm(Z, axis=1, keepdims=True), 1e-9, None)
                ).astype(np.float32)

    # -------------------------------------------------------------- 한 곡 처리

    def embed(self, y: np.ndarray, segments: Sequence[Segment]) -> np.ndarray:
        """파형 + 구간 목록 → (n, 1536) 정규화된 임베딩."""
        if not segments:
            return np.zeros((0, EMB_DIM), dtype=np.float32)
        H = self.frames(y)
        V = np.asarray([self.pool(H, s.start, s.end) for s in segments],
                       dtype=np.float32)
        return self.normalize(V)


def _load_zstats(data_dir: Path) -> tuple[np.ndarray, np.ndarray]:
    """기존 mu/sd 를 읽는다. **없으면 멈춘다 — 새로 계산하지 않는다.**

    신곡을 제 분포로 정규화하면 그 곡만 다른 공간에 놓인다. side 통계를 동결한 것과
    같은 이유이고, 증상도 같다 — 오류 없이 추천 품질만 떨어진다.
    """
    p = Path(data_dir) / ZSTATS_NAME
    if not p.exists():
        raise ArtifactMissing(
            f"정규화 통계가 없다: {p}\n"
            f"  기존 색인을 만든 mu/sd 여야 한다. 새로 계산하면 신곡만 다른 공간에 놓인다.\n"
            f"  배포 묶음에 넣는다 — infra/AI-SERVER-K8S-HANDOVER.md 참조.")
    with np.load(p, allow_pickle=False) as z:
        mu = np.asarray(z["mu"], dtype=np.float32)
        sd = np.asarray(z["sd"], dtype=np.float32)
    if mu.shape != (EMB_DIM,) or sd.shape != (EMB_DIM,):
        raise ArtifactMissing(f"통계 모양이 다르다: mu{mu.shape} sd{sd.shape}")
    return mu, sd


def load_audio(path: Path) -> np.ndarray:
    """SR 24000 · mono · float32. 원본과 같은 적재 규약이다."""
    import librosa

    y, _ = librosa.load(str(path), sr=SR, mono=True)
    return np.asarray(y, dtype=np.float32)
