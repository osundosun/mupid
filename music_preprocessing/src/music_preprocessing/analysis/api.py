"""운영 입력·출력 — Spring 내부 API 클라이언트.

    요구 1   PUT   /api/v1/music/{id}/section              구간 적재 + 숫자 ID 회수
    요구 5   GET   /internal/music/pending-analysis        무엇을 분석할까
    요구 6   PATCH /internal/music/{id}/analysis-status    PROCESSING · FAILED

계약은 `api/docs/AI/` 에 있다. **요구 1 은 2026-09-16 에 구현됐고, 나머지 둘은 아직
없다.** 서버가 없으면 배치는 **교체를 보류**하고 끝난다. 가짜로 성공 처리하지 않는다.

## 인증이 둘이다

요구 1 은 확정된 계약대로 **일반 경로 + `Authorization: Bearer`** 다. 토큰은 저장소의
`MupidClient` 가 리프레시 토큰으로 그때그때 받아 쓰고 회전까지 맡는다 — 여기서 다시
만들지 않는다. 요구 5·6 은 아직 제안 단계라 `/internal/*` + `X-Internal-Token` 으로
적어 두었다. **BE 확정이 나면 이쪽도 요구 1 과 같은 형태가 될 가능성이 높다.**

## 의존성을 늘리지 않는다

요구 5·6 은 `urllib` 만 쓴다. 저장소의 `api_client.py` 와 같은 방침이다.

## 곡 ID

`music_id` 를 문자열로 바꿔 분석 `track_id` 로 쓰고, 구간은 `<music_id>#<index>` 다.
그래서 색인에는 `JAMENDO_1083659` 같은 기존 ID 와 `1001` 같은 새 ID 가 **섞인다.**
문제는 없다 — 색인은 문자열 키로만 다루고, 숫자 ID 로의 환산은 `id_map` 한 곳에서만
일어난다. 다만 나중에 ID 만 보고 출처를 짐작하지 못한다는 것은 알아 두어야 한다.

## 비밀

내부 토큰과 다운로드 URL 은 **로그에 남기지 않는다.** 다운로드 URL 은 단기 서명이
붙어 있어 그 자체가 자격증명이다.
"""
from __future__ import annotations

import hashlib
import json
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional, Sequence

from . import content as ct
from . import embedder as emb
from .batch import BatchTrack

TIMEOUT = 60
DOWNLOAD_TIMEOUT = 300
CHUNK = 1 << 20

# 다시 불러 볼 만한 것만. 400·401·404·409 는 다시 불러도 같은 답이다.
RETRY_STATUS = (500, 503)
RETRIES = 1

# ClickHouse·MySQL 로 갈 때의 어휘. `moments.section_label` 은 소문자다.
SECTION_ENUM = ("INTRO", "VERSE", "PRE_CHORUS", "CHORUS", "BRIDGE",
                "BREAK", "INST", "SOLO", "OUTRO")


class ApiError(RuntimeError):
    """2xx 가 아닌 응답. `code` 로 다시 시도할지 판단한다."""

    def __init__(self, status: int, code: str = "", message: str = ""):
        super().__init__(f"[{status}] {code or '-'}: {message}")
        self.status, self.code, self.message = status, code, message

    @property
    def retryable(self) -> bool:
        return self.status in RETRY_STATUS


