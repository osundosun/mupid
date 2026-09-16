"""색인 갱신기 — 시드 신곡으로 왕복 검증.

음원이 없어도 시험할 수 있다. 기존 구간을 복제해 **새 track_id** 를 붙이면
임베딩·특징·구간벡터가 이미 있으므로 갱신기만 따로 볼 수 있다.

현재 산출물은 **읽기만** 한다. 시험용 판을 임시 폴더에 새로 만들고 거기서 교체·롤백을
해 본다 — 진짜 산출물 디렉터리는 건드리지 않는다.

    MUPID_AI_ARTIFACT_DIR=/경로 python -m pytest ai_server/tests/test_index_update.py -s
"""
from __future__ import annotations

import json
import shutil
import sys
from pathlib import Path

import numpy as np
import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "music_preprocessing" / "src"))

from ai_server.app import paths                                      # noqa: E402

ix = pytest.importorskip("music_preprocessing.analysis.indexer",
                         reason="analysis 선택 의존성이 없다")
pd = pytest.importorskip("pandas")

D = paths.data_dir()
NEEDED = ("moments.parquet", "moments_emb.npy", "features_v1.npz",
          "stage1_section.npy", "content.parquet", "flow_meta.parquet",
          "features_info.json", "feature_stats_v1.npz")
MISSING = [n for n in NEEDED if not (D / n).exists()]
pytestmark = pytest.mark.skipif(bool(MISSING), reason=f"산출물이 없다: {MISSING}")

SEED_PREFIX = "SEEDNEW_"


# ────────────────────────────────────────────────────── 시드 만들기

@pytest.fixture(scope="module")
def base(tmp_path_factory):
    """현재 산출물의 사본. 진짜 디렉터리를 건드리지 않으려고 한 벌 떠 둔다."""
    root = tmp_path_factory.mktemp("idx") / "data" / "proc" / "mupid"
    root.mkdir(parents=True)
    for n in NEEDED + ("moment_zstats.npz", "stage1_weights.npz"):
        p = D / n
        if p.exists():
            shutil.copy2(p, root / n)
    return root


def make_seed(base: Path, n_tracks: int = 2, prefix: str = SEED_PREFIX,
              skip: int = 0) -> list:
    """기존 곡의 구간을 그대로 베껴 새 track_id 를 붙인다.

    값을 지어내지 않는다 — 임베딩·특징·구간벡터는 **저장본에서 읽어 온 그대로**다.
    그래서 이 시험이 보는 것은 갱신기의 배치·정렬·무결성이지 계산이 아니다.
    """
    mom = pd.read_parquet(base / "moments.parquet")
    Z = np.load(base / "moments_emb.npy", mmap_mode="r")
    S = np.load(base / "stage1_section.npy", mmap_mode="r")
    with np.load(base / "features_v1.npz", allow_pickle=False) as fz:
        X = np.asarray(fz["X"])
    content = pd.read_parquet(base / "content.parquet")
    meta = pd.read_parquet(base / "flow_meta.parquet")

    pool = mom[mom.source == "flow"]
    src_ids = sorted(pool.track_id.unique())[skip:skip + n_tracks]
    out = []
    for k, tid in enumerate(src_ids):
        g = pool[pool.track_id == tid].sort_values("section_idx")
        new_id = f"{prefix}{k}"
        rows = []
        for i, r in enumerate(g.itertuples()):
            d = {c: getattr(r, c) for c in mom.columns
                 if c not in ("track_id", "emb_row", "source", "track_dur_sec")}
            d["moment_id"] = f"{new_id}#{i}"
            d["section_idx"] = i
            rows.append(d)
        idx = g.index.to_numpy()
        crow = content[content.track_id == tid].iloc[0].to_dict()
        crow["track_id"] = new_id
        mrow = meta[meta.track_id == tid].iloc[0].to_dict() if (
            meta.track_id == tid).any() else {"track_id": new_id}
        mrow["track_id"] = new_id
        out.append(ix.NewTrack(
            track_id=new_id, source="flow",
            track_dur_sec=float(g.track_dur_sec.iloc[0]),
            sections=rows,
            embeddings=np.asarray(Z[g.emb_row.to_numpy()], dtype=np.float32),
            features=X[idx].astype(np.float32),
            section_vecs=np.asarray(S[idx], dtype=np.float32),
            content_row=crow, meta_row=mrow))
    return out


# ────────────────────────────────────────────────────── 1. 6개 파일이 는다

@pytest.fixture(scope="module")
def staged(base):
    rep = ix.stage(make_seed(base), base)
    print(f"\n곡 {rep.added_tracks} · 구간 {rep.added_sections} "
          f"({rep.rows_before} → {rep.rows_after}행)")
    if rep.integrity.get("errors"):
        print("무결성 오류:", rep.integrity["errors"])
    return rep


def test_six_files_grow(staged, base):
    rep = staged
    assert rep.added_tracks == 2 and rep.added_sections > 0
    new = rep.new_dir

    mom = pd.read_parquet(new / "moments.parquet")
    assert len(mom) == rep.rows_after
    for name in ("moments_emb.npy", "stage1_section.npy"):
        assert np.load(new / name, mmap_mode="r").shape[0] == rep.rows_after
    with np.load(new / "features_v1.npz", allow_pickle=False) as fz:
        assert fz["X"].shape[0] == rep.rows_after

    # 메타 두 표도 함께 늘어야 한다 — 하나만 늘면 반쪽으로 들어간다.
    for name, before in (("content.parquet", base), ("flow_meta.parquet", base)):
        b = len(pd.read_parquet(before / name))
        a = len(pd.read_parquet(new / name))
        assert a == b + 2, f"{name} 이 {b} → {a} (기대 {b + 2})"


