"""관통 시험 — 조회 → 다운로드 → 분석 → 등록 → 매핑 → 교체 → 추천 후보.

Spring 이 없으므로 **가짜 HTTP 서버**를 띄워 계약(`요구 5`·`6`·`1`)대로 답하게 한다.
없다고 가짜 성공으로 처리하지 않는다 — 서버가 답을 주지 않으면 배치는 **교체를 보류**한다.

음원과 MERT 는 없다. 기존 구간을 복제해 새 `music_id` 를 붙이고, 저장본에서 읽은 배열을
그대로 돌려주는 `analyze` 를 끼운다. 여기서 보는 것은 계산이 아니라 **단계가 계약을 타고
이어지는가**다.

가장 중요한 두 가지:

- 등록이 끝나기 전에는 색인을 **교체하지 않는다**
- 신곡이 전량 매핑되지 않으면 역시 교체하지 않는다 — 그 상태로 교체하면 그 곡이 뽑힐
  때마다 요청이 404 이고 `/internal/ready` 는 503 이다
"""
from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np
import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "music_preprocessing" / "src"))

from ai_server.app import paths                                      # noqa: E402

bt = pytest.importorskip("music_preprocessing.analysis.batch",
                         reason="analysis 선택 의존성이 없다")
ap = pytest.importorskip("music_preprocessing.analysis.api")
sg = pytest.importorskip("music_preprocessing.analysis.segmenter")
ix = pytest.importorskip("music_preprocessing.analysis.indexer")
pl = pytest.importorskip("music_preprocessing.analysis.pipeline")
pd = pytest.importorskip("pandas")

from test_index_update import NEEDED, make_seed                      # noqa: E402
from test_analysis_batch import write_id_map                         # noqa: E402

D = paths.data_dir()
MISSING = [n for n in NEEDED if not (D / n).exists()]
pytestmark = pytest.mark.skipif(bool(MISSING), reason=f"산출물이 없다: {MISSING}")

TOKEN = "test-internal-token"
BASE_SHA = ix.sha256(D / "features_v1.npz") if not MISSING else ""


# ────────────────────────────────────────────────────── 가짜 Spring

