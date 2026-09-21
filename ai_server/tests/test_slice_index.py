"""서빙용 색인 자르기.

핵심 주장은 하나다 — **자른 색인으로 계산한 값이 전체 색인으로 계산한 값과 같다.**
그 주장이 깨지면 서비스는 정상처럼 뜨면서 다른 곡을 추천하고, 어디에도 오류가 남지
않는다. 그래서 여기서는 행 수만 세지 않고 같은 구간의 특징 벡터·임베딩·구간 벡터를
값으로 비교한다.
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd
import pytest

POOL = ("flow", "jamendo")
DIM_EMB, DIM_X, DIM_Z = 8, 5, 4


def _build(root, n_pool=6, n_fma=14):
    """풀과 학습분이 섞인 색인 한 벌. 실제와 같은 정렬 규칙을 지킨다."""
    data = root / "data" / "proc" / "mupid"
    data.mkdir(parents=True)
    rng = np.random.default_rng(0)

    rows = []
    # 풀과 학습분을 **섞어서** 넣는다. 뒤쪽에 몰아 두면 잘못된 슬라이스(앞에서 N개)도
    # 통과해 버린다.
    for i in range(n_pool + n_fma):
        pool = i % 3 == 0 and sum(1 for r in rows if r["source"] != "fma") < n_pool
        rows.append({
            "moment_id": f"T{i}#0", "track_id": f"T{i}",
            "source": "flow" if pool else "fma",
            "emb_row": i, "section_label": "chorus",
            "rel_start": 0.1, "rel_end": 0.3, "dur_sec": 20.0, "rms": float(i),
            "start_sec": 10.0, "end_sec": 30.0,
        })
    mom = pd.DataFrame(rows)
    mom.to_parquet(data / "moments.parquet", index=False)

    total = len(mom)
    np.save(data / "moments_emb.npy", rng.normal(size=(total, DIM_EMB)).astype(np.float32))
    np.savez(data / "features_v1.npz",
             X=rng.normal(size=(total, DIM_X)).astype(np.float32),
             moment_id=mom.moment_id.to_numpy(dtype="<U80"),
             pca_mean=rng.normal(size=DIM_EMB).astype(np.float32),
             pca_comp=rng.normal(size=(DIM_EMB, DIM_X)).astype(np.float32),
             side_cols=np.array(["a"], dtype="<U24"), evr=np.float32(0.7))
    np.save(data / "stage1_section.npy", rng.normal(size=(total, DIM_Z)).astype(np.float32))
    pd.DataFrame({"track_id": mom.track_id, "bpm": 120.0}).to_parquet(
        data / "content.parquet", index=False)
    (data / "features_info.json").write_text(json.dumps({"version": "feat-v1"}),
                                             encoding="utf-8")
    return data


@pytest.fixture
def sliced(tmp_path, monkeypatch):
    monkeypatch.setenv("MUPID_POOL", "flow")
    monkeypatch.setenv("MUPID_POOL_EXTRA", "jamendo")
    from ai_server.tools import slice_index

    src, dst = tmp_path / "full", tmp_path / "serving"
    data = _build(src)
    report = slice_index.slice_index(src, dst)
    return data, dst / "data" / "proc" / "mupid", report


def test_keeps_only_pool_sections(sliced):
    full, out, _ = sliced
    before = pd.read_parquet(full / "moments.parquet")
    after = pd.read_parquet(out / "moments.parquet")

    assert set(after.source) == {"flow"}
    assert len(after) == int((before.source != "fma").sum())
    assert len(after) < len(before)


def test_every_vector_keeps_its_value(sliced):
    """같은 구간이면 같은 값이어야 한다. **이 시험이 이 도구의 전부다.**

    행만 골라내는 것이므로 재계산이 없다. 값이 달라졌다면 마스크가 산출물마다 다르게
    적용된 것이고, 그때 증상은 "오류 없이 다른 곡을 추천" 이다.
    """
    full, out, _ = sliced
    before = pd.read_parquet(full / "moments.parquet")
    after = pd.read_parquet(out / "moments.parquet")

    Z_before = np.load(full / "moments_emb.npy")
    Z_after = np.load(out / "moments_emb.npy")
    X_before = np.load(full / "features_v1.npz")["X"]
    X_after = np.load(out / "features_v1.npz")["X"]
    S_before = np.load(full / "stage1_section.npy")
    S_after = np.load(out / "stage1_section.npy")

    old_row = before.set_index("moment_id").emb_row
    for i, mid in enumerate(after.moment_id):
        j = int(old_row.loc[mid])
        assert np.array_equal(Z_after[i], Z_before[j]), f"{mid} 임베딩이 바뀌었다"
        assert np.array_equal(X_after[i], X_before[j]), f"{mid} 특징이 바뀌었다"
        assert np.array_equal(S_after[i], S_before[j]), f"{mid} 구간 벡터가 바뀌었다"


def test_moment_id_order_matches_between_moments_and_features(sliced):
    """`c.idx` 는 features 의 moment_id 로 만들고 `Z` 는 moments 의 emb_row 로 닿는다.

    둘의 순서가 어긋나면 한 구간의 특징과 임베딩이 다른 구간의 것이 된다.
    """
    _, out, _ = sliced
    mom = pd.read_parquet(out / "moments.parquet")
    feat = np.load(out / "features_v1.npz")

    assert [str(m) for m in feat["moment_id"]] == mom.moment_id.tolist()


def test_emb_row_is_renumbered_contiguously(sliced):
    """`check_integrity()` 가 기동 때 보는 조건. 어긋나면 ready 가 영영 false 다."""
    _, out, _ = sliced
    mom = pd.read_parquet(out / "moments.parquet")

    assert mom.emb_row.tolist() == list(range(len(mom)))


def test_pca_basis_is_carried_over_not_refitted(sliced):
    """PCA 성분·평균을 다시 맞추면 **기존 구간의 값이 전부 바뀐다**(FEATURE-SPACE.md).

    자르기는 행 선택이므로 이 값들은 그대로 따라와야 한다.
    """
    full, out, _ = sliced
    before = np.load(full / "features_v1.npz")
    after = np.load(out / "features_v1.npz")

    assert np.array_equal(before["pca_mean"], after["pca_mean"])
    assert np.array_equal(before["pca_comp"], after["pca_comp"])
    assert np.array_equal(before["side_cols"], after["side_cols"])


def test_content_is_narrowed_to_pool_tracks(sliced):
    _, out, _ = sliced
    mom = pd.read_parquet(out / "moments.parquet")
    content = pd.read_parquet(out / "content.parquet")

    assert set(content.track_id) == set(mom.track_id)


def test_refuses_to_overwrite_the_source(tmp_path, monkeypatch):
    """전체 색인은 학습에 필요하다. 덮어쓰면 다시 만들려면 GPU 배치를 다시 돌려야 한다."""
    monkeypatch.setenv("MUPID_POOL", "flow")
    monkeypatch.setattr("sys.argv", ["slice_index", "--src", str(tmp_path),
                                     "--out", str(tmp_path)])
    from ai_server.tools import slice_index

    with pytest.raises(SystemExit) as e:
        slice_index.main()
    assert e.value.code == 65


def test_refuses_when_features_are_out_of_order(tmp_path, monkeypatch):
    """어긋난 색인을 자르면 어긋난 채로 작아질 뿐이다. 그 전에 멈춘다."""
    monkeypatch.setenv("MUPID_POOL", "flow")
    from ai_server.tools import slice_index

    src, dst = tmp_path / "full", tmp_path / "serving"
    data = _build(src)
    feat = dict(np.load(data / "features_v1.npz", allow_pickle=False))
    feat["moment_id"] = feat["moment_id"][::-1]          # 순서만 뒤집는다
    np.savez(data / "features_v1.npz", **feat)

    with pytest.raises(SystemExit) as e:
        slice_index.slice_index(src, dst)
    assert e.value.code == 65


def test_report_records_what_was_dropped(sliced):
    _, out, report = sliced

    assert report["dropped"] > 0
    assert len(set(report["rows"].values())) == 1
    assert json.loads((out / "slice_report.json").read_text(encoding="utf-8"))["rows"]


# ------------------------------------------------- 배송 묶음이 갖춰야 하는 것

def test_features_info_row_count_follows_the_slice(sliced):
    """통째로 복사하면 65,540행이라고 적힌 채 잘린 npz 옆에 놓인다.

    기동을 막지 않아 더 나쁘다 — 어느 판이 올라가 있는지 이 파일로 확인할 때
    조용히 거짓말을 한다.
    """
    _, out, _ = sliced
    info = json.loads((out / "features_info.json").read_text(encoding="utf-8"))
    rows = len(pd.read_parquet(out / "moments.parquet"))

    assert info["features_rows"] == rows
    assert info["features_sha256"]
    assert info["version"] == "feat-v1"          # 기저 항목은 그대로


def test_id_map_travels_with_the_bundle(tmp_path, monkeypatch):
    """운영에는 `MUPID_ID_MAP` 이 없고 `/artifacts` 가 읽기 전용이다.

    그래서 매핑표가 노드에 놓일 길은 색인과 함께 올라가는 것뿐이다. 빠지면
    `pull_signals` 가 재생신호를 전부 버린다 — 2026-09-18 운영에서 실제로 그랬다.
    """
    monkeypatch.setenv("MUPID_POOL", "flow")
    from ai_server.tools import slice_index

    src, dst = tmp_path / "full", tmp_path / "serving"
    data = _build(src)
    pd.DataFrame({"source_track_id": ["T0"], "music_id": [1],
                  "external_id": ["T0#0"], "music_section_analysis_id": [9330]}
                 ).to_parquet(data / "id_map.parquet", index=False)

    report = slice_index.slice_index(src, dst)
    out = dst / "data" / "proc" / "mupid"

    assert (out / "id_map.parquet").exists()
    assert report["id_map"] == {"present": True, "rows": 1}


def test_missing_id_map_is_reported_not_swallowed(tmp_path, monkeypatch, capsys):
    """없는 것 자체는 막지 않는다 — 운영본을 나중에 넣을 수 있다. 다만 말은 한다."""
    monkeypatch.setenv("MUPID_POOL", "flow")
    from ai_server.tools import slice_index

    src, dst = tmp_path / "full", tmp_path / "serving"
    _build(src)
    report = slice_index.slice_index(src, dst)

    assert report["id_map"] == {"present": False}
    assert not (dst / "data" / "proc" / "mupid" / "id_map.parquet").exists()
    assert "id_map.parquet 이 판에 없다" in capsys.readouterr().err


def test_state_directories_never_ship(sliced):
    """`logs`(실측 51MB)·`serve` 는 판에만 있고 묶음에는 없다.

    `shrink_index`(판)와 갈리는 지점이다. 보내면 서빙 로그를 색인이라고 배포하게 된다.
    """
    _, out, _ = sliced
    assert not (out / "logs").exists()
    assert not (out / "serve").exists()


def test_src_dir_reads_a_versioned_plate(tmp_path, monkeypatch):
    """교체 전 버전 판을 잘라야 한다 — 확인 전에 교체부터 하게 되면 순서가 거꾸로다."""
    monkeypatch.setenv("MUPID_POOL", "flow")
    from ai_server.tools import slice_index

    src, dst = tmp_path / "full", tmp_path / "serving"
    _build(src)
    plate = src / "data" / "proc" / "mupid_v20260921-161142"
    (src / "data" / "proc" / "mupid").rename(plate)

    report = slice_index.slice_index(src, dst, src_data=plate)

    # 들어온 이름이 무엇이든 **나가는 자리는 언제나 mupid** 다.
    assert (dst / "data" / "proc" / "mupid" / "moments.parquet").exists()
    assert report["rows"]["moments"] > 0
