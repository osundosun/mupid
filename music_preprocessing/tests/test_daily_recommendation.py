"""오늘의 모먼트 시작 단계 (S15P21A204-448). 진짜 HTTP 로 보낸다.

배치가 부르는 경로·인증이 맞는지, 서버가 "이미 진행 중" 이라고 답해도 실패로 보지
않는지를 본다. 중복 실행은 서버의 날짜별 선점이 막으므로 여기서는 재시도를 막지 않는다.
"""
import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from music_preprocessing.api_client import MupidClient

from test_genre_mood_http import access_token


class Fake(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def _json(self, status, body, headers=()):
        raw = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        for k, v in headers:
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def do_GET(self):
        self._json(200, {"data": {"access_token": access_token()}},
                   [("Set-Cookie", "refresh_token=rotated; Path=/")])

    def do_POST(self):
        state = self.server.state
        length = int(self.headers.get("Content-Length") or 0)
        self.rfile.read(length)
        state["posts"].append((self.path, self.headers.get("Authorization")))
        started = not state["posts"][:-1]      # 두 번째부터는 이미 진행 중
        self._json(202, {"data": {"date": "2026-09-25", "started": started}})


class DailyRecommendationTest(unittest.TestCase):
    def setUp(self):
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Fake)
        self.server.state = {"posts": []}
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.client = MupidClient(f"http://127.0.0.1:{self.server.server_port}",
                                  "refresh", timeout=30, max_retries=3)

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()

    def test_posts_to_the_start_endpoint_with_a_bearer_token(self):
        data = self.client.start_daily_recommendations()
        self.assertEqual(data, {"date": "2026-09-25", "started": True})
        path, auth = self.server.state["posts"][0]
        self.assertEqual(path, "/api/v1/recommendation/daily/start")
        self.assertTrue(auth.startswith("Bearer "))

    def test_already_running_is_an_answer_not_an_error(self):
        self.client.start_daily_recommendations()
        again = self.client.start_daily_recommendations()
        self.assertFalse(again["started"])
        self.assertEqual(len(self.server.state["posts"]), 2)


if __name__ == "__main__":
    unittest.main()
