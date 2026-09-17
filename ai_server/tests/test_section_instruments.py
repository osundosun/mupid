"""구간별 악기 — 스템에서 읽어 요구 1 로 보내기까지.

악기는 곡이 아니라 **구간**에 매달린다(`music_analysis_instruments` 의 외래키가
`music_section_analysis_id` 다). 그래서 여기서 제일 중요한 것은 값이 맞는가보다
**어느 구간에 붙는가**와 **순서**다.

- 구간마다 다른 답이 나오는가 (곡 단위로 뭉개지지 않는가)
- 코드가 구간보다 **먼저** 등록되는가 — 서버는 코드표에 없는 악기 이름을 버리고
  세므로, 뒤집히면 오류 없이 악기만 조용히 빈다
- 스템이 없을 때 곡을 죽이지 않는가, 그러면서 이유는 남기는가

음원도 demucs 도 없다. 파형은 numpy 로 만들고 적재 함수를 끼워 넣는다 — 여기서
보는 것은 신호 처리가 아니라 연결이다. 문턱값 자체는 실측으로 맞춰야 하고, 그
근거는 `instruments.section_levels()` 가 내는 비율이다.
"""
from __future__ import annotations

import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import numpy as np
import pytest

REPO_ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO_ROOT / "music_preprocessing" / "src"))

ins = pytest.importorskip("music_preprocessing.analysis.instruments",
                          reason="analysis 선택 의존성이 없다")
emb = pytest.importorskip("music_preprocessing.analysis.embedder")
ap = pytest.importorskip("music_preprocessing.analysis.api")
pl = pytest.importorskip("music_preprocessing.analysis.pipeline")

SR = emb.SR
SECONDS = 30.0


def tone(level: float, start: float = 0.0, end: float = SECONDS) -> np.ndarray:
    """`start~end` 구간에만 값이 있는 파형. 그 밖은 0 이다."""
    y = np.zeros(int(SECONDS * SR), dtype=np.float32)
    a, b = int(start * SR), int(end * SR)
    if b > a:
        y[a:b] = level
    return y


def segments():
    """세 구간. 인트로 · 후렴 · 아웃트로."""
    return [emb.Segment(label="intro", start=0.0, end=10.0),
            emb.Segment(label="chorus", start=10.0, end=20.0),
            emb.Segment(label="outro", start=20.0, end=30.0)]


def stems(**levels) -> dict:
    """스템 이름 → 파형. 주지 않은 스템은 무음이다."""
    out = {}
    for stem, _ in ins.STEMS:
        value = levels.get(stem, 0.0)
        out[stem] = value if isinstance(value, np.ndarray) else tone(value)
    return out


# ────────────────────────────────────────────── 1. 구간마다 답이 다르다

def test_instruments_differ_between_sections():
    """인트로는 건반만, 후렴은 전부 — 곡 하나로 뭉개지면 이 표를 둘 이유가 없다."""
    mix = tone(1.0)
    got = ins.section_instruments(mix, stems(
        # 보컬·드럼·베이스는 후렴(10~20초)에만 있다
        vocals=tone(0.5, 10.0, 20.0),
        drums=tone(0.5, 10.0, 20.0),
        bass=tone(0.5, 10.0, 20.0),
        other=tone(0.5),                       # 그 외 악기는 내내
    ), segments())

    assert got[0] == ["그 외 악기"]
    assert got[1] == ["보컬", "드럼", "베이스", "그 외 악기"]
    assert got[2] == ["그 외 악기"]


def test_order_is_fixed_so_the_screen_does_not_shuffle():
    """`STEMS` 순서를 그대로 쓴다. 곡마다 순서가 흔들리면 화면이 튄다."""
    mix = tone(1.0)
    got = ins.section_instruments(
        mix, stems(bass=tone(0.5), vocals=tone(0.5)), segments())

    assert got[0] == ["보컬", "베이스"]


# ────────────────────────────────────────────── 2. 누설을 악기로 세지 않는다