@dataclass
class InternalApi:
    """내부 토큰 하나로 부르는 세 API."""

    base_url: str
    token: str
    timeout: int = TIMEOUT
    retries: int = RETRIES
    analysis_version: str = "v1"

    def _url(self, path: str, params: Optional[dict] = None) -> str:
        url = self.base_url.rstrip("/") + path
        if params:
            q = {k: v for k, v in params.items() if v not in (None, "")}
            if q:
                url += "?" + urllib.parse.urlencode(q)
        return url

    def call(self, method: str, path: str, *, params: Optional[dict] = None,
             body: Optional[dict] = None) -> dict:
        """한 번 더 시도하는 것은 `500`·`503`·timeout 뿐이다."""
        last: Optional[Exception] = None
        for attempt in range(self.retries + 1):
            try:
                return self._once(method, path, params, body)
            except ApiError as e:
                if not e.retryable or attempt >= self.retries:
                    raise
                last = e
            except TimeoutError as e:
                if attempt >= self.retries:
                    raise
                last = e
        raise last                                   # pragma: no cover - 위에서 끝난다

    def _once(self, method: str, path: str, params, body) -> dict:
        data = json.dumps(body, ensure_ascii=False).encode() if body is not None else None
        req = urllib.request.Request(self._url(path, params), data=data, method=method)
        req.add_header("X-Internal-Token", self.token)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                raw = r.read().decode("utf-8")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            code, msg = "", raw[:300]
            try:
                d = json.loads(raw)
                code = str(d.get("code") or "")
                msg = str(d.get("message") or msg)
            except Exception:
                pass
            raise ApiError(e.code, code, msg) from None
        except urllib.error.URLError as e:
            raise TimeoutError(f"{self.base_url} 에 닿지 못했다: {e.reason}") from None
        d = json.loads(raw) if raw.strip() else {}
        return d.get("data") if isinstance(d, dict) and "data" in d else d

    # ------------------------------------------------------------- 요구 5

    def pending(self, limit: int = 200, max_pages: int = 100) -> tuple[str, list[dict]]:
        """대상 곡을 **전부** 가져온다. `next_cursor` 가 없을 때까지 돈다."""
        out: list[dict] = []
        cursor, version = None, self.analysis_version
        for _ in range(max_pages):
            d = self.call("GET", "/api/v1/internal/music/pending-analysis",
                          params={"limit": limit, "cursor": cursor})
            version = str(d.get("analysis_version") or version)
            out.extend(d.get("tracks") or [])
            cursor = d.get("next_cursor")
            if not cursor:
                break
        self.analysis_version = version
        return version, out

    # ------------------------------------------------------------- 요구 6

    def set_status(self, music_id, status: str, audio_sha256: str, *,
                   error_code: str = "", error_message: str = "") -> dict:
        """`PROCESSING` · `FAILED` 만 보낸다.

        **`DONE` 은 보내지 않는다.** 요구 1 이 구간 적재와 `DONE` 전이를 한 트랜잭션으로
        처리한다 — 여기서 `DONE` 을 보내면 구간 없이 완료로 표시될 수 있다.
        """
        if status not in ("PROCESSING", "FAILED"):
            raise ValueError(f"이 API 는 PROCESSING·FAILED 만 받는다: {status}")
        body = {"analysis_version": self.analysis_version,
                "audio_sha256": audio_sha256, "status": status,
                "error_code": error_code or None,
                "error_message": (error_message or None)}
        return self.call("PATCH",
                         f"/api/v1/internal/music/{music_id}/analysis-status", body=body)

    # ------------------------------------------------------------- 음원

    def download(self, url: str, dst: Path, sha256: str) -> str:
        """내려받고 **해시를 검증한다.** 어긋나면 파일을 지우고 실패다.

        URL 은 단기 서명이 붙어 있어 그 자체가 자격증명이다 — 예외 메시지에도 싣지 않는다.
        """
        dst = Path(dst)
        dst.parent.mkdir(parents=True, exist_ok=True)
        h = hashlib.sha256()
        req = urllib.request.Request(url)
        try:
            with urllib.request.urlopen(req, timeout=DOWNLOAD_TIMEOUT) as r, \
                    dst.open("wb") as f:
                while True:
                    b = r.read(CHUNK)
                    if not b:
                        break
                    h.update(b)
                    f.write(b)
        except urllib.error.HTTPError as e:
            dst.unlink(missing_ok=True)
            raise ApiError(e.code, "", "음원을 내려받지 못했다") from None
        except urllib.error.URLError as e:
            dst.unlink(missing_ok=True)
            raise TimeoutError(f"음원 서버에 닿지 못했다: {e.reason}") from None
        got = h.hexdigest()
        if sha256 and got != str(sha256):
            dst.unlink(missing_ok=True)
            raise ValueError(f"음원 해시가 다르다 — 받은 {got[:16]} · 기대 {str(sha256)[:16]}")
        return got


