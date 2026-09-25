"""Mupid API 클라이언트 — 배치가 곡·스트림·청크를 등록할 때 쓴다.

인증은 **리프레시 토큰 하나**로만 굴린다.

  1. 배치는 파일에 저장된 리프레시 토큰만 들고 있다.
  2. 액세스 토큰이 필요하면 `GET /api/v1/auth` 에 리프레시 토큰을 쿠키로 보내 받는다.
  3. 서버가 응답의 `Set-Cookie` 로 **새 리프레시 토큰**을 주므로 그때마다 파일을 갱신한다.
     (`AuthProvider.refresh` 가 매번 새로 발급한다 — 7일 만료를 계속 밀어낸다.)

의존성을 늘리지 않으려고 `urllib` 만 쓴다. multipart 인코딩도 직접 한다.
"""

from __future__ import annotations

import base64
import json
import logging
import mimetypes
import os
import time
import urllib.error
import urllib.request
import uuid
from http.cookies import SimpleCookie
from pathlib import Path
from urllib.parse import urlencode

logger = logging.getLogger(__name__)

#: 소프트 삭제된 자원의 상태코드. 서버가 404(없음)와 나눠 쓴다.
GONE_STATUS = 410

#: 소프트 삭제된 곡의 오류 코드. 상태코드가 프록시에 접혀도 이 값은 헤더로 온다.
GONE_CODE = "MUPID_MUSIC_GONE"

#: 곡은 있는데 파형만 아직 없을 때의 오류 코드. 곡이 없는 404 와 나눠 쓴다.
WAVEFORM_MISSING_CODE = "MUPID_WAVEFORM_NOT_FOUND"


def is_music_gone(error: "MupidApiError") -> bool:
    """서버에서 삭제된 곡이라는 응답인가.

    상태코드가 일차 기준이지만 코드로도 본다 — nginx `auth_request` 를 거치는
    배포에서는 4xx 가 500 으로 접히고 사유가 헤더에만 온다.
    """
    return error.status == GONE_STATUS or error.code == GONE_CODE

# 액세스 토큰이 이 시간 안에 만료되면 미리 갱신한다.
# 업로드 한 번이 오래 걸려도 도중에 만료되지 않도록 넉넉히 잡는다.
_TOKEN_REFRESH_MARGIN_SEC = 120


class MupidApiError(RuntimeError):
    """API 가 2xx 가 아닌 응답을 준 경우."""

    def __init__(self, status: int, code: str | None, message: str, detail=None):
        super().__init__(f"[{status}] {code or '-'}: {message}")
        self.status = status
        self.code = code
        self.message = message
        self.detail = detail