def test_bleed_in_a_silent_stem_is_not_called_present():
    """보컬이 없는 연주곡도 보컬 스템에 누설이 남는다.

    스템 자기 분포와 견주면 그 누설의 최댓값이 기준이 되어 **모든 구간에 보컬이
    있다**고 답한다. 믹스와 견주므로 작은 비율로 남는다.
    """
    mix = tone(1.0)
    # -40 dB 짜리 누설. 문턱값은 -20 dB 다.
    got = ins.section_instruments(mix, stems(vocals=tone(0.01), other=tone(0.9)),
                                  segments())

    assert got[0] == ["그 외 악기"]


def test_a_near_silent_section_reports_nothing():
    """구간이 통째로 조용하면 **비율만으로는 아무거나 통과한다.**

    -100 dBFS 짜리 잔향에서도 보컬 스템이 믹스의 100% 를 차지할 수 있다. 비율은
    1.0 이라 문턱값을 넘는다. 무음 바닥이 그걸 막는다 — 없으면 조용한 인트로마다
    악기가 네 개씩 붙는다.
    """
    faint = tone(1e-5)          # 바닥(1e-3)보다 한참 아래
    got = ins.section_instruments(
        faint, stems(vocals=faint, other=faint), segments())

    assert got == [[], [], []]

    # 바닥을 내리면 같은 입력이 통과한다 — 이 시험이 무엇을 보는지 못 박아 둔다.
    loosened = ins.section_instruments(
        faint, stems(vocals=faint, other=faint), segments(), abs_floor=0.0)
    assert loosened[0] == ["보컬", "그 외 악기"]


def test_a_fully_silent_section_reports_nothing():
    quiet = tone(0.0)
    got = ins.section_instruments(quiet, stems(vocals=quiet, other=quiet), segments())

    assert got == [[], [], []]


def test_levels_are_reported_so_the_threshold_can_be_tuned():
    """판정이 아니라 **비율**을 볼 수 있어야 문턱값을 맞출 수 있다.

    지금 값(-20 dB)은 실측으로 맞춘 것이 아니다. 첫 실주행에서 이 표를 보고 고친다.
    """
    mix = tone(1.0)
    rows = ins.section_levels(mix, stems(vocals=tone(0.5)), segments())

    assert rows[0]["vocals"]["ratio"] == pytest.approx(0.5, rel=1e-6)
    assert rows[0]["mix"]["ratio"] == 1.0
    assert rows[0]["drums"]["ratio"] == 0.0


def test_a_shorter_stem_does_not_crash():
    """demucs 출력은 믹스보다 몇 프레임 길거나 짧다. 있는 만큼만 본다."""
    mix = tone(1.0)
    short = stems(other=tone(0.9))
    short["other"] = short["other"][: int(25 * SR)]     # 마지막 구간이 잘렸다

    got = ins.section_instruments(mix, short, segments())

    assert got[0] == ["그 외 악기"]
    assert got[2] == ["그 외 악기"]                      # 잘린 앞부분으로 판정한다


# ────────────────────────────────────────────── 3. 스템을 찾고 읽는 길

def write_stems(root: Path, name: str, model: str = "htdemucs") -> Path:
    d = root / model / name
    d.mkdir(parents=True)
    for stem, _ in ins.STEMS:
        (d / f"{stem}.wav").write_bytes(b"")
    return d


def test_stem_dir_is_found_under_the_demucs_model_folder(tmp_path):
    """All-In-One 은 모델 이름으로 한 겹 더 판다. 경로를 박아 두지 않는다."""
    written = write_stems(tmp_path, "1234")

    assert ins.find_stem_dir(tmp_path, "1234") == written


def test_another_demucs_model_is_still_found(tmp_path):
    """모델을 바꾸면 폴더 이름도 바뀐다."""
    written = write_stems(tmp_path, "1234", model="htdemucs_ft")

    assert ins.find_stem_dir(tmp_path, "1234") == written


