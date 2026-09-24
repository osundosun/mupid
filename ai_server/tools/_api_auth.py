"""Spring 배치 API 인증. `register_sections` 가 쓴다.

**실물은 `app/spring_api.py` 에 있다.** 적재 도구만 Spring 을 부른다 — 추천 서버는
부르지 않는다(연동 계약 2026-09-23 절). 이 파일은 **도구용 실패 방식**만 덧입힌다: 도구는 그 자리에서
`SystemExit` 으로 멈추는 편이 낫고, 서버는 죽으면 안 되기 때문이다.

    MUPID_API_BASE_URL       Spring 주소 (`/api/v1` 앞까지)
    MUPID_API_REFRESH_TOKEN  ROLE_SYSTEM 계정의 리프레시 토큰
"""
from __future__ import annotations

from ai_server.app import spring_api
from ai_server.app.spring_api import TIMEOUT, request_json  # noqa: F401  (호출부 계약)


def base() -> str:
    try:
        return spring_api.base()
    except spring_api.SpringApiError as e:
        raise SystemExit(str(e)) from e


def access_token() -> str:
    """리프레시 토큰으로 액세스 토큰을 받는다. 값은 돌려주기만 하고 남기지 않는다."""
    try:
        return spring_api.access_token()
    except spring_api.SpringApiError as e:
        raise SystemExit(str(e)) from e
