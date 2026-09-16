"""배치 ②~④ 경로의 왕복 재현 시험.

이식한 코드가 **기존 색인과 같은 값**을 만드는지 본다. 비교 대상은 산출물 파일이다 —
다른 환경에서 새로 뽑은 값끼리 비교하지 않는다. 모델이 학습된 공간은 저 파일이다.

| 단계 | GPU fp16 (판정) | CPU fp32 (구조 확인) | 비교 대상 |
|---|---|---|---|
| 임베딩 1536 | < 1e-6 | < 1e-3 | `moments_emb.npy` |
| 특징 285 | < 1e-5 | < 1e-3 | `features_v1.npz` |
| 구간벡터 128 | < 1e-6 | < 1e-3 | `stage1_section.npy` |

**판정은 산출물을 만든 것과 같은 조건(GPU fp16)으로 한다.** 원본이 `model.half()` 로
돌았기 때문이다. 실측해 보니 그쪽이 **비트 단위로 일치**하고(0.0), CPU float32 는 더
정확하지만 **다른 값**이라 1e-4 대로 갈린다. 저장본을 기준으로 삼는 이상 CPU 로는
1e-6 을 만족시킬 수 없다 — 산출물이 fp32 로 다시 만들어지면 그때 기준을 바꾼다.

GPU 가 없으면 느슨한 기준(1e-3)으로 "구조가 맞는지"만 본다. 그 통과는 재현 확인이
아니다.

음원과 torch 가 있어야 돌아간다. 없으면 통째로 건너뛴다 — CI 는 색인 없이 도는 것만
검사한다.

    MUPID_AI_ARTIFACT_DIR=/경로 MUPID_ANALYSIS_AUDIO=/음원폴더 \\
        python -m pytest ai_server/tests/test_analysis_pipeline.py -s
"""
from __future__ import annotations

import os
import sys
from pathlib import Path

import numpy as np
import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "music_preprocessing" / "src"))

from ai_server.app import paths                                       # noqa: E402

pipeline = pytest.importorskip("music_preprocessing.analysis.pipeline",
                               reason="analysis 선택 의존성이 없다")
emb = pytest.importorskip("music_preprocessing.analysis.embedder")
fs = pytest.importorskip("music_preprocessing.analysis.feature_space")
pytest.importorskip("torch", reason="torch 가 없다 (배치 전용 의존성)")
pytest.importorskip("librosa", reason="librosa 가 없다 (배치 전용 의존성)")

D = paths.data_dir()
NEEDED = ("moments.parquet", "moments_emb.npy", "features_v1.npz",
          "feature_stats_v1.npz", "features_info.json", "moment_zstats.npz",
          "stage1_section.npy", "stage1_weights.npz")
MISSING = [n for n in NEEDED if not (D / n).exists()]

# 음원은 산출물에 없다. 어디 있는지 환경변수로 받는다.
AUDIO_DIR = Path(os.environ.get("MUPID_ANALYSIS_AUDIO", ""))
SECTION_DIR = Path(os.environ.get("MUPID_ANALYSIS_SECTIONS", ""))

pytestmark = pytest.mark.skipif(
    bool(MISSING) or not AUDIO_DIR.is_dir() or not SECTION_DIR.is_dir(),
    reason=f"산출물/음원이 없다 (없는 산출물 {MISSING}, 음원 {AUDIO_DIR}, "
           f"구간 {SECTION_DIR})")

N_TRACKS = int(os.environ.get("MUPID_ANALYSIS_TRACKS", "3"))


# ────────────────────────────────────────────────────────── 준비

@pytest.fixture(scope="module")
def space():
    return fs.FeatureSpace(D)


@pytest.fixture(scope="module")
def stored():
    import pandas as pd

    mom = pd.read_parquet(D / "moments.parquet")
    content = pd.read_parquet(D / "content.parquet").set_index("track_id")
    return mom, content


