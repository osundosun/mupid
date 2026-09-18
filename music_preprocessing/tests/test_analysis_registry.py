"""로컬 수집과 자식 분석기 — 외부 서버·GPU 없이 계약만 검증한다.

핵심은 **값의 출처를 섞지 않는 것**이다. 색인의 `genre_top1`·`mood_tags` 는
metadata 의 `genre`·`mood` 와 다른 값이고(실측), 태그는 쉼표로 이은 문자열이어야
parquet 쓰기가 멈추지 않는다. 여기서 그 두 가지를 못 박는다.
"""
import argparse
import json
import subprocess
import sys
import tempfile
import textwrap
import unittest
from pathlib import Path
from unittest import mock

from music_preprocessing.analysis import batch as ab
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


class TagFileDiscoveryTests(unittest.TestCase):
    """태깅 산출물의 **기본 자리**를 찾는가.

    `root` 는 `<artifact>/data/proc/mupid` 이고 태깅 산출물은 `<artifact>/data/
    test_pipeline/tracks.parquet` 이다 — **두 단계** 위다. 한 단계로 잡으면 못 찾고,
    그러면 태그 없이 색인이 만들어져 299곡이 빈 태그로 고정된다. 오류는 나지 않는다.

    실제 디렉터리 구조를 만들어 확인한다(경로 계산은 문자열 비교로는 안 잡힌다).
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.artifact = Path(self.tmp.name) / "art"
        self.root = self.artifact / "data" / "proc" / "mupid"
        self.root.mkdir(parents=True)
        self.cfg = Path(self.tmp.name) / "config.json"
        self.cfg.write_text("{}", encoding="utf-8")

    def _args(self, sections_dir=None):
        ns = argparse.Namespace()
        ns.config = str(self.cfg)
        ns.sections_dir = sections_dir
        ns.allin1_dir = None
        ns.demix_dir = None
        return ns

    def test_default_location_is_two_levels_up(self):
        got = self.artifact / "data" / "test_pipeline" / "tracks.parquet"
        got.parent.mkdir(parents=True)
        got.write_bytes(b"x")
        found = ab._tag_file(self.cfg, self._args(), self.root)
        self.assertEqual(found, got)

    def test_missing_tag_file_is_not_an_error(self):
        """태그가 없어도 배치는 돈다 — 빈 값으로 넣고 이유를 남긴다."""
        self.assertIsNone(ab._tag_file(self.cfg, self._args(), self.root))

    def test_config_value_wins(self):
        explicit = Path(self.tmp.name) / "other.parquet"
        explicit.write_bytes(b"x")

        class C:
            analysis_tag_file = str(explicit)

        self.assertEqual(ab._tag_file(C(), self._args(), self.root), explicit)


class LocalCollectGuardsTests(unittest.TestCase):
    """`--local` 이 **조용히 틀리지 않는가.**

    두 가지 실패 모드를 막는다. 둘 다 오류 없이 지나가고 나중에야 드러난다.

    1. 3.10 인터프리터가 없으면 ②③④ 진입에서 죽는다 — 부모(3.12)에는 torch 가 없다.
       이건 그나마 시끄럽다. 그래서 **미리 멈춘다**(무엇이 없는지 말하고).
    2. `analysis_limit`(요구 5 한 페이지 크기)을 `--local` 에 그대로 쓰면 남은 곡이
       조용히 빠진다. 299곡 중 200곡만 분석되고 나머지는 영영 안 들어간다.
    """

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.artifact = Path(self.tmp.name) / "art"
        self.root = self.artifact / "data" / "proc" / "mupid"
        self.root.mkdir(parents=True)
        # 색인 최소 요건 — `main()` 이 moments 존재를 본다
        (self.root / "moments.parquet").write_bytes(b"x")
        self.cfg_file = Path(self.tmp.name) / "config.json"
        self.cfg_file.write_text(json.dumps({
            "analysis_artifact_dir": str(self.artifact),
            "analysis_work_dir": "./analysis_work",
        }), encoding="utf-8")

    def _args(self, **kw):
        ns = argparse.Namespace(
            manifest=None, config=str(self.cfg_file), data_dir=None, device=None,
            local=True, flow_dir=None, all_tracks=False, allin1_dir=None,
            demix_dir=None, sections_dir=None, segmenter_python=None, limit=0,
            keep=None, force_unlock=False)
        for k, v in kw.items():
            setattr(ns, k, v)
        return ns

    def test_missing_interpreter_stops_with_a_reason(self):
        """3.10 인터프리터가 없으면 **무엇이 없는지 말하고 멈춘다.**"""
        with self.assertRaises(SystemExit) as got:
            ab._collect_local(self._args(), ab._load_config(str(self.cfg_file)))
        self.assertIn("3.10", str(got.exception))

    def test_bogus_interpreter_path_stops(self):
        with self.assertRaises(SystemExit) as got:
            ab._collect_local(self._args(segmenter_python="/nope/python"),
                              ab._load_config(str(self.cfg_file)))
        self.assertIn("/nope/python", str(got.exception))

    def test_config_limit_is_not_applied_to_local(self):
        """`analysis_limit=200` 이 `--local` 을 200곡으로 자르면 안 된다.

        실제로 299곡이 200곡으로 잘려 나갔다. 잘림을 잡으려면 대상 수가 곡 수와
        같아야 하는데, 여기서는 곡이 없으므로 **호출이 limit=0 으로 가는지**를 본다
        (`Registry.build` 에 넘어가는 값).
        """
        seen = {}

        class FakeRegistry:
            def __init__(self, **kw):
                self.warn = []

            def build(self, ids, **kw):
                seen.update(kw)
                return [], {"failed": {}, "skipped": {}, "warn": []}

        py = sys.executable          # 존재하는 인터프리터면 충분하다
        cfg = ab._load_config(str(self.cfg_file))
        # config 에 큰 값을 심어도 --local 은 그걸 쓰면 안 된다
        cfg.analysis_limit = 200
        # `registry` 는 `_collect_local` 안에서 import 된다 — 모듈 속성으로 패치한다.
        # 등록기 생성은 토큰 파일이 필요하다 — 시험 대상이 아니므로 빈 등록기로 바꾼다.
        with mock.patch.object(rg, "LocalRegistry", FakeRegistry), \
                mock.patch.object(ab, "_local_track_ids", return_value=[]), \
                mock.patch.object(ab, "ai_registrar",
                                  return_value=ab.UnavailableRegistrar("시험")), \
                mock.patch.object(ab.seg, "Segmenter"):
            ab._collect_local(self._args(segmenter_python=py), cfg)
        self.assertEqual(seen.get("limit"), 0, "config 의 analysis_limit 이 샜다")


class MusicIdMappingTests(unittest.TestCase):
    """요구 1 은 **숫자 music_id** 를 요구한다 — 로컬 파일명을 보내면 안 된다.

    실측: `--local` 로 299곡을 다 돌린 뒤 ⑥ 등록에서 죽었다.

        ValueError: invalid literal for int() with base 10: 'T000501'

    `HttpRegistrar` 가 `int(track_id)` 를 불렀기 때문이다. `T000501` 같은 파일명을
    숫자로 바꾸는 것은 **지어내는 것**이다 — `JAMENDO_1083659` 같은 ID 는 맞지 않는다.
    `music_id` 는 `registered.json`(catalog 산출물)에서만 온다.
    """

    def test_track_carries_music_id_separately(self):
        t = ab.BatchTrack(track_id="T000501", audio_path=Path("a.mp3"),
                          sections_json=Path("a.json"), content_row={}, meta_row={},
                          music_id=501)
        self.assertEqual(t.music_id, 501)
        self.assertEqual(t.track_id, "T000501")

    def test_manifest_without_music_id_stops(self):
        """manifest 로 돌릴 때도 숫자 ID 가 없으면 멈춘다."""
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "m.json"
            p.write_text(json.dumps({"tracks": [
                {"track_id": "T000501", "audio_path": "a.mp3",
                 "sections_json": "a.json"}]}), encoding="utf-8")
            with self.assertRaises(SystemExit) as got:
                ab.read_manifest(p)
            self.assertIn("music_id", str(got.exception))

    def test_manifest_with_music_id_loads(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "m.json"
            p.write_text(json.dumps({"tracks": [
                {"track_id": "T000501", "music_id": 501, "audio_path": "a.mp3",
                 "sections_json": "a.json"}]}), encoding="utf-8")
            got = ab.read_manifest(p)
            self.assertEqual(len(got), 1)
            self.assertEqual(got[0].music_id, 501)

    def test_registrar_refuses_track_without_music_id(self):
        """숫자 ID 를 모르는 곡이 있으면 **등록을 시작하지 않는다.**

        등록기가 받는 것은 `NewTrack`(색인에 붙일 곡)이다 — 거기에 `music_id` 가
        실려 온다(`_analyze_all` 이 `BatchTrack.music_id` 를 옮긴다).
        """
        import numpy as np

        from music_preprocessing.analysis import api as ai
        from music_preprocessing.analysis.indexer import NewTrack

        reg = ai.HttpRegistrar(client=object())
        bad = NewTrack(
            track_id="JAMENDO_1083659", source="flow", track_dur_sec=100.0,
            sections=[{"section_idx": 0, "section_label": "verse", "start_sec": 0.0,
                       "end_sec": 10.0, "dur_sec": 10.0}],
            embeddings=np.zeros((1, 1536), np.float32),
            features=np.zeros((1, 285), np.float32),
            section_vecs=np.zeros((1, 128), np.float32),
            content_row={}, meta_row={}, music_id=None)
        with self.assertRaises(ValueError) as got:
            reg.register([bad], Path("."))
        self.assertIn("music_id", str(got.exception))


if __name__ == "__main__":
    unittest.main()
