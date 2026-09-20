"""`repair-track` CLI 를 **진짜 HTTP 서버** 상대로 확인한다.

목 서버로는 "부르긴 불렀는데 서버가 못 알아듣는" 오류가 잡히지 않는다
(`GENRE_MOOD.md` 의 함정 절). 특히 `--repair-track` 은 새 명령이라 옵션 이름·
종료 코드·인자 검사가 실제로 도는지 봐야 한다.

## 재현하는 상황 (ticket S15P21A204-399)

곡 두 개가 등록돼 있고 그중 `T000002`(music_id=2)가 **소프트 삭제**됐다.

    GET  /api/v1/music                 → 2번은 목록에 없다 (findPage 의 isDeleted 필터)
    GET  /api/v1/music/2               → 404
    PATCH /api/v1/music/2              → 404   (getMusic 이 isDeleted 를 본다)
    GET  /api/v1/music/2/stream        → 404   → 남은 스트림을 조회로 볼 수 없다

그래서 배치는 목록 대조로만 그 곡을 알아낼 수 있고, 남은 스트림은
`registered.json` 의 청킹 기록으로만 알 수 있다.

이 스크립트는 서버를 띄우고 `repair-track` 을 세 번 부른다.

    1. 미리보기 (`--apply` 없이) — 아무것도 고치지 않아야 한다
    2. `--apply`            — 그 곡만 기록에서 걷어내야 한다
    3. 형식이 틀린 ID        — 0 이 아닌 코드로 거부해야 한다

마지막에 `registered.json` 을 다시 읽어 살아 있는 곡이 남았는지 확인한다.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "src"
sys.path.insert(0, str(SRC))

# ── 서버가 들고 있을 상태 ──
MUSIC = {
    1: {"music_id": 1, "title": "Alive Song", "deleted": False},
    2: {"music_id": 2, "title": "Gone Song", "deleted": True},
}
CALLS: list[tuple[str, str]] = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):  # 조용히
        pass

    def _json(self, status: int, body: dict) -> None:
        """**요청 본문을 읽고 버린 뒤** 응답한다.

        읽지 않고 응답하면 수신 버퍼에 데이터가 남은 채 연결이 닫히고, Windows 는
        그때 RST 를 보낸다 — 배치 쪽은 `WinError 10053`(연결이 끊겼습니다)으로
        죽는다. 이 하네스는 PATCH 본문을 쓰지 않으므로 내용은 버리고 소비만 한다.
        (`verify_batch_survives_deleted_song.py` 에서 같은 결함으로 5회에 1회꼴
        실패했다.)
        """
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)

        raw = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)
        # 남은 것을 비운 뒤 연결을 닫는다 — keep-alive 로 두면 다음 요청이
        # 앞 요청의 잔여 데이터와 섞인다.
        self.close_connection = True

    def _auth(self) -> bool:
        """액세스 토큰 검사. `MupidClient` 가 `GET /api/v1/auth` 로 먼저 받아 온다."""
        if self.headers.get("Authorization", "").startswith("Bearer "):
            return True
        self._json(401, {"code": "UNAUTHORIZED", "message": "토큰 없음"})
        return False

    def do_GET(self):
        path = self.path.split("?")[0]
        CALLS.append(("GET", path))

        if path == "/api/v1/auth":
            # 리프레시 토큰은 쿠키로 온다. 회전도 흉내 낸다.
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Set-Cookie", "refresh_token=rotated-token; Path=/")
            raw = json.dumps({"data": {"access_token": "fake.access.token"}}).encode()
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            return

        if not self._auth():
            return

        if path == "/api/v1/music":
            # 살아 있는 곡만 준다. 삭제된 곡은 목록에서 사라진다.
            items = [
                {"music_id": m["music_id"], "title": m["title"],
                 "genres": [], "moods": []}
                for m in MUSIC.values() if not m["deleted"]
            ]
            self._json(200, {"data": {"items": items, "total_elements": len(items),
                                      "total_pages": 1}})
            return

        if path.startswith("/api/v1/music/") and path.endswith("/stream"):
            music_id = int(path.split("/")[4])
            # 곡이 삭제됐으면 404 — 딸린 스트림이 있어도 보이지 않는다.
            if MUSIC.get(music_id, {}).get("deleted") or music_id not in MUSIC:
                self._json(404, {"code": "MUPID_MUSIC_NOT_FOUND",
                                 "message": "요청한 음악을 찾을 수 없습니다."})
                return
            self._json(200, {"data": []})
            return

        self._json(404, {"code": "NOT_FOUND", "message": path})

    def do_PATCH(self):
        path = self.path.split("?")[0]
        CALLS.append(("PATCH", path))
        if not self._auth():
            return
        if path.startswith("/api/v1/music/"):
            music_id = int(path.rsplit("/", 1)[-1])
            if MUSIC.get(music_id, {}).get("deleted") or music_id not in MUSIC:
                self._json(404, {"code": "MUPID_MUSIC_NOT_FOUND",
                                 "message": "요청한 음악을 찾을 수 없습니다."})
                return
            self._json(200, {"data": None})
            return
        self._json(404, {"code": "NOT_FOUND", "message": path})


def make_registry(root: Path) -> Path:
    """곡 둘 · 아티스트/앨범 등록 완료 상태. 2번은 이미 표식이 붙어 있다."""
    audio2 = root / "audio" / "T000002.mp3"
    audio1 = root / "audio" / "T000001.mp3"
    (root / "audio").mkdir(parents=True, exist_ok=True)
    audio1.write_bytes(b"alive")
    audio2.write_bytes(b"gone")
    path = root / "registered.json"
    path.write_text(json.dumps({
        "artists": {}, "albums": {},
        "tracks": {
            str(audio1): {"music_id": 1, "stream_id": 11},
            str(audio2): {"music_id": 2, "stream_id": 77},
        },
        "catalog": {
            "artists": {}, "albums": {},
            "lyrics": {"T000002": {"music_id": 2, "line_count": 5}},
            "tracks": {
                "T000001": {"music_id": 1, "title": "Alive Song"},
                "T000002": {
                    "music_id": 2, "title": "Gone Song",
                    "server_missing": {"detected_at": "2026-09-18T18:00:00+09:00",
                                       "music_id": 2,
                                       "reason": "서버 곡 목록에 없음"},
                },
            },
        },
    }), encoding="utf-8")
    return path


def run_cli(root: Path, *args: str) -> tuple[int, str]:
    """`music-preprocessing repair-track …` 을 실제로 실행한다.

    경로는 **설정 파일로** 준다. 다른 명령(`classify` · `catalog`)과 같은 방식이고,
    실제 배치(`run_batch.sh`)가 쓰는 방식이기도 하다.
    """
    (root / "config.json").write_text(json.dumps({
        "api_base_url": f"http://127.0.0.1:{PORT}",
        "api_refresh_token_file": str(root / "refresh.txt"),
        "api_registry_log": str(root / "registered.json"),
        "log_dir": str(root / "logs"),
    }), encoding="utf-8")

    env = dict(os.environ)
    env["PYTHONPATH"] = str(SRC)
    env["PYTHONIOENCODING"] = "utf-8"
    proc = subprocess.run(
        [sys.executable, "-m", "music_preprocessing", "repair-track", *args,
         "-c", "config.json", "--no-log-file"],
        cwd=str(root), env=env, capture_output=True, text=True, encoding="utf-8",
    )
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def main() -> int:
    failures: list[str] = []
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        (root / "refresh.txt").write_text("refresh-token-value", encoding="utf-8")
        registry_path = make_registry(root)

        server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        time.sleep(0.3)
        print(f"서버 기동: http://127.0.0.1:{PORT}")

        # ── 1) 미리보기 ──
        before = json.loads(registry_path.read_text(encoding="utf-8"))
        code, out = run_cli(root, "T000002")
        after = json.loads(registry_path.read_text(encoding="utf-8"))
        print("\n── 1) 미리보기 ──")
        print(out.strip()[-600:])
        if code != 0:
            failures.append(f"미리보기가 0 이 아니다: {code}")
        if before != after:
            failures.append("미리보기가 registered.json 을 고쳤다")
        if "미리보기" not in out:
            failures.append("미리보기 표시가 없다")
        if "77" not in out:
            failures.append("남은 스트림 번호(77)를 보여 주지 않았다")
        if after["catalog"]["tracks"].get("T000002") is None:
            failures.append("미리보기인데 곡 항목이 사라졌다")

        # ── 2) 실제로 고치기 ──
        code, out = run_cli(root, "T000002", "--apply")
        fixed = json.loads(registry_path.read_text(encoding="utf-8"))
        print("\n── 2) --apply ──")
        print(out.strip()[-600:])
        if code != 0:
            failures.append(f"--apply 가 0 이 아니다: {code}")
        if "T000002" in fixed["catalog"]["tracks"]:
            failures.append("없는 곡이 등록 기록에 남아 있다")
        if "T000002" in fixed["catalog"]["lyrics"]:
            failures.append("가사 기록이 남아 있다")
        if any(v.get("music_id") == 2 for v in fixed["tracks"].values()):
            failures.append("청킹 기록이 남아 있다")
        # 살아 있는 곡은 그대로여야 한다.
        if "T000001" not in fixed["catalog"]["tracks"]:
            failures.append("살아 있는 곡까지 지웠다")
        if not any(v.get("music_id") == 1 for v in fixed["tracks"].values()):
            failures.append("살아 있는 곡의 청킹 기록을 지웠다")
        # 원본 오디오는 손대지 않는다.
        if not (root / "audio" / "T000002.mp3").exists():
            failures.append("원본 오디오를 지웠다")

        # ── 3) 형식이 틀린 ID ──
        code, out = run_cli(root, "T2", "--apply")
        print("\n── 3) 형식 오류 ──")
        print(out.strip()[-300:])
        if code == 0:
            failures.append("형식이 틀린 곡 ID 를 받아들였다")
        if "형식" not in out:
            failures.append("형식 오류 안내가 없다")

        # ── 4) 등록 기록에 없는 곡 ──
        code, out = run_cli(root, "T999999", "--apply")
        print("\n── 4) 없는 곡 ──")
        print(out.strip()[-300:])
        if code == 0:
            failures.append("등록 기록에 없는 곡을 통과시켰다")

        # ── 호출 기록 ──
        print("\n── 서버가 받은 호출 ──")
        for method, path in CALLS:
            print(f"  {method} {path}")
        if ("GET", "/api/v1/music/2/stream") in CALLS:
            # 물어봐도 404 라 볼 수 없다. 실제로는 부르지 않는 경로다.
            pass

        server.shutdown()

    print()
    if failures:
        print("실패:")
        for line in failures:
            print(f"  ✖ {line}")
        return 1
    print("전부 통과 — repair-track 이 실제 HTTP 상대로 동작한다")
    return 0


if __name__ == "__main__":
    PORT = 18742
    sys.exit(main())
