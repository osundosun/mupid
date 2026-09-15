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

logger = logging.getLogger(__name__)

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
        self, album_id: int, artist_id: int, title: str, release_date: str
    ) -> int:
        body = self._request(
            "POST",
            "/api/v1/music",
            json_body={
                "album_id": album_id,
                "artist_id": artist_id,
                "title": title,
                "release_date": release_date,
            },
        )
        return body["data"]["music_id"]

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
