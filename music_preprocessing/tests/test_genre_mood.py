"""외부 서버 없이 원본 검증과 분류 적재 계약을 검증한다."""
import copy
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

import music_preprocessing.genre_mood as gn
from music_preprocessing.genre_mood import (
    BatchClient, apply_plan, inspect, main, summary, supplement_moods, validate,
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
    """`BatchClient` 가 부르는 네 가지만 흉내 낸다.

    관리자 API 는 없다 — 그걸 부르면 테스트 대역에 메서드가 없어 터진다.
    """

    def __init__(self):
        self.codes = [{"codeId": 7, "codeType": "GENRE", "codeName": "Pop"},
                      {"codeId": 30, "codeType": "MOOD", "codeName": "bright"}]
        self.music = [{"musicId": 91, "title": "First", "genres": [], "moods": []},
                      {"musicId": 37, "title": "Second", "genres": [], "moods": []}]
        self.writes = []
        self.fail_after_commit = False
        self.swallow_moods = False

    # ── 읽기 ────────────────────────────────────────────────────────────
    def options(self):
        return {kind.lower() + "s": [{"codeId": c["codeId"], "name": c["codeName"]}
                                     for c in self.codes if c["codeType"] == kind]
                for kind in ("GENRE", "MOOD")}

    def music_rows(self):
        return copy.deepcopy(self.music)

    # ── 쓰기 ────────────────────────────────────────────────────────────
    def add_code(self, code_type, name, display_name=None):
        """표시명을 따로 받는다 — 서버가 `(종류, 이름)` 으로 멱등 판정을 하므로
        이미 있는 코드면 표시명은 무시된다(`CodeServiceImpl.addCode`)."""
        folded = {(c["codeType"], c["codeName"].casefold()): c for c in self.codes}
        existing = folded.get((code_type, name.casefold()))
        if existing:
            return {**existing, "created": False}
        # 표시명을 안 보내면 코드 이름이 곧 표시명이 된다 (서버와 같은 규칙).
        shown = display_name if display_name else name
        row = {"codeId": max(c["codeId"] for c in self.codes) + 1,
               "codeType": code_type, "codeName": name, "displayName": shown}
        self.codes.append(row)
        self.writes.append(("code", row))
        self._maybe_lose_response()
        return {**row, "created": True}

    def replace_classification(self, music_id, genre_code_ids, mood_code_ids):
        names = {c["codeId"]: c["codeName"] for c in self.codes}
        row = next(m for m in self.music if m["musicId"] == music_id)
        row["genres"] = [names[i] for i in dict.fromkeys(genre_code_ids)]
        if self.swallow_moods:
            mood_code_ids = []
        row["moods"] = [names[i] for i in dict.fromkeys(mood_code_ids)]
        self.writes.append(("classification", music_id))
        self._maybe_lose_response()
        return {"musicId": music_id,
                "genreCodeIds": sorted(dict.fromkeys(genre_code_ids)),
                "moodCodeIds": sorted(dict.fromkeys(mood_code_ids))}

    def _maybe_lose_response(self):
        if self.fail_after_commit:
            self.fail_after_commit = False
            raise RuntimeError("응답 손실")


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

    def apply(self, replace_existing=False):
        state = inspect(self.server, self.tracks)
        report = {"created": []}
        apply_plan(self.server, self.tracks, state, report, lambda: None, replace_existing)
        return report

    def test_second_run_writes_nothing(self):
        self.assertEqual(self.apply()["status"], "verified")
        writes = len(self.server.writes)
        self.assertEqual(writes, 4)  # 신규 코드 2(synthpop·warm) + 곡 2
        self.assertEqual(self.apply()["created"], [])
        self.assertEqual(len(self.server.writes), writes)

    def test_preflight_does_not_write(self):
        inspect(self.server, self.tracks)
        self.assertEqual(self.server.writes, [])

    def test_committed_request_with_lost_response_is_not_duplicated_on_rerun(self):
        self.server.fail_after_commit = True
        with self.assertRaises(RuntimeError):
            self.apply()
        self.apply()
        self.assertEqual(len(self.server.codes), 4)
        self.assertEqual(len(self.server.writes), 4)

    def test_classification_is_whole_song_not_per_relation(self):
        self.apply()
        first = next(m for m in self.server.music if m["musicId"] == 91)
        self.assertEqual(sorted(first["genres"]), ["synthpop"])
        # 한 곡에 한 번만 쓴다 — 관계마다 따로 부르면 중간 상태가 정상으로 보인다.
        self.assertEqual([w for w in self.server.writes if w[0] == "classification"],
                         [("classification", 91), ("classification", 37)])

    def test_existing_different_classification_is_left_alone(self):
        # 사람이 손으로 넣은 값을 배치가 조용히 지우면 안 된다.
        held = next(m for m in self.server.music if m["musicId"] == 91)
        held["genres"] = ["Pop"]
        state = inspect(self.server, self.tracks)
        self.assertEqual(state["differs"], [91])
        self.assertEqual(state["empty"], [37])
        report = {"created": []}
        apply_plan(self.server, self.tracks, state, report, lambda: None)
        self.assertEqual(report["skipped"], [91])
        self.assertEqual(held["genres"], ["Pop"])
        self.assertEqual([w for w in self.server.writes if w[0] == "classification"],
                         [("classification", 37)])

    def test_replace_existing_overwrites_and_records_what_was_there(self):
        held = next(m for m in self.server.music if m["musicId"] == 91)
        held["genres"] = ["Pop"]
        report = self.apply(replace_existing=True)
        self.assertEqual(held["genres"], ["synthpop"])
        replaced = [r for r in report["created"] if r.get("replaced")]
        self.assertEqual(replaced[0]["replaced"], {"genres": ["Pop"], "moods": []})

    def test_case_only_difference_reuses_the_existing_code(self):
        # 부트스트랩이 `Pop` 을 심어 두었고 원본 장르는 `pop` 이다. `codes` 의
        # UNIQUE 는 대소문자를 구분하지 않아 새 행이 생기지 않는다. 이름 그대로
        # 비교하면 반영 뒤에도 "다르다" 가 되어 매 배치가 같은 곡을 다시 쓴다.
        raw = source()
        for entry in raw["metadata"]:
            entry["genre"] = "pop"
        raw["taxonomy"]["genres"] = [{"name": "pop", "track_count": 2}]
        tracks = validate(raw)
        state = inspect(self.server, tracks)
        self.assertEqual(state["missing_codes"], [("MOOD", "warm")])
        report = {"created": []}
        apply_plan(self.server, tracks, state, report, lambda: None)
        self.assertEqual(len([c for c in self.server.codes if c["codeType"] == "GENRE"]), 1)
        self.assertEqual(next(m for m in self.server.music if m["musicId"] == 91)["genres"],
                         ["Pop"])
        # 두 번째 실행이 아무것도 쓰지 않아야 수렴한 것이다.
        self.assertEqual(self.apply_again(tracks), [])

    def apply_again(self, tracks):
        report = {"created": []}
        apply_plan(self.server, tracks, inspect(self.server, tracks), report, lambda: None)
        return report["created"]

    def test_server_storing_less_than_it_was_sent_stops_at_that_song(self):
        # 저장된 것이 돌아온다. 보낸 것과 다르면 나머지 1000곡을 마저 밀어 넣고
        # 끝에 가서 알아차리는 대신 그 자리에서 멈춘다.
        source_with_mood = source()
        source_with_mood["metadata"][0]["mood"] = {"primary": "bright", "secondary": None}
        source_with_mood["taxonomy"]["moods"] = [{"name": "bright", "track_count": 2},
                                                 {"name": "warm", "track_count": 1}]
        source_with_mood["taxonomy"]["tracks_without_mood"] = []
        tracks = validate(source_with_mood)

        self.server.swallow_moods = True
        with self.assertRaisesRegex(ValueError, "저장 결과가 요청과 다름"):
            apply_plan(self.server, tracks, inspect(self.server, tracks),
                       {"created": []}, lambda: None)
        self.assertEqual([w for w in self.server.writes if w[0] == "classification"],
                         [("classification", 91)])

    def test_wrong_server_music_stops_before_writing(self):
        self.server.music[0]["title"] = "Wrong DB"
        with self.assertRaises(ValueError):
            self.apply()
        self.assertEqual(self.server.writes, [])

    def test_ambiguous_codes_stop_before_writing(self):
        self.server.codes.append({"codeId": 99, "codeType": "MOOD", "codeName": "bright"})
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
        self.assertEqual(len(approximate), 1)  # 곡 단위로 한 번
        self.assertEqual(approximate[0]["music_id"], 91)
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
    def write_source_tree(self, root, raw):
        (root / "metadata").mkdir()
        for track in raw["metadata"]:
            entry = dict(track)
            entry["flow_title"] = entry.pop("title")
            (root / "metadata" / track["filename"]).write_text(json.dumps(entry))
        (root / "genre_mood_taxonomy.json").write_text(json.dumps(raw["taxonomy"]))
        registry = root / "registered.json"
        registry.write_text(json.dumps({"catalog": {"tracks": raw["registry"]}}))
        return registry

    def test_local_snapshot_preserves_source_files(self):
        raw = source()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            registry = self.write_source_tree(root, raw)
            original = registry.read_text()
            target = root / "snapshot.json"
            with patch("sys.argv", ["genre_mood", "snapshot", "--source-dir", str(root),
                                    "--registry", str(registry), "--output", str(target)]), patch("builtins.print"):
                self.assertEqual(main(), 0)
            self.assertEqual(summary(validate(json.loads(target.read_text())))["tracks"], 2)
            self.assertEqual(registry.read_text(), original)

    def test_import_reads_the_source_directory_without_a_snapshot_file(self):
        # 배치는 JupyterHub 안에서 원본을 직접 읽는다. 중간 파일을 두지 않는다.
        raw = source()
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            registry = self.write_source_tree(root, raw)
            report = root / "report.json"
            with patch("sys.argv", ["genre_mood", "import", "--source-dir", str(root),
                                    "--registry", str(registry), "--report", str(report),
                                    "--skip-mood-approximations"]), patch("builtins.print"):
                self.assertEqual(main(), 0)
            data = json.loads(report.read_text(encoding="utf-8"))
            self.assertEqual(data["status"], "source_validated")
            self.assertEqual(data["summary"]["tracks"], 2)
            self.assertEqual(len(data["source_sha256"]), 64)

    def test_import_without_a_source_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            report = Path(temp) / "report.json"
            with patch("sys.argv", ["genre_mood", "import", "--report", str(report)]), \
                 patch("builtins.print"):
                self.assertEqual(main(), 1)
            self.assertFalse(report.exists())

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
        client = object.__new__(BatchClient)
        with patch.object(client, "_request", side_effect=[
            {"data": {"items": [{"musicId": 1}], "totalPages": 2, "totalElements": 2}},
            {"data": {"items": [{"musicId": 2}], "totalPages": 2, "totalElements": 2}},
        ]) as request:
            self.assertEqual(len(client.music_rows()), 2)
            self.assertIn("page=1", request.call_args.args[1])

    def test_admin_api_is_never_called(self):
        client = object.__new__(BatchClient)
        with patch.object(client, "_request", return_value={"data": {}}) as request:
            client.add_code("GENRE", "synthpop")
            client.replace_classification(91, [31], [30])
            client.options()
        paths = [call.args[1] for call in request.call_args_list]
        self.assertEqual(paths, ["/api/v1/code", "/api/v1/music/91/classification",
                                 "/api/v1/onboarding/option"])
        self.assertFalse(any("/admin/" in path for path in paths))

    def test_post_and_put_contracts(self):
        client = object.__new__(BatchClient)
        with patch.object(client, "_request", return_value={"data": {}}) as request:
            client.add_code("GENRE", "synthpop")
            request.assert_called_once_with(
                "POST", "/api/v1/code",
                json_body={"codeType": "GENRE", "codeName": "synthpop"})
        with patch.object(client, "_request", return_value={"data": {}}) as request:
            client.replace_classification(91, [31], [30, 33])
            request.assert_called_once_with(
                "PUT", "/api/v1/music/91/classification",
                json_body={"genreCodeIds": [31], "moodCodeIds": [30, 33]})

    def test_post_code_carries_display_name_only_when_given(self):
        """표시명은 **줄 게 있을 때만** 싣는다.

        taxonomy 무드처럼 코드 이름이 영문 원문인 경우에만 한국어 표시명이 필요하다.
        빈 값을 실어 보내면 옛 서버(이 필드를 모르는 판)에서 검증에 걸릴 수 있다."""
        client = object.__new__(BatchClient)
        with patch.object(client, "_request", return_value={"data": {}}) as request:
            client.add_code("MOOD", "melancholic", "애상적인")
            request.assert_called_once_with(
                "POST", "/api/v1/code",
                json_body={"codeType": "MOOD", "codeName": "melancholic",
                           "displayName": "애상적인"})
        with patch.object(client, "_request", return_value={"data": {}}) as request:
            client.add_code("MOOD", "bright")
            body = request.call_args.kwargs["json_body"]
            self.assertNotIn("displayName", body)

    def test_mood_display_names_match_the_ai_table(self):
        """뜻 표가 AI 쪽과 어긋나면 안 된다.

        `MISMATCHED_MOOD_DISPLAY_NAMES` 는 `ai_server/app/onboarding.py` 의
        `MOOD_MAP` 뒷 9줄과 **같은 출처**인데, 이 패키지가 `ai_server` 를 부르면
        numpy·pandas·torch 를 끌고 오게 되어 표를 두 벌로 둘 수밖에 없다.
        그래서 대조를 여기서 한다 — 한쪽만 고치면 여기서 멈춘다."""
        ai_root = Path(__file__).resolve().parents[2] / "ai_server"
        if not (ai_root / "app" / "onboarding.py").exists():
            self.skipTest("ai_server 가 없다")
        sys.path.insert(0, str(ai_root))
        try:
            from app import onboarding as onb
        finally:
            sys.path.remove(str(ai_root))

        # taxonomy 원문 → 그 태그가 뜻으로 이어지는 한국어 이름이 아니라,
        # **taxonomy 이름 → 한국어 코드 이름** 표를 직접 맞춘다. 이게 두 벌이
        # 어긋나는지 볼 수 있는 유일한 지점이다 — 색인 태그(emotional 등)를
        # 거쳐서는 melancholic 이 어느 항목인지 알 수 없다.
        self.assertEqual(gn.MISMATCHED_MOOD_DISPLAY_NAMES, onb.TAXONOMY_MOOD_NAMES,
                         "taxonomy 무드의 한국어 이름이 AI 표와 다르다")
        # 그 이름들이 실제로 MOOD_MAP 에 있어야 한다 — 없으면 변환이 비어
        # 온보딩에서 고른 그 무드가 추천에 반영되지 않는다.
        for korean in onb.TAXONOMY_MOOD_NAMES.values():
            self.assertIn(korean, onb.MOOD_MAP, f"{korean} 이 AI 표에 없다")

    def test_display_name_is_blank_for_genres_and_known_moods(self):
        """장르·악기는 표시명이 그대로 코드 이름이다 (빈 값 → 서버가 채운다).

        이미 한국어 코드가 서버에 있으면 그 코드에는 표시명을 붙이지 않는다."""
        self.assertEqual(gn.display_name_for("GENRE", "synthpop", {}), "")
        self.assertEqual(gn.display_name_for("INSTRUMENT", "drums", {}), "")
        # 뜻 표에 없는 이름 — 서버가 코드 이름을 표시명으로 쓴다.
        self.assertEqual(gn.display_name_for("MOOD", "잔잔한", {}), "")
        # 이미 한국어 코드가 서버에 있으면 붙이지 않는다.
        folded = gn.fold([("MOOD", "애상적인")])
        self.assertEqual(gn.display_name_for("MOOD", "melancholic", folded), "")

    def test_taxonomy_mood_outside_the_table_stops_the_run(self):
        """원본에 새 taxonomy 무드가 생기면 멈춘다 — 영문이 화면에 뜨는 것을 막는다."""
        gn.validate_taxonomy_moods([{"moods": ["warm", "calm"]}])
        with self.assertRaises(ValueError) as caught:
            gn.validate_taxonomy_moods([{"moods": ["warm", "새로운무드"]}])
        self.assertIn("새로운무드", str(caught.exception))

    def test_offline_cli_never_connects(self):
        with tempfile.TemporaryDirectory() as temp:
            snapshot = Path(temp) / "source.json"
            snapshot.write_text(json.dumps(source()), encoding="utf-8")
            report = Path(temp) / "report.json"
            with patch("sys.argv", ["genre_mood", "import", "--snapshot", str(snapshot), "--report", str(report), "--skip-mood-approximations"]), \
                 patch("music_preprocessing.genre_mood.BatchClient") as client, patch("builtins.print"):
                self.assertEqual(main(), 0)
                client.assert_not_called()
            self.assertEqual(json.loads(report.read_text())["status"], "source_validated")


if __name__ == "__main__":
    unittest.main()