@pytest.fixture(scope="module")
def cases(stored):
    """음원과 구간 JSON 이 둘 다 있는 곡만 고른다."""
    mom, content = stored
    out = []
    for tid in sorted(mom[mom.source == "flow"].track_id.unique()):
        audio = AUDIO_DIR / f"{tid}.mp3"
        secs = SECTION_DIR / f"{tid}.json"
        if audio.exists() and secs.exists() and tid in content.index:
            out.append(tid)
        if len(out) >= N_TRACKS:
            break
    if not out:
        pytest.skip("음원·구간이 함께 있는 flow 곡이 없다")
    return out


def _judge_device() -> tuple[str, bool]:
    """판정 장치와 엄격 여부. 산출물을 만든 조건과 같아야 엄격 판정이 성립한다."""
    import torch

    if torch.cuda.is_available():
        return "cuda:0", True
    return "cpu", False


@pytest.fixture(scope="module")
def strict():
    return _judge_device()[1]


@pytest.fixture(scope="module")
def computed(cases, stored, space):
    """산출물을 만든 조건으로 계산한다 (GPU fp16). 없으면 CPU 로 구조만 본다."""
    mom, content = stored
    dev, is_strict = _judge_device()
    print(f"\n판정 장치 {dev}  ({'엄격' if is_strict else '느슨 — 구조 확인만'})")
    engine = emb.Embedder(D, device=dev)
    out = {}
    for tid in cases:
        segs = pipeline.read_sections(SECTION_DIR / f"{tid}.json")
        meta = pipeline.read_track_stats(content.loc[tid].to_dict())
        r = pipeline.analyze_track(tid, AUDIO_DIR / f"{tid}.mp3", segs, meta,
                                   engine=engine, space=space)
        assert r.status == "ok", f"{tid} 실패: {r.stage} {r.error}"
        out[tid] = r
    return out


def rows_of(mom, tid):
    return mom[mom.track_id == tid].sort_values("section_idx")


# ────────────────────────────────────────────────────────── 재현 3단

def test_embedding_matches(computed, stored, strict):
    mom, _ = stored
    Z = np.load(D / "moments_emb.npy", mmap_mode="r")
    worst = 0.0
    for tid, r in computed.items():
        g = rows_of(mom, tid)
        assert len(g) == r.n_sections, (
            f"{tid}: 구간 수가 다르다 — 저장 {len(g)} vs 재계산 {r.n_sections}")
        ref = np.asarray(Z[g.emb_row.to_numpy()], dtype=np.float32)
        worst = max(worst, float(np.abs(r.embeddings - ref).max()))
    print(f"\n임베딩 1536  최대 절대오차 {worst:.3e}  (곡 {len(computed)})")
    tol = 1e-6 if strict else 1e-3
    assert worst < tol, f"임베딩 오차 {worst:.3e} > {tol:.0e}"


def test_features_match(computed, stored, strict):
    mom, _ = stored
    with np.load(D / "features_v1.npz", allow_pickle=False) as fz:
        X = np.asarray(fz["X"])
    worst = 0.0
    for tid, r in computed.items():
        idx = rows_of(mom, tid).index.to_numpy()
        worst = max(worst, float(np.abs(r.features - X[idx]).max()))
    print(f"특징 285     최대 절대오차 {worst:.3e}")
    tol = 1e-5 if strict else 1e-3
    assert worst < tol, f"285차 특징 오차 {worst:.3e} > {tol:.0e}"


def test_section_vectors_match(computed, stored, strict):
    mom, _ = stored
    S = np.load(D / "stage1_section.npy", mmap_mode="r")
    if len(S) != len(mom):
        pytest.skip(f"stage1_section 행 수가 다르다: {len(S)} vs {len(mom)}")
    worst = 0.0
    for tid, r in computed.items():
        idx = rows_of(mom, tid).index.to_numpy()
        worst = max(worst, float(np.abs(r.section_vecs - np.asarray(S[idx])).max()))
    print(f"구간벡터 128 최대 절대오차 {worst:.3e}")
    tol = 1e-6 if strict else 1e-3
    assert worst < tol, f"구간 벡터 오차 {worst:.3e} > {tol:.0e}"