class MupidClient:
    """Mupid API 호출기.

    Args:
        base_url: API 루트 (예: ``http://localhost:9900``)
        refresh_token: 시작 시점의 리프레시 토큰
        token_file: 회전된 리프레시 토큰을 다시 저장할 경로 (None 이면 저장하지 않음)
        timeout: 요청 타임아웃 (초)
        max_retries: 5xx·네트워크 오류 재시도 횟수
    """

    def __init__(
        self,
        base_url: str,
        refresh_token: str,
        token_file: str | Path | None = None,
        timeout: int = 30,
        max_retries: int = 3,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.timeout = timeout
        self.max_retries = max(1, max_retries)
        self._refresh_token = refresh_token
        self._token_file = Path(token_file) if token_file else None
        self._access_token: str | None = None
        self._access_expires_at: float = 0.0

    # ─────────────────────────────────────────────────────────
    # 인증
    # ─────────────────────────────────────────────────────────

    def _ensure_access_token(self, force: bool = False) -> str:
        if (
            not force
            and self._access_token
            and time.time() < self._access_expires_at - _TOKEN_REFRESH_MARGIN_SEC
        ):
            return self._access_token

        body, headers = self._send(
            "GET",
            "/api/v1/auth",
            headers={"Cookie": f"refresh_token={self._refresh_token}"},
        )
        self._access_token = body["data"]["access_token"]
        self._access_expires_at = _jwt_expires_at(self._access_token)

        # 서버가 리프레시 토큰을 회전시킨다. 새 토큰을 놓치면 다음 배치가 못 붙는다.
        rotated = _read_set_cookie(headers, "refresh_token")
        if rotated and rotated != self._refresh_token:
            self._refresh_token = rotated
            self._save_refresh_token(rotated)

        return self._access_token

    def _save_refresh_token(self, token: str) -> None:
        if self._token_file is None:
            return
        # 쓰다가 죽어도 기존 토큰이 남도록 임시 파일에 쓰고 바꿔친다.
        self._token_file.parent.mkdir(parents=True, exist_ok=True)
        tmp = self._token_file.with_suffix(self._token_file.suffix + ".tmp")
        tmp.write_text(token, encoding="utf-8")
        os.replace(tmp, self._token_file)
        logger.debug("리프레시 토큰 갱신: %s", self._token_file)

    # ─────────────────────────────────────────────────────────
    # 요청
    # ─────────────────────────────────────────────────────────

    def _request(
        self,
        method: str,
        path: str,
        *,
        json_body: dict | None = None,
        multipart: tuple[bytes, str] | None = None,
        raw_body: tuple[bytes, str] | None = None,
    ) -> dict:
        """인증을 붙여 호출한다. 401 이면 토큰을 새로 받아 한 번 더 시도한다."""
        for attempt in range(2):
            token = self._ensure_access_token(force=attempt > 0)
            headers = {"Authorization": f"Bearer {token}"}
            data = None

            if json_body is not None:
                headers["Content-Type"] = "application/json"
                data = json.dumps(json_body, ensure_ascii=False).encode("utf-8")
            elif multipart is not None:
                data, content_type = multipart
                headers["Content-Type"] = content_type
            elif raw_body is not None:
                data, content_type = raw_body
                headers["Content-Type"] = content_type

            try:
                body, _ = self._send(method, path, headers=headers, data=data)
                return body
            except MupidApiError as e:
                # 액세스 토큰이 서버 재기동 등으로 무효해진 경우만 한 번 더 준다.
                if e.status == 401 and attempt == 0:
                    logger.debug("401 — 액세스 토큰 재발급 후 재시도")
                    continue
                raise
        raise AssertionError("unreachable")

    def _send(
        self,
        method: str,
        path: str,
        *,
        headers: dict | None = None,
        data: bytes | None = None,
    ) -> tuple[dict, dict]:
        """실제 HTTP 호출. (본문 dict, 응답 헤더) 를 돌려준다."""
        url = f"{self.base_url}{path}"
        last_error: Exception | None = None

        for attempt in range(self.max_retries):
            request = urllib.request.Request(
                url, data=data, method=method, headers=headers or {}
            )
            try:
                with urllib.request.urlopen(request, timeout=self.timeout) as res:
                    raw = res.read()
                    parsed = json.loads(raw) if raw else {}
                    return parsed, dict(res.headers.items())

            except urllib.error.HTTPError as e:
                raw = e.read()
                parsed = {}
                if raw:
                    try:
                        parsed = json.loads(raw)
                    except json.JSONDecodeError:
                        # nginx 가 대신 만든 HTML 오류 페이지다. 메시지로 쓰면 로그만 더럽힌다.
                        parsed = {}

                # 업로드 경로는 nginx auth_request 를 거치므로 본문이 사라지고
                # 4xx 가 500 으로 접힌다. 사유는 헤더로 온다.
                code = parsed.get("code") or e.headers.get("X-Mupid-Error")
                message = parsed.get("message") or e.reason

                # 4xx 는 다시 보내도 같은 답이 온다. 바로 올린다.
                # 사유 헤더가 붙은 500 도 같다 — 스프링이 거절한 것이지 장애가 아니다.
                if e.code < 500 or e.headers.get("X-Mupid-Error"):
                    raise MupidApiError(
                        e.code, code, message, parsed.get("error_details")
                    ) from None

                last_error = MupidApiError(e.code, code, message)

            except urllib.error.URLError as e:
                last_error = e

            if attempt < self.max_retries - 1:
                backoff = 2**attempt
                logger.warning(
                    "%s %s 실패 (%s) — %d초 후 재시도 (%d/%d)",
                    method,
                    path,
                    last_error,
                    backoff,
                    attempt + 1,
                    self.max_retries,
                )
                time.sleep(backoff)

        raise last_error if last_error else RuntimeError("요청 실패")

    # ─────────────────────────────────────────────────────────
    # 도메인 호출
    # ─────────────────────────────────────────────────────────

    def upload_image(self, category: str, storage_key: str, image_path: str | Path) -> int:
        """이미지를 올리고 file_id 를 돌려준다. category 는 ARTIST / ALBUM / PROFILE."""
        image_path = Path(image_path)
        mime = mimetypes.guess_type(image_path.name)[0] or "image/jpeg"
        multipart = _encode_multipart(
            files=[("imageFile", image_path.name, image_path.read_bytes(), mime)]
        )
        body = self._request(
            "POST",
            f"/api/v1/storage/image/{category}/{_quote(storage_key)}",
            multipart=multipart,
        )
        return body["data"]["file_id"]

    def create_artist(self, name: str, image_file_id: int) -> int:
        body = self._request(
            "POST",
            "/api/v1/artist",
            json_body={"name": name, "image_file_id": image_file_id},
        )
        return body["data"]["artist_id"]

    def create_album(
        self, artist_id: int, title: str, image_file_id: int, release_date: str
    ) -> int:
        body = self._request(
            "POST",
            "/api/v1/album",
            json_body={
                "artist_id": artist_id,
                "title": title,
                "image_file_id": image_file_id,
                "release_date": release_date,
            },
        )
        return body["data"]["album_id"]

    def add_music(
        self,
        album_id: int,
        artist_id: int,
        title: str,
        release_date: str,
        image_file_id: int | None = None,
    ) -> int:
        """곡을 등록한다.

        `image_file_id` 는 곡 커버(`TRACK` 카테고리)다. 앨범·아티스트 이미지와
        달리 **선택이다.** 비우면 화면이 앨범 커버를 대신 쓴다.
        """
        body = self._request(
            "POST",
            "/api/v1/music",
            json_body={
                "album_id": album_id,
                "artist_id": artist_id,
                "title": title,
                "release_date": release_date,
                "image_file_id": image_file_id,
            },
        )
        return body["data"]["music_id"]

    def get_music(self, music_id: int) -> dict | None:
        """곡 하나를 읽는다. **삭제된 곡이면 `None`**, 그런 번호가 없으면 예외다.

        `GET /api/v1/music/{id}` 는 소프트 삭제된 곡에 **410 Gone** 을 준다
        (2026-09-18 변경, 그전엔 404). 그래서 상태코드로 "삭제됨" 과 "등록 기록이
        낡음" 을 가를 수 있다 — 곡 목록(`GET /api/v1/music`)은 삭제분을 걸러 주므로
        그 목록만으로는 둘이 구분되지 않는다(`audit.py` 참고).

        곡마다 부르면 1060회라 비싸다. 그래서 목록에 없던 곡만 골라 부른다.

        Returns:
            곡 행(`music_id`·`title`). 삭제된 곡이면 `None`.

        Raises:
            MupidApiError: 번호가 잘못됐거나 인증이 안 됐을 때(404·401 등).
        """
        try:
            body = self._request("GET", f"/api/v1/music/{music_id}")
        except MupidApiError as e:
            # 상태코드가 일차 기준이지만 코드로도 본다 — nginx `auth_request` 를
            # 거치는 배포에서는 4xx 가 500 으로 접히고 사유가 헤더에만 온다.
            if e.status == GONE_STATUS or e.code == GONE_CODE:
                return None
            raise
        return body.get("data") or {}

    def music_rows(self, page_size: int = 100) -> list[dict]:
        """서버가 보여 주는 살아 있는 곡을 전부 훑는다.

        `GET /api/v1/music` 은 **삭제된 곡을 걸러서** 준다(`findPage` 의
        `m.isDeleted = false`). 그래서 여기 없는 곡은 소프트 삭제됐거나
        `registered.json` 이 낡은 것이고, 이 목록만으로는 구분되지 않는다
        (`audit.py` 의 "비율로 가르지 않는다" 참고). 단건 조회는 410 으로
        구분해 주지만, 곡마다 부르면 1060회라 훑는 쪽을 쓴다.

        응답 키는 snake_case 다(`total_elements`·`total_pages`). 서버의
        `spring.jackson.property-naming-strategy: SNAKE_CASE` 가 여기에도 걸린다.

        여러 곳이 같은 것을 부르므로 클라이언트에 둔다 — 등록 대조(`audit`)와
        장르·무드 등록(`genre_mood`)이 이 목록을 함께 쓴다.
        """
        rows: list[dict] = []
        seen: set[int] = set()
        page, total = 0, None
        while True:
            params = urlencode({"page": page, "size": page_size})
            data = self._request("GET", f"/api/v1/music?{params}")["data"]

            # 조회 중에 서버 목록이 바뀌면 페이지 경계가 어긋나 곡을 건너뛴다.
            if total is not None and total != data["total_elements"]:
                raise ValueError("조회 중 곡 수가 바뀜; 재실행 필요")
            total = data["total_elements"]

            for row in data["items"]:
                music_id = row.get("music_id")
                if music_id in seen:
                    raise ValueError(f"곡 목록에 같은 번호가 두 번: {music_id}")
                seen.add(music_id)
                rows.append(row)

            page += 1
            if page >= data["total_pages"]:
                break
            if not data["items"]:
                raise ValueError("빈 페이지")

        if len(rows) != total:
            raise ValueError("조회 개수 불일치")
        return rows

    def update_music(self, music_id: int, **fields) -> None:
        """곡 정보를 고친다. 준 필드만 바뀐다 (`PATCH` 규약).

        받는 이름: `album_id` · `artist_id` · `title` · `release_date` ·
        `image_file_id`.

        **삭제된 곡은 410 Gone 이다** (2026-09-18 변경, 그전엔 404). 없는 곡은 404 다.
        그래서 이 호출의 상태코드로 "없다" 와 "삭제됐다" 를 가릴 수 있다.
        """
        self._request("PATCH", f"/api/v1/music/{music_id}", json_body=fields)

    def update_music_image(self, music_id: int, image_file_id: int) -> None:
        """곡 커버를 갈아 끼운다."""
        self.update_music(music_id, image_file_id=image_file_id)

    def update_artist(self, artist_id: int, **fields) -> None:
        """아티스트 정보를 고친다. 서버가 받는 것은 `name` 뿐이다."""
        self._request("PATCH", f"/api/v1/artist/{artist_id}", json_body=fields)

    def update_album(self, album_id: int, **fields) -> None:
        """앨범 정보를 고친다. 받는 이름: `artist_id` · `title` · `release_date`."""
        self._request("PATCH", f"/api/v1/album/{album_id}", json_body=fields)

    def get_lyrics(self, music_id: int) -> list[dict]:
        """곡에 붙어 있는 가사 줄 목록. 없으면 빈 목록이다 (404 가 아니다)."""
        body = self._request("GET", f"/api/v1/music/{music_id}/lyric")
        return body["data"]["lines"]

    def replace_analysis(self, music_id: int, body: dict) -> dict:
        """한 곡의 분석 값을 통째로 갈아 끼운다 (`PUT /api/v1/music/{id}/analysis`).

        보컬 유무·성별과 BPM·요청 BPM 범위·언어·스타일이 한 행이다. **교체라 보내지
        않은 값은 비워진다** — 부분만 보내지 않는다.

        추천이 이 값을 Spring 요청 본문으로 받는다(연동 계약 2026-09-23 절). 예전에는
        AI 색인 파일에만 있었다.

        Args:
            body: `has_vocal`(필수) · `vocal_gender` · `bpm` · `bpm_requested_min` ·
                `bpm_requested_max` · `language` · `category`. 모르는 값은 `None`.
        """
        got = self._request("PUT", f"/api/v1/music/{int(music_id)}/analysis",
                            json_body=body)
        return got["data"]

    def replace_lyrics(self, music_id: int, lines: list[tuple[int, str]]) -> dict:
        """한 곡의 가사를 통째로 갈아 끼운다. **기존 가사는 지워진다.**

        줄마다 `POST /api/v1/lyric` 을 부르던 것을 곡마다 한 번으로 줄인다
        (505곡 23,628줄 → 505번). 곡 하나가 서버에서 한 트랜잭션이라 중간에
        끊겨도 반쯤 올라간 가사가 남지 않는다.

        Args:
            lines: `(time_ms, text)` 를 **보일 순서대로**. 서버는 정렬하지
                않으므로, 같은 `time_ms` 인 줄들은 여기 순서대로 보인다

        Returns:
            `{ "music_id": …, "deleted": 지운 줄 수, "created": 넣은 줄 수 }`
        """
        body = self._request(
            "PUT",
            f"/api/v1/music/{music_id}/lyric",
            json_body={
                "lines": [{"text": text, "time_ms": time_ms} for time_ms, text in lines]
            },
        )
        return body["data"]

    def add_code(self, code_type: str, code_name: str, display_name: str = "") -> dict:
        """공통 코드를 더한다. **이미 있으면 그 행이 그대로 온다** (`created: false`).

        `POST` 인데 멱등이다 — `codes` 의 `(code_type, code_name)` 이 UNIQUE 라
        두 번 보내도 결과가 같다. 배치가 곡마다 같은 장르·악기를 수백 번 보내는데
        두 번째부터 409 가 오면 부르는 쪽이 "이미 있음" 과 "진짜 실패" 를 가려야 하고,
        그 분기를 빠뜨리면 배치가 통째로 멈춘다.

        관리자 API 가 아니다. `@SystemRole` 이라 배치 계정으로 부를 수 있다.

        Args:
            code_type: `GENRE` · `MOOD` · `INSTRUMENT`
            code_name: 표시 이름. 같은 종류 안에서 고유하고, MySQL 기본
                콜레이션이라 **대소문자를 구분하지 않는다** (`Pop` 과 `pop` 은 같다).
            display_name: 화면에 보여줄 이름. **비우면 서버가 `code_name` 으로 채운다.**
                분위기 taxonomy 9종처럼 색인 원문이 코드 이름과 다를 때만 넘긴다 —
                이때 `code_name` 이 화면용 한국어, `display_name` 이 색인 원문이 된다.

        Returns:
            `{ "code_id": …, "code_type": …, "code_name": …, "display_name": …, "created": bool }`.
            `code_name` 은 서버에 저장된 철자다 — 보낸 것과 다를 수 있다.

        **와이어 이름은 snake_case 다.** 서버는 `spring.jackson.property-naming-strategy:
        SNAKE_CASE` 로 요청·응답을 전부 바꾼다. 자바 필드명(`codeName`)을 그대로 보내면
        스프링이 모르는 필드로 버리고(`FAIL_ON_UNKNOWN_PROPERTIES` 가 기본 활성), 필수값이
        `null` 이 되어 400 이 난다 — 배치가 응답의 `codeId` 를 읽기도 전에 죽는다.
        """
        payload = {"code_type": code_type, "code_name": code_name}
        # 빈 문자열을 실어 보내지 않는다. 서버는 `null`·빈 값을 같은 뜻으로 보지만,
        # 옛 서버(이 필드를 모르는 판)에 `display_name` 을 실어 보내면 검증에 걸릴 수 있다.
        if display_name:
            payload["display_name"] = display_name
        body = self._request("POST", "/api/v1/code", json_body=payload)
        return body["data"]

    def replace_sections(
        self,
        music_id: int,
        sections: list[dict],
        analysis_version: str = "v1",
    ) -> dict:
        """한 곡의 구간 분석 결과를 통째로 갈아 끼운다 (요구 1).

        **곡 단위로 바꾼다.** 구간마다 나눠 넣으면 재분석 도중에 옛 구간과 새 구간이
        섞이고, 그 상태로도 조회는 정상으로 보인다. 서버에서 한 트랜잭션이다.

        돌려받는 `music_section_analysis_id` 가 추천 서버의 `id_map` 을 채운다. 이
        번호가 없으면 추천 결과가 곡으로 이어지지 않아 통째로 버려진다.

        **이 한 번의 호출이 MySQL 과 ClickHouse 둘 다에 넣는다** (S15P21A204-435).
        ClickHouse `track_sections` 가 채워져야 재생 위치가 어느 구간인지 판정되고,
        비어 있으면 그 곡의 행동 신호가 오류 없이 통째로 버려진다. 배치가 따로 할
        일은 없다 — 다만 ClickHouse 적재가 실패해도 응답은 200 이므로, 구간이 안
        보이면 **서버 로그**를 본다.

        Args:
            sections: `section_index` · `external_id` · `section_label` ·
                `start_time_ms` · `end_time_ms` 를 담은 줄. 라벨은 대문자다
                (`INTRO` · `VERSE` · `CHORUS` …). `moods` · `instruments` 는
                **코드 이름**으로 실어 보낸다 — 번호는 서버가 붙이고, 코드표에
                없는 이름은 버리고 센다. 그래서 `instruments` 를 쓸 곡은
                `add_code("INSTRUMENT", …)` 를 **먼저** 불러 둬야 한다.

        Returns:
            `{ "music_id": …, "analysis_version": …, "replaced": 바뀐 구간 수,
               "sections": [ { "music_section_analysis_id": …,
                               "section_index": …, "external_id": … } ] }`
        """
        body = self._request(
            "PUT",
            f"/api/v1/music/{music_id}/section",
            json_body={"analysis_version": analysis_version, "sections": sections},
        )
        return body["data"]

    def create_stream(
        self,
        music_id: int,
        bitrate_kbps: int,
        codec: str,
        duration_ms: int,
        chunk_count: int,
    ) -> int:
        body = self._request(
            "POST",
            f"/api/v1/music/{music_id}/stream",
            json_body={
                "bitrate_kbps": bitrate_kbps,
                "codec": codec,
                "duration_ms": duration_ms,
                "chunk_count": chunk_count,
            },
        )
        return body["data"]["stream_id"]

    def upload_chunk(
        self,
        stream_id: int,
        index: int,
        duration_ms: int,
        file_hash: str,
        chunk_path: str | Path,
    ) -> dict:
        """청크 하나를 올리고 메타데이터를 확정한다.

        두 번 호출한다.

        1. `PUT /upload/chunk/{streamId}/{index}` — nginx 가 스프링에 인증만 묻고
           본문은 오브젝트 스토리지로 그대로 흘려보낸다. 음원 바이트는 JVM 을
           거치지 않는다.
        2. `POST /api/v1/chunk/{streamId}/{index}` — 길이·해시·크기를 기록한다.
           서버는 오브젝트가 실제로 있고 크기가 맞는지 HEAD 로 대조한다.
        """
        chunk_path = Path(chunk_path)
        data = chunk_path.read_bytes()

        self._request(
            "PUT",
            f"/upload/chunk/{stream_id}/{index}",
            raw_body=(data, "application/octet-stream"),
        )

        body = self._request(
            "POST",
            f"/api/v1/chunk/{stream_id}/{index}",
            json_body={
                "duration_ms": duration_ms,
                "file_hash": file_hash,
                "size_bytes": len(data),
            },
        )
        return body["data"]

    def put_waveform(self, music_id: int, values: list[float]) -> dict:
        """한 곡의 파형을 통째로 갈아 끼운다.

        같은 본문을 두 번 보내면 결과가 같다. 서버가 0~1 로 가두고 소수 넷째
        자리에서 반올림해 다시 직렬화하므로, 여기서 보내는 값의 자릿수는
        그대로 저장되는 것이 아니다.
        """
        body = self._request(
            "PUT",
            f"/api/v1/music/{music_id}/waveform",
            json_body={"values": values},
        )
        return body["data"]

    def get_waveform(self, music_id: int) -> list[float] | None:
        """곡의 파형. **아직 없으면 `None` 이다** (404 를 오류로 올리지 않는다).

        파형이 없는 것은 정상이라 `waveform` 명령이 이걸로 대상을 가린다.
        곡 자체가 없거나 삭제된 경우는 그대로 예외로 올라간다 — 그때는 코드가
        `MUPID_MUSIC_NOT_FOUND` · `MUPID_MUSIC_GONE` 이고, 부르는 쪽이
        `is_music_gone` 으로 갈라 본다.
        """
        try:
            body = self._request("GET", f"/api/v1/music/{music_id}/waveform")
        except MupidApiError as e:
            if e.code == WAVEFORM_MISSING_CODE:
                return None
            raise
        return body["data"]["values"]

    def get_streams(self, music_id: int) -> list[dict]:
        """곡에 달린 스트림 목록.

        **완료(`DONE`)된 것만 돌려준다** — 서버가 그렇게 거른다
        (`StreamServiceImpl.getStreamByMusicId`). 올리다 만 `PENDING` 스트림은
        이 길로는 보이지 않는다.
        """
        body = self._request("GET", f"/api/v1/music/{music_id}/stream")
        return body["data"]["streams"]

    def get_chunks(self, stream_id: int) -> list[dict]:
        """스트림에 기록된 청크 목록. 인덱스 오름차순."""
        body = self._request("GET", f"/api/v1/chunk/{stream_id}")
        return body["data"]["chunks"]

    def complete_stream(self, stream_id: int) -> None:
        self._request(
            "PATCH", f"/api/v1/stream/{stream_id}", json_body={"status": "DONE"}
        )

    def fail_stream(self, stream_id: int) -> None:
        self._request(
            "PATCH", f"/api/v1/stream/{stream_id}", json_body={"status": "FAILED"}
        )

    def delete_stream(self, stream_id: int) -> None:
        self._request("DELETE", f"/api/v1/stream/{stream_id}")

    def start_daily_recommendations(self) -> dict:
        """오늘의 모먼트 하루치 생성을 **시작만** 시킨다. ``{"date", "started"}`` 를 돌려준다.

        생성은 서버 안에서 따로 돌고 이 요청은 바로 202 로 끝난다. 서버가 날짜별 선점을
        잡으므로 다시 보내도 같은 날이 두 번 돌지 않는다 — 이미 돌고 있으면
        ``started`` 가 거짓이다. 날짜는 서버의 오늘(서울)이다.
        """
        body = self._request("POST", "/api/v1/recommendation/daily/start", json_body={})
        return body["data"]


# ─────────────────────────────────────────────────────────────
# 유틸리티
# ─────────────────────────────────────────────────────────────


def _encode_multipart(
    fields: list[tuple[str, str]] | None = None,
    files: list[tuple[str, str, bytes, str]] | None = None,
) -> tuple[bytes, str]:
    """multipart/form-data 본문을 만든다.

    Args:
        fields: (이름, 값) 목록
        files: (이름, 파일명, 바이트, mime) 목록

    Returns:
        (본문 바이트, Content-Type 헤더 값)
    """
    boundary = f"----MupidBatch{uuid.uuid4().hex}"
    parts: list[bytes] = []

    for name, value in fields or []:
        parts.append(f"--{boundary}\r\n".encode())
        parts.append(
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
        )
        parts.append(str(value).encode("utf-8"))
        parts.append(b"\r\n")

    for name, filename, content, mime in files or []:
        parts.append(f"--{boundary}\r\n".encode())
        parts.append(
            f'Content-Disposition: form-data; name="{name}"; filename="{filename}"\r\n'.encode()
        )
        parts.append(f"Content-Type: {mime}\r\n\r\n".encode())
        parts.append(content)
        parts.append(b"\r\n")

    parts.append(f"--{boundary}--\r\n".encode())
    return b"".join(parts), f"multipart/form-data; boundary={boundary}"


def _jwt_expires_at(token: str) -> float:
    """JWT 의 exp 를 읽는다 (서명 검증은 서버가 한다).

    읽지 못하면 0 을 돌려주고, 그러면 매 요청마다 재발급을 받게 된다.
    느릴 뿐 틀리지는 않는다.
    """
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)  # base64url 패딩 복원
        return float(json.loads(base64.urlsafe_b64decode(payload))["exp"])
    except Exception:
        logger.debug("액세스 토큰 만료 시각을 읽지 못했습니다.")
        return 0.0


def _read_set_cookie(headers: dict, name: str) -> str | None:
    """응답 헤더에서 쿠키 값을 꺼낸다."""
    raw = headers.get("Set-Cookie") or headers.get("set-cookie")
    if not raw:
        return None
    cookie = SimpleCookie()
    cookie.load(raw)
    morsel = cookie.get(name)
    return morsel.value if morsel else None


def _quote(value: str) -> str:
    from urllib.parse import quote

    return quote(value, safe="")
