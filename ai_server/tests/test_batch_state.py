"""배치 상태와 새 판 — 색인 없이 확인할 수 있는 것들.

여기서 보는 네 가지는 전부 **조용히 나빠지는** 종류다. 오류는 나지 않고, 그날 로그도
정상으로 보이고, 며칠 뒤에 "왜 이 곡만 추천에 안 나오나" 나 "왜 디스크가 찼나" 로 돌아온다.

- 요구 5 가 준 `music_id` 를 등록까지 들고 가는가
- `ok` 는 **교체가 끝난 곡만** 받는가
- 기록이 색인과 어긋나면 어느 쪽을 믿는가
- 붙지 못한 새 판이 쌓이지 않는가

산출물(모델·색인)은 필요 없다. 임시 디렉터리만 쓴다.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "music_preprocessing" / "src"))

bt = pytest.importorskip("music_preprocessing.analysis.batch",
                         reason="analysis 선택 의존성이 없다")
ap = pytest.importorskip("music_preprocessing.analysis.api")
ix = pytest.importorskip("music_preprocessing.analysis.indexer")
pl = pytest.importorskip("music_preprocessing.analysis.pipeline")
pd = pytest.importorskip("pandas")


# ────────────────────────────────────────────────── 1. 요구 5 의 music_id

class _FakeApi:
    """`prepare()` 가 부르는 것은 다운로드 하나다. 그것만 흉내낸다."""

    def download(self, url: str, dst: Path, sha256: str) -> str:
        Path(dst).parent.mkdir(parents=True, exist_ok=True)
        Path(dst).write_bytes(b"audio")
        return sha256


def test_prepare_carries_the_music_id_it_was_given(tmp_path):
    """요구 5 가 준 숫자를 등록 단계까지 들고 간다.

    빠뜨리면 `HttpRegistrar` 가 "music_id 를 모르는 곡" 으로 보고 배치 전체가 보류된다.
    분석은 다 끝난 뒤라 비용은 이미 치렀고, 그 상태가 매일 반복된다.
    """
    track = {"music_id": 4242, "audio_download_url": "http://x/1",
             "audio_sha256": "0" * 64, "bpm_requested": 120}
    t = ap.prepare(track, _FakeApi(), tmp_path,
                   load=lambda p: None, measure=lambda y: {"centroid": 1.0})

    assert t.music_id == 4242
    assert isinstance(t.music_id, int), "요구 1 은 숫자를 요구한다"
    assert t.track_id == "4242"


def test_registrar_no_longer_refuses_a_prepared_track(tmp_path):
    """`prepare()` 로 만든 곡은 등록기가 받아야 한다 — 그 전에는 전부 거부됐다."""
    track = {"music_id": 4243, "audio_download_url": "http://x/1",
             "audio_sha256": "0" * 64}
    t = ap.prepare(track, _FakeApi(), tmp_path,
                   load=lambda p: None, measure=lambda y: {})
    assert getattr(t, "music_id", None) is not None, \
        "등록기는 이 값이 없으면 곡 전체를 거부한다(api.HttpRegistrar.register)"


# ────────────────────────────────────────────────── 2. ok 는 교체 뒤에만

def _state(path: Path, **tracks) -> Path:
    pl.save_state(path, {"version": pl.STATE_VERSION, "tracks": dict(tracks)})
    return path


def test_mark_indexed_promotes_only_the_named_tracks(tmp_path):
    p = _state(tmp_path / "s.json",
               A={"status": pl.STATUS_ANALYZED, "audio_sha256": "a"},
               B={"status": pl.STATUS_ANALYZED, "audio_sha256": "b"})

    assert bt.mark_indexed(p, ["A"]) == 1
    got = pl.load_state(p)["tracks"]
    assert got["A"]["status"] == pl.STATUS_OK
    assert got["B"]["status"] == pl.STATUS_ANALYZED, "교체된 곡만 올라가야 한다"


def test_mark_indexed_ignores_tracks_it_never_saw(tmp_path):
    """모르는 곡에 `ok` 를 지어 넣지 않는다. 그 기록이 다음 배치의 건너뛰기가 된다."""
    p = _state(tmp_path / "s.json", A={"status": pl.STATUS_ANALYZED})
    assert bt.mark_indexed(p, ["없는곡"]) == 0
    assert "없는곡" not in pl.load_state(p)["tracks"]


def test_analyzed_is_not_ok(tmp_path):
    """두 값이 같으면 이 장치 전체가 무의미하다."""
    assert pl.STATUS_ANALYZED != pl.STATUS_OK


# ────────────────────────────────────────────────── 3. 색인이 정본이다

def _moments(data: Path, track_ids: list) -> Path:
    data.mkdir(parents=True, exist_ok=True)
    pd.DataFrame({"track_id": [str(t) for t in track_ids]}).to_parquet(
        data / "moments.parquet", index=False)
    return data


def test_indexed_ids_come_from_the_index(tmp_path):
    data = _moments(tmp_path / "mupid", ["T1", "T2"])
    assert bt._indexed_track_ids(data) == {"T1", "T2"}


def test_missing_index_means_no_judgement(tmp_path):
    """색인을 못 읽으면 **판단하지 않는다.** 없다고 단정하면 전곡을 다시 돈다."""
    assert bt._indexed_track_ids(tmp_path / "없는곳") is None


def test_a_broken_index_does_not_stop_the_batch(tmp_path):
    data = tmp_path / "mupid"
    data.mkdir()
    (data / "moments.parquet").write_bytes(b"not a parquet file")
    assert bt._indexed_track_ids(data) is None


# ────────────────────────────────────────────────── 4. 새 판이 쌓이지 않는다

def test_prune_staged_removes_only_unswapped_plates(tmp_path):
    """`_v*` 는 붙지 못한 새 판, `_prev*` 는 롤백용 이전 판이다. 뒤엣것은 건드리지 않는다."""
    data = tmp_path / "mupid"
    for name in ("mupid", "mupid_v20260101-000000", "mupid_v20260102-000000",
                 "mupid_prev20260101-000000"):
        (tmp_path / name).mkdir()

    dropped = ix.prune_staged(data)

    assert sorted(dropped) == ["mupid_v20260101-000000", "mupid_v20260102-000000"]
    assert (tmp_path / "mupid").is_dir(), "지금 쓰는 판을 지웠다"
    assert (tmp_path / "mupid_prev20260101-000000").is_dir(), "롤백용 판을 지웠다"


def test_prune_staged_can_keep_the_newest(tmp_path):
    data = tmp_path / "mupid"
    for name in ("mupid", "mupid_v20260101-000000", "mupid_v20260102-000000"):
        (tmp_path / name).mkdir()

    dropped = ix.prune_staged(data, keep=1)

    assert dropped == ["mupid_v20260101-000000"]
    assert (tmp_path / "mupid_v20260102-000000").is_dir()


def test_prune_staged_never_touches_the_plate_in_use(tmp_path):
    data = tmp_path / "mupid"
    keep_me = tmp_path / "mupid_v20260103-000000"
    for p in (data, tmp_path / "mupid_v20260101-000000", keep_me):
        p.mkdir()

    dropped = ix.prune_staged(data, exclude=keep_me)

    assert dropped == ["mupid_v20260101-000000"]
    assert keep_me.is_dir()


# ────────────────────────────────────────────────── 5. 요약에 남는다

def test_summary_carries_the_cleanup_and_promotion_counts(tmp_path):
    """운영은 요약 한 줄로 판단한다. 지운 판과 올린 곡이 거기 없으면 볼 길이 없다."""
    s = bt.BatchSummary()
    s.pruned_staged = ["mupid_v20260101-000000"]
    s.marked_ok = 3
    got = json.loads(s.as_line())
    assert got["pruned_staged"] == ["mupid_v20260101-000000"]
    assert got["marked_ok"] == 3