# ────────────────────────────────────────────────────── 요구 5 → BatchTrack

def prepare(track: dict, api: InternalApi, work_dir: Path, *,
            sections_json: Optional[Path] = None,
            demix_root: Optional[Path] = None,
            load=None, measure=None) -> BatchTrack:
    """곡 하나를 내려받아 배치 입력으로 만든다.

    **트랙 5값은 여기서 직접 잰다.** 요구 5 가 `centroid`·`rolloff`·`rms_mean`·
    `flatness` 를 함께 주지만, 같은 정의로 쟀다는 보장이 없으면 신곡만 다른 분포에
    놓인다. 재는 비용은 곡당 1초 미만이고, 어긋났을 때의 값은 조용히 나빠진 추천이다
    (`analysis/content.py`).
    """
    mid = str(track["music_id"])
    work = Path(work_dir)
    audio = work / f"{mid}.audio"
    api.download(str(track["audio_download_url"]), audio, str(track.get("audio_sha256") or ""))

    # `load`·`measure` 는 시험에서 librosa 를 빼기 위한 자리다. 비우면 진짜를 쓴다 —
    # librosa 는 analysis 선택 의존성이라 CI 에는 없다.
    row = (measure or ct.track_content)((load or emb.load_audio)(audio))
    row["track_id"] = mid

    sj = Path(sections_json) if sections_json else work / f"{mid}.sections.json"
    return BatchTrack(track_id=mid, audio_path=audio, sections_json=sj,
                      content_row=row, meta_row=meta_row(track, mid), source="flow",
                      demix_root=Path(demix_root) if demix_root else None,
                      # 요구 5 가 준 **그 숫자**를 등록까지 들고 간다. 빠뜨리면
                      # `HttpRegistrar` 가 "music_id 를 모르는 곡" 으로 보고 배치가
                      # 통째로 보류된다 — 요구 5 경로가 끝까지 간 적이 없던 이유다.
                      # `track_id` 로 대신하지 않는다: 저쪽은 문자열이고 요구 1 은
                      # 숫자를 요구한다. 여기서 형을 맞춰 두면 뒤에서 짐작하지 않는다.
                      music_id=int(track["music_id"]),
                      temp_audio=True)      # 내려받은 사본이다. 처리 뒤 배치가 지운다


def meta_row(track: dict, music_id: str) -> dict:
    """`flow_meta.parquet` 한 줄 — 조건 검색이 읽는 것들.

    `bpm_lo`·`bpm_hi` 에는 **요청 BPM** 을 넣는다(요구 5 문서). 측정값이 아니다 —
    필터는 "이 곡이 어떤 템포로 만들어졌나" 를 묻는 자리다. 285차의 `trk_bpm` 이
    측정값인 것과 서로 어긋나지 않는다. 자리가 다르다.

    **태그는 배열이 아니라 쉼표로 이은 문자열이다.** 저장본의 `flow_meta.mood_codes`·
    `instrument_tags` 는 `large_string` 이고 추천기가 `_tag_list()` 로 쉼표 분리해
    읽는다. 배열을 그대로 넣으면 parquet 쓰기가 타입 오류로 멈춘다 — 실제로 여기서
    한 번 막혔다.
    """
    bpm = track.get("bpm_requested")
    lo = hi = float(bpm) if bpm not in (None, "") else float("nan")
    return {"track_id": music_id,
            "title": str(track.get("title") or music_id),
            "category": str(track.get("category") or "unknown"),
            "vocal_type": str(track.get("vocal_type") or "unknown"),
            "language": str(track.get("language") or "unknown"),
            "bpm_lo": lo, "bpm_hi": hi,
            "sound_raw": str(track.get("sound_raw") or "")[:600],
            # 장르·무드는 **DB 코드표의 `code_name`** 이다 (2026-09-21 어휘 전환).
            # 예전 `genre_top1`(`Rock---Punk`)·`mood_tags`(`relaxing`)는 카탈로그
            # 자체 어휘였고, 코드표와 이어지지 않아 세분류 장르 45종이 통째로
            # 빠졌다. `lyrics_types` 는 읽는 곳이 한 군데도 없어 함께 뺐다.
            "genre_code": str(track.get("genre_code")
                              or track.get("genre") or ""),
            "mood_codes": _tags(track.get("mood_codes") or track.get("moods")),
            "instrument_tags": _tags(track.get("instrument_tags")),
            "voice_label": str(track.get("voice_label") or ""),
            "gender_label": str(track.get("gender_label") or "")}