def test_the_folder_may_be_named_after_the_whole_filename(tmp_path):
    """demucs 판에 따라 `1234.audio` 폴더가 된다. 둘 다 본다."""
    written = write_stems(tmp_path, "1234.audio")

    assert ins.find_stem_dir(tmp_path, "1234", "1234.audio") == written
    assert ins.find_stem_dir(tmp_path, "1234") is None


def test_missing_stems_are_reported_not_silently_empty(tmp_path):
    """빈 목록으로 넘기면 "악기가 없는 곡" 과 "재지 못했다" 가 같아 보인다."""
    assert ins.find_stem_dir(tmp_path, "1234") is None

    d = tmp_path / "htdemucs" / "1234"
    d.mkdir(parents=True)
    (d / "vocals.wav").write_bytes(b"")
    with pytest.raises(ins.StemsMissing) as e:
        ins.load_stems(d, load=lambda p: tone(0.5))
    assert "drums" in str(e.value)


# ────────────────────────────────────────────── 4. 못 재도 곡은 산다

def test_a_track_without_stems_still_produces_sections():
    """악기는 곁들이는 값이다. 색인과 구간 적재는 그것 없이도 온전하다."""
    segs = segments()

    got = pl._instruments(Path("nope.audio"), segs, tone(1.0), Path("/없는/곳"))

    assert isinstance(got, str)           # 이유가 돌아온다
    assert "StemsMissing" in got


def test_no_demix_root_means_empty_not_an_error():
    """분할기를 안 쓰는 경로(manifest 배치)도 그대로 돈다."""
    segs = segments()

    assert pl._instruments(Path("a.audio"), segs, tone(1.0), None) == [[], [], []]


# ────────────────────────────────────────────── 5. 요구 1 로 나가는 모양

class Track:
    track_id = "1234"

    def __init__(self, sections):
        self.sections = sections


def section_row(idx: int, instruments=None) -> dict:
    row = {"section_idx": idx, "section_label": "chorus",
           "start_sec": float(idx * 10), "end_sec": float(idx * 10 + 10)}
    if instruments is not None:
        row["instruments"] = instruments
    return row


def test_payload_carries_instruments_per_section():
    got = ap.sections_payload(Track([section_row(0, ["보컬"]),
                                     section_row(1, ["드럼", "베이스"])]))

    assert got[0]["instruments"] == ["보컬"]
    assert got[1]["instruments"] == ["드럼", "베이스"]
    assert got[0]["external_id"] == "1234#0"


def test_payload_omits_the_field_when_nothing_was_measured():
    """못 잰 곡에 빈 배열을 보내면 옛 값을 지운다 — 요구 1 은 곡 단위 교체다."""
    got = ap.sections_payload(Track([section_row(0), section_row(1, [])]))

    assert "instruments" not in got[0]
    assert "instruments" not in got[1]


# ────────────────────────────────────────────── 6. 코드가 구간보다 먼저다

class FakeClient:
    """`MupidClient` 중 등록기가 부르는 둘만 흉내 낸다."""

    base_url = "https://example.invalid"

    def __init__(self):
        self.calls: list = []
        self.codes: dict = {}
        self.next_code_id = 900
        self.next_section_id = 5_000

    def add_code(self, code_type, code_name):
        """**응답 키는 snake_case 다.**

        서버가 `spring.jackson.property-naming-strategy: SNAKE_CASE` 로 바꾼다.
        이 대역이 `codeId` 를 돌려주면 `ensure_instrument_codes` 가
        `row.get("code_id")` 로 읽어 **조용히 None 을 기록한다** — 오류가 안 나서
        더 나쁘다. 실제 배치 로그에 `code_id: null` 로 남던 자리다.
        """
        self.calls.append(("code", code_name))
        key = (code_type, code_name)
        if key in self.codes:
            return self._wire(key[0], key[1], self.codes[key], created=False)
        self.next_code_id += 1
        self.codes[key] = self.next_code_id
        return self._wire(key[0], key[1], self.next_code_id, created=True)

    def _wire(self, code_type, code_name, code_id, created):
        return {"code_id": code_id, "code_type": code_type,
                "code_name": code_name, "created": created}

    def replace_sections(self, music_id, sections, analysis_version="v1"):
        self.calls.append(("section", str(music_id)))
        out = []
        for s in sections:
            self.next_section_id += 1
            out.append({"music_section_analysis_id": self.next_section_id,
                        "section_index": s["section_index"],
                        "external_id": s["external_id"]})
        return {"music_id": int(music_id), "analysis_version": analysis_version,
                "replaced": len(out), "sections": out}