class FakeSpring(ThreadingHTTPServer):
    """계약대로 답한다. 호출 기록을 남겨 **무엇이 언제 나갔는지** 확인한다."""

    daemon_threads = True

    def __init__(self):
        super().__init__(("127.0.0.1", 0), _Handler)
        self.calls: list = []            # (method, kind, music_id, payload)
        self.tracks: dict = {}           # music_id -> 요구 5 응답 한 줄
        self.audio: dict = {}            # music_id -> bytes
        self.next_section_id = 7_000_001
        self.section_status = 200        # 요구 1 이 돌려줄 상태. 500 이면 실패
        self.page_size = 1               # 페이지네이션을 실제로 돌려 본다

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.server_address[1]}"

    def add_track(self, music_id: str, audio: bytes, **meta) -> dict:
        sha = hashlib.sha256(audio).hexdigest()
        t = {"music_id": int(music_id), "audio_object_key": f"music/{music_id}.mp3",
             "audio_download_url": f"{self.url}/audio/{music_id}",
             "audio_sha256": sha, "bpm_requested": 128,
             "genre_top1": "Rock", "category": "male_english_rock",
             "mood_tags": ["energetic"], "instrument_tags": ["guitar"],
             "vocal_type": "male", "language": "english",
             "analysis_status": "PENDING"}
        t.update(meta)
        self.tracks[str(music_id)] = t
        self.audio[str(music_id)] = audio
        return t

    def kinds(self, kind: str) -> list:
        return [c for c in self.calls if c[1] == kind]


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):           # 시험 출력이 접속 로그로 덮이지 않게
        pass

    # ---- 공통
    def _json(self, status: int, body: dict) -> None:
        raw = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _auth(self) -> bool:
        if self.headers.get("X-Internal-Token") == TOKEN:
            return True
        self._json(401, {"code": "MUPID_UNAUTHORIZED",
                         "message": "내부 호출 토큰이 올바르지 않습니다.",
                         "error_details": None})
        return False

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    # ---- 요구 5 · 음원
    def do_GET(self):
        s = self.server
        if self.path.startswith("/audio/"):
            mid = self.path.rsplit("/", 1)[-1]
            blob = s.audio.get(mid)
            if blob is None:
                self._json(404, {"code": "NOT_FOUND", "message": "없다"})
                return
            self.send_response(200)
            self.send_header("Content-Type", "audio/mpeg")
            self.send_header("Content-Length", str(len(blob)))
            self.end_headers()
            self.wfile.write(blob)
            return
        if not self._auth():
            return
        if "/pending-analysis" not in self.path:
            self._json(404, {"code": "NOT_FOUND", "message": "없다"})
            return
        from urllib.parse import parse_qs, urlparse

        q = parse_qs(urlparse(self.path).query)
        cursor = (q.get("cursor") or [""])[0]
        ids = sorted(s.tracks)
        start = ids.index(cursor) if cursor in ids else 0
        page = ids[start:start + s.page_size]
        nxt = ids[start + s.page_size] if start + s.page_size < len(ids) else None
        s.calls.append(("GET", "pending", cursor, len(page)))
        self._json(200, {"message": None, "data": {
            "analysis_version": "v1",
            "tracks": [s.tracks[i] for i in page], "next_cursor": nxt}})

    # ---- 요구 6
    def do_PATCH(self):
        if not self._auth():
            return
        s, b = self.server, self._body()
        mid = self.path.split("/music/")[1].split("/")[0]
        s.calls.append(("PATCH", "status", mid, b.get("status")))
        if b.get("status") not in ("PROCESSING", "FAILED"):
            # 계약: DONE 은 이 API 가 받지 않는다
            self._json(400, {"code": "MUPID_INVALID_REQUEST",
                             "message": "PROCESSING·FAILED 만 받습니다."})
            return
        self._json(200, {"message": None, "data": {
            "music_id": int(mid), "analysis_version": b.get("analysis_version"),
            "analysis_status": b.get("status")}})

    # ---- 요구 1
    def do_PUT(self):
        if not self._auth():
            return
        s, b = self.server, self._body()
        mid = self.path.split("/music/")[1].split("/")[0]
        s.calls.append(("PUT", "section", mid, len(b.get("sections") or [])))
        if s.section_status != 200:
            self._json(s.section_status, {"code": "MUPID_INTERNAL",
                                          "message": "일부러 실패"})
            return
        out = []
        for sec in b.get("sections") or []:
            out.append({"music_section_analysis_id": s.next_section_id,
                        "section_index": sec["section_index"],
                        "external_id": sec["external_id"]})
            s.next_section_id += 1
        self._json(200, {"message": None, "data": {
            "music_id": int(mid), "analysis_version": b.get("analysis_version"),
            "section_count": len(out), "created": len(out), "updated": 0,
            "sections": out, "removed_section_ids": []}})


@pytest.fixture(scope="module")
def spring():
    srv = FakeSpring()
    t = threading.Thread(target=srv.serve_forever, daemon=True)
    t.start()
    yield srv
    srv.shutdown()
    srv.server_close()


@pytest.fixture(scope="module")
def sandbox(tmp_path_factory):
    root = tmp_path_factory.mktemp("e2e")
    data = root / "data" / "proc" / "mupid"
    data.mkdir(parents=True)
    for p in D.iterdir():
        if p.is_file():
            shutil.copy2(p, data / p.name)
    # 기존 풀 **전량** 매핑을 먼저 깔아 둔다. 전량이 아니면 ready 503 · 추천 404 다.
    write_id_map(data)
    return root, data


# ────────────────────────────────────────────────────── 시드

def seed_track(data: Path, spring: FakeSpring, music_id: str, skip: int = 0):
    """기존 곡의 구간을 복제해 **새 music_id** 로 등록 대기 상태를 만든다."""
    seed = make_seed(data, 1, prefix=f"{music_id}#SEED", skip=skip)[0]
    seed.track_id = music_id
    for i, s in enumerate(seed.sections):
        s["moment_id"] = f"{music_id}#{i}"
    audio = f"fake-audio-{music_id}".encode() + b"\0" * 128
    spring.add_track(music_id, audio)
    return seed


def make_prepared(track: dict, api, work: Path, seed):
    """`prepare()` 를 librosa 없이 돈다 — 내려받기와 해시 검증은 진짜로 한다."""
    row = dict(seed.content_row)
    row.pop("track_id", None)
    return ap.prepare(track, api, work,
                      load=lambda p: np.zeros(1000, dtype=np.float32),
                      measure=lambda y: dict(row))