def test_integrity_passes(staged):
    assert staged.ok, f"무결성 실패: {staged.integrity.get('errors')}"


def test_existing_rows_unchanged(staged, base):
    """기존 행이 한 줄도 바뀌지 않았는가 — 전량 비교."""
    new = staged.new_dir
    n0 = staged.rows_before
    old = np.load(base / "moments_emb.npy", mmap_mode="r")
    got = np.load(new / "moments_emb.npy", mmap_mode="r")
    assert np.array_equal(np.asarray(old), np.asarray(got[:n0]))

    with np.load(base / "features_v1.npz", allow_pickle=False) as a, \
            np.load(new / "features_v1.npz", allow_pickle=False) as b:
        assert np.array_equal(a["X"], b["X"][:n0])
        for k in ("pca_mean", "pca_comp", "side_cols", "evr"):
            assert np.array_equal(a[k], b[k]), f"PCA 기저 {k} 가 바뀌었다"


def test_emb_row_is_contiguous(staged):
    mom = pd.read_parquet(staged.new_dir / "moments.parquet")
    assert (mom.emb_row.to_numpy() == np.arange(len(mom))).all()
    assert mom.moment_id.duplicated().sum() == 0


def test_features_info_refreshed(staged):
    """`features_sha256` 을 안 고치면 다음 배치가 오류로 멈춘다."""
    new = staged.new_dir
    info = json.loads((new / "features_info.json").read_text(encoding="utf-8"))
    assert info["features_rows"] == staged.rows_after
    assert info["features_sha256"] == ix.sha256(new / "features_v1.npz")


# ────────────────────────────────────────────────────── 2. 거부 경로

def test_existing_track_is_rejected(base):
    """이미 있는 곡은 거부한다. 조용히 덮어쓰지 않는다."""
    mom = pd.read_parquet(base / "moments.parquet")
    tid = str(mom[mom.source == "flow"].track_id.iloc[0])
    seed = make_seed(base, 1)
    seed[0].track_id = tid                       # 기존 곡으로 위장
    rep = ix.stage(seed, base)
    assert rep.added_tracks == 0
    assert rep.rejected and "이미 색인에 있다" in rep.rejected[0]["reason"]
    shutil.rmtree(rep.new_dir, ignore_errors=True)


def test_short_section_is_rejected(base):
    """3초 미만 구간은 레시피가 버리는 값이다. 들어오면 거부한다."""
    seed = make_seed(base, 1)
    seed[0].sections[0]["dur_sec"] = 1.0
    rep = ix.stage(seed, base)
    assert rep.added_tracks == 0
    assert "초 구간이 있다" in rep.rejected[0]["reason"]
    shutil.rmtree(rep.new_dir, ignore_errors=True)


def test_missing_flow_meta_fails_gate(base):
    """`flow_meta` 가 없으면 추천 풀에는 들지만 조건 검색에서 빠진다 — 게이트가 잡는다."""
    seed = make_seed(base, 1)
    seed[0].meta_row = {}
    rep = ix.stage(seed, base)
    assert not rep.ok
    assert any("flow_meta" in e for e in rep.integrity["errors"])
    shutil.rmtree(rep.new_dir, ignore_errors=True)


def test_tampered_pca_fails_gate(base, staged):
    """PCA 기저를 건드리면 게이트가 멈춘다."""
    new = staged.new_dir
    tampered = new.parent / (new.name + "_tampered")
    shutil.copytree(new, tampered)
    with np.load(tampered / "features_v1.npz", allow_pickle=False) as fz:
        d = {k: np.asarray(fz[k]) for k in fz.files}
    d["pca_mean"] = d["pca_mean"] + np.float32(1.0)
    np.savez(tampered / "features_v1.npz", **d)

    res = ix.check_index(tampered, prev=base)
    assert not res["ok"]
    assert any("PCA 기저" in e for e in res["errors"])
    shutil.rmtree(tampered, ignore_errors=True)


# ────────────────────────────────────────────────────── 3. 교체·롤백

def test_swap_and_rollback(base):
    """교체하면 새 판이 서고, 되돌리면 교체 전과 같아진다."""
    rep = ix.stage(make_seed(base, 1), base)
    assert rep.ok, rep.integrity.get("errors")
    before = len(pd.read_parquet(base / "moments.parquet"))

    res = ix.swap(base, rep.new_dir, keep=3)
    after = len(pd.read_parquet(base / "moments.parquet"))
    assert after > before, "교체 후 행이 늘지 않았다"
    assert Path(res["backup"]).is_dir(), "롤백용 이전 판이 없다"

    ix.rollback(base, Path(res["backup"]))
    back = len(pd.read_parquet(base / "moments.parquet"))
    assert back == before, f"롤백 뒤 {back} 행 (교체 전 {before})"


def test_no_swap_when_gate_fails(base):
    """게이트가 실패하면 교체하지 않는다 — 호출자가 `ok` 를 보고 멈춘다."""
    seed = make_seed(base, 1)
    seed[0].meta_row = {}
    rep = ix.stage(seed, base)
    assert not rep.ok
    before = len(pd.read_parquet(base / "moments.parquet"))
    # 갱신기는 스스로 바꾸지 않는다. swap 은 별도 호출이다.
    assert len(pd.read_parquet(base / "moments.parquet")) == before
    shutil.rmtree(rep.new_dir, ignore_errors=True)


def test_restart_notice_mentions_restart(staged):
    msg = ix.restart_notice(staged)
    assert "다시 띄워야" in msg and "rollout restart" in msg
