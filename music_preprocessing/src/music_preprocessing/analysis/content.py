"""트랙 단위 특징 — `content.parquet` 의 열을 만드는 규약 그대로.

정본은 GPU 박스의 `~/rec_mupid/src/m02_content.py` 이고, 값을 그대로 옮겨 적었다.
`m17_flow_ingest` 도 flow 곡에 같은 함수를 썼다.

## 왜 배치가 직접 재는가

285차의 `trk_*` 다섯 열은 **동결 통계로 z-score 된다.** 그 통계는 여기서 나온 값들의
분포다 (`trk_bpm` 평균 109.644 · 표준편차 43.291 ≈ `content.bpm` 전체 108.963 / 43.172).
다른 방법으로 잰 값을 그 자리에 넣으면 **신곡만 다른 분포에 놓인다.** 오류는 나지 않고
추천 품질만 떨어진다.

특히 BPM 이 그렇다. 실측으로 flow 763곡 중 246곡의 측정 BPM 이 생성 요청 범위 밖이고
(평균 절대차 15.4 BPM), 옥타브 오류도 섞여 있다 — `T000002` 는 요청 114~122 인데 측정
57.4 다. **그 오류까지 포함한 분포가 색인이다.** 요청값(평균 111.0 · 표준편차 25.6)을
넣으면 신곡만 중앙으로 몰린다.

`bpm_requested` 는 **다른 자리**에서 쓴다 — `flow_meta` 의 `bpm_lo`·`bpm_hi` 와 추천기의
BPM 필터다. 거기서는 요청값이 맞다. 두 자리를 섞지 않는다.

## librosa 의 비트 추적기는 쓰지 않는다

원본 주석 그대로다 — `librosa.beat.beat_track` 이 segfault 한다(numpy ABI 불일치).
그래서 BPM 은 스펙트럼 플럭스의 자기상관으로 직접 구한다. numpy 만 쓴다.
"""
from __future__ import annotations

from dataclasses import asdict
from pathlib import Path
from typing import Optional

import numpy as np

from .embedder import SR
from .feature_space import TrackStats

BEAT_WINDOW_SEC = 90.0     # 곡 단위 특징은 **중앙 90초**만 본다 (원본과 같다)
N_MFCC = 13
N_FFT = 2048
HOP = 512
BPM_MIN, BPM_MAX = 50.0, 190.0


def bpm_from_stft(S: np.ndarray, sr: int = SR, hop: int = HOP,
                  bmin: float = BPM_MIN, bmax: float = BPM_MAX) -> float:
    """스펙트럼 플럭스의 자기상관에서 BPM 을 고른다. **numpy 전용.**

    `m02_content.bpm_from_stft` 를 그대로 옮겼다. 상수 하나만 달라져도 색인의 BPM 분포와
    어긋난다.
    """
    L = np.log1p(S)
    flux = np.maximum(0.0, np.diff(L, axis=1)).sum(0)
    if flux.size < 16:
        return 0.0
    flux = flux - flux.mean()
    sd = float(flux.std())
    if sd < 1e-9:
        return 0.0
    flux = flux / sd
    fps = sr / hop
    lag_min = max(1, int(round(60.0 / bmax * fps)))
    lag_max = min(flux.size - 1, int(round(60.0 / bmin * fps)))
    if lag_max <= lag_min:
        return 0.0
    ac = np.correlate(flux, flux, mode="full")[flux.size - 1:]
    lag = lag_min + int(np.argmax(ac[lag_min:lag_max + 1]))
    return float(60.0 * fps / lag)


def track_content(y: np.ndarray, *, with_mfcc: bool = True) -> dict:
    """24kHz mono 파형 → `content.parquet` 한 줄.

    `track_id` 는 넣지 않는다 — 호출자가 붙인다.
    """
    import librosa

    yf = np.asarray(y, dtype=np.float32)
    n = yf.shape[0]

    half = int(BEAT_WINDOW_SEC * SR / 2)
    c = n // 2
    yc = yf[max(0, c - half): min(n, c + half)]
    if yc.size < SR:                       # 90초가 안 되는 곡은 통째로 본다
        yc = yf

    S = np.abs(librosa.stft(yc, n_fft=N_FFT, hop_length=HOP))
    row = {
        "bpm": bpm_from_stft(S),
        # rms_mean 은 **곡 전체**다. 중앙 창이 아니다.
        "rms_mean": float(np.sqrt(np.mean(np.square(yf)) + 1e-12)),
        "rms_std": float(np.std(np.abs(yf))),
        "centroid": float(librosa.feature.spectral_centroid(S=S, sr=SR).mean()),
        "rolloff": float(librosa.feature.spectral_rolloff(S=S, sr=SR).mean()),
        "flatness": float(librosa.feature.spectral_flatness(S=S).mean()),
        "zcr": float(librosa.feature.zero_crossing_rate(yc).mean()),
        "dur_sec": n / SR,
        "error": "",
    }
    if with_mfcc:
        # 서빙은 mfcc 를 읽지 않지만, 신곡만 비면 나중에 쓰려 할 때 다시 돌려야 한다.
        mf = librosa.feature.mfcc(S=librosa.power_to_db(
            librosa.feature.melspectrogram(S=S ** 2, sr=SR)), n_mfcc=N_MFCC)
        row.update({f"mfcc_m{i}": float(v) for i, v in enumerate(mf.mean(1))})
        row.update({f"mfcc_s{i}": float(v) for i, v in enumerate(mf.std(1))})
    return row


def stats_from_content(row: dict) -> TrackStats:
    """`content` 한 줄 → 285차가 쓰는 다섯 값.

    **`bpm_requested` 를 보지 않는다.** 동결 통계가 측정 BPM 의 분포이기 때문이다 —
    이 파일 맨 위 설명을 본다.
    """
    return TrackStats(bpm=float(row.get("bpm") or 0.0),
                      centroid=float(row.get("centroid") or 0.0),
                      rolloff=float(row.get("rolloff") or 0.0),
                      rms_mean=float(row.get("rms_mean") or 0.0),
                      flatness=float(row.get("flatness") or 0.0))


__all__ = ["bpm_from_stft", "track_content", "stats_from_content",
           "BEAT_WINDOW_SEC", "N_MFCC", "N_FFT", "HOP"]
