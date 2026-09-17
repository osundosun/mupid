"""실제 HTTP 로 한 바퀴 돌린다.

목 객체는 `client.add_code(...)` 가 불렸다는 것까지만 본다. 그런데 이 도구가
실패해 온 자리는 늘 그 아래였다 — 경로 오타, 쿼리 이름, 인증 헤더 누락처럼
"부르긴 불렀는데 서버가 못 알아듣는" 것들이다. 그래서 여기서는 진짜 서버를
띄우고 진짜 `urllib` 로 보낸다. 서버 쪽 응답 모양은 스프링의
`BaseResponse`(`{"data": ...}`)와 `MusicPageResponse` 를 그대로 흉내 낸다.
"""
import base64
import json
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from music_preprocessing.genre_mood import BatchClient, apply_plan, inspect, validate

from test_genre_mood import source


def access_token():
    payload = base64.urlsafe_b64encode(
        json.dumps({"exp": time.time() + 600}).encode()).decode().rstrip("=")
    return f"header.{payload}.signature"


class Fake(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):  # 테스트 출력에 접근 로그를 섞지 않는다
        pass

    # ── 라우팅 ──────────────────────────────────────────────────────────
    def do_GET(self):
        url = urlparse(self.path)
        state = self.server.state
        state["seen"].append(("GET", self.path, self.headers.get("Authorization")))
        if url.path == "/api/v1/auth":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Set-Cookie", "refresh_token=rotated-2; Path=/")
            body = json.dumps({"data": {"access_token": access_token()}}).encode()
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if url.path == "/api/v1/onboarding/option":
            return self.json({kind.lower() + "s": [
                {"codeId": c["codeId"], "name": c["codeName"]}
                for c in state["codes"] if c["codeType"] == kind]
                for kind in ("GENRE", "MOOD")})
        if url.path == "/api/v1/music":
            query = parse_qs(url.query)
            size = int(query["size"][0])
            page = int(query["page"][0])
            rows = state["music"]
            window = rows[page * size:(page + 1) * size]
            return self.json({"items": window, "page": page, "size": size,
                              "totalElements": len(rows),
                              "totalPages": max(1, -(-len(rows) // size))})
        self.send_error(404)

    def do_POST(self):
        state = self.server.state
        body = self.read_body()
        state["seen"].append(("POST", self.path, self.headers.get("Authorization")))
        if self.path != "/api/v1/code":
            return self.send_error(404)
        folded = {(c["codeType"], c["codeName"].casefold()): c for c in state["codes"]}
        found = folded.get((body["codeType"], body["codeName"].casefold()))
        if found:
            return self.json({**found, "created": False})
        row = {"codeId": max(c["codeId"] for c in state["codes"]) + 1,
               "codeType": body["codeType"], "codeName": body["codeName"]}
        state["codes"].append(row)
        self.json({**row, "created": True})

    def do_PUT(self):
        state = self.server.state
        body = self.read_body()
        state["seen"].append(("PUT", self.path, self.headers.get("Authorization")))
        parts = self.path.strip("/").split("/")
        if len(parts) != 5 or parts[:3] != ["api", "v1", "music"] or parts[4] != "classification":
            return self.send_error(404)
        music_id = int(parts[3])
        names = {c["codeId"]: c["codeName"] for c in state["codes"]}
        row = next(m for m in state["music"] if m["musicId"] == music_id)
        genres = sorted(dict.fromkeys(body["genreCodeIds"]))
        moods = sorted(dict.fromkeys(body["moodCodeIds"]))
        row["genres"] = [names[i] for i in genres]
        row["moods"] = [names[i] for i in moods]
        self.json({"musicId": music_id, "genreCodeIds": genres, "moodCodeIds": moods})

    # ── 도우미 ──────────────────────────────────────────────────────────
    def read_body(self):
        length = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(length)) if length else {}

    def json(self, data):
        body = json.dumps({"message": "ok", "data": data}, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class OverHttpTests(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Fake)
        self.server.state = {
            "codes": [{"codeId": 7, "codeType": "GENRE", "codeName": "Pop"},
                      {"codeId": 30, "codeType": "MOOD", "codeName": "bright"}],
            "music": [{"musicId": 91, "title": "First", "genres": [], "moods": []},
                      {"musicId": 37, "title": "Second", "genres": [], "moods": []}],
            "seen": [],
        }
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = "http://127.0.0.1:%d" % self.server.server_address[1]
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)

    def client(self, tmp):
        token = tmp / "refresh_token.txt"
        token.write_text("initial-refresh", encoding="utf-8")
        return BatchClient(self.base, refresh_token_file=str(token)), token

    def test_full_round_trip_over_real_http(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as temp:
            client, token = self.client(Path(temp))
            tracks = validate(source())

            state = inspect(client, tracks)
            self.assertEqual(state["missing_codes"], [("GENRE", "synthpop"), ("MOOD", "warm")])
            self.assertEqual(state["empty"], [91, 37])

            report = {"created": []}
            apply_plan(client, tracks, state, report, lambda: None)
            self.assertEqual(report["status"], "verified")

            stored = {m["musicId"]: (m["genres"], m["moods"]) for m in self.server.state["music"]}
            self.assertEqual(stored[91], (["synthpop"], []))
            self.assertEqual(sorted(stored[37][1]), ["bright", "warm"])

            # 토큰 회전을 받아 파일에 저장한다. 놓치면 다음 배치가 못 붙는다.
            self.assertEqual(token.read_text(encoding="utf-8"), "rotated-2")

    def test_every_call_carries_the_bearer_token_and_no_admin_path(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as temp:
            client, _ = self.client(Path(temp))
            tracks = validate(source())
            apply_plan(client, tracks, inspect(client, tracks), {"created": []}, lambda: None)

        seen = self.server.state["seen"]
        business = [row for row in seen if not row[1].startswith("/api/v1/auth")]
        self.assertTrue(business)
        for method, path, auth in business:
            with self.subTest(path=path):
                self.assertTrue(auth and auth.startswith("Bearer "), f"{method} {path}")
                self.assertNotIn("/admin/", path)
        self.assertIn(("PUT", "/api/v1/music/91/classification"),
                      [(m, p) for m, p, _ in business])
        self.assertTrue(any(p.startswith("/api/v1/music?") and "size=100" in p
                            for _, p, _ in business))

    def test_second_run_writes_nothing_over_the_wire(self):
        import tempfile
        from pathlib import Path
        with tempfile.TemporaryDirectory() as temp:
            client, _ = self.client(Path(temp))
            tracks = validate(source())
            apply_plan(client, tracks, inspect(client, tracks), {"created": []}, lambda: None)
            self.server.state["seen"].clear()
            report = {"created": []}
            apply_plan(client, tracks, inspect(client, tracks), report, lambda: None)

        self.assertEqual(report["created"], [])
        writes = [row for row in self.server.state["seen"] if row[0] in ("POST", "PUT")]
        self.assertEqual(writes, [])

    def test_plain_http_to_a_remote_host_is_refused(self):
        # 토큰을 평문으로 실어 보내게 된다. 로컬 주소만 예외로 둔다.
        with self.assertRaises(ValueError):
            BatchClient("http://j15a204.p.ssafy.io", refresh_token_file=None,
                        token_file=None)


if __name__ == "__main__":
    unittest.main()