def test_boundaries_and_labels_match(computed, stored):
    """구간 경계·라벨이 저장본과 같은가 (3초 미만 버림 규칙 포함)."""
    mom, _ = stored
    for tid, r in computed.items():
        g = rows_of(mom, tid)
        segs = emb.keep_segments(pipeline.read_sections(SECTION_DIR / f"{tid}.json"))
        assert len(segs) == len(g), f"{tid}: 구간 수 {len(segs)} vs {len(g)}"
        for s, row in zip(segs, g.itertuples()):
            assert abs(s.start - float(row.start_sec)) < 0.01, f"{tid} 시작이 다르다"
            assert abs(s.end - float(row.end_sec)) < 0.01, f"{tid} 끝이 다르다"
            assert str(s.label) == str(row.section_label), f"{tid} 라벨이 다르다"


def test_cpu_reference_only(cases, stored, space):
    """CPU float32 값은 **참고**로만 찍는다. 판정하지 않는다.

    더 정확한 계산이지만 저장본(fp16)과는 다른 값이다. 1e-4 대가 나오는 것이 정상이고,
    그것이 코드가 틀렸다는 뜻은 아니다. 자릿수가 그보다 크게 벌어지면 그때는 구조가
    어긋난 것이다.
    """
    import torch

    if not torch.cuda.is_available():
        pytest.skip("판정이 이미 CPU 로 돌았다")
    mom, _ = stored
    Z = np.load(D / "moments_emb.npy", mmap_mode="r")
    engine = emb.Embedder(D, device="cpu")
    worst = 0.0
    for tid in cases:
        segs = emb.keep_segments(pipeline.read_sections(SECTION_DIR / f"{tid}.json"))
        y = emb.load_audio(AUDIO_DIR / f"{tid}.mp3")
        got = engine.embed(y, segs)
        ref = np.asarray(Z[rows_of(mom, tid).emb_row.to_numpy()], dtype=np.float32)
        worst = max(worst, float(np.abs(got - ref).max()))
    print(f"[참고] CPU fp32 최대 절대오차 {worst:.3e}  (저장본은 fp16 이라 이 정도가 정상)")
    assert worst < 1e-2, f"CPU 값이 지나치게 벌어진다 — 구조를 의심한다: {worst:.3e}"


# ────────────────────────────────────────────────────────── 실패 경로

def test_missing_zstats_raises(tmp_path):
    """정규화 통계를 빼면 **조용히 계산하지 않고** 오류로 멈추는가."""
    with pytest.raises(emb.ArtifactMissing, match="정규화 통계가 없다"):
        emb.Embedder(tmp_path)


def test_keep_segments_rules():
    """양끝 표시와 3초 미만을 거르는가."""
    segs = [emb.Segment("start", 0.0, 0.5), emb.Segment("intro", 0.5, 2.0),
            emb.Segment("verse", 2.0, 30.0), emb.Segment("end", 30.0, 30.2)]
    kept = emb.keep_segments(segs)
    assert [s.label for s in kept] == ["verse"]


def test_state_file_is_atomic(tmp_path):
    """상태 파일이 임시 파일을 거쳐 바뀌는가 (반쪽 JSON 이 남지 않는다)."""
    p = tmp_path / "state.json"
    pipeline.save_state(p, {"version": 1, "tracks": {"T1": {"status": "ok"}}})
    assert pipeline.load_state(p)["tracks"]["T1"]["status"] == "ok"
    assert not list(tmp_path.glob("*.tmp")), "임시 파일이 남았다"

    p.write_text("{깨진", encoding="utf-8")
    # 깨진 기록으로 전부 건너뛰는 것보다 처음부터 도는 편이 안전하다.
    assert pipeline.load_state(p)["tracks"] == {}


def test_bpm_prefers_requested():
    """`bpm_requested` 가 있으면 그쪽을 쓴다 (추천기 규칙과 맞춘다)."""
    assert pipeline.read_track_stats({"bpm": 90.0, "bpm_requested": 128.0}).bpm == 128.0
    assert pipeline.read_track_stats({"bpm": 90.0}).bpm == 90.0
    assert pipeline.read_track_stats({"bpm": 90.0, "bpm_requested": ""}).bpm == 90.0