def _tags(v) -> str:
    """배열이든 문자열이든 **쉼표로 이은 문자열**로 맞춘다 (`recommender._tag_list`)."""
    if v in (None, ""):
        return ""
    if isinstance(v, (list, tuple)):
        return ", ".join(str(x).strip() for x in v if str(x).strip())
    return str(v)


# ────────────────────────────────────────────────────── 요구 6 상태 알림

@dataclass
class StatusReporter:
    """분석 시작·실패를 Spring 에 남긴다.

    상태가 없으면 같은 곡을 두 배치가 동시에 처리하고, 실패 이유가 다음 실행에 남지
    않는다. **성공은 보내지 않는다** — 요구 1 의 적재 성공이 그 자리를 맡는다.
    """

    api: InternalApi
    sha: dict = field(default_factory=dict)      # track_id -> audio_sha256
    calls: list = field(default_factory=list)
    errors: list = field(default_factory=list)

    def started(self, track_id: str) -> None:
        self._send(track_id, "PROCESSING")

    def failed(self, track_id: str, code: str, message: str) -> None:
        self._send(track_id, "FAILED", code=code or "ANALYSIS_FAILED",
                   message=(message or "")[:500])

    def _send(self, track_id: str, status: str, code: str = "", message: str = "") -> None:
        try:
            self.api.set_status(track_id, status, self.sha.get(str(track_id), ""),
                                error_code=code, error_message=message)
            self.calls.append((str(track_id), status))
        except Exception as e:
            # 상태를 못 남겼다고 분석을 멈추지 않는다. 남기지 못했다는 사실만 기록한다.
            self.errors.append({"track_id": str(track_id), "status": status,
                                "error": f"{type(e).__name__}: {e}"[:300]})


# ────────────────────────────────────────────────────── 요구 1 등록기

