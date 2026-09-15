"""구간 특징 — Stage 1/2 와 서비스가 공유하는 입력 구성.

  X = [ MERT PCA 256 (동결) | side 특징 ]

PCA 는 라벨을 쓰지 않는 비지도 축소지만, 엄격하게 **학습 곡의 구간만으로 적합**한다.
side 특징은 사용자와 무관해야 한다(Stage 1 의 구간 타워는 카탈로그를 1회 인코딩해
색인하므로). 사용자×구간 특징은 Stage 2 에서만 쓴다.

  python src/m10_features.py --fit      # PCA 적합 + 특징 캐시 생성
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

# 산출물 경로는 recommender 와 같은 환경변수를 쓴다. 서로 다른 곳을 보면
# 색인 일부만 다른 판이 섞여도 서비스가 정상처럼 뜬다.
import os as _os
ROOT = Path(_os.environ.get("MUPID_AI_ARTIFACT_DIR",
                            Path(__file__).resolve().parents[2] / "artifacts"))
OUT_DIR = ROOT / "data" / "proc" / "mupid"
FEAT = OUT_DIR / "features_v1.npz"

FEATURE_VERSION = "feat-v1"
PCA_DIM = 256
POS_BINS = 10
LABELS = ["intro", "verse", "chorus", "bridge", "inst", "solo", "outro", "other"]
TRACK_COLS = ["bpm", "centroid", "rolloff", "rms_mean", "flatness"]


def _z(v: np.ndarray) -> np.ndarray:
    v = np.asarray(v, dtype=np.float64)
    m, s = np.nanmean(v), np.nanstd(v)
    return np.nan_to_num((v - m) / (s if s > 1e-9 else 1.0))


def side_features(mom: pd.DataFrame, content: pd.DataFrame) -> tuple[np.ndarray, list[str]]:
    """사용자 무관 구간 특징."""
    c = content.set_index(content.track_id.astype(str))
    n = len(mom)
    rel_start = mom.rel_start.to_numpy(dtype=np.float64)
    rel_end = mom.rel_end.to_numpy(dtype=np.float64)
    rel_c = (rel_start + rel_end) / 2
    dur = mom.dur_sec.to_numpy(dtype=np.float64)
    rms = mom.rms.to_numpy(dtype=np.float64) if "rms" in mom.columns else np.zeros(n)
    rms_pct = mom.assign(_r=rms).groupby("track_id")._r.rank(pct=True).to_numpy()

    cols = ["rel_start", "rel_end", "rel_center", "log_dur", "rms_pct", "rms_z"]
    blocks = [rel_start, rel_end, rel_c, np.log1p(dur), np.nan_to_num(rms_pct, nan=0.5), _z(rms)]

    pb = np.clip((rel_c * POS_BINS).astype(int), 0, POS_BINS - 1)
    onehot_pos = np.zeros((n, POS_BINS))
    onehot_pos[np.arange(n), pb] = 1.0
    cols += [f"pos{i}" for i in range(POS_BINS)]

    lab = mom.section_label.astype(str).to_numpy()
    onehot_lab = np.zeros((n, len(LABELS)))
    idx = {L: i for i, L in enumerate(LABELS)}
    for i, L in enumerate(lab):
        onehot_lab[i, idx.get(L, idx["other"])] = 1.0
    cols += [f"lab_{L}" for L in LABELS]

    tvals = []
    for col in TRACK_COLS:
        s = c[col] if col in c.columns else pd.Series(dtype=float)
        v = mom.track_id.map(s).to_numpy(dtype=np.float64)
        tvals.append(_z(v))
    cols += [f"trk_{c_}" for c_ in TRACK_COLS]

    X = np.column_stack(blocks + [onehot_pos, onehot_lab] + tvals)
    return np.nan_to_num(X).astype(np.float32), cols


def fit(train_track_ids: set[str] | None = None) -> dict:
    mom = pd.read_parquet(OUT_DIR / "moments.parquet")
    if "source" not in mom.columns:
        mom["source"] = "fma"
    content = pd.read_parquet(OUT_DIR / "content.parquet")
    Z = np.load(OUT_DIR / "moments_emb.npy", mmap_mode="r")

    rows = mom.emb_row.to_numpy()
    if train_track_ids is None:                    # 기본: FMA 만으로 적합 (local 은 평가·서비스용)
        mask = (mom.source == "fma").to_numpy()
    else:
        mask = mom.track_id.isin(train_track_ids).to_numpy()
    fit_rows = rows[mask]
    print(f"PCA 적합 구간 {len(fit_rows):,} / 전체 {len(mom):,}")

    A = np.asarray(Z[np.sort(fit_rows)], dtype=np.float32)
    mu = A.mean(0)
    A = A - mu
    # 표본 55k × 1536 → 공분산 고유분해가 SVD 보다 싸다
    C = (A.T @ A) / max(len(A) - 1, 1)
    w, V = np.linalg.eigh(C.astype(np.float64))
    order = np.argsort(-w)[:PCA_DIM]
    comp = V[:, order].astype(np.float32)          # (1536, 256)
    evr = float(w[order].sum() / max(w.sum(), 1e-12))
    print(f"PCA {PCA_DIM}차원 설명분산 {100*evr:.1f}%")

    S, cols = side_features(mom, content)
    P = (np.asarray(Z, dtype=np.float32) - mu) @ comp
    P /= np.clip(np.linalg.norm(P, axis=1, keepdims=True), 1e-9, None)
    X = np.concatenate([P[rows], S], axis=1).astype(np.float32)

    np.savez(FEAT, X=X, moment_id=mom.moment_id.astype(str).to_numpy(dtype='<U80'), pca_mean=mu,
             pca_comp=comp, side_cols=np.array(cols, dtype='<U24'), evr=np.float32(evr))
    (OUT_DIR / "features_info.json").write_text(json.dumps({
        "version": FEATURE_VERSION, "pca_dim": PCA_DIM, "side_dim": S.shape[1],
        "total_dim": int(X.shape[1]), "explained_variance_ratio": evr,
        "pca_fit_on": "fma" if train_track_ids is None else "train_tracks",
        "side_cols": cols,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"특징 {X.shape} (PCA {PCA_DIM} + side {S.shape[1]}) -> {FEAT}")
    return {"X": X}


def load() -> tuple[np.ndarray, dict[str, int], dict]:
    d = np.load(FEAT, allow_pickle=False)
    idx = {str(m): i for i, m in enumerate(d["moment_id"])}
    info = json.loads((OUT_DIR / "features_info.json").read_text(encoding="utf-8"))
    return d["X"], idx, info


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--fit", action="store_true")
    a = ap.parse_args()
    if a.fit or not FEAT.exists():
        fit()
    else:
        X, idx, info = load()
        print(f"[캐시] {X.shape} | {json.dumps({k: v for k, v in info.items() if k != 'side_cols'}, ensure_ascii=False)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
