"""외부 서버 없이 원본 검증과 추가 전용 등록 계약을 검증한다."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from music_preprocessing.genre_mood import (
    AdminClient, apply_plan, inspect, main, summary, supplement_moods, validate,
)
from music_preprocessing.mood_approximations import MOOD_APPROXIMATIONS


def source():
    return {
        "version": 1,
        "metadata": [
            {"filename": "T000001.json", "track_id": "T000001", "title": "First",
             "genre": "synthpop", "mood": None},
            {"filename": "T000002.json", "track_id": "T000002", "title": "Second",
             "genre": "synthpop", "mood": {"primary": "bright", "secondary": "warm"}},
        ],
        "registry": {"T000001": {"music_id": 91, "title": "First"},
                     "T000002": {"music_id": 37, "title": "Second"}},
        "taxonomy": {"genres": [{"name": "synthpop", "track_count": 2}],
                     "moods": [{"name": "bright", "track_count": 1}, {"name": "warm", "track_count": 1}],
                     "genre_total_distinct": 1, "mood_total_distinct": 2,
                     "tracks_without_mood": ["T000001"]},
    }


class Server:
    def __init__(self):
        self.data = {
            "music": [{"musicId": 91, "title": "First"}, {"musicId": 37, "title": "Second"}],
            "code": [{"codeId": 7, "codeType": "GENRE", "codeName": "Pop"},
                     {"codeId": 30, "codeType": "MOOD", "codeName": "bright"}],
            "musicGenre": [{"music": 999, "genreCode": 7}],
            "musicMood": [],
        }
        self.writes = []
        self.fail_after_commit = False

    def rows(self, resource):
        return copy.deepcopy(self.data[resource])

    def create(self, resource, body):
        if resource not in ("code", "musicGenre", "musicMood"):
            raise AssertionError("기존 카탈로그 변경 시도")
        row = dict(body)
        if resource == "code":
            row["codeId"] = max(r["codeId"] for r in self.data["code"]) + 1
        self.data[resource].append(row)
        self.writes.append((resource, row))
        if self.fail_after_commit:
            self.fail_after_commit = False
            raise RuntimeError("응답 손실")
        return row


class SourceTests(unittest.TestCase):
    def test_mapping_uses_registry_not_numeric_track_suffix(self):
        tracks = validate(source())
        self.assertEqual([t["music_id"] for t in tracks], [91, 37])
        self.assertEqual(summary(tracks)["mood_relations"], 2)

    def test_duplicate_primary_secondary_is_one_relation(self):
        raw = source()
        raw["metadata"][1]["mood"]["secondary"] = "bright"
        raw["taxonomy"]["moods"] = [{"name": "bright", "track_count": 2}]
        raw["taxonomy"]["mood_total_distinct"] = 1
        self.assertEqual(summary(validate(raw))["mood_relations"], 1)

    def test_invalid_sources_fail_closed(self):
        for case in ("missing_mapping", "wrong_filename", "duplicate_track", "same_music",
                     "wrong_title", "unknown_genre", "bad_primary", "unknown_mood", "counts", "null_list", "bool_id"):
            with self.subTest(case=case):
                raw = source()
                if case == "missing_mapping":
                    del raw["registry"]["T000002"]
                elif case == "wrong_filename":
                    raw["metadata"][0]["filename"] = "T000003.json"
                elif case == "duplicate_track":
                    raw["metadata"].append(raw["metadata"][0])
                elif case == "same_music":
                    raw["registry"]["T000002"]["music_id"] = 91
                elif case == "wrong_title":
                    raw["registry"]["T000002"]["title"] = "Other"
                elif case == "unknown_genre":
                    raw["metadata"][0]["genre"] = "unknown"
                elif case == "bad_primary":
                    raw["metadata"][1]["mood"]["primary"] = None
                elif case == "unknown_mood":
                    raw["metadata"][1]["mood"]["secondary"] = "unknown"
                elif case == "counts":
                    raw["taxonomy"]["genres"][0]["track_count"] = 3
                elif case == "null_list":
                    raw["taxonomy"]["tracks_without_mood"] = []
                else:
                    raw["registry"]["T000001"]["music_id"] = True
                with self.assertRaises(ValueError):
                    validate(raw)


class RegistrationTests(unittest.TestCase):
    def setUp(self):
        self.server = Server()
        self.tracks = validate(source())

    def apply(self):
        state = inspect(self.server, self.tracks)
        report = {"created": []}
        apply_plan(self.server, self.tracks, state, report, lambda: None)
        return report

    def test_add_only_and_second_run_is_noop(self):
        before = copy.deepcopy(self.server.data)
        self.assertEqual(self.apply()["status"], "verified")
        writes = len(self.server.writes)
        self.assertEqual(writes, 6)  # 신규 코드 2 + 장르 2 + 무드 2
        self.assertEqual(self.apply()["created"], [])
        self.assertEqual(len(self.server.writes), writes)
        self.assertEqual(self.server.data["music"], before["music"])
        for resource in ("code", "musicGenre"):
            for row in before[resource]:
                self.assertIn(row, self.server.data[resource])

    def test_preflight_does_not_write(self):
        inspect(self.server, self.tracks)
        self.assertEqual(self.server.writes, [])

    def test_committed_request_with_lost_response_is_not_duplicated_on_rerun(self):
        self.server.fail_after_commit = True
        with self.assertRaises(RuntimeError):
            self.apply()
        self.apply()
        self.assertEqual(len(self.server.data["code"]), 4)
        self.assertEqual(len(self.server.writes), 6)

    def test_unexpected_existing_relation_stops_before_writing(self):
        self.server.data["musicMood"].append({"music": 91, "moodCode": 30})
        with self.assertRaises(ValueError):
            self.apply()
        self.assertEqual(self.server.writes, [])

    def test_wrong_server_music_stops_before_writing(self):
        self.server.data["music"][0]["title"] = "Wrong DB"
        with self.assertRaises(ValueError):
            self.apply()
        self.assertEqual(self.server.writes, [])

    def test_ambiguous_codes_stop_before_writing(self):
        self.server.data["code"].append({"codeId": 99, "codeType": "MOOD", "codeName": "bright"})
        with self.assertRaises(ValueError):
            self.apply()
        self.assertEqual(self.server.writes, [])


class ApproximationTests(unittest.TestCase):
    def snapshot(self):
        # 원본 10개 결측 + 어휘 검증용으로 실제 무드가 있는 1곡.
        raw = source()
        raw["metadata"][0]["genre"] = "contemporary_pop"
        raw["taxonomy"]["genres"] = [{"name": "contemporary_pop", "track_count": 1},
                                      {"name": "synthpop", "track_count": 1}]
        raw["taxonomy"]["genre_total_distinct"] = 2
        return raw

    def test_null_only_and_original_is_preserved(self):
        raw = self.snapshot()
        original = copy.deepcopy(raw)
        tracks = validate(raw)
        effective, provenance = supplement_moods(tracks, raw)
        self.assertEqual(effective[0]["moods"], ["bright", "warm"])
        self.assertEqual(tracks[0]["moods"], [])
        self.assertEqual(raw, original)
        self.assertEqual(effective[1], tracks[1])
        self.assertEqual(provenance[0]["music_id"], 91)
        self.assertEqual(provenance[0]["confidence"], "approx")

    def test_real_mood_takes_precedence(self):
        raw = self.snapshot()
        tracks = validate(raw)
        tracks[0]["moods"] = ["warm"]
        effective, provenance = supplement_moods(tracks, raw)
        self.assertEqual(effective[0]["moods"], ["warm"])
        self.assertEqual(provenance, [])

    def test_unknown_track_remains_null(self):
        raw = self.snapshot()
        tracks = validate(raw)
        tracks[0]["track_id"] = "T999999"
        effective, provenance = supplement_moods(tracks, raw)
        self.assertEqual(effective[0]["moods"], [])
        self.assertEqual(provenance, [])

    def test_genre_mismatch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "장르 불일치"):
            supplement_moods(validate(source()), source())

    def test_missing_vocabulary_is_rejected(self):
        raw = self.snapshot()
        tracks = validate(raw)
        raw["taxonomy"]["moods"] = [{"name": "bright", "track_count": 1}]
        with self.assertRaisesRegex(ValueError, "어휘에 없음"):
            supplement_moods(tracks, raw)

    def test_all_ten_values_match_handoff(self):
        expected = {
            "T000001": ["bright", "warm"], "T000014": ["bright", "warm"],
            "T000003": ["warm", "dreamy"], "T000004": ["warm", "dreamy"],
            "T000005": ["powerful", "hopeful"], "T000006": ["bright", "hopeful"],
            "T000007": ["warm", "calm"], "T000008": ["dreamy", "powerful"],
            "T000009": ["calm", "melancholic"], "T000010": ["powerful", "dark"],
        }
        tracks = [{"track_id": tid, "music_id": 100 + i, "genre": entry[0], "moods": []}
                  for i, (tid, entry) in enumerate(MOOD_APPROXIMATIONS.items())]
        raw = {"taxonomy": {"moods": [{"name": mood} for mood in {m for pair in expected.values() for m in pair}]}}
        effective, provenance = supplement_moods(tracks, raw)
        self.assertEqual({t["track_id"]: t["moods"] for t in effective}, expected)
        self.assertEqual(len(provenance), 10)
        self.assertEqual(sum(len(t["moods"]) for t in effective), 20)

    def test_provenance_recorded_and_rerun_does_not_duplicate(self):
        raw = self.snapshot()
        tracks, provenance = supplement_moods(validate(raw), raw)
        server = Server()
        report = {"created": [], "mood_approximations": provenance}
        apply_plan(server, tracks, inspect(server, tracks), report, lambda: None)
        approximate = [row for row in report["created"] if row.get("confidence") == "approx"]
        self.assertEqual(len(approximate), 2)
        self.assertTrue(all(row["music_id"] == 91 for row in approximate))
        second = {"created": []}
        apply_plan(server, tracks, inspect(server, tracks), second, lambda: None)
        self.assertEqual(second["created"], [])

    def test_cli_enables_supplement_by_default_and_reports_original_counts(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "snapshot.json"
            path.write_text(json.dumps(self.snapshot()))
            report = Path(temp) / "report.json"
            with patch("sys.argv", ["genre_mood", "import", "--snapshot", str(path), "--report", str(report)]), patch("builtins.print"):
                self.assertEqual(main(), 0)
            data = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(data["source_summary"]["mood_relations"], 2)
            self.assertEqual(data["summary"]["mood_relations"], 4)
            self.assertEqual(data["mood_approximations"][0]["track_id"], "T000001")


class ClientTests(unittest.TestCase):
    def test_local_snapshot_preserves_source_files(self):
        raw = source()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "metadata").mkdir()
            for track in raw["metadata"]:
                entry = dict(track)
                entry["flow_title"] = entry.pop("title")
                (root / "metadata" / track["filename"]).write_text(json.dumps(entry))
            (root / "genre_mood_taxonomy.json").write_text(json.dumps(raw["taxonomy"]))
            registry = root / "registered.json"
            original = json.dumps({"catalog": {"tracks": raw["registry"]}})
            registry.write_text(original)
            target = root / "snapshot.json"
            with patch("sys.argv", ["genre_mood", "snapshot", "--source-dir", str(root),
                                    "--registry", str(registry), "--output", str(target)]), patch("builtins.print"):
                self.assertEqual(main(), 0)
            self.assertEqual(summary(validate(json.loads(target.read_text())))["tracks"], 2)
            self.assertEqual(registry.read_text(), original)

    def test_report_cannot_overwrite_snapshot(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "source.json"
            original = json.dumps(source())
            path.write_text(original)
            with patch("sys.argv", ["genre_mood", "import", "--snapshot", str(path),
                                    "--report", str(path), "--skip-mood-approximations"]), patch("builtins.print"):
                self.assertEqual(main(), 1)
            self.assertEqual(path.read_text(), original)

    def test_pagination_reads_all_pages(self):
        client = object.__new__(AdminClient)
        with patch.object(client, "_request", side_effect=[
            {"data": {"content": [{"musicId": 1}], "pages": 2, "total": 2}},
            {"data": {"content": [{"musicId": 2}], "pages": 2, "total": 2}},
        ]) as request:
            self.assertEqual(len(client.rows("music")), 2)
            self.assertIn("page=1", request.call_args.args[1])

    def test_catalog_writes_are_rejected(self):
        client = object.__new__(AdminClient)
        with self.assertRaises(ValueError):
            client.create("music", {"title": "not allowed"})

    def test_post_contract_uses_relation_names(self):
        client = object.__new__(AdminClient)
        with patch.object(client, "_request", return_value={"data": {}}) as request:
            client.create("musicGenre", {"music": 91, "genreCode": 31})
            request.assert_called_once_with("POST", "/api/v1/admin/resource/musicGenre",
                                            json_body={"music": 91, "genreCode": 31})

    def test_offline_cli_never_connects(self):
        with tempfile.TemporaryDirectory() as temp:
            snapshot = Path(temp) / "source.json"
            snapshot.write_text(json.dumps(source()), encoding="utf-8")
            report = Path(temp) / "report.json"
            with patch("sys.argv", ["genre_mood", "import", "--snapshot", str(snapshot), "--report", str(report), "--skip-mood-approximations"]), \
                 patch("music_preprocessing.genre_mood.AdminClient") as client, patch("builtins.print"):
                self.assertEqual(main(), 0)
                client.assert_not_called()
            self.assertEqual(json.loads(report.read_text())["status"], "source_validated")


if __name__ == "__main__":
    unittest.main()