@dataclass
class HttpRegistrar:
    """`batch.Registrar` 구현 — 구간을 적재하고 숫자 ID 를 받아 `id_map` 을 채운다.

    받은 `music_section_analysis_id` 를 **staging 판 안의** `id_map.parquet` 에 쓴다.
    새 판에는 교체 전 시점의 낡은 매핑표가 복사돼 있어서, 여기서 갱신하지 않으면
    확인 단계가 반드시 막힌다 — 그리고 그게 맞다. 한 곡이라도 미매핑이면 교체하지 않는다.

    호출은 `MupidClient.replace_sections` 가 한다. 확정된 계약은 곡 단위 통째 교체라
    중복 방지 인자가 없다 — 같은 음원을 다시 분석하지 않는 일은 배치가 SHA-256 으로
    앞단에서 끝낸다.
    """

    client: object                               # MupidClient
    analysis_version: str = "v1"
    registered: list = field(default_factory=list)
    errors: list = field(default_factory=list)
    codes_added: list = field(default_factory=list)
    # 보컬 유무를 몰라 분석 값을 넣지 못한 곡(생성 요청도 분류기 판정도 없다).
    analysis_skipped: list = field(default_factory=list)

    def available(self) -> tuple[bool, str]:
        if self.client is None:
            return False, "API 클라이언트가 없다 (요구 1 미설정)"
        if not getattr(self.client, "base_url", ""):
            return False, "API 주소가 없다 (api_base_url)"
        return True, ""

    def register(self, tracks: Sequence, new_dir: Path) -> dict:
        self.ensure_instrument_codes(tracks)
        rows = []
        missing = []
        for t in tracks:
            mid = str(t.track_id)
            # 요구 1 은 **숫자 music_id** 를 요구한다. `track_id` 는 로컬 파일명
            # (`T000501`)일 수 있으므로 `registered.json` 에서 받은 값을 쓴다.
            api_id = getattr(t, "music_id", None)
            if api_id is None:
                missing.append(mid)
                continue
            payload = sections_payload(t)
            d = self.client.replace_sections(int(api_id), payload, self.analysis_version)
            got = d.get("sections") or []
            for s in got:
                rows.append({"source_track_id": mid,
                             "music_id": int(d.get("music_id") or api_id),
                             "external_id": _uid_to_moment(str(s["external_id"])),
                             "music_section_analysis_id":
                                 int(s["music_section_analysis_id"])})
            # 곡 분석 값(보컬·BPM·언어·스타일). 구간과 같은 곡 단위 교체다.
            body = analysis_payload(getattr(t, "meta_row", {}) or {},
                                    getattr(t, "content_row", {}) or {})
            if body is not None:
                self.client.replace_analysis(int(api_id), body)
            else:
                self.analysis_skipped.append(mid)
            self.registered.append({"track_id": mid,
                                    "sections": int(d.get("replaced") or len(got)),
                                    "analysis": body is not None})
        if missing:
            # 숫자 ID 를 모르는 곡이 있으면 **여기서 멈춘다.** 지어낸 ID 로 등록하면
            # 엉뚱한 곡에 구간이 붙고, 그 상태로 교체되면 되돌리기 어렵다.
            raise ValueError(
                f"music_id 를 모르는 곡이 {len(missing)}개다 (registered.json 에 없다) — "
                f"예: {missing[:5]}")
        n = merge_id_map(Path(new_dir), rows)
        return {"tracks": len(self.registered), "sections": len(rows),
                "id_map_rows": n, "codes_added": len(self.codes_added),
                "analysis_skipped": len(self.analysis_skipped)}

    def ensure_instrument_codes(self, tracks: Sequence) -> None:
        """구간을 올리기 **전에** 악기 코드를 만들어 둔다.

        `CodeType.INSTRUMENT` 는 부트스트랩이 심지 않는다 — 확정된 목록이 없어서다.
        그리고 서버는 코드표에 없는 악기 이름을 **버리고 센다** (구간은 살린다).
        그래서 순서가 뒤집히면 오류 없이 악기만 조용히 비어 보인다.

        `POST /api/v1/code` 는 멱등이라 매 배치가 다시 불러도 값이 같다.
        """
        from .instruments import CODE_NAMES

        wanted = []
        for t in tracks:
            for s in getattr(t, "sections", []):
                for name in (s.get("instruments") or []):
                    if name not in wanted:
                        wanted.append(name)
        # **확정 목록 밖의 이름은 만들지 않는다.** 이 API 는 없는 이름을 받으면 새
        # 코드를 만들어 버리고, 한번 생긴 코드는 사람이 지워야 한다 — 분위기에서
        # 실제로 두 번 늘었다(98~106, 107~123). 정본은 `instruments.STEMS` 하나다.
        unknown = sorted(set(wanted) - set(CODE_NAMES))
        if unknown:
            raise ValueError(
                f"확정 목록에 없는 악기 이름이다: {unknown} (정본: {list(CODE_NAMES)}) "
                "— 코드를 새로 만들지 않고 등록을 멈춘다")
        for name in wanted:
            row = self.client.add_code("INSTRUMENT", name)
            if row.get("created"):
                self.codes_added.append({"code_id": row.get("code_id"),
                                         "name": row.get("code_name")})


# 생성 요청의 보컬 종류 → (has_vocal, vocal_gender). **이것이 정답이다.**
#
# 보컬 분류기(Essentia)는 확률 0.60 미만이면 `unknown` 을 낸다(`t01_pipeline.flat_class`).
# 그 135곡은 전부 연주곡으로 요청한 곡이었고 들어 보니 연주곡이었다(2026-09-23). 생성
# 음원은 무엇을 만들라고 했는지가 남아 있으므로 그것을 먼저 쓰고, 요청이 없을 때만
# 분류기를 쓴다.
VOCAL_BY_REQUEST = {"instrumental": (False, None), "male": (True, "M"),
                    "female": (True, "F"), "mixed": (True, "MIX")}
