"""실제 HTTP 쓰기 없이 교체/복구/실패 재개 경계를 검증한다."""
import copy
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch

from music_preprocessing.artist_profiles import (
    ArtistClient, artist_image_read_url, check_artists, digest, image_url, load_bundle,
    prepare, process, validate_manifest, validate_png, write_json,
)


def png(index):
    return b"\x89PNG\x0d\x0a\x1a\x0a" + struct.pack(">I", 13) + b"IHDR" + struct.pack(">II", 1254, 1254) + bytes(9) + str(index).encode()


class Server:
    base_url = "https://example.test"

    def __init__(self, manifest):
        # 조회 API 는 **파일 번호** 주소를 준다(`StorageImageService.imageUrl`). 저장
        # 키 주소(`/ARTIST/{key}`)를 주면 배치가 등록 기록의 `image_file_id` 와 대조하지
        # 못해 `check_artists` 에서 멈춘다 — 운영에서 실제로 그렇게 멈추게 된다.
        self.info = sorted([{"artist_id": row["artist_id"], "name": row["name"],
                             "image_url": artist_image_read_url(row["image_file_id"])}
                            for row in manifest["artists"]],
                           key=lambda row: row["artist_id"])
        self.images = {row["image_file_id"]: b"old image " + row["artist_key"].encode() for row in manifest["artists"]}
        self.writes = []
        self.lose_response = False
        self.break_content = False

    def artists(self):
        return copy.deepcopy(self.info)

    def download(self, file_id):
        return self.images[file_id], "image/jpeg", str(file_id) + ".jpg"

    def upload(self, row, data, mime, filename):
        self.writes.append((row["artist_key"], mime, filename))
        self.images[row["image_file_id"]] = b"corrupt" if self.break_content else data
        if self.lose_response:
            self.lose_response = False
            raise RuntimeError("server committed, response lost")


class ProfileTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.bundle = self.root / "bundle"
        self.bundle.mkdir()
        self.run_dir = self.root / "run"
        self.run_dir.mkdir()
        rows = []
        for i in range(87):
            key = f"artist_{i}"
            data = png(i)
            (self.bundle / (key + ".png")).write_bytes(data)
            rows.append({"artist_key": key, "artist_id": 1000 - i, "name": f"Artist {i}",
                         "image_file_id": i + 12, "filename": key + ".png",
                         "original_filename": f"Artist {i}.png",
                         "registered_cover_sha256": digest(b"old image " + key.encode()),
                         "profile_image": {"mime_type": "image/png", "width": 1254, "height": 1254,
                                           "size_bytes": len(data), "sha256": digest(data)}})
        self.manifest = {"version": 1, "artists": rows}
        (self.bundle / "manifest.json").write_text(json.dumps(self.manifest))
        self.server = Server(self.manifest)
        self.quiet = patch("builtins.print")
        self.quiet.start()

    def tearDown(self):
        self.quiet.stop()
        self.temp.cleanup()

    def prepare(self):
        return prepare(self.server, self.manifest, self.run_dir)

    def test_read_source_and_dry_run_back_up_every_image_without_upload(self):
        self.assertEqual(len(load_bundle(self.bundle)["artists"]), 87)
        state = self.prepare()
        result = process(self.server, state, self.bundle, self.run_dir)
        self.assertEqual(result["pending"], 87)
        self.assertEqual(len(list((self.run_dir / "backups").iterdir())), 87)
        self.assertEqual(self.server.writes, [])

    def test_apply_keeps_ids_names_urls_and_second_run_has_zero_uploads(self):
        state = self.prepare()
        before = self.server.artists()
        result = process(self.server, state, self.bundle, self.run_dir, apply=True)
        self.assertEqual(result["verified_images"], 87)
        self.assertEqual(self.server.artists(), before)
        self.assertEqual(len(self.server.writes), 87)
        again = process(self.server, state, self.bundle, self.run_dir, apply=True)
        self.assertEqual(again["pending"], 0)
        self.assertEqual(len(self.server.writes), 87)

    def test_rollback_restores_exact_original_bytes(self):
        state = self.prepare()
        before = dict(self.server.images)
        process(self.server, state, self.bundle, self.run_dir, apply=True)
        result = process(self.server, state, None, self.run_dir, apply=True, rollback=True)
        self.assertEqual(result["status"], "verified")
        self.assertEqual(self.server.images, before)
        self.assertTrue(all(mime == "image/jpeg" for _, mime, _ in self.server.writes[87:]))

    def test_lost_response_resumes_from_server_hash_without_duplicate_upload(self):
        state = self.prepare()
        self.server.lose_response = True
        with self.assertRaises(RuntimeError):
            process(self.server, state, self.bundle, self.run_dir, apply=True)
        first = json.loads((self.run_dir / "result.json").read_text())
        self.assertEqual(first["in_flight"], "artist_0")
        result = process(self.server, state, self.bundle, self.run_dir, apply=True)
        self.assertEqual(result["pending"], 86)
        self.assertEqual(len(self.server.writes), 87)

    def test_backup_corruption_stops_before_writes(self):
        state = self.prepare()
        (self.run_dir / "backups" / "artist_86.bin").write_bytes(b"broken")
        with self.assertRaisesRegex(ValueError, "백업 해시"):
            process(self.server, state, self.bundle, self.run_dir, apply=True)
        self.assertEqual(self.server.writes, [])

    def test_unexpected_remote_change_stops_entire_batch_before_writes(self):
        state = self.prepare()
        self.server.images[98] = b"someone else changed this"
        with self.assertRaisesRegex(ValueError, "예상 밖 이미지"):
            process(self.server, state, self.bundle, self.run_dir, apply=True)
        self.assertEqual(self.server.writes, [])

    def test_wrong_artist_mapping_stops_before_backup(self):
        self.server.info[0]["name"] = "different artist"
        with self.assertRaisesRegex(ValueError, "이름/이미지 URL"):
            self.prepare()
        self.assertFalse((self.run_dir / "backup-manifest.json").exists())

    def test_existing_new_profile_is_skipped(self):
        self.server.images[12] = png(0)
        state = self.prepare()
        result = process(self.server, state, self.bundle, self.run_dir, apply=True)
        self.assertEqual(result["pending"], 86)

    def test_wrong_api_origin_stops_before_writes(self):
        state = self.prepare()
        self.server.base_url = "https://other.test"
        with self.assertRaisesRegex(ValueError, "API 대상"):
            process(self.server, state, self.bundle, self.run_dir, apply=True)
        self.assertEqual(self.server.writes, [])

    def test_corrupt_upload_stops_at_first_image(self):
        state = self.prepare()
        self.server.break_content = True
        with self.assertRaisesRegex(ValueError, "제공 이미지 불일치"):
            process(self.server, state, self.bundle, self.run_dir, apply=True)
        self.assertEqual(len(self.server.writes), 1)

    def test_invalid_source_images_and_manifest(self):
        meta = self.manifest["artists"][0]["profile_image"]
        for data in (b"not png", png(1), png(0)[:16] + struct.pack(">II", 1, 1) + png(0)[24:]):
            with self.assertRaises(ValueError):
                validate_png(data, meta)
        duplicate = copy.deepcopy(self.manifest)
        duplicate["artists"][1]["artist_id"] = duplicate["artists"][0]["artist_id"]
        with self.assertRaises(ValueError):
            validate_manifest(duplicate)

    def test_upload_only_calls_artist_storage_and_checks_file_id(self):
        client = object.__new__(ArtistClient)
        row = self.manifest["artists"][0]
        with patch.object(client, "_request", return_value={"data": {"file_id": 12}}) as request:
            client.upload(row, png(0), "image/png", "Artist 0.png")
            self.assertEqual(request.call_args.args, ("POST", "/api/v1/storage/image/ARTIST/artist_0"))
        with patch.object(client, "_request", return_value={"data": {"file_id": 999}}):
            with self.assertRaisesRegex(ValueError, "file_id"):
                client.upload(row, png(0), "image/png", "Artist 0.png")

    def test_plus_in_artist_key_is_encoded_as_literal_plus(self):
        row = self.manifest["artists"][0]
        row["artist_key"] = "mixed_english_pop_mainstream+rock_edge_01"
        row["filename"] = row["artist_key"] + ".png"
        validate_manifest(self.manifest)
        self.assertIn("%2B", image_url(row["artist_key"]))

    def test_read_and_write_urls_have_different_shapes(self):
        """업로드는 자리를, 조회는 파일 번호를 가리킨다.

        두 주소가 같은 모양이던 때는 조회 URL 이 저장 키를 되짚어 만들어졌고, 키를
        손으로 만든 코드 하나가 주소를 조용히 null 로 만들었다. 지금은 조회가 번호라
        서로 다른 모양인 것이 정상이다 — 같아지면 서버 계약이 되돌아간 것이다.
        """
        self.assertEqual(image_url("artist_0"), "/api/v1/storage/image/ARTIST/artist_0")
        self.assertEqual(artist_image_read_url(42), "/api/v1/storage/image/42")

    def test_artist_client_accepts_the_real_response_shape(self):
        """`artist` 응답에는 `artist_key` 가 없다 — 실제 응답 모양 그대로 넣는다.

        운영에서 확인한 응답은 `artist_id` · `name` · `image_url` 셋뿐이다. 처음에는
        `image_file_ids` 대응표를 만들면서 `item["artist_key"]` 를 읽었는데, 그 필드가
        없어 `KeyError` 로 죽었다. 테스트 대역(`Server`)이 `artist_key` 를 넣어 주고
        있어서 단위 테스트로는 드러나지 않았다 — **가짜를 실제보다 너그럽게 만들면
        그 너그러움이 버그를 감춘다.**
        """
        client = object.__new__(ArtistClient)
        items = [{"artist_id": i, "name": "Artist %d" % i,
                  "image_url": artist_image_read_url(i + 12)} for i in range(87)]
        with patch.object(client, "_request",
                          return_value={"data": {"total_elements": 87, "items": items}}):
            client.artists()  # 예외가 없어야 한다

    def test_artist_client_stops_when_read_url_has_no_file_number(self):
        """주소 모양이 바뀌면 조용히 넘어가지 않고 멈춘다.

        여기서 지나가면 `check_artists` 의 대조가 무의미해지고, 백업이 남의
        아티스트 사진으로 채워질 수 있다.
        """
        client = object.__new__(ArtistClient)
        items = [{"artist_id": i, "name": "Artist %d" % i,
                  "image_url": "/api/v1/storage/image/ARTIST/artist_%d" % i}
                 for i in range(87)]
        with patch.object(client, "_request",
                          return_value={"data": {"total_elements": 87, "items": items}}):
            with self.assertRaisesRegex(ValueError, "파일 번호"):
                client.artists()

    def test_mismatched_read_url_stops_before_any_download(self):
        """등록 기록의 파일 번호가 서버와 어긋나면 조회를 시작하지 않는다.

        `download` 는 등록 기록의 번호를 그대로 믿으므로, 이 대조가 유일한 방어선이다.
        """
        rows = self.manifest["artists"]
        current = [{"artist_id": r["artist_id"], "name": r["name"],
                    "image_url": artist_image_read_url(r["image_file_id"] + 1)} for r in rows]
        with self.assertRaisesRegex(ValueError, "이름/이미지 URL"):
            check_artists(rows, current)

    def test_temporary_windows_file_lock_does_not_lose_report(self):
        import os
        original = os.replace
        attempts = []
        def replace(src, dest):
            attempts.append(src)
            if len(attempts) == 1:
                raise PermissionError("temporary antivirus lock")
            original(src, dest)
        with patch("music_preprocessing.artist_profiles.os.replace", side_effect=replace):
            write_json(self.run_dir / "report.json", {"status": "verified"})
        self.assertEqual(json.loads((self.run_dir / "report.json").read_text())["status"], "verified")


if __name__ == "__main__":
    unittest.main()