def write_sections(seed, dur: float, path: Path):
    sg.dump_sections(sg.SectionsFile(
        track_id=str(seed.track_id), duration_sec=float(dur),
        segments=[sg.Segment(str(s["section_label"]), float(s["start_sec"]),
                             float(s["end_sec"])) for s in seed.sections],
        segmenter={"name": "allin1", "model": "harmonix-all"}), path)


def fake_analyze(seeds):
    def analyze(track, sections, meta):
        s = seeds[str(track.track_id)]
        r = pl.TrackResult(track_id=str(track.track_id), status="ok", stage="done",
                           n_sections=len(s.sections))
        r.rows = [dict(x) for x in s.sections]
        r.embeddings, r.features, r.section_vecs = s.embeddings, s.features, s.section_vecs
        return r
    return analyze


def _probe(root: Path) -> dict:
    script = """
import json
from fastapi.testclient import TestClient
from ai_server.app.main import app
out = {}
c = TestClient(app, raise_server_exceptions=False)
with c:
    h = c.get("/health")
    out["health"] = h.status_code
    out["rows"] = (h.json().get("integrity") or {}).get("rows")
    out["backend"] = h.json().get("model_backend")
    r = c.get("/internal/ready")
    out["ready"] = r.status_code
    out["ready_body"] = str(r.json())[:200]
print("PROBE " + json.dumps(out, ensure_ascii=False))
"""
    import os

    env = dict(os.environ)
    env["MUPID_AI_ARTIFACT_DIR"] = str(root)
    env["PYTHONIOENCODING"] = "utf-8"
    p = subprocess.run([sys.executable, "-c", script], capture_output=True, text=True,
                       cwd=str(REPO_ROOT), env=env, encoding="utf-8")
    for line in (p.stdout or "").splitlines():
        if line.startswith("PROBE "):
            return json.loads(line[6:])
    pytest.skip(f"앱을 띄우지 못했다: {(p.stderr or '')[-400:]}")


# ────────────────────────────────────────────────────── 1. 클라이언트 계약

def test_pending_pages_through_every_track(spring, tmp_path):
    for i, mid in enumerate(("9001", "9002", "9003")):
        spring.add_track(mid, f"a{i}".encode())
    api = ap.InternalApi(spring.url, TOKEN)
    version, tracks = api.pending(limit=1)
    assert version == "v1"
    got = {str(t["music_id"]) for t in tracks}
    assert {"9001", "9002", "9003"} <= got, got
    assert len(spring.kinds("pending")) >= 3, "페이지를 한 번만 불렀다"
    spring.tracks.clear()
    spring.audio.clear()


def test_bad_token_is_not_retried(spring):
    api = ap.InternalApi(spring.url, "wrong")
    with pytest.raises(ap.ApiError) as e:
        api.pending()
    assert e.value.status == 401
    assert not e.value.retryable, "401 은 다시 불러도 같다"


def test_download_verifies_sha256(spring, tmp_path):
    t = spring.add_track("9100", b"real-bytes")
    api = ap.InternalApi(spring.url, TOKEN)
    dst = tmp_path / "ok.audio"
    api.download(t["audio_download_url"], dst, t["audio_sha256"])
    assert dst.read_bytes() == b"real-bytes"

    bad = tmp_path / "bad.audio"
    with pytest.raises(ValueError) as e:
        api.download(t["audio_download_url"], bad, "0" * 64)
    assert "해시가 다르다" in str(e.value)
    assert not bad.exists(), "해시가 틀린 파일을 남겼다"
    spring.tracks.clear()
    spring.audio.clear()


def test_status_refuses_done(spring):
    """`DONE` 은 요구 1 의 트랜잭션이 맡는다. 클라이언트가 보내지 않는다."""
    api = ap.InternalApi(spring.url, TOKEN)
    with pytest.raises(ValueError) as e:
        api.set_status(9001, "DONE", "abc")
    assert "PROCESSING" in str(e.value)


def test_sections_payload_matches_the_contract():
    class T:
        track_id = "1001"
        sections = [{"section_idx": 0, "section_label": "intro",
                     "start_sec": 0.0, "end_sec": 15.5},
                    {"section_idx": 1, "section_label": "chorus",
                     "start_sec": 15.5, "end_sec": 45.0}]
    got = ap.sections_payload(T())
    assert got[0] == {"section_index": 0, "external_id": "1001#0",
                      "section_label": "INTRO", "start_time_ms": 0,
                      "end_time_ms": 15500}
    assert got[1]["external_id"] == "1001#1" and got[1]["section_label"] == "CHORUS"