GENDER_BY_LABEL = {"female": "F", "male": "M", "mixed": "MIX", "mix": "MIX"}
_UNKNOWN = {"", "unknown", "nan", "none", "null"}


def _known(v) -> Optional[str]:
    text = "" if v is None else str(v).strip()
    return None if text.lower() in _UNKNOWN else text


def _num(v) -> Optional[float]:
    try:
        x = float(v)
    except (TypeError, ValueError):
        return None
    return x if x == x and x > 0 else None       # NaN·0 은 "모른다"


def vocal_of(meta: dict) -> tuple[Optional[bool], Optional[str], str]:
    """`(has_vocal, vocal_gender, 근거)`. 판정할 수 없으면 `has_vocal` 이 `None`."""
    requested = str(meta.get("vocal_type") or "").strip().lower()
    if requested in VOCAL_BY_REQUEST:
        has, gender = VOCAL_BY_REQUEST[requested]
        return has, gender, "requested"
    voice = str(meta.get("voice_label") or "").strip().lower()
    if voice == "instrumental":
        return False, None, "classifier"
    if voice == "voice":
        gender = GENDER_BY_LABEL.get(str(meta.get("gender_label") or "").strip().lower())
        return True, gender, "classifier"
    return None, None, "unknown"


def analysis_payload(meta: dict, content: dict) -> Optional[dict]:
    """곡 한 줄 → `PUT /music/{id}/analysis` 본문. 보컬 유무를 모르면 `None`.

    값의 출처는 색인의 `track_features` 와 같다 — BPM 은 `content.bpm`, 언어·스타일·
    요청 BPM 범위는 `flow_meta` 다(`ai_server/tools/slice_index.py`). 다른 열(태깅
    산출물의 `bpm_allin1` 등)을 쓰면 추천이 지금과 다른 BPM 으로 돈다 — 실측으로 1,061곡
    중 409곡이 달랐다.

    교체라 **모든 필드를 한 번에** 보낸다. `has_vocal` 은 ERD 가 `NOT NULL` 이라, 그것을
    모르면 행을 만들지 않는다(요청이 있는 flow 곡에서는 생기지 않는다).
    """
    has, gender, _src = vocal_of(meta)
    if has is None:
        return None
    lo, hi = _num(meta.get("bpm_lo")), _num(meta.get("bpm_hi"))
    if lo is None or hi is None:
        lo = hi = None
    return {"has_vocal": has, "vocal_gender": gender,
            "bpm": round(_num(content.get("bpm")), 3) if _num(content.get("bpm")) else None,
            "bpm_requested_min": int(round(lo)) if lo is not None else None,
            "bpm_requested_max": int(round(hi)) if hi is not None else None,
            "language": _known(meta.get("language")),
            "category": _known(meta.get("category"))}


def sections_payload(new_track) -> list[dict]:
    """`NewTrack` → 요구 1 본문. **`external_id` 는 `<music_id>#<index>`** 다."""
    mid = str(new_track.track_id)
    out = []
    for s in new_track.sections:
        label = str(s["section_label"]).upper()
        if label not in SECTION_ENUM:
            raise ValueError(f"{mid}: ERD ENUM 에 없는 라벨 {label}")
        a, b = int(round(float(s["start_sec"]) * 1000)), int(round(float(s["end_sec"]) * 1000))
        if b <= a:
            raise ValueError(f"{mid}: 경계가 뒤집혔다 {a} >= {b}")
        line = {"section_index": int(s["section_idx"]),
                "external_id": f"{mid}#{int(s['section_idx'])}",
                "section_label": label, "start_time_ms": a, "end_time_ms": b}
        # 악기는 **코드 이름**으로 보낸다 — 번호는 서버가 붙인다. 못 잰 곡은 비어
        # 있고, 그때 필드를 아예 빼 옛 값을 지키지 않는다: 요구 1 은 곡 단위 교체라
        # 빈 목록과 "안 보냄" 이 같다.
        names = [str(x) for x in (s.get("instruments") or [])]
        if names:
            line["instruments"] = names
        # 구간 에너지(RMS). 추천의 흐름 감점·"더 차분하게" 조건이 쓴다 — 예전에는
        # 색인에만 있었다(연동 계약 2026-09-23 절). 못 쟀으면 싣지 않는다(null).
        rms = _num(s.get("rms"))
        if rms is not None and rms <= 1.0:
            line["energy"] = round(rms, 6)
        out.append(line)
    return out