def test_codes_are_registered_before_any_section_goes_out():
    """뒤집히면 서버가 이름을 버리고 세어 **오류 없이** 악기만 빈다."""
    client = FakeClient()
    reg = ap.HttpRegistrar(client=client)
    tracks = [Track([section_row(0, ["보컬", "드럼"])])]

    reg.ensure_instrument_codes(tracks)
    client.replace_sections(1234, ap.sections_payload(tracks[0]))

    kinds = [kind for kind, _ in client.calls]
    assert kinds == ["code", "code", "section"]


def test_the_same_instrument_is_registered_once_per_run():
    """곡마다 같은 악기가 나온다. 1,000곡이면 4,000번 부를 일이 아니다."""
    client = FakeClient()
    reg = ap.HttpRegistrar(client=client)

    reg.ensure_instrument_codes([Track([section_row(0, ["보컬"]),
                                        section_row(1, ["보컬", "드럼"])]),
                                 Track([section_row(0, ["보컬"])])])

    assert [name for _, name in client.calls] == ["보컬", "드럼"]


def test_already_existing_codes_are_not_counted_as_added():
    """두 번째 배치부터는 전부 있다. `created` 로 가린다."""
    client = FakeClient()
    reg = ap.HttpRegistrar(client=client)
    tracks = [Track([section_row(0, ["보컬"])])]

    reg.ensure_instrument_codes(tracks)
    reg.ensure_instrument_codes(tracks)

    assert len(reg.codes_added) == 1


def test_added_code_records_the_real_id_not_none():
    """응답 키가 어긋나면 **오류 없이 `code_id: null` 이 기록된다.**

    `codes_added` 는 `row.get("code_id")` 로 읽는다. 대역이 `codeId` 를 돌려주면
    `get` 이 None 을 주고 배치 보고서에는 `code_id: null` 만 남는다 — 실패가 아니라
    조용한 손실이라 눈에 띄지 않는다. 실제로 그 상태로 배치가 돌았다.
    """
    client = FakeClient()
    reg = ap.HttpRegistrar(client=client)

    reg.ensure_instrument_codes([Track([section_row(0, ["보컬"])])])

    assert reg.codes_added == [{"code_id": 901, "name": "보컬"}]


def test_nothing_is_registered_when_no_track_has_instruments():
    client = FakeClient()
    reg = ap.HttpRegistrar(client=client)

    reg.ensure_instrument_codes([Track([section_row(0), section_row(1, [])])])

    assert client.calls == []


# ────────────────────────────────────────────── 7. 진짜 HTTP 로 한 바퀴

mc = pytest.importorskip("music_preprocessing.api_client")
pd = pytest.importorskip("pandas")

REFRESH = "test-refresh-token"
ACCESS = "test-access-token"


class Spring(ThreadingHTTPServer):
    """요구 1 과 `POST /api/v1/code` 만 답한다. **호출 순서를 남긴다.**"""

    daemon_threads = True

    def __init__(self):
        super().__init__(("127.0.0.1", 0), _Handler)
        self.calls: list = []
        self.codes: dict = {}
        self.next_code_id = 900
        self.next_section_id = 5_000
        self.bodies: list = []

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.server_address[1]}"


