"""transform-only 경로 왕복 검증.

`music_preprocessing` 의 변환기가 **기존 색인과 같은 값**을 만드는지 본다. 미묘하게
다르면 신곡만 순위가 틀어지는데 오류는 나지 않는다 — 이 저장소가 색인 불일치로 두 번
겪은 실패다.

산출물이 있을 때만 돈다. CI 는 색인 없이 도는 것만 검사하므로 여기서는 건너뛴다.

    MUPID_AI_ARTIFACT_DIR=/경로 python -m pytest ai_server/tests/test_feature_transform.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np
import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
# 변환기는 전처리 배치 쪽에 있다. 서빙은 이 변환을 쓰지 않는다.
sys.path.insert(0, str(REPO_ROOT / "music_preprocessing" / "src"))

from ai_server.app import paths                                    # noqa: E402

fs = pytest.importorskip("music_preprocessing.analysis.feature_space",
                         reason="analysis 선택 의존성이 없다")

D = paths.data_dir()
NEEDED = ("features_v1.npz", "feature_stats_v1.npz", "features_info.json",
          "moments.parquet", "content.parquet")
HAVE = all((D / n).exists() for n in NEEDED)
pytestmark = pytest.mark.skipif(
    not HAVE, reason=f"산출물이 없다: {[n for n in NEEDED if not (D / n).exists()]}")

SEED = 20260916
N_SAMPLE = 200


@pytest.fixture(scope="module")
def space():
    return fs.FeatureSpace(D)


@pytest.fixture(scope="module")
def stored():
    import pandas as pd

    mom = pd.read_parquet(D / "moments.parquet")
    content = pd.read_parquet(D / "content.parquet").set_index("track_id")
    with np.load(D / "features_v1.npz", allow_pickle=False) as fz:
        X = np.asarray(fz["X"])
        ids = [str(m) for m in fz["moment_id"]]
    # features_v1.npz 의 행 순서는 moments.parquet 그대로다. 그 전제부터 확인한다.
    assert ids == mom.moment_id.astype(str).tolist(), "moment_id 정렬이 어긋난다"
    return mom, content, X


def sections_of(group) -> list:
    return [fs.Section(rel_start=float(r.rel_start), rel_end=float(r.rel_end),
                       dur_sec=float(r.dur_sec), rms=float(r.rms),
                       section_label=str(r.section_label))
            for r in group.itertuples()]


def track_of(content, tid: str):
    row = content.loc[tid]
    return fs.TrackStats(bpm=float(row.bpm), centroid=float(row.centroid),
                         rolloff=float(row.rolloff), rms_mean=float(row.rms_mean),
                         flatness=float(row.flatness))


# ────────────────────────────────── PCA 블록

def test_pca_block_matches(space, stored):
    """무작위 200구간의 PCA 256열이 저장된 값과 같은가."""
    mom, _, X = stored
    rng = np.random.default_rng(SEED)
    pick = rng.choice(len(mom), size=min(N_SAMPLE, len(mom)), replace=False)

    Z = np.load(D / "moments_emb.npy", mmap_mode="r")
    rows = mom.emb_row.to_numpy()[pick]
    got = space.pca(np.asarray(Z[rows], dtype=np.float32))
    ref = X[pick, :space.pca_dim]

    err = float(np.abs(got - ref).max())
    print(f"\nPCA 블록 최대 절대오차 {err:.3e}  (구간 {len(pick)}개)")
    assert err < 1e-6, f"PCA 블록이 어긋난다: {err:.3e}"


# ────────────────────────────────── side 29열 (전량)

def test_side_block_matches_every_row(space, stored):
    """side 29열은 **전량 완전 일치**여야 한다.

    표본이 아니라 전부 보는 이유는, 열 순서가 어긋나면 값은 멀쩡한데 자리만 바뀌어
    특정 구간에서만 드러나기 때문이다.
    """
    mom, content, X = stored
    ref = X[:, space.pca_dim:]
    got = np.zeros_like(ref)

    # 곡 단위로 계산한다. rms_pct 가 곡 안 순위라 곡을 쪼개면 값이 달라진다.
    for tid, g in mom.groupby("track_id", sort=False):
        if tid not in content.index:
            continue
        got[g.index.to_numpy()] = space.side(sections_of(g), track_of(content, tid))

    diff = np.abs(got - ref)
    worst = int(np.unravel_index(diff.argmax(), diff.shape)[1])
    print(f"\nside 최대 절대오차 {diff.max():.3e}  (가장 어긋난 열: "
          f"{space.side_cols[worst]})")
    assert diff.max() == 0.0, (
        f"side 블록이 완전 일치가 아니다 — 최대 {diff.max():.3e}, "
        f"열 {space.side_cols[worst]}")


def test_side_columns_named_in_order(space):
    """열 이름과 순서가 동결 파일·특징 파일과 같은가."""
    with np.load(D / "features_v1.npz", allow_pickle=False) as fz:
        assert [str(x) for x in fz["side_cols"]] == space.side_cols
    assert len(space.side_cols) == 29
    assert space.side_cols[:6] == ["rel_start", "rel_end", "rel_center",
                                   "log_dur", "rms_pct", "rms_z"]
    assert space.side_cols[6:16] == [f"pos{i}" for i in range(10)]
    assert space.side_cols[-5:] == ["trk_bpm", "trk_centroid", "trk_rolloff",
                                    "trk_rms_mean", "trk_flatness"]


# ────────────────────────────────── 구간 타워

def test_enc_sec_matches_stage1_section(space, stored):
    """`enc_sec` 출력이 저장된 `stage1_section.npy` 와 같은가."""
    if not (D / "stage1_section.npy").exists() or not (D / "stage1_weights.npz").exists():
        pytest.skip("stage1 산출물이 없다")
    mom, _, X = stored
    S = np.load(D / "stage1_section.npy", mmap_mode="r")
    if len(S) != len(mom):
        pytest.skip(f"stage1_section 행 수가 다르다: {len(S)} vs {len(mom)}")

    rng = np.random.default_rng(SEED)
    pick = rng.choice(len(mom), size=min(N_SAMPLE, len(mom)), replace=False)
    got = space.enc_sec(X[pick])
    ref = np.asarray(S[pick], dtype=np.float32)

    err = float(np.abs(got - ref).max())
    print(f"\nenc_sec 최대 절대오차 {err:.3e}  (구간 {len(pick)}개)")
    assert err < 1e-6, f"구간 타워 출력이 어긋난다: {err:.3e}"


# ────────────────────────────────── 실패 경로

def test_missing_stats_raises(tmp_path):
    """동결 통계를 빼면 **조용히 계산하지 않고** 오류로 멈추는가."""
    for n in ("features_v1.npz", "features_info.json"):
        (tmp_path / n).write_bytes((D / n).read_bytes())
    with pytest.raises(fs.ArtifactMissing, match="동결 통계가 없다"):
        fs.FeatureSpace(tmp_path)


def test_mismatched_features_hash_raises(tmp_path):
    """기저 파일이 통계를 뽑을 때와 다르면 오류로 멈추는가."""
    for n in ("features_v1.npz", "feature_stats_v1.npz"):
        (tmp_path / n).write_bytes((D / n).read_bytes())
    info = json.loads((D / "features_info.json").read_text(encoding="utf-8"))
    info["features_sha256"] = "0" * 64
    (tmp_path / "features_info.json").write_text(json.dumps(info), encoding="utf-8")

    with pytest.raises(fs.ArtifactMissing, match="다른 파일이다"):
        fs.FeatureSpace(tmp_path)


def test_mismatched_feature_version_raises(tmp_path):
    """판 이름이 어긋나면 오류로 멈추는가."""
    for n in ("features_v1.npz", "feature_stats_v1.npz"):
        (tmp_path / n).write_bytes((D / n).read_bytes())
    info = json.loads((D / "features_info.json").read_text(encoding="utf-8"))
    info["feature_version"] = "feat-v99"
    (tmp_path / "features_info.json").write_text(json.dumps(info), encoding="utf-8")

    with pytest.raises(fs.ArtifactMissing, match="feature_version"):
        fs.FeatureSpace(tmp_path, verify_hash=False)


def test_unknown_label_falls_back_to_other(space):
    """목록에 없는 라벨이 other 로 떨어지는가."""
    secs = [fs.Section(0.0, 0.5, 60.0, 0.1, "no-such-label")]
    trk = fs.TrackStats(120.0, 1800.0, 3700.0, 0.15, 0.01)
    S = space.side(secs, trk)
    other = space.side_cols.index("lab_other")
    assert S[0, other] == 1.0
    assert sum(S[0, i] for i, c in enumerate(space.side_cols)
               if c.startswith("lab_")) == 1.0