# ────────────────────────────────────────────────────── 2. 관통

def test_one_track_goes_all_the_way(spring, sandbox, tmp_path):
    root, data = sandbox
    before = _probe(root)
    if before["ready"] != 200:
        pytest.skip(f"교체 전부터 준비되지 않았다: {before['ready_body']}")
    rows_before = (before.get("rows") or {}).get("moments")
    print(f"\n교체 전  /health {before['health']} moments {rows_before} "
          f"· ready {before['ready']} · backend {before['backend']}")

    spring.tracks.clear()
    spring.audio.clear()
    spring.calls.clear()
    spring.section_status = 200
    seed = seed_track(data, spring, "9501", skip=20)
    seeds = {"9501": seed}

    api = ap.InternalApi(spring.url, TOKEN)
    _, pend = api.pending(limit=1)
    assert len(pend) == 1

    work = tmp_path / "work"
    tracks = [make_prepared(pend[0], api, work, seed)]
    assert tracks[0].audio_path.exists(), "음원을 내려받지 못했다"
    write_sections(seed, float(seed.track_dur_sec), tracks[0].sections_json)

    sha = {"9501": pend[0]["audio_sha256"]}
    reporter = ap.StatusReporter(api=api, sha=sha)
    reg = ap.HttpRegistrar(api=api, sha=sha)

    s = bt.run(tracks, data, state_path=tmp_path / "state.json",
               summary_path=tmp_path / "sum.jsonl", registrar=reg,
               status=reporter, analyze=fake_analyze(seeds))

    assert s.exit_code == bt.EXIT_OK, s.hold_reason
    assert s.swapped and s.added_tracks == 1

    # 요구 6 — PROCESSING 은 분석 **전에** 나가고, DONE 은 나가지 않는다
    sent = [c[3] for c in spring.kinds("status")]
    assert sent == ["PROCESSING"], sent
    assert not reporter.errors, reporter.errors
    # 요구 1 — 구간 수만큼 실렸다
    put = spring.kinds("section")
    assert len(put) == 1 and put[0][3] == len(seed.sections)
    # 순서: PROCESSING 이 PUT 보다 먼저다
    order = [c[1] for c in spring.calls if c[1] in ("status", "section")]
    assert order == ["status", "section"], order

    # 임시 음원은 지웠다
    assert not tracks[0].audio_path.exists(), "내려받은 음원이 남았다"

    after = _probe(root)
    rows_after = (after.get("rows") or {}).get("moments")
    print(f"교체 후  /health {after['health']} moments {rows_after} "
          f"· ready {after['ready']}")
    assert rows_after == rows_before + s.added_sections
    assert after["ready"] == 200, after["ready_body"]

    # 매핑이 실제로 붙었는가 — 신곡 구간이 id_map 에 있다
    idm = pd.read_parquet(data / "id_map.parquet")
    mine = idm[idm.source_track_id == "9501"]
    assert len(mine) == len(seed.sections)
    assert mine.music_section_id.min() >= 7_000_001


def test_second_run_skips_by_sha256(spring, sandbox, tmp_path):
    root, data = sandbox
    rows = len(pd.read_parquet(data / "moments.parquet"))
    spring.calls.clear()
    seed = seed_track(data, spring, "9502", skip=21)
    api = ap.InternalApi(spring.url, TOKEN)
    state = tmp_path / "state.json"

    def once(tag):
        _, pend = api.pending(limit=10)
        t = [x for x in pend if str(x["music_id"]) == "9502"][0]
        work = tmp_path / f"w{tag}"
        tr = make_prepared(t, api, work, seed)
        write_sections(seed, float(seed.track_dur_sec), tr.sections_json)
        sha = {"9502": t["audio_sha256"]}
        return bt.run([tr], data, state_path=state, summary_path=tmp_path / "s.jsonl",
                      registrar=ap.HttpRegistrar(api=api, sha=sha),
                      status=ap.StatusReporter(api=api, sha=sha),
                      analyze=fake_analyze({"9502": seed}))

    first = once("a")
    assert first.swapped
    grew = len(pd.read_parquet(data / "moments.parquet"))
    assert grew > rows

    second = once("b")
    assert second.skipped == 1 and second.ok == 0 and not second.swapped
    assert len(pd.read_parquet(data / "moments.parquet")) == grew, "두 번째가 또 붙였다"
    print(f"\n두 번째 — 건너뜀 {second.skipped} · 색인 {grew}행 그대로")


