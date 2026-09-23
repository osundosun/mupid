"""색인 축소 — `shrink_index` 가 만드는 **새 판**.

이 시험이 있어야 하는 이유는 도구 머리말이 직접 말한다 — "행을 빼도 계산이 달라지지
않는다. 정말 그런지는 도구가 아니라 시험이 판정한다(`test_shrink_index.py`)". 그
파일이 없었다. 여기서 채운다.

주장은 둘이다.

  1. **남은 순간의 값이 한 비트도 바뀌지 않는다.** 임베딩·285차·구간벡터는 행 복사이고
     PCA 기저와 동결 통계는 그대로 옮겨진다. 이게 깨지면 그때부터는 다른 모델인데,
     서비스는 정상처럼 뜨고 추천만 달라진다.
  2. **출처만으로 고르지 않는다.** `flow` 안에도 `T000847_1` 처럼 DB 에 없는 곡이
     섞여 있다. 남기면 추천에 뽑히고 Spring 이 `external_id` 로 되돌리지 못한다.

`slice_index` 와 겹쳐 보이지만 만드는 것이 다르다 — 이쪽은 상태(`logs`·`serve`)를
데리고 가는 **판**이고, 그쪽은 상태를 떼어 내는 **배송 묶음**이다. 그 차이도 여기서
못 박는다.
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd
import pytest

DIM_EMB, DIM_X, DIM_Z = 8, 5, 4
PATTERN = r"^T\d{6}$"


def _build(plate):
    """`flow`·`fma` 와 규격 밖 `track_id` 가 섞인 판 한 벌.

    셋을 **번갈아** 넣는다. 뒤에 몰아 두면 "앞에서 N 개" 같은 잘못된 자르기도 통과한다.
    """
    plate.mkdir(parents=True)
    rng = np.random.default_rng(7)

    spec = [
        ("T000001", "flow"), ("T000002", "fma"), ("T000847_1", "flow"),
        ("T000003", "flow"), ("T000004", "fma"),
        ("20260901T044346Z_take2", "flow"), ("T000005", "flow"),
    ]
    rows = [{"moment_id": f"{tid}#0", "track_id": tid, "source": src,
             "emb_row": i, "section_label": "chorus", "dur_sec": 20.0,
             "start_sec": 10.0, "end_sec": 30.0, "rms": float(i)}
            for i, (tid, src) in enumerate(spec)]
    mom = pd.DataFrame(rows)
    mom.to_parquet(plate / "moments.parquet", index=False)

    n = len(mom)
    np.save(plate / "moments_emb.npy", rng.normal(size=(n, DIM_EMB)).astype(np.float32))
    np.save(plate / "stage1_section.npy", rng.normal(size=(n, DIM_Z)).astype(np.float32))
    np.savez(plate / "features_v1.npz",
             X=rng.normal(size=(n, DIM_X)).astype(np.float32),
             moment_id=mom.moment_id.to_numpy(dtype="<U80"),
             pca_mean=rng.normal(size=DIM_EMB).astype(np.float32),
             pca_comp=rng.normal(size=(DIM_EMB, DIM_X)).astype(np.float32),
             side_cols=np.array(["a"], dtype="<U24"), evr=np.float32(0.8469))
    for name in ("content.parquet", "flow_meta.parquet"):
        pd.DataFrame({"track_id": mom.track_id, "bpm": 120.0}).to_parquet(
            plate / name, index=False)
    np.savez(plate / "clap_moments.npz",
             moment_ids=mom.moment_id.to_numpy(dtype="<U80"),
             embeddings=rng.normal(size=(n, 3)).astype(np.float32),
             model="clap-test")
    # 동결 통계·모델은 손대지 않고 옮겨야 하는 것들이다.
    np.savez(plate / "feature_stats_v1.npz", mu=np.zeros(DIM_X, dtype=np.float32))
    np.savez(plate / "moment_zstats.npz", mu=np.zeros(DIM_Z, dtype=np.float32))
    (plate / "features_info.json").write_text(
        json.dumps({"version": "feat-v1", "pca_fit_on": "fma", "evr": 0.8469,
                    "features_rows": n, "features_sha256": "old"}),
        encoding="utf-8")
    # 상태. 판을 따라가야 하는 것 — 배송 묶음과 갈리는 지점이다.
    (plate / "logs").mkdir()
    (plate / "logs" / "events.jsonl").write_text('{"a":1}\n', encoding="utf-8")
    (plate / "serve").mkdir()
    (plate / "serve" / "onboarding.parquet").write_bytes(b"x")
    return mom


@pytest.fixture
def shrunk(tmp_path):
    from ai_server.tools import shrink_index

    src, dst = tmp_path / "mupid", tmp_path / "mupid_v1"
    before = _build(src)
    report = shrink_index.shrink(src, dst, {"flow"}, PATTERN)
    return src, dst, before, report


def _kept_ids(before):
    return ["T000001#0", "T000003#0", "T000005#0"]


# --------------------------------------------------------------- 무엇이 남는가

def test_drops_other_sources_and_malformed_track_ids(shrunk):
    """출처만 보면 `T000847_1` 이 남는다. 그 곡은 DB 에 없다."""
    _, dst, before, report = shrunk
    after = pd.read_parquet(dst / "moments.parquet")

    assert list(after.moment_id) == _kept_ids(before)
    assert set(after.source) == {"flow"}
    assert "T000847_1" not in set(after.track_id)
    assert "20260901T044346Z_take2" not in set(after.track_id)
    assert (report["rows_before"], report["rows_after"]) == (len(before), 3)


def test_emb_row_is_renumbered_from_zero(shrunk):
    """`check_integrity` 와 `recommender` 가 이 연속성을 전제로 행을 찾는다."""
    _, dst, _, _ = shrunk
    after = pd.read_parquet(dst / "moments.parquet")
    assert list(after.emb_row) == list(range(len(after)))


def test_track_tables_follow_the_kept_tracks(shrunk):
    _, dst, _, _ = shrunk
    kept = set(pd.read_parquet(dst / "moments.parquet").track_id)
    for name in ("content.parquet", "flow_meta.parquet"):
        assert set(pd.read_parquet(dst / name).track_id) == kept


# ------------------------------------------------- 값이 바뀌지 않는다 (핵심 주장)

@pytest.mark.parametrize("name,key", [("moments_emb.npy", None),
                                      ("stage1_section.npy", None),
                                      ("features_v1.npz", "X")])
def test_kept_rows_are_bit_identical(shrunk, name, key):
    """남은 순간의 벡터가 원본과 **같은 값**이어야 한다. 재계산이 아니라 행 복사다."""
    src, dst, before, _ = shrunk
    keep = before.moment_id.isin(_kept_ids(before)).to_numpy()

    def load(path):
        if key is None:
            return np.load(path)
        with np.load(path, allow_pickle=False) as z:
            return z[key]

    np.testing.assert_array_equal(load(src / name)[keep], load(dst / name))


def test_features_moment_id_order_matches_moments(shrunk):
    """둘이 어긋나면 특징과 구간이 뒤바뀐 채 서비스가 정상처럼 뜬다."""
    _, dst, before, _ = shrunk
    after = pd.read_parquet(dst / "moments.parquet")
    with np.load(dst / "features_v1.npz", allow_pickle=False) as z:
        assert [str(m) for m in z["moment_id"]] == list(after.moment_id)


def test_pca_basis_is_carried_not_recomputed(shrunk):
    """PCA 성분·평균이 바뀌면 남은 곡의 285차가 전부 달라진다."""
    src, dst, _, _ = shrunk
    with np.load(src / "features_v1.npz", allow_pickle=False) as a, \
         np.load(dst / "features_v1.npz", allow_pickle=False) as b:
        for key in ("pca_mean", "pca_comp", "side_cols", "evr"):
            np.testing.assert_array_equal(a[key], b[key])


def test_frozen_stats_and_model_are_copied_untouched(shrunk):
    src, dst, _, _ = shrunk
    for name in ("feature_stats_v1.npz", "moment_zstats.npz"):
        assert (dst / name).read_bytes() == (src / name).read_bytes()


def test_clap_keeps_only_kept_moments(shrunk):
    _, dst, before, _ = shrunk
    with np.load(dst / "clap_moments.npz", allow_pickle=False) as z:
        assert [str(m) for m in z["moment_ids"]] == _kept_ids(before)


# ------------------------------------------------------------ 판이라는 것의 의미

def test_features_info_row_count_follows_the_new_plate(shrunk):
    """행 수와 해시만 고치고 기저 항목은 건드리지 않는다."""
    src, dst, _, _ = shrunk
    old = json.loads((src / "features_info.json").read_text(encoding="utf-8"))
    new = json.loads((dst / "features_info.json").read_text(encoding="utf-8"))

    assert new["features_rows"] == 3
    assert new["features_sha256"] != old["features_sha256"]
    assert (new["pca_fit_on"], new["evr"]) == (old["pca_fit_on"], old["evr"])


def test_state_travels_with_the_plate(shrunk):
    """`slice_index`(배송 묶음)와 갈리는 지점 — 판은 상태를 데리고 간다."""
    _, dst, _, _ = shrunk
    assert (dst / "logs" / "events.jsonl").read_text(encoding="utf-8") == '{"a":1}\n'
    assert (dst / "serve" / "onboarding.parquet").exists()


def test_the_original_plate_is_untouched(shrunk):
    """교체는 사람이 한다. 도구는 옆에 만들 뿐이다."""
    src, _, before, _ = shrunk
    assert list(pd.read_parquet(src / "moments.parquet").moment_id) == list(before.moment_id)


def test_refuses_to_write_over_an_existing_plate(tmp_path):
    """덮어쓰면 되돌릴 판이 사라진다."""
    from ai_server.tools import shrink_index

    src, dst = tmp_path / "mupid", tmp_path / "mupid_v1"
    _build(src)
    dst.mkdir()
    with pytest.raises(SystemExit):
        shrink_index.shrink(src, dst, {"flow"}, PATTERN)
