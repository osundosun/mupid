"""job 581197 의 실패를 **그대로 재현**하고, 이제 통과하는지 본다.

그날 실제 배치 순서와 명령이었다.

    catalog --yes --no-progress   → 종료 0  (삭제된 곡을 눈치채지 못했다)
    covers  --yes --no-progress   → 종료 0
    lyrics  --yes --no-progress   → 종료 1  ← 404 MUPID_MUSIC_NOT_FOUND (T000957)
    classify --yes                → 종료 1  ← 서버 곡 누락/곡명 불일치: T000957
    batch                         → 종료 0  (경고만)

마지막에 배치 전체가 종료 1 로 끝나 파이프라인이 실패했다.

여기서는 같은 곡 구성을 만들어 같은 순서로 돌리고 **모든 단계가 0 으로 끝나는지**
본다. 진짜 HTTP 서버를 띄운다 — 목으로는 인증·경로·상태 코드가 검증되지 않는다.

## 서버가 흉내 내는 것

| 경로 | 삭제된 곡(music_id=957)에 대한 응답 |
| --- | --- |
| `GET /api/v1/music` | 목록에 없음 (삭제분을 걸러 준다) |
| `GET /api/v1/music/957` | **410 Gone** — 삭제가 확인된다 |
| `PATCH /api/v1/music/957` | 410 |
| `PUT /api/v1/music/957/lyric` | 410 |
| `PUT /api/v1/music/957/classification` | 410 (2026-09-18 수정 — 그전엔 통과했다) |
| `GET /api/v1/music/957/stream` | 410 |

실제 서버에서는 `classification` 이 `isDeleted` 를 보지 않아 **통과해 버린다**
(`MusicClassificationServiceImpl.replaceClassification` 은 `findById` 만 쓴다).
이건 이 티켓 범위 밖의 별개 결함이라 여기서는 404 로 두고, 배치가 그 곡을 아예
부르지 않는지(건너뛰는지)를 본다.
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

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src"
PORT = 18743

# 곡 3개: 957 은 서버에서 삭제됨. 나머지 둘은 정상.
MUSIC = {
    956: {"title": "Alive One", "deleted": False},
    957: {"title": "Gone Song", "deleted": True},
    958: {"title": "Alive Two", "deleted": False},
}
TITLE_BY_TRACK = {"T000956": "Alive One", "T000957": "Gone Song", "T000958": "Alive Two"}
TRACK_BY_MUSIC = {v: k for k, v in {"T000956": 956, "T000957": 957, "T000958": 958}.items()}
CALLS: list[str] = []
# 곡별 현재 분류(코드 이름). 삭제된 곡은 서버에서 사라져도 행은 남아 있다.
CLASSIFIED: dict[int, tuple[list, list]] = {}
# `options()` 가 주는 코드 번호 -> 이름
CODE_NAME = {7: "synthpop", 30: "화사한"}


class Handler(BaseHTTPRequestHandler):
    #: HTTP/1.1 + keep-alive 로 답한다. 기본값(HTTP/1.0)은 매 응답마다 연결을
    #: 닫아, Windows 에서 소켓이 RST 로 끊기며 `WinError 10053` 이 산발적으로 났다
    #: (5회 중 1회꼴로 하네스가 실패해 검증 결과를 믿을 수 없었다).
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _drain_body(self):
        """**본문을 읽고 버린다.**

        읽지 않고 응답하면 수신 버퍼에 데이터가 남은 채로 다음 응답을 쓰게 되고,
        Windows 는 그 상태에서 연결을 닫을 때 RST 를 보낸다 — 배치 쪽은
        `WinError 10053`(연결이 끊겼습니다)으로 죽는다. 이 하네스는 PATCH·POST
        본문을 쓰지 않으므로 내용은 버리고 소비만 한다.

        **두 번 부르면 안 된다.** 이미 읽은 뒤 또 읽으면 소켓에 남은 것이 없어
        `read()` 가 상대가 연결을 닫을 때까지 멈춘다(`timed out`). 그래서 본문을
        직접 읽는 `do_PUT` 같은 곳은 이걸 부르지 않는다.
        """
        length = int(self.headers.get("Content-Length") or 0)
        if length:
            self.rfile.read(length)

    def _json(self, status, body, headers=None, drain=True):
        if drain:
            self._drain_body()
        raw = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)
        # 본문을 안 읽고 버린 응답 뒤에도 keep-alive 를 이어 가려면 남은 것을
        # 비워야 한다. 다만 이 시험의 클라이언트는 요청마다 연결을 새로 쓰고,
        # 스트림을 닫는 쪽이 더 안전해 여기서는 연결을 닫는다.
        self.close_connection = True

    def _auth(self):
        if self.headers.get("Authorization", "").startswith("Bearer "):
            return True
        self._json(401, {"code": "UNAUTHORIZED", "message": "토큰 없음"})
        return False

    def _missing(self, music_id):
        return music_id not in MUSIC or MUSIC[music_id]["deleted"]

    def do_GET(self):
        path = self.path.split("?")[0]
        CALLS.append(f"GET {path}")
        if path == "/api/v1/auth":
            self._json(200, {"data": {"access_token": "fake.access.token"}},
                       {"Set-Cookie": "refresh_token=rotated; Path=/"})
            return
        if not self._auth():
            return
        if path == "/api/v1/music":
            items = [{"music_id": m, "title": v["title"],
                      "genres": CLASSIFIED.get(m, ([], []))[0],
                      "moods": CLASSIFIED.get(m, ([], []))[1]}
                     for m, v in sorted(MUSIC.items()) if not v["deleted"]]
            self._json(200, {"data": {"items": items, "total_elements": len(items),
                                      "total_pages": 1}})
            return
        if path == "/api/v1/onboarding/option":
            self._json(200, {"data": {
                "genres": [{"code_id": 7, "name": "synthpop"}],
                "moods": [{"code_id": 30, "name": "화사한"}]}})
            return
        if path.endswith("/stream"):
            music_id = int(path.split("/")[4])
            if self._missing(music_id):
                self._json(410, {"code": "MUPID_MUSIC_GONE",
                                 "message": "요청한 음악은 삭제되었습니다."})
                return
            self._json(200, {"data": []})
            return
        # 단건 곡 조회. 감사가 목록에 없던 곡만 골라 이걸 부른다 —
        # **410(삭제) 과 404(그런 번호 없음) 를 갈라 줘야** 배치가 등록 기록이
        # 낡은 것과 서버에서 지워진 것을 구분한다.
        if path.startswith("/api/v1/music/"):
            music_id = int(path.rsplit("/", 1)[-1])
            if self._missing(music_id):
                self._json(410, {"code": "MUPID_MUSIC_GONE",
                                 "message": "요청한 음악은 삭제되었습니다."})
                return
            row = MUSIC.get(music_id)
            if row is None:
                self._json(404, {"code": "MUPID_MUSIC_NOT_FOUND",
                                 "message": "요청한 음악을 찾을 수 없습니다."})
                return
            self._json(200, {"data": {"music_id": music_id, "title": row["title"]}})
            return
        self._json(404, {"code": "NOT_FOUND", "message": path})

    def do_PATCH(self):
        path = self.path.split("?")[0]
        CALLS.append(f"PATCH {path}")
        if not self._auth():
            return
        if path.startswith("/api/v1/music/"):
            music_id = int(path.rsplit("/", 1)[-1])
            if self._missing(music_id):
                # 삭제된 곡은 404 가 아니라 410 이다 (2026-09-18 변경).
                self._json(410, {"code": "MUPID_MUSIC_GONE",
                                 "message": "요청한 음악은 삭제되었습니다."})
                return
        # 아티스트·앨범 PATCH 는 200 이면 된다 (이 시험이 보는 것이 아니다).
        self._json(200, {"data": None})

    def do_PUT(self):
        path = self.path.split("?")[0]
        CALLS.append(f"PUT {path}")
        if not self._auth():
            return
        music_id = int(path.split("/")[4])
        if self._missing(music_id):
            self._json(404, {"code": "MUPID_MUSIC_NOT_FOUND",
                             "message": "요청한 음악을 찾을 수 없습니다."})
            return
        # 본문을 **여기서 직접** 읽는다. `_json` 에 읽기를 맡기면 두 번 읽어 멈춘다.
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length)) if length else {}
        if path.endswith("/classification"):
            CLASSIFIED[music_id] = (
                [CODE_NAME[i] for i in body.get("genre_code_ids", [])],
                [CODE_NAME[i] for i in body.get("mood_code_ids", [])])
            self._json(200, {"data": {"music_id": music_id,
                                      "genre_code_ids": body.get("genre_code_ids", []),
                                      "mood_code_ids": body.get("mood_code_ids", [])}}, drain=False)
            return
        self._json(200, {"data": {"music_id": music_id, "deleted": 0}}, drain=False)

    def do_POST(self):
        path = self.path.split("?")[0]
        CALLS.append(f"POST {path}")
        if not self._auth():
            return
        if path == "/api/v1/code":
            self._json(200, {"data": {"code_id": 90, "code_name": "synthpop",
                                      "display_name": "synthpop", "created": False}})
            return
        if path.startswith("/api/v1/storage/image/"):
            # 커버 업로드. 번호만 있으면 된다.
            self._json(200, {"data": {"file_id": 555}})
            return
        self._json(404, {"code": "NOT_FOUND", "message": path})


def build_inputs(root: Path) -> Path:
    """메타데이터·커버·어휘·등록 기록. 삭제된 곡에 예외 표식은 아직 없다.

    표식이 없는 상태에서 시작하는 것이 핵심이다 — `catalog` 이 스스로 찾아내야
    한다. 그날 실패한 이유가 바로 이것이었다.
    """
    meta = root / "metadata"
    covers = root / "covers"
    audio = root / "audio"
    for d in (meta, covers, audio):
        d.mkdir(parents=True, exist_ok=True)

    for track_id, music_id in (("T000956", 956), ("T000957", 957), ("T000958", 958)):
        title = TITLE_BY_TRACK[track_id]
        (meta / f"{track_id}.json").write_text(json.dumps({
            "track_id": track_id, "flow_title": title, "genre": "synthpop",
            "mood": {"primary": "bright", "secondary": None},
            "generated_at": "2026-09-08T05:03:31.236059+00:00",
            "artist": {"artist_key": "a", "name": "아티스트"},
            "album": {"album_key": "al", "title": "앨범"},
            "lyrics_sync": [{"text": "hello", "start_sec": 1.0, "end_sec": 2.0}],
        }), encoding="utf-8")
        (covers / f"{track_id}.jpg").write_bytes(b"\xff\xd8\xff\xd9")
        (audio / f"{track_id}.mp3").write_bytes(b"placeholder audio")

    # 어휘: 곡 3개 · 장르 1 · 무드 1. taxonomy 수량 검증이 이 값을 요구한다.
    (root / "genre_mood_taxonomy.json").write_text(json.dumps({
        "genres": [{"name": "synthpop", "track_count": 3}],
        "moods": [{"name": "bright", "track_count": 3}],
        "genre_total_distinct": 1, "mood_total_distinct": 1,
        "tracks_without_mood": [],
    }), encoding="utf-8")

    # 아티스트·앨범은 이미 등록된 상태. 곡만 남았다.
    (root / "registered.json").write_text(json.dumps({
        "artists": {}, "albums": {}, "tracks": {},
        "catalog": {
            "artists": {"a": {"artist_id": 10, "name": "아티스트",
                              "fields_sha256": "artist-fp"}},
            "albums": {"al": {"album_id": 20, "artist_id": 10, "title": "앨범",
                              "fields_sha256": "album-fp"}},
            "lyrics": {},
            "tracks": {t: {"music_id": m, "title": TITLE_BY_TRACK[t]}
                       for t, m in (("T000956", 956), ("T000957", 957), ("T000958", 958))},
        },
    }), encoding="utf-8")

    (root / "refresh.txt").write_text("refresh-token-value", encoding="utf-8")
    (root / "config.json").write_text(json.dumps({
        "api_base_url": f"http://127.0.0.1:{PORT}",
        "api_refresh_token_file": str(root / "refresh.txt"),
        "api_registry_log": str(root / "registered.json"),
        "metadata_dir": str(meta),
        "covers_dir": str(covers),
        "genre_taxonomy_file": str(root / "genre_mood_taxonomy.json"),
        "log_dir": str(root / "logs"),
    }), encoding="utf-8")
    return root


def run_stage(root: Path, cmd: str) -> tuple[int, str]:
    env = dict(os.environ)
    env["PYTHONPATH"] = str(SRC)
    env["PYTHONIOENCODING"] = "utf-8"
    args = [sys.executable, "-m", "music_preprocessing", cmd,
            "-c", "config.json", "--yes", "--no-log-file"]
    if cmd == "classify":
        args.append("--skip-mood-approximations")
    proc = subprocess.run(args, cwd=str(root), env=env,
                          capture_output=True, text=True, encoding="utf-8")
    return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


def main() -> int:
    failures: list[str] = []
    with tempfile.TemporaryDirectory() as temp:
        root = build_inputs(Path(temp))
        registry = root / "registered.json"

        server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        time.sleep(0.3)

        # 그날의 순서 그대로. catalog 가 삭제된 곡을 스스로 찾아내야 한다.
        codes: dict[str, int] = {}
        for stage in ("catalog", "covers", "lyrics", "classify"):
            code, out = run_stage(root, stage)
            codes[stage] = code
            print(f"\n═══ {stage} (종료 {code}) ═══")
            print(out.strip()[-1200:])

        print("\n═══ 단계별 종료 코드 ═══")
        for stage, code in codes.items():
            mark = "OK " if code == 0 else "FAIL"
            print(f"  [{mark}] {stage}: {code}")

        # ── 단언 ──
        for stage in ("catalog", "covers", "lyrics", "classify"):
            if codes[stage] != 0:
                failures.append(f"{stage} 가 종료 {codes[stage]} 로 실패했다 (0 이어야 한다)")

        data = json.loads(registry.read_text(encoding="utf-8"))
        track = data["catalog"]["tracks"].get("T000957", {})
        if not track.get("server_missing"):
            failures.append("catalog 이 삭제된 곡을 예외로 남기지 않았다")
        print(f"\nT000957 예외 표식: {track.get('server_missing')}")

        # 삭제된 곡은 서버를 부르지 않았어야 한다.
        touched_957 = [c for c in CALLS if "/957" in c]
        print(f"\n서버가 받은 music_id=957 호출: {touched_957 or '없음'}")
        # 단건 조회 한 번은 **의도된 것**이다 — 그것으로 410 을 받아 "삭제됨" 을
        # 판정한다. 이걸 금지하면 판정 자체가 불가능해진다. 금지할 것은 등록·갱신
        # (쓰기) 요청이다.
        allowed = {"GET /api/v1/music/957"}  # 감사의 단건 조회
        unexpected = [c for c in touched_957 if c not in allowed]
        if unexpected:
            failures.append(f"삭제된 곡에 등록·갱신 요청이 나갔다: {unexpected}")
        write_paths = [
            c for c in unexpected
            if any(v in c for v in ("PATCH", "PUT", "POST", "DELETE"))
        ]
        if write_paths:
            failures.append(f"삭제된 곡에 쓰기 요청이 나갔다: {write_paths}")

        # ── 완료조건: 기존 정상 곡이 중복 등록되지 않는다 ──
        # `POST /api/v1/music` 은 **새 행을 만든다**(중복 검사가 없다). 이미
        # `registered.json` 에 있는 곡을 다시 POST 하면 옛 행 + DONE 스트림이
        # 고아로 남는다. 그래서 등록된 곡은 POST 가 아니라 PATCH 로만 가야 한다.
        music_posts = [c for c in CALLS if c == "POST /api/v1/music"]
        print(f"POST /api/v1/music 호출: {len(music_posts)}건 (0 이어야 한다)")
        if music_posts:
            failures.append(
                f"이미 등록된 곡을 다시 만들었다(POST): {len(music_posts)}건 — "
                "중복 행과 고아 스트림이 생긴다"
            )

        # 살아 있는 두 곡은 실제로 등록됐어야 한다.
        if 956 not in CLASSIFIED or 958 not in CLASSIFIED:
            failures.append(f"살아 있는 곡의 분류가 등록되지 않았다: {sorted(CLASSIFIED)}")
        if 957 in CLASSIFIED:
            failures.append("삭제된 곡에 분류를 썼다")
        print(f"분류가 등록된 곡: {sorted(CLASSIFIED)}")

        # 곡명 불일치 곡이 없으므로 catalog 는 한 건도 안 멈추고 끝나야 한다.
        if track.get("title") != "Gone Song":
            failures.append("등록 기록의 곡명이 바뀌었다")

        server.shutdown()

    print()
    if failures:
        print("실패:")
        for line in failures:
            print(f"  ✖ {line}")
        return 1
    print("전부 통과 — 그날 실패한 배치가 삭제된 곡 하나 때문에 멈추지 않는다")
    return 0


if __name__ == "__main__":
    sys.exit(main())
