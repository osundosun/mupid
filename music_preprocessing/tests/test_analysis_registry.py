"""로컬 수집과 자식 분석기 — 외부 서버·GPU 없이 계약만 검증한다.

핵심은 **값의 출처를 섞지 않는 것**이다. 색인의 `genre_top1`·`mood_tags` 는
metadata 의 `genre`·`mood` 와 다른 값이고(실측), 태그는 쉼표로 이은 문자열이어야
parquet 쓰기가 멈추지 않는다. 여기서 그 두 가지를 못 박는다.
"""
import json
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path

from music_preprocessing.analysis import registry as rg
from music_preprocessing.analysis import pipeline as pl


def metadata(tid, *, category="male_english_pop", vocal="male", lang="english",
             bpm=(95, 105), title="A Song", sound="Pop, 100 BPM"):
    """`flow_music_data/metadata/T*.json` 한 건. 실제 구조를 그대로 흉내 낸다."""
    return {
        "schema_version": 2, "track_id": tid, "flow_title": title,
        "requested": {"category": category, "vocal_type": vocal, "language": lang,
                      "bpm_range": list(bpm)},
        "flow_generated": {"sound_raw": sound, "lyrics_raw": "[Intro]\n…"},
        "genre": "contemporary_pop",                 # ← 색인의 genre_top1 과 다른 값
        "mood": {"primary": "bright", "secondary": "warm"},
        "lyrics_sections": [{"type": "intro"}, {"type": "chorus"}],
    }


class TagsTests(unittest.TestCase):
    def test_confident_labels_become_a_comma_string(self):
        """`tracks.parquet` 의 `*_json` → 쉼표로 이은 문자열.

        배열을 그대로 넣으면 parquet 쓰기가 타입 오류로 멈춘다(`flow_meta` 는
        `large_string` 이다).
        """
        raw = json.dumps([{"label": "piano", "prob": 0.9, "confident": True},
                          {"label": "drums", "prob": 0.7, "confident": True}])
        self.assertEqual(rg._tags(raw), "piano, drums")

    def test_low_confidence_labels_are_dropped(self):
        raw = json.dumps([{"label": "piano", "prob": 0.9, "confident": True},
                          {"label": "harp", "prob": 0.2, "confident": False}])
        self.assertEqual(rg._tags(raw), "piano")

    def test_probability_is_dropped_and_duplicates_collapse(self):
        """순위는 `label` 만 본다. 확률은 색인에 넣지 않는다."""
        raw = json.dumps([{"label": "piano", "prob": 0.9, "confident": True},
                          {"label": "piano", "prob": 0.8, "confident": True}])
        self.assertEqual(rg._tags(raw), "piano")

    def test_empty_and_broken_input_do_not_raise(self):
        """태그는 곁들이는 값이다. 없다고 배치가 멈추면 안 된다."""
        for v in (None, "", "[]", "not json"):
            self.assertIsInstance(rg._tags(v), str)


class ApiTrackShapeTests(unittest.TestCase):
    def test_requested_bpm_range_becomes_a_single_value(self):
        """`meta_row` 는 `bpm_requested`(단일)를 읽는다. 범위의 가운데를 쓴다."""
        t = rg._as_api_track("T000001", metadata("T000001", bpm=(100, 110)), 7)
        self.assertAlmostEqual(t["bpm_requested"], 105.0)

    def test_meta_row_takes_sound_raw_from_flow_generated(self):
        """`sound_raw` 는 `flow_generated` 아래에 있다 — 이름이 다르다."""
        t = rg._as_api_track("T000001", metadata("T000001", sound="Synth-pop, 118 BPM"), 7)
        self.assertEqual(t["sound_raw"], "Synth-pop, 118 BPM")

    def test_missing_metadata_does_not_raise(self):
        t = rg._as_api_track("T000009", {}, 9)
        self.assertEqual(t["music_id"], 9)
        self.assertEqual(t["title"], "T000009")
        self.assertIsNone(t["bpm_requested"])


class RegistryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.flow = self.tmp / "flow_music_data"
        (self.flow / "audio").mkdir(parents=True)
        (self.flow / "metadata").mkdir(parents=True)
        self.root = self.tmp / "rec_mupid" / "data" / "proc" / "mupid"
        self.root.mkdir(parents=True)

    def _registry(self, *, registered=None, tags=None):
        reg = self.tmp / "registered.json"
        reg.write_text(json.dumps(registered if registered is not None
                                  else {"tracks": {
                                      str(self.flow / "audio" / "T000001.mp3"):
                                          {"music_id": 11}}}, ensure_ascii=False),
                       encoding="utf-8")
        tag_file = None
        if tags is not None:
            import pandas as pd
            tag_file = self.tmp / "tracks.parquet"
            pd.DataFrame(tags).to_parquet(tag_file, index=False)
        return rg.LocalRegistry(flow_dir=self.flow, artifact_root=self.tmp / "rec_mupid",
                                registered_json=reg, tag_file=tag_file)

    def _audio(self, tid):
        (self.flow / "audio" / f"{tid}.mp3").write_bytes(b"\0" * 32)
        (self.flow / "metadata" / f"{tid}.json").write_text(
            json.dumps(metadata(tid), ensure_ascii=False), encoding="utf-8")

    def test_music_ids_come_from_registered_json(self):
        """숫자 ID 는 Spring 이 발급한 값이다 — 지어내지 않는다."""
        lib = self._registry(registered={"tracks": {
            str(self.flow / "audio" / "T000001.mp3"): {"music_id": 11},
            str(self.flow / "audio" / "T000007.mp3"): {"music_id": "17"},
        }})
        got = lib.music_ids()
        self.assertEqual(got["T000001"], 11)
        self.assertEqual(got["T000007"], 17)      # 문자열도 정수로

    def test_registered_json_missing_raises(self):
        """표가 없으면 **멈춘다.** 없는 채로 돌면 곡이 조용히 사라진다."""
        lib = rg.LocalRegistry(flow_dir=self.flow, artifact_root=self.tmp,
                               registered_json=self.tmp / "nope.json", tag_file=None)
        with self.assertRaises(rg.RegistryError):
            lib.music_ids()

    def test_unregistered_track_is_skipped_not_failed(self):
        """등록 안 된 곡(jamendo 등)은 건너뛰고 **센다.**"""
        self._audio("T000002")
        lib = self._registry(registered={"tracks": {
            str(self.flow / "audio" / "T000001.mp3"): {"music_id": 11}}})
        tracks, info = lib.build(["T000002"], sections_of=lambda t: self.tmp / f"{t}.json",
                                 load=lambda p: None, measure=lambda y: {"bpm": 1.0})
        self.assertEqual(tracks, [])
        self.assertIn("T000002", info["skipped"])

    def test_missing_audio_is_skipped(self):
        lib = self._registry(registered={"tracks": {
            str(self.flow / "audio" / "T000003.mp3"): {"music_id": 13}}})
        tracks, info = lib.build(["T000003"], sections_of=lambda t: self.tmp / f"{t}.json",
                                 load=lambda p: None, measure=lambda y: {"bpm": 1.0})
        self.assertEqual(tracks, [])
        self.assertIn("음원이 없다", info["skipped"]["T000003"])

    def test_tags_land_in_the_meta_row_as_a_comma_string(self):
        """태그가 색인 행까지 **문자열**로 간다. 배열이면 parquet 이 멈춘다."""
        self._audio("T000001")
        lib = self._registry(
            tags=[{"track_id": "T000001", "genre_top1": "Pop---Ballad",
                   "mood_json": json.dumps([{"label": "love", "confident": True}]),
                   "instrument_json": json.dumps([{"label": "piano", "confident": True}]),
                   "voice_label": "voice", "gender_label": "female"}])
        tracks, _ = lib.build(["T000001"], sections_of=lambda t: self.tmp / f"{t}.json",
                              load=lambda p: None, measure=lambda y: {"bpm": 100.0})
        row = tracks[0].meta_row
        self.assertEqual(row["genre_top1"], "Pop---Ballad")
        self.assertEqual(row["mood_tags"], "love")
        self.assertEqual(row["instrument_tags"], "piano")
        self.assertEqual(row["voice_label"], "voice")
        self.assertEqual(row["gender_label"], "female")

    def test_without_tag_file_the_row_is_still_complete(self):
        """태그 산출물이 없어도 배치는 돈다 — 빈 값으로 넣고 **이유를 남긴다.**"""
        self._audio("T000001")
        lib = self._registry()
        tracks, info = lib.build(["T000001"], sections_of=lambda t: self.tmp / f"{t}.json",
                                 load=lambda p: None, measure=lambda y: {"bpm": 100.0})
        self.assertEqual(tracks[0].meta_row["genre_top1"], "")
        self.assertTrue(any("태깅 산출물이 없다" in w for w in info["warn"]))

    def test_demix_root_is_carried_to_the_batch_track(self):
        """구간별 악기는 분할기가 남긴 스템에서 읽는다."""
        self._audio("T000001")
        lib = self._registry()
        tracks, _ = lib.build(["T000001"], sections_of=lambda t: self.tmp / f"{t}.json",
                              demix_root=self.tmp / "demix",
                              load=lambda p: None, measure=lambda y: {"bpm": 100.0})
        self.assertEqual(tracks[0].demix_root, self.tmp / "demix")


