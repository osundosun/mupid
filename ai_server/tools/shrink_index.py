"""색인에서 서비스하지 않는 블록을 떼어 **새 판**을 만든다.

    MUPID_AI_ARTIFACT_DIR=/경로 python -m ai_server.tools.shrink_index --keep-tracks T

색인은 네 블록(`fma` · `local` · `flow` · `jamendo`)이 `emb_row` 순서로 이어 붙은
한 덩어리인데, 유사도 계산은 추천 풀 행만 쓴다(`recommender` 의 `c.Z[c.pool_rows]`).
`fma` 55,232행은 PCA 기저를 적합할 때만 쓰였고 그 기저는 `features_info.json` 의
`pca_fit_on` 으로 이미 동결됐다. 그래서 **행을 빼도 계산이 달라지지 않는다.**
정말 그런지는 도구가 아니라 시험이 판정한다(`test_shrink_index.py`).

## 제자리에서 고치지 않는다

`data/proc/mupid_v<stamp>` 를 옆에 만들고 **교체하지 않는다.** 교체는 사람이 한다 —
서비스가 기동 때 색인을 통째로 메모리에 올리므로 재기동 계획과 함께 가야 한다.

## 다시 계산하는 것은 하나도 없다

임베딩 · 285차 · 구간벡터는 **행 복사**다. PCA 기저(`pca_mean` · `pca_comp` ·
`side_cols` · `evr`)와 동결 통계(`feature_stats_v1.npz` · `moment_zstats.npz`)는
그대로 옮긴다. 남은 곡의 값이 한 비트라도 바뀌면 그때부터는 다른 모델이다.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

from ai_server.app import paths

# 행 단위로 자르는 것과, 곡 단위로 자르는 것.
ROW_FILES = ("moments.parquet", "moments_emb.npy", "features_v1.npz",
             "stage1_section.npy")
TRACK_FILES = ("content.parquet", "flow_meta.parquet")
# 손대지 않고 그대로 옮기는 것. **동결 통계와 모델이 여기 있다.**
CARRY = ("feature_stats_v1.npz", "moment_zstats.npz", "stage1_weights.npz",
         "stage1.pt", "logistic_flow_stage1.json", "labels_service.parquet")
CARRY_DIRS = ("logs", "serve")
BLOCK = 4096


def log(msg: str) -> None:
    print(f"[shrink] {msg}", flush=True)


def keep_mask(mom: pd.DataFrame, sources: set[str], pattern: str) -> pd.Series:
    """남길 순간. 출처와 `track_id` 모양 **둘 다** 맞아야 한다.

    `flow` 안에도 `T000847_1` 처럼 DB 에 없는 곡이 섞여 있다. 출처만 보면 그 곡이
    남아 추천에 뽑히고, Spring 이 `external_id` 로 되돌리지 못한다.
    """
    tid = mom.track_id.astype(str)
    return mom.source.isin(sources) & tid.str.match(pattern)


def shrink(src: Path, dst: Path, sources: set[str], pattern: str) -> dict:
    if dst.exists():
        raise SystemExit(f"새 판 경로가 이미 있다: {dst}")
    dst.mkdir(parents=True)

    mom = pd.read_parquet(src / "moments.parquet")
    n0 = len(mom)
    mask = keep_mask(mom, sources, pattern)
    rows = mom.emb_row.to_numpy()[mask.to_numpy()]
    if not np.array_equal(rows, np.sort(rows)):
        raise SystemExit("남길 emb_row 가 오름차순이 아니다 — 순서를 먼저 확인한다")
    kept = mom[mask].copy()
    n1 = len(kept)
    log(f"순간 {n0} → {n1} · 곡 {mom.track_id.nunique()} → {kept.track_id.nunique()}")

    # ── moments: emb_row 를 0..N-1 로 다시 매긴다. 다른 열은 손대지 않는다.
    old_ids = list(kept.moment_id.astype(str))
    kept = kept.reset_index(drop=True)
    kept["emb_row"] = np.arange(n1, dtype=mom.emb_row.dtype)
    kept.to_parquet(dst / "moments.parquet", index=False)

    # ── 임베딩 · 구간벡터: 블록 단위 복사. 402MB 를 통째로 올리지 않는다.
    for name in ("moments_emb.npy", "stage1_section.npy"):
        old = np.load(src / name, mmap_mode="r")
        if old.shape[0] != n0:
            raise SystemExit(f"{name} 행 수 {old.shape[0]} != moments {n0}")
        new = np.lib.format.open_memmap(dst / name, mode="w+", dtype=old.dtype,
                                        shape=(n1, old.shape[1]))
        for i in range(0, n1, BLOCK):
            j = min(i + BLOCK, n1)
            new[i:j] = old[rows[i:j]]
        new.flush()
        del new, old
        log(f"  {name} {n1}행")

    # ── 285차: X 와 moment_id 를 **같은 순서로** 자르고 기저는 그대로 옮긴다.
    with np.load(src / "features_v1.npz", allow_pickle=False) as fz:
        ids = np.asarray([str(m) for m in fz["moment_id"]])
        if len(ids) != n0:
            raise SystemExit(f"features moment_id {len(ids)} != moments {n0}")
        if list(ids[rows]) != old_ids:
            raise SystemExit("features 의 moment_id 차례가 moments 와 어긋난다")
        X = np.asarray(fz["X"], dtype=np.float32)[rows]
        carry = {k: np.asarray(fz[k]) for k in fz.files if k not in ("X", "moment_id")}
    np.savez(dst / "features_v1.npz", X=X,
             moment_id=np.array(old_ids, dtype="<U80"), **carry)
    log(f"  features_v1.npz {X.shape} · 기저 {sorted(carry)} 그대로")

    # ── 곡 단위 두 표.
    tracks = set(kept.track_id.astype(str))
    for name in TRACK_FILES:
        df = pd.read_parquet(src / name)
        out = df[df.track_id.astype(str).isin(tracks)].reset_index(drop=True)
        out.to_parquet(dst / name, index=False)
        log(f"  {name} {len(df)} → {len(out)}행")

    # ── CLAP 은 순간 일부만 있다. 있는 것만 남긴다.
    clap = src / "clap_moments.npz"
    if clap.exists():
        with np.load(clap, allow_pickle=False) as cz:
            cid = np.asarray([str(x) for x in cz["moment_ids"]])
            sel = np.isin(cid, np.array(old_ids))
            np.savez(dst / "clap_moments.npz", moment_ids=cid[sel],
                     embeddings=np.asarray(cz["embeddings"])[sel],
                     model=cz["model"])
        log(f"  clap_moments.npz {len(cid)} → {int(sel.sum())}행")

    for name in CARRY:
        p = src / name
        if p.exists():
            shutil.copy2(p, dst / name)
    for sub in CARRY_DIRS:
        if (src / sub).is_dir():
            shutil.copytree(src / sub, dst / sub, dirs_exist_ok=True)

    # ── features_info: 행 수와 해시만 고친다. **기저 항목은 건드리지 않는다.**
    info = json.loads((src / "features_info.json").read_text(encoding="utf-8"))
    info["features_rows"] = int(n1)
    info["features_sha256"] = _sha256(dst / "features_v1.npz")
    (dst / "features_info.json").write_text(
        json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    return {"rows_before": n0, "rows_after": n1,
            "tracks_after": int(kept.track_id.nunique()), "new_dir": str(dst)}


def _sha256(p: Path) -> str:
    import hashlib

    h = hashlib.sha256()
    with p.open("rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="색인 축소 (새 판을 만들고 교체하지 않는다)")
    ap.add_argument("--sources", default="flow", help="남길 moments.source (쉼표)")
    ap.add_argument("--track-pattern", default=r"^T\d{6}$",
                    help="남길 track_id 모양. 기본은 DB 의 T000001 꼴")
    ap.add_argument("--out", default=None, help="새 판 경로. 비우면 mupid_v<stamp>")
    a = ap.parse_args(argv)

    src = paths.data_dir()
    if not (src / "moments.parquet").exists():
        print(f"색인이 없다: {src}", file=sys.stderr)
        return 1
    dst = Path(a.out) if a.out else src.parent / (
        f"{src.name}_v{datetime.now().strftime('%Y%m%d-%H%M%S')}")
    rep = shrink(src, dst, {s.strip() for s in a.sources.split(",") if s.strip()},
                 a.track_pattern)
    log(f"새 판 {rep['new_dir']} — 교체하지 않았다")
    print(json.dumps(rep, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
