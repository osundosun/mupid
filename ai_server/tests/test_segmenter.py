"""① 분할 실행기 — 계약과 **실패 경로**.

GPU·음원·All-In-One 이 있는 곳에서만 진짜 분할이 돈다. CI 에는 셋 다 없다. 그래서
여기서 보는 것은 대부분 **없을 때 어떻게 되는가**다.

가장 중요한 것: 분할기를 부를 수 없을 때 **무엇이 없는지 말하고 멈춘다.** 조용히 빈
결과를 돌려주면 그 곡이 "구간 0개" 로 색인에 들어가거나 실패가 성공으로 보고된다.

실제 분할 재현(경계·라벨 8/8)은 GPU 박스에서 잰다 — `ai_server/docs/ANALYSIS-BATCH.md`.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "music_preprocessing" / "src"))

sg = pytest.importorskip("music_preprocessing.analysis.segmenter",
                         reason="analysis 선택 의존성이 없다")

HAVE_ALLIN1 = sg.allin1_available()[0]


# ────────────────────────────────────────────── 1. 옮겨 적은 설정은 바뀌면 안 된다

def test_allin1_settings_match_the_recipe():
    """색인의 flow 6,767구간이 이 설정으로 만들어졌다.

    정본은 GPU 박스의 `~/rec_mupid/src/allin1_flow.py` 다. 하나라도 다르면 신곡의
    경계가 기존 곡과 **다른 규칙으로** 잘린다 — 오류는 나지 않고 추천만 어긋난다.
    """
    assert sg.ALLIN1_MODEL == "harmonix-all"
    assert sg.ALLIN1_DEVICE == "cuda:0"
    assert sg.ALLIN1_BATCH == 8
    assert sg.ALLIN1_MULTIPROCESS is False
    assert sg.ALLIN1_KEEP_BYPRODUCTS is True


def test_runner_only_runs_the_model(tmp_path):
    """자식 프로세스는 **모델 실행만** 한다. 스키마 변환은 부모 한 곳에서 한다."""
    src = sg._RUNNER
    assert "allin1.analyze" in src
    assert "mupid.sections" not in src and "duration_sec" not in src


# ────────────────────────────────────────────── 2. 없을 때 무엇이라고 말하는가

@pytest.mark.skipif(HAVE_ALLIN1, reason="이 환경에는 All-In-One 이 있다")
def test_missing_allin1_says_what_is_missing():
    ok, why = sg.allin1_available()
    assert not ok
    assert "allin1" in why


@pytest.mark.skipif(HAVE_ALLIN1, reason="이 환경에는 All-In-One 이 있다")
def test_run_segmenter_raises_instead_of_returning_nothing(tmp_path):
    """조용히 빈 결과를 돌려주지 않는다."""
    audio = tmp_path / "T999999.mp3"
    audio.write_bytes(b"not really audio")
    with pytest.raises(sg.SegmenterUnavailable) as e:
        sg.run_segmenter(audio, tmp_path / "out.json", work_dir=tmp_path / "w")
    msg = str(e.value)
    assert "allin1" in msg or "3.10" in msg, msg
    assert not (tmp_path / "out.json").exists(), "실패했는데 파일을 남겼다"


def test_missing_audio_is_a_failure_not_a_crash(tmp_path):
    """곡 하나가 없다고 배치가 멈추면 그날 들어온 곡이 전부 밀린다."""
    seg = sg.Segmenter(work_dir=tmp_path / "w", out_dir=tmp_path / "o")
    got = seg.analyze([("T000001", tmp_path / "없는파일.mp3")])
    assert got == {}
    assert seg.failures["T000001"]["stage"] == "segment"
    assert "음원이 없다" in seg.failures["T000001"]["error"]


def test_unknown_python_is_reported_with_the_path(tmp_path):
    seg = sg.Segmenter(work_dir=tmp_path / "w", out_dir=tmp_path / "o",
                       python=str(tmp_path / "no-such-python"))
    audio = tmp_path / "T000001.mp3"
    audio.write_bytes(b"x")
    got = seg.analyze([("T000001", audio)])
    assert got == {}
    assert "파이썬을 찾지 못했다" in seg.failures["T000001"]["error"]


@pytest.mark.skipif(HAVE_ALLIN1, reason="자식이 성공해 버린다")
def test_child_failure_keeps_exit_code_and_stderr(tmp_path):
    """자식이 죽으면 **종료 코드와 stderr 끝부분**을 남긴다. 원인을 못 찾으면 못 고친다."""
    seg = sg.Segmenter(work_dir=tmp_path / "w", out_dir=tmp_path / "o",
                       python=sys.executable)          # allin1 이 없는 인터프리터
    audio = tmp_path / "T000001.mp3"
    audio.write_bytes(b"x")
    got = seg.analyze([("T000001", audio)])
    assert got == {}
    err = seg.failures["T000001"]["error"]
    assert "종료 코드" in err
    assert "ModuleNotFoundError" in err or "allin1" in err, err


@pytest.mark.skipif(HAVE_ALLIN1, reason="자식이 성공해 버린다")
def test_one_bad_track_does_not_hide_the_others(tmp_path):
    """묶음이 깨지면 한 곡씩 다시 돌려 **곡별로** 이유를 남긴다."""
    seg = sg.Segmenter(work_dir=tmp_path / "w", out_dir=tmp_path / "o",
                       python=sys.executable, batch=8)
    a = tmp_path / "T000001.mp3"
    a.write_bytes(b"x")
    got = seg.analyze([("T000001", a), ("T000002", tmp_path / "없다.mp3")])
    assert got == {}
    assert set(seg.failures) == {"T000001", "T000002"}
    assert "음원이 없다" in seg.failures["T000002"]["error"]


def test_missing_result_file_is_an_error(tmp_path):
    """분할이 돌지 않았는데 변환을 시도하면 멈춘다."""
    seg = sg.Segmenter(work_dir=tmp_path / "w", out_dir=tmp_path / "o")
    with pytest.raises(sg.SegmenterUnavailable):
        seg.to_sections("T000001", tmp_path / "T000001.mp3")


# ────────────────────────────────────────────── 3. 스키마 검증 (GPU 없이 돈다)

def _file(segments, dur=100.0):
    return sg.SectionsFile(track_id="T000001", duration_sec=dur,
                           segments=[sg.Segment(*s) for s in segments])


def test_overlap_is_rejected():
    """겹치면 같은 프레임이 두 구간에 들어가 임베딩이 중복된다."""
    p = sg.validate(_file([("intro", 0.0, 20.0), ("verse", 15.0, 40.0)]))
    assert any("겹친다" in x for x in p), p


def test_out_of_range_is_rejected():
    p = sg.validate(_file([("intro", 0.0, 20.0), ("verse", 20.0, 150.0)]))
    assert any("곡 길이를 넘는다" in x for x in p), p


def test_unknown_label_is_rejected():
    p = sg.validate(_file([("intro", 0.0, 20.0), ("hook", 20.0, 40.0)]))
    assert any("어휘에 없다" in x for x in p), p


def test_all_short_sections_is_rejected():
    """3초 미만만 남으면 색인에 넣을 것이 없다."""
    p = sg.validate(_file([("start", 0.0, 1.0), ("intro", 1.0, 2.0)]))
    assert any("남는 구간이 없다" in x for x in p), p


def test_valid_file_has_no_problems():
    f = _file([("start", 0.0, 2.43), ("verse", 2.43, 39.0), ("chorus", 39.0, 66.42)])
    assert sg.validate(f) == []
    # 거르는 규칙은 `keep_segments` 한 곳에 있다 — start 마커가 빠진다.
    assert [s.label for s in f.kept()] == ["verse", "chorus"]


def test_round_trip_keeps_the_schema(tmp_path):
    f = sg.SectionsFile(track_id="T000001", duration_sec=176.576,
                        segments=[sg.Segment("start", 0.0, 2.43),
                                  sg.Segment("verse", 2.43, 39.0)],
                        segmenter={"name": "allin1", "model": "harmonix-all"})
    p = sg.dump_sections(f, tmp_path / "s.json")
    d = json.loads(p.read_text(encoding="utf-8"))
    assert d["schema"] == sg.SCHEMA
    back = sg.load_sections(p)
    assert back.track_id == f.track_id
    assert back.duration_sec == pytest.approx(f.duration_sec)
    assert len(back.segments) == 2


def test_other_schema_is_refused(tmp_path):
    """판이 다르면 열 뜻이 달라질 수 있다. 조용히 읽지 않는다."""
    p = tmp_path / "s.json"
    p.write_text(json.dumps({"schema": "mupid.sections/v2", "track_id": "T1",
                             "duration_sec": 10.0, "segments": []}), encoding="utf-8")
    with pytest.raises(sg.SectionsError) as e:
        sg.load_sections(p)
    assert "schema" in str(e.value)


# ────────────────────────────────────────────── 4. 진짜 분할 (GPU 박스에서만)

AUDIO_ENV = "MUPID_SEG_TEST_AUDIO"


@pytest.mark.skipif(not HAVE_ALLIN1, reason="All-In-One 이 없다")
def test_real_track_produces_v1_json(tmp_path):
    """음원 하나 → `mupid.sections/v1`. 음원 경로는 환경변수로 준다."""
    import os

    src = os.environ.get(AUDIO_ENV, "")
    if not src or not Path(src).exists():
        pytest.skip(f"{AUDIO_ENV} 에 음원 경로를 준다")
    out = sg.run_segmenter(Path(src), tmp_path / "s.json",
                           work_dir=tmp_path / "w")
    d = json.loads(Path(out).read_text(encoding="utf-8"))
    assert d["schema"] == sg.SCHEMA
    assert d["segmenter"]["model"] == sg.ALLIN1_MODEL
    assert d["duration_sec"] > 0
    assert sg.load_sections(out).kept()
