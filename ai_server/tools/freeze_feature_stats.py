"""`features_v1.npz` 를 만든 그 표에서 z-score 통계를 뽑아 동결한다.

    MUPID_AI_ARTIFACT_DIR=/경로 python -m ai_server.tools.freeze_feature_stats

## 왜 따로 내보내는가

`features.side_features()` 의 `_z()` 는 **넘긴 표 전체**로 평균·표준편차를 잡는다.
그래서 신곡을 그냥 얹으면 두 갈래 모두 틀린다.

  · 신곡을 섞어 다시 만든다 → 기존 10,007구간의 값이 **전부** 바뀐다. 모델은 지금
    분포로 학습됐으므로 입력이 통째로 어긋난다.
  · 신곡만 따로 계산한다 → 그 곡 안에서의 z 가 되어 아예 다른 값이 된다.

어느 쪽이든 오류는 나지 않고 추천만 이상해진다. 그래서 지금 분포를 파일로 굳혀 두고
신곡은 **그 통계를 읽어 쓰기만** 한다.

동결 대상은 `_z()` 를 타는 6개다 — `rms` 와 트랙 레벨 5개(`bpm` · `centroid` ·
`rolloff` · `rms_mean` · `flatness`). `rms_pct` 는 곡 안에서의 순위라 신곡에도 그대로
계산되므로 동결하지 않는다.

## 쓰지 않는 것

`features_v1.npz` 를 **덮어쓰지 않는다.** 그 파일은 PCA 기저(`pca_mean`·`pca_comp`)와
기존 285차 행렬을 들고 있어서, 한 번 어긋나면 되돌릴 근거가 사라진다. 통계는 별도
파일로 내보내고 `features_info.json` 에 어느 판에서 뽑았는지만 적는다.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from ai_server.app import paths
from ai_server.app.features import FEATURE_VERSION, TRACK_COLS

STATS_NAME = "feature_stats_v1.npz"


def sha256(p: Path) -> str:
    h = hashlib.sha256()
    with p.open("rb") as f:
        for blk in iter(lambda: f.read(1 << 20), b""):
            h.update(blk)
    return h.hexdigest()


def moments_stats(mom: pd.DataFrame, content: pd.DataFrame) -> dict[str, tuple[float, float]]:
    """`side_features()` 의 `_z()` 가 보는 것과 **같은 벡터**로 평균·표준편차를 잡는다.

    트랙 레벨 값도 곡 단위가 아니라 **구간 단위로 펼친 뒤** 통계를 낸다. 구간이 많은 곡이
    그만큼 더 반영된다 — 원본이 그렇게 계산하므로 여기서 곡 단위로 바꾸면 값이 달라진다.
    """
    c = content.set_index(content.track_id.astype(str))
    out: dict[str, tuple[float, float]] = {}

    rms = (mom.rms.to_numpy(dtype=np.float64) if "rms" in mom.columns
           else np.zeros(len(mom), dtype=np.float64))
    out["rms"] = _mean_std(rms)

    for col in TRACK_COLS:
        s = c[col] if col in c.columns else pd.Series(dtype=float)
        v = mom.track_id.map(s).to_numpy(dtype=np.float64)
        out[f"trk_{col}"] = _mean_std(v)
    return out


def _mean_std(v: np.ndarray) -> tuple[float, float]:
    """`_z()` 와 같은 규칙. 표준편차가 0 에 가까우면 1.0 으로 둔다(0 나눗셈 방지)."""
    m = float(np.nanmean(v))
    s = float(np.nanstd(v))
    return m, (s if s > 1e-9 else 1.0)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--out", default=None, help=f"기본: <산출물>/{STATS_NAME}")
    ap.add_argument("--force", action="store_true", help="이미 있어도 다시 쓴다")
    a = ap.parse_args()

    d = paths.data_dir()
    feat = d / "features_v1.npz"
    mom_p, cont_p = d / "moments.parquet", d / "content.parquet"
    for p in (feat, mom_p, cont_p):
        if not p.exists():
            print(f"산출물이 없다: {p}", file=sys.stderr)
            return 1

    out = Path(a.out) if a.out else d / STATS_NAME
    if out.exists() and not a.force:
        print(f"이미 있다: {out}  (--force 로 다시 쓴다)")
        return 0

    mom = pd.read_parquet(mom_p)
    content = pd.read_parquet(cont_p)
    stats = moments_stats(mom, content)

    # 열 이름 순서까지 함께 굳힌다. 순서가 바뀌면 오차 없이 엉뚱한 열이 모델에 들어간다.
    with np.load(feat, allow_pickle=False) as fz:
        side_cols = [str(x) for x in fz["side_cols"]]
        n_rows, n_dim = fz["X"].shape

    keys = list(stats)
    np.savez(
        out,
        keys=np.array(keys, dtype="<U24"),
        mean=np.array([stats[k][0] for k in keys], dtype=np.float64),
        std=np.array([stats[k][1] for k in keys], dtype=np.float64),
        side_cols=np.array(side_cols, dtype="<U24"),
        n_moments=np.int64(len(mom)),
        feature_version=np.array(FEATURE_VERSION, dtype="<U16"),
    )

    info_p = d / "features_info.json"
    info = json.loads(info_p.read_text(encoding="utf-8")) if info_p.exists() else {}
    info["feature_version"] = FEATURE_VERSION
    info["feature_stats"] = STATS_NAME
    # 어느 판에서 뽑았는지 남긴다. 색인을 다시 만들면 이 값이 달라지므로,
    # 통계와 특징 행렬이 갈라진 상태를 조회만으로 알아챌 수 있다.
    info["features_sha256"] = sha256(feat)
    info["features_rows"] = int(n_rows)
    info["features_dim"] = int(n_dim)
    info_p.write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n",
                      encoding="utf-8")

    print(f"동결 {len(keys)}쌍 → {out}")
    print(f"{'키':<16}{'mean':>18}{'std':>18}")
    for k in keys:
        m, s = stats[k]
        print(f"{k:<16}{m:>18.9g}{s:>18.9g}")
    print(f"\n근거: {mom_p.name} {len(mom):,}행 · {cont_p.name} {len(content):,}행")
    print(f"features_v1.npz  {n_rows:,}×{n_dim}  sha256={info['features_sha256'][:16]}…")
    return 0


if __name__ == "__main__":
    sys.exit(main())