def test_registration_failure_blocks_the_swap(spring, sandbox, tmp_path):
    """등록이 안 되면 **교체하지 않는다.** 이 순서를 어기면 서비스가 통째로 멈춘다."""
    root, data = sandbox
    before = len(pd.read_parquet(data / "moments.parquet"))
    spring.calls.clear()
    spring.section_status = 500                 # 요구 1 이 계속 실패한다
    seed = seed_track(data, spring, "9503", skip=22)
    api = ap.InternalApi(spring.url, TOKEN)
    _, pend = api.pending(limit=10)
    t = [x for x in pend if str(x["music_id"]) == "9503"][0]
    tr = make_prepared(t, api, tmp_path / "w3", seed)
    write_sections(seed, float(seed.track_dur_sec), tr.sections_json)
    sha = {"9503": t["audio_sha256"]}

    s = bt.run([tr], data, state_path=tmp_path / "state3.json",
               summary_path=tmp_path / "s.jsonl",
               registrar=ap.HttpRegistrar(api=api, sha=sha),
               status=ap.StatusReporter(api=api, sha=sha),
               analyze=fake_analyze({"9503": seed}))

    assert s.exit_code == bt.EXIT_HOLD and not s.swapped
    assert len(pd.read_parquet(data / "moments.parquet")) == before
    assert Path(s.staged_dir).is_dir(), "새 판을 지우면 등록 뒤 다시 분석해야 한다"
    # 500 은 한 번 더 시도한다 (재시도 정책)
    assert len(spring.kinds("section")) == 2, spring.kinds("section")
    print(f"\n보류 — {s.hold_reason[:90]}")
    spring.section_status = 200
    shutil.rmtree(s.staged_dir, ignore_errors=True)


def test_analysis_failure_reports_failed(spring, sandbox, tmp_path):
    """분석이 깨지면 `FAILED` 와 짧은 코드가 나간다. 다음 배치가 맹목적으로 다시 돌지 않게."""
    root, data = sandbox
    spring.calls.clear()
    seed = seed_track(data, spring, "9504", skip=23)
    api = ap.InternalApi(spring.url, TOKEN)
    _, pend = api.pending(limit=10)
    t = [x for x in pend if str(x["music_id"]) == "9504"][0]
    tr = make_prepared(t, api, tmp_path / "w4", seed)
    # 구간 파일을 깨뜨린다 — 겹치면 같은 프레임이 두 구간에 들어간다
    write_sections(seed, float(seed.track_dur_sec), tr.sections_json)
    d = json.loads(tr.sections_json.read_text(encoding="utf-8"))
    d["segments"][1]["start"] = d["segments"][0]["start"]
    tr.sections_json.write_text(json.dumps(d), encoding="utf-8")
    sha = {"9504": t["audio_sha256"]}

    s = bt.run([tr], data, state_path=tmp_path / "state4.json",
               summary_path=tmp_path / "s.jsonl",
               registrar=ap.HttpRegistrar(api=api, sha=sha),
               status=ap.StatusReporter(api=api, sha=sha),
               analyze=fake_analyze({"9504": seed}))

    assert s.failed == 1 and not s.swapped
    sent = [c[3] for c in spring.kinds("status")]
    assert sent == ["PROCESSING", "FAILED"], sent
    assert not tr.audio_path.exists(), "실패해도 임시 음원은 지운다"


# ────────────────────────────────────────────────────── 3. 정리

def test_nothing_is_left_behind(sandbox):
    root, data = sandbox
    left = [p.name for p in data.parent.glob(f"{data.name}_*") if p.is_dir()]
    for p in data.parent.glob(f"{data.name}_*"):
        shutil.rmtree(p, ignore_errors=True)
    print(f"\n남아 있던 판 {len(left)}개 정리")
    assert not list(data.parent.glob(f"{data.name}_*"))

    assert ix.sha256(D / "features_v1.npz") == BASE_SHA, "진짜 산출물이 바뀌었다"
    assert not (D / "id_map.parquet").exists(), "진짜 산출물에 시험 매핑표를 썼다"
    assert not (D / bt.LOCK_NAME).exists()
    assert not (D / bt.SUMMARY_NAME).exists()