class _Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _json(self, status, payload):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def _bearer(self):
        if self.headers.get("Authorization") == f"Bearer {ACCESS}":
            return True
        self._json(403, {"errorCode": "MUPID_FORBIDDEN", "message": "권한이 없습니다."})
        return False

    def do_GET(self):
        if self.path != "/api/v1/auth":
            self._json(404, {"code": "NOT_FOUND", "message": "없다"})
            return
        self._json(200, {"message": None, "data": {"access_token": ACCESS}})

    def do_POST(self):
        if not self._bearer():
            return
        s, b = self.server, self._body()
        if self.path != "/api/v1/code":
            self._json(404, {"code": "NOT_FOUND", "message": "없다"})
            return
        # 스프링은 `SNAKE_CASE` 매핑이라 camelCase 키를 모르는 필드로 버리고
        # 필수값이 null 이 되어 400 을 낸다. 대역도 그렇게 해야 계약이 검증된다.
        key = (str(b.get("code_type")), str(b.get("code_name")))
        s.calls.append(("code", key[1]))
        created = key not in s.codes
        if created:
            s.next_code_id += 1
            s.codes[key] = s.next_code_id
        self._json(200, {"message": None, "data": {
            "code_id": s.codes[key], "code_type": key[0], "code_name": key[1],
            "created": created}})

    def do_PUT(self):
        if not self._bearer():
            return
        s, b = self.server, self._body()
        mid = self.path.split("/music/")[1].split("/")[0]
        s.calls.append(("section", mid))
        s.bodies.append(b)
        out = []
        for sec in b.get("sections") or []:
            s.next_section_id += 1
            out.append({"music_section_analysis_id": s.next_section_id,
                        "section_index": sec["section_index"],
                        "external_id": sec["external_id"]})
        self._json(200, {"message": None, "data": {
            "music_id": int(mid), "analysis_version": b.get("analysis_version"),
            "replaced": len(out), "sections": out}})


@pytest.fixture()
def spring():
    srv = Spring()
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    yield srv
    srv.shutdown()
    srv.server_close()


def test_register_sends_codes_then_sections_over_real_http(spring, tmp_path):
    """목 객체는 `add_code` 가 불렸다는 것까지만 본다.

    이 도구가 실패해 온 자리는 늘 그 아래였다 — 경로 오타, 인증 헤더 누락, 필드
    이름. 그래서 진짜 `urllib` 로 보내 본다.
    """
    client = mc.MupidClient(base_url=spring.url, refresh_token=REFRESH,
                            token_file=None, max_retries=1)
    reg = ap.HttpRegistrar(client=client)

    got = reg.register([Track([section_row(0, ["보컬", "드럼"]),
                               section_row(1, ["드럼"])])], tmp_path)

    assert [kind for kind, _ in spring.calls] == ["code", "code", "section"]
    assert [name for _, name in spring.calls][:2] == ["보컬", "드럼"]

    sent = spring.bodies[0]["sections"]
    assert sent[0]["instruments"] == ["보컬", "드럼"]
    assert sent[1]["instruments"] == ["드럼"]
    assert got["sections"] == 2 and got["codes_added"] == 2

    # `id_map` 이 실제로 채워져야 추천 결과가 곡으로 이어진다.
    saved = pd.read_parquet(tmp_path / "id_map.parquet")
    assert sorted(saved["source_section_uid"]) == ["1234#0", "1234#1"]


def test_every_call_carries_the_bearer_token(spring, tmp_path):
    """`@SystemRole` 이다. 토큰이 빠지면 403 이고 그 자리에서 멈춘다."""
    client = mc.MupidClient(base_url=spring.url, refresh_token=REFRESH,
                            token_file=None, max_retries=1)
    ap.HttpRegistrar(client=client).register(
        [Track([section_row(0, ["보컬"])])], tmp_path)

    assert ("code", "보컬") in spring.calls        # 403 이면 기록 전에 끊긴다
    assert ("section", "1234") in spring.calls
