"""Spring 호출 한 벌. 배치 도구와 서빙이 **같은 인증 흐름**을 쓴다.

예전에는 이 흐름이 `tools/_api_auth.py` 에만 있었다. 그때는 Spring 을 부르는 것이
적재 도구뿐이었기 때문이다. 이제 서빙(`app/catalog_api.py`)도 부르는데, 거기에
한 벌을 더 만들면 **한쪽만 고쳐져 인증 방식이 갈라진다** — `_api_auth` 의 첫 문단이
바로 그것을 경계한다. 그래서 실물은 여기 하나이고 `tools/_api_auth.py` 는 이 모듈을
감싸기만 한다.

    MUPID_API_BASE_URL       Spring 주소 (`/api/v1` 앞까지)
    MUPID_API_REFRESH_TOKEN  ROLE_SYSTEM 계정의 리프레시 토큰

**리프레시 토큰만 환경변수로 받는다.** 액세스 토큰은 그때그때 `GET /api/v1/auth` 로
받아 메모리에만 둔다 — 파일·로그·응답 어디에도 남기지 않는다.

도구와 서버의 차이는 **실패를 어떻게 알리느냐** 하나다. 도구는 그 자리에서 죽어도
되지만(`SystemExit`), 서버는 죽으면 안 된다 — 이미 들고 있는 카탈로그로 계속 추천해야
한다. 그래서 여기서는 `SpringApiError` 만 던지고, 그것을 무엇으로 바꿀지는 부르는 쪽이
정한다.
"""
from __future__ import annotations

import json
import os
import urllib.request

TIMEOUT = 15


class SpringApiError(Exception):
    """Spring 을 부르지 못했거나 기대한 모양이 아니다.

    **메시지에 토큰을 싣지 않는다.** 이 예외는 로그와 `/health` 로 나간다.
    """


def base() -> str:
    b = os.environ.get("MUPID_API_BASE_URL", "").strip().rstrip("/")
    if not b:
        raise SpringApiError("MUPID_API_BASE_URL 이 비어 있다.")
    return b


def configured() -> bool:
    """부를 수 있는 설정이 갖춰졌는가. **값 자체는 보지 않는다.**"""
    return bool(os.environ.get("MUPID_API_BASE_URL", "").strip()
                and os.environ.get("MUPID_API_REFRESH_TOKEN", "").strip())


def request_json(url: str, method: str = "GET", token: str | None = None,
                 cookie: str | None = None, body: dict | None = None,
                 timeout: int = TIMEOUT) -> dict:
    req = urllib.request.Request(url, method=method)
    if body is not None:
        req.data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req.add_header("Content-Type", "application/json; charset=utf-8")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if cookie:
        req.add_header("Cookie", cookie)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode() or "{}")


def access_token(timeout: int = TIMEOUT) -> str:
    """리프레시 토큰으로 액세스 토큰을 받는다. 값은 돌려주기만 하고 남기지 않는다."""
    refresh = os.environ.get("MUPID_API_REFRESH_TOKEN", "").strip()
    if not refresh:
        raise SpringApiError(
            "MUPID_API_REFRESH_TOKEN 이 비어 있다. "
            "POST /api/v1/user/system 으로 발급한다(관리자 권한).")
    body = request_json(f"{base()}/api/v1/auth",
                        cookie=f"refresh_token={refresh}", timeout=timeout)
    tok = (body.get("data") or {}).get("access_token", "")
    if not tok:
        raise SpringApiError("액세스 토큰을 받지 못했다. 리프레시 토큰이 만료됐는지 본다.")
    return tok