def _uid_to_moment(external_id: str) -> str:
    """`external_id` 와 `moment_id` 는 같은 모양이다 (`<track>#<idx>`)."""
    return external_id


def merge_id_map(new_dir: Path, rows: list[dict]) -> int:
    """새 판의 `id_map.parquet` 에 **덧붙인다.** 기존 매핑을 지우지 않는다.

    이전 산출물의 별칭은 읽을 때 실제 DB 컬럼명으로 한 번만 바꾼다.
    """
    import pandas as pd

    cols = ["source_track_id", "music_id", "external_id", "music_section_analysis_id"]
    p = Path(new_dir) / "id_map.parquet"
    base = pd.read_parquet(p) if p.exists() else pd.DataFrame(columns=cols)
    base = base.rename(columns={
        "source_section_uid": "external_id",
        "music_section_id": "music_section_analysis_id",
    })
    add = pd.DataFrame(rows, columns=cols)
    if len(add):
        out = pd.concat([base[cols], add], ignore_index=True)
        out = out.drop_duplicates("external_id", keep="last")
    else:
        out = base[cols]
    out.to_parquet(p, index=False)
    return len(out)


def segmenter_demix_dirname() -> str:
    from .segmenter import DEMIX_DIRNAME

    return DEMIX_DIRNAME


def collect(api: InternalApi, work_dir: Path, *, limit: int = 200,
            segmenter=None) -> tuple[list, dict]:
    """요구 5 → 내려받기 → 분할 → 배치 입력.

    곡 하나가 깨져도 나머지는 간다. 실패는 `{track_id: 이유}` 로 돌려준다 — 호출자가
    요구 6 `FAILED` 로 남긴다.

    `segmenter` 를 주면 `sections.json` 이 없는 곡을 **여기서 분할한다.** 비우면 이미
    만들어져 있다고 보고, 없으면 그 곡은 배치의 `sections` 단계에서 실패한다.
    """
    work = Path(work_dir)
    work.mkdir(parents=True, exist_ok=True)
    # 분할기가 demucs 스템을 남기는 곳. 구간별 악기를 거기서 읽는다
    # (`segmenter.ALLIN1_KEEP_BYPRODUCTS`). 분할기가 없으면 악기도 없다.
    demix = (Path(segmenter.work_dir) / segmenter_demix_dirname()
             if segmenter is not None else None)
    _, pending = api.pending(limit=limit)
    out, failed, sha = [], {}, {}
    for t in pending:
        mid = str(t.get("music_id"))
        try:
            bt_ = prepare(t, api, work, demix_root=demix)
            sha[mid] = str(t.get("audio_sha256") or "")
            if segmenter is not None and not bt_.sections_json.exists():
                got = segmenter.analyze([(mid, bt_.audio_path)])
                if mid not in got:
                    raise RuntimeError(
                        segmenter.failures.get(mid, {}).get("error", "분할 실패"))
                from .segmenter import dump_sections

                dump_sections(got[mid], bt_.sections_json)
            out.append(bt_)
        except Exception as e:
            failed[mid] = f"{type(e).__name__}: {e}"[:400]
    return out, {"failed": failed, "sha": sha}


__all__ = ["ApiError", "InternalApi", "StatusReporter", "HttpRegistrar",
           "prepare", "collect", "meta_row", "sections_payload", "merge_id_map",
           "analysis_payload", "vocal_of",
           "SECTION_ENUM"]
