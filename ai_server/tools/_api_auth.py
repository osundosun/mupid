"""Spring 배치 API 인증. `register_sections` 가 쓴다.

**리프레시 토큰만 환경변수로 받는다.** 액세스 토큰은 그때그때 `GET /api/v1/auth` 로
받아 메모리에만 둔다 — 파일·로그·응답 어디에도 남기지 않는다. 두 도구가 각자 이 흐름을
들고 있으면 한쪽만 고쳐져 인증 방식이 갈라진다.

    MUPID_API_BASE_URL       Spring 주소 (`/api/v1` 앞까지)
    MUPID_API_REFRESH_TOKEN  ROLE_SYSTEM 계정의 리프레시 토큰
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request

TIMEOUT = 15


def base() -> str:
    b = os.environ.get("MUPID_API_BASE_URL", "").strip().rstrip("/")
    if not b:
        raise SystemExit("MUPID_API_BASE_URL 이 비어 있다.")
    return b


def request_json(url: str, method: str = "GET", token: str | None = None,
                 cookie: str | None = None, body: dict | None = None,
                 timeout: int = TIMEOUT) -> dict:
    data = None
    req = urllib.request.Request(url, method=method)
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req.data = data
        req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if cookie:
        req.add_header("Cookie", cookie)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode() or "{}")


def access_token() -> str:
    """리프레시 토큰으로 액세스 토큰을 받는다. 값은 돌려주기만 하고 남기지 않는다."""
    refresh = os.environ.get("MUPID_API_REFRESH_TOKEN", "").strip()
    if not refresh:
        raise SystemExit("MUPID_API_REFRESH_TOKEN 이 비어 있다. "
                         "POST /api/v1/user/system 으로 발급한다(관리자 권한).")
    body = request_json(f"{base()}/api/v1/auth", cookie=f"refresh_token={refresh}")
    tok = (body.get("data") or {}).get("access_token", "")
    if not tok:
        raise SystemExit("액세스 토큰을 받지 못했다. 리프레시 토큰이 만료됐는지 본다.")
    return tok