class ChildAnalyzerTests(unittest.TestCase):
    """②③④ 를 다른 인터프리터에서 돌리는 경로.

    실제 모델을 올리지 않고 **프로토콜**만 본다 — 자식은 `ANALYZE_OK` 뒤에 JSON 을
    쓰고, 부모는 그걸 `TrackResult` 로 되살린다. `runner=` 로 가짜 자식을 넣는 이유는
    실제 러너가 `FeatureSpace`(PCA 기저)와 MERT 를 요구해 시험에서 못 돌리기 때문이다.
    """

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())

    def _analyzer(self, body: str, **kw):
        return pl.ChildAnalyzer(python=sys.executable, data_dir=self.tmp,
                                src_root=self.tmp, runner=textwrap.dedent(body), **kw)

    def _item(self, tid):
        return _FakeTrack(tid, content_row={"bpm": 100.0}, audio_path=self.tmp / "a.mp3")

    def _sections(self):
        return _FakeSections([("verse", 0.0, 10.0)])

    def test_arrays_come_back_as_numpy(self):
        an = self._analyzer('''
            import json, sys
            payload = json.loads(sys.stdin.read())
            print("ANALYZE_OK")
            sys.stdout.write(json.dumps([{
                "track_id": payload["tracks"][0]["track_id"], "status": "ok",
                "stage": "done", "n_sections": 2, "error": "",
                "instrument_error": "", "rows": [{"moment_id": "T1#0"}],
                "embeddings": [[0.1, 0.2]], "features": [[1.0]], "section_vecs": [[2.0]]}]))
        ''')
        got = an.run([(self._item("T1"), self._sections())])
        r = got["T1"]
        self.assertEqual(r.status, "ok")
        self.assertEqual(r.embeddings.shape, (1, 2))
        self.assertEqual(r.features.shape, (1, 1))
        self.assertEqual(len(r.rows), 1)

    def test_nonzero_exit_becomes_a_failure_not_an_exception(self):
        """한 곡이 깨져도 배치 전체가 멈추면 안 된다."""
        an = self._analyzer('''
            import sys
            sys.stderr.write("boom")
            sys.exit(3)
        ''')
        got = an.run([(self._item("T2"), self._sections())])
        self.assertEqual(got, {})
        self.assertIn("종료 코드 3", an.failures["T2"]["error"])

    def test_success_marker_without_body_is_a_failure(self):
        """`ANALYZE_OK` 만 찍고 JSON 이 없으면 실패로 둔다 — 빈 성공으로 넘기지 않는다."""
        an = self._analyzer('print("ANALYZE_OK")')
        got = an.run([(self._item("T4"), self._sections())])
        self.assertEqual(got, {})
        self.assertIn("T4", an.failures)

    def test_missing_interpreter_is_reported_by_name(self):
        an = pl.ChildAnalyzer(python=str(self.tmp / "no-such-python"),
                              data_dir=self.tmp, src_root=self.tmp, runner="pass")
        got = an.run([(self._item("T3"), self._sections())])
        self.assertEqual(got, {})
        self.assertIn("찾지 못했다", an.failures["T3"]["error"])

    def test_runner_forbids_pandas(self):
        """3.10 환경에는 pandas 가 없다 — 러너가 pandas 를 import 하면 안 된다.

        여기서 잡지 않으면 배치가 ②③④ 진입에서 죽는다(`uv sync --extra analysis`
        를 아무리 해도 3.10 에는 pandas 가 없다). 같은 이유로 러너는 ⑤⑥(색인 쓰기)을
        하지 않는다 — 부모가 한다.
        """
        self.assertNotIn("import pandas", pl._RUNNER)
        self.assertNotIn("pyarrow", pl._RUNNER)
        self.assertNotIn("indexer", pl._RUNNER)


class _FakeTrack:
    def __init__(self, tid, *, content_row, audio_path):
        self.track_id = tid
        self.content_row = content_row
        self.audio_path = audio_path
        self.demix_root = None


class _FakeSections:
    def __init__(self, segs):
        self.segments = [pl.emb.Segment(label=l, start=s, end=e) for l, s, e in segs]


if __name__ == "__main__":
    unittest.main()
