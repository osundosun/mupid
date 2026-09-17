"""전체 색인 → 서빙용 색인. **추천 풀 행만 남긴다.**

    MUPID_AI_ARTIFACT_DIR=/경로/artifacts python -m ai_server.tools.slice_index \
        --out /경로/serving-artifacts

왜 필요한가
    색인 산출물이 537MB 인데 그중 `moments_emb.npy` 만 403MB 다. 그런데 **65,540행
    가운데 서빙이 쓰는 것은 10,007행뿐이다** — 나머지는 PCA 와 Stage 1 을 학습시킨
    FMA 곡이고, 추천 풀(`source ∈ {flow, jamendo}`)에 들어가지 않는다. 학습이 끝난
    뒤로는 서빙 이미지도 노드도 그 행을 읽지 않는다.

    자르면 약 80MB 가 된다. 파드에 색인을 밀어 넣는 일이 그만큼 가벼워진다.

값이 바뀌지 않는다
    이것이 이 도구의 전제다. 추천 계산은 이미 풀만 본다 —

      · `Z`(임베딩)는 `c.pool_rows` 로만 색인한다. 카탈로그 평균(`_pool_mean`)도
        `Z[pool_rows].mean(0)` 이라 풀 밖 행이 섞여 있어도 쓰이지 않는다.
      · `X`(특징)와 `ZS`(구간 벡터)는 `c.idx[moment_id]` 로만 닿는다. 그 moment_id 는
        라벨과 요청에서 오고, 둘 다 풀 안이다.
      · 풀 밖을 가리키는 조회는 전부 "못 찾으면 건너뛴다" 로 막혀 있다
        (`if key not in minfo.index: continue`, `if ref.empty: ...`).

    그래서 자르기는 **행 선택**이지 재계산이 아니다. `FEATURE-SPACE.md` 가 경고하는
    "신곡을 섞어 다시 만들면 기존 10,007구간 값이 전부 바뀐다" 는 함정과 다르다 —
    PCA 성분·평균은 npz 안에 이미 굳어 있고 여기서 건드리지 않는다.

무엇을 검사하나
    네 색인은 **같은 행 번호로 서로를 가리킨다.** 하나만 어긋나면 서비스는 정상처럼
    뜨면서 엉뚱한 곡을 추천한다. 그래서 자른 뒤에

      1. 네 산출물의 행 수가 같은지
      2. `emb_row` 가 0부터 연속인지 (`check_integrity` 가 보는 것과 같은 조건)
      3. `features_v1` 의 moment_id 순서가 `moments.parquet` 순서와 같은지

    를 확인하고, 하나라도 어긋나면 **아무것도 쓰지 않고** 종료한다.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from pathlib import Path

import numpy as np
import pandas as pd

from ai_server.app import paths

# 추천 풀의 정의는 `recommender` 와 같은 환경변수를 쓴다. 여기서 따로 정하면 자른 색인과
# 서빙의 풀이 어긋나 — 서비스는 뜨는데 후보가 0건이 된다.
POOL_SOURCE = os.environ.get("MUPID_POOL", "flow")
POOL_EXTRA = os.environ.get("MUPID_POOL_EXTRA", "jamendo")

# 행 정렬이 걸린 것들. 넷은 반드시 함께 잘린다.
ALIGNED = ("moments.parquet", "moments_emb.npy", "features_v1.npz", "stage1_section.npy")

# 행과 무관한 것들. 그대로 복사한다 — 모델 가중치·결합기·특징 정의.
COPY_AS_IS = ("stage1_weights.npz", "stage1.pt", "logistic_flow_stage1.json",
              "features_info.json", "feature_stats_v1.npz")


def pool_sources() -> set[str]:
    return {s for s in ({POOL_SOURCE} | set(POOL_EXTRA.split(","))) if s.strip()}


def fail(message: str) -> None:
    print(message, file=sys.stderr)
    raise SystemExit(65)


def slice_index(src: Path, dst: Path) -> dict:
    data = src / "data" / "proc" / "mupid"
    out = dst / "data" / "proc" / "mupid"
    for name in ALIGNED:
        if not (data / name).exists():
            fail(f"{name} 이 없다 — 자를 수 없다: {data / name}")

    mom = pd.read_parquet(data / "moments.parquet")
    if "source" not in mom.columns:
        mom["source"] = "fma"
    keep = mom.source.isin(pool_sources()).to_numpy()
    if not keep.any():
        fail(f"풀 출처 {sorted(pool_sources())} 에 해당하는 구간이 0건이다")

    # 원본이 이미 조건을 만족하는지 먼저 본다. 여기서 어긋나면 자르기 이전의 문제다.
    if not (mom.emb_row.to_numpy() == np.arange(len(mom))).all():
        fail("원본의 emb_row 가 0부터 연속이 아니다 — 먼저 색인을 고쳐야 한다")

    features = np.load(data / "features_v1.npz", allow_pickle=False)
    feat_ids = [str(m) for m in features["moment_id"]]
    if feat_ids != mom.moment_id.astype(str).tolist():
        fail("features_v1 의 moment_id 순서가 moments.parquet 과 다르다 — "
             "이 상태로 자르면 특징과 구간이 어긋난 채 정상처럼 뜬다")

    rows = np.flatnonzero(keep)
    out.mkdir(parents=True, exist_ok=True)

    # ① moments — emb_row 를 0부터 다시 매긴다. 새 Z 의 행 순서와 같아야 한다.
    sliced = mom.loc[keep].reset_index(drop=True).copy()
    old_rows = sliced.emb_row.to_numpy()
    sliced["emb_row"] = np.arange(len(sliced))
    sliced.to_parquet(out / "moments.parquet", index=False)

    # ② 임베딩 — mmap 으로 읽어 필요한 행만 만진다. 403MB 를 통째로 올리지 않는다.
    source_emb = np.load(data / "moments_emb.npy", mmap_mode="r")
    np.save(out / "moments_emb.npy", np.asarray(source_emb[old_rows], dtype=source_emb.dtype))

    # ③ 특징 — PCA 성분·평균·열 이름은 그대로 옮긴다(재계산 아님).
    payload = {key: features[key] for key in features.files}
    payload["X"] = features["X"][rows]
    payload["moment_id"] = features["moment_id"][rows]
    np.savez(out / "features_v1.npz", **payload)

    # ④ 구간 벡터 — X 와 같은 행 색인이다(`c.idx` 가 둘을 함께 가리킨다).
    section = np.load(data / "stage1_section.npy", mmap_mode="r")
    if len(section) != len(mom):
        fail(f"stage1_section 행 수가 moments 와 다르다: {len(section)} vs {len(mom)}")
    np.save(out / "stage1_section.npy", np.asarray(section[rows], dtype=section.dtype))

    tracks = set(sliced.track_id.astype(str))
    _slice_by_track(data, out, "content.parquet", tracks, required=True)
    _slice_by_track(data, out, "flow_meta.parquet", tracks, required=False)
    _slice_clap(data, out, set(sliced.moment_id.astype(str)))

    for name in COPY_AS_IS:
        if (data / name).exists():
            shutil.copy2(data / name, out / name)

    report = _verify(out)
    report["dropped"] = int(len(mom) - len(sliced))
    (out / "slice_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return report


def _slice_by_track(data: Path, out: Path, name: str, tracks: set[str],
                    required: bool) -> None:
    path = data / name
    if not path.exists():
        if required:
            fail(f"{name} 이 없다")
        return
    frame = pd.read_parquet(path)
    column = "track_id" if "track_id" in frame.columns else frame.index.name
    if column != "track_id":
        shutil.copy2(path, out / name)          # 모양을 모르면 건드리지 않는다
        return
    frame[frame.track_id.astype(str).isin(tracks)].to_parquet(out / name, index=False)


def _slice_clap(data: Path, out: Path, moments: set[str]) -> None:
    path = data / "clap_moments.npz"
    if not path.exists():
        return
    with np.load(path) as d:
        ids = np.array([str(m) for m in d["moment_ids"]])
        pick = np.flatnonzero(np.isin(ids, list(moments)))
        np.savez(out / "clap_moments.npz",
                 moment_ids=d["moment_ids"][pick], embeddings=d["embeddings"][pick])


def _verify(out: Path) -> dict:
    """`main.check_integrity()` 가 기동 때 보는 것과 같은 조건을 미리 본다.

    여기서 잡지 못하면 노드에 올린 뒤 `/internal/ready` 가 영영 false 인 것으로만
    드러난다 — 원인이 색인이라는 사실이 응답에 나오지 않는다.
    """
    mom = pd.read_parquet(out / "moments.parquet", columns=["moment_id", "emb_row"])
    counts = {
        "moments": len(mom),
        "moments_emb": int(np.load(out / "moments_emb.npy", mmap_mode="r").shape[0]),
        "features_v1": int(np.load(out / "features_v1.npz")["X"].shape[0]),
        "stage1_section": int(np.load(out / "stage1_section.npy", mmap_mode="r").shape[0]),
    }
    if len(set(counts.values())) != 1:
        fail(f"자른 색인의 행 수가 어긋난다: {counts}")
    if not (mom.emb_row.to_numpy() == np.arange(len(mom))).all():
        fail("자른 뒤 emb_row 가 0부터 연속이 아니다")
    if int(mom.moment_id.duplicated().sum()):
        fail("moment_id 가 중복이다")
    size = sum(p.stat().st_size for p in out.rglob("*") if p.is_file())
    return {"rows": counts, "bytes": size}


def main() -> int:
    ap = argparse.ArgumentParser(description="서빙용 색인만 잘라낸다")
    ap.add_argument("--out", required=True, help="자른 색인을 쓸 디렉터리")
    ap.add_argument("--src", default=None, help="기본: MUPID_AI_ARTIFACT_DIR")
    a = ap.parse_args()

    src = Path(a.src) if a.src else paths.artifact_root()
    dst = Path(a.out)
    if dst.resolve() == src.resolve():
        fail("원본 위에 덮어쓰지 않는다 — 학습에 쓸 전체 색인이 사라진다")

    report = slice_index(src, dst)
    print(f"풀 {report['rows']['moments']:,}구간 남김 "
          f"({report['dropped']:,}행 버림) · {report['bytes'] / 1e6:.1f}MB -> {dst}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
