"""Qdrant 에 닿는 곳 — 설정과 REST 호출 (S15P21A204-456, 설계 §6.1·§6.4).

**읽기만 한다.** 이 모듈에는 포인트를 쓰거나 지우는 호출이 없다. 벡터를 쓰는 것은 Spring 의
벡터 등록 API(457) 하나다(설계 원칙 1). 서빙이 받는 키도 Qdrant 의 **읽기 전용 키**
(`read_only_api_key`)라, 코드가 쓰려 해도 Qdrant 가 403 으로 거절한다.

SDK(`qdrant-client`)를 쓰지 않고 표준 라이브러리 HTTP 로 부른다. 부르는 것이 넷뿐이고
(컬렉션 정보·검색·조회·개수), SDK 를 넣으면 CI 이미지(`mupid-ci-ai:1`)를 러너에서 다시 구워야
한다 — `ai_server/requirements.txt` 를 바이트로 대조한다. `app/clickhouse.py` 와 같은 방식이다.

## 환경변수

    MUPID_VECTOR_BACKEND             local | qdrant | pinecone. 기본 local (`vector_store.backend_name`)
    MUPID_QDRANT_URL                 기본 http://qdrant:6333 (클러스터 안 ClusterIP)
    MUPID_QDRANT_READ_ONLY_API_KEY   비밀. Secret `mupid-ai-secrets` 에서만 온다. 로그·응답에 싣지 않는다
    MUPID_QDRANT_COLLECTION          기본은 정의 파일의 이름 (`moments__mert-v1-95m`)
    MUPID_QDRANT_TIMEOUT             초. 기본 5
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from typing import Optional

from . import qdrant_schema

DEFAULT_URL = "http://qdrant:6333"
DEFAULT_TIMEOUT = 5.0
KEY_ENV = "MUPID_QDRANT_READ_ONLY_API_KEY"


class QdrantError(Exception):
    """Qdrant 에 닿지 못했거나 거절당했다. **삼키지 않는다** — 빈 결과와 구분되어야 한다."""


def _env(name: str, default: str = "") -> str:
    return (os.environ.get(name) or default).strip()


def url() -> str:
    return _env("MUPID_QDRANT_URL", DEFAULT_URL).rstrip("/")


def collection() -> str:
    return _env("MUPID_QDRANT_COLLECTION") or qdrant_schema.collection_name()


def timeout() -> float:
    try:
        return float(_env("MUPID_QDRANT_TIMEOUT") or DEFAULT_TIMEOUT)
    except ValueError:
        return DEFAULT_TIMEOUT


def settings() -> dict:
    """진단용. **키는 있는지 여부만** 싣는다."""
    return {"url": url(), "collection": collection(), "timeout": timeout(),
            "api_key_present": bool(_env(KEY_ENV))}


def request(method: str, path: str, body=None, timeout_s: Optional[float] = None,
            api_key: Optional[str] = None, base: Optional[str] = None):
    """REST 한 번. 돌려주는 것은 응답의 `result`. 실패는 `QdrantError`.

    `api_key`·`base` 는 도구(`tools/qdrant_collection.py`)가 쓰기 키와 주소를 따로 줄 때만 쓴다.
    서빙은 주지 않는다 — 환경의 읽기 전용 키로 간다.

    `body` 는 dict 이거나 **이미 직렬화한 bytes** 다. 허용 목록(`HasId`)은 수천 개의 UUID 라
    검색마다 다시 직렬화하지 않으려고 부르는 쪽이 조각을 만들어 둔다(`vector_store`).
    """
    if body is None or isinstance(body, (bytes, bytearray)):
        data = body
    else:
        data = json.dumps(body, separators=(",", ":")).encode()
    req = urllib.request.Request((base or url()).rstrip("/") + path, data=data, method=method)
    req.add_header("content-type", "application/json")
    key = api_key if api_key is not None else _env(KEY_ENV)
    if key:
        req.add_header("api-key", key)
    try:
        with urllib.request.urlopen(req, timeout=timeout_s or timeout()) as r:
            got = json.loads(r.read())
    except urllib.error.HTTPError as e:
        # 본문의 status.error 만 싣는다. 요청 본문(벡터)은 싣지 않는다.
        try:
            why = json.loads(e.read()).get("status", {}).get("error", "")
        except Exception:
            why = ""
        raise QdrantError(f"HTTP {e.code} {method} {path}: {str(why)[:300]}") from None
    except (urllib.error.URLError, OSError, ValueError) as e:
        raise QdrantError(f"{type(e).__name__} {method} {path}: {e}") from None
    return got.get("result")


def collection_info(name: Optional[str] = None) -> Optional[dict]:
    """컬렉션 정보. **없으면 None** — 닿지 못한 것(`QdrantError`)과 구분한다."""
    try:
        return request("GET", f"/collections/{name or collection()}")
    except QdrantError as e:
        if str(e).startswith("HTTP 404"):
            return None
        raise


def count(filter_: Optional[dict] = None, name: Optional[str] = None) -> int:
    body: dict = {"exact": True}
    if filter_:
        body["filter"] = filter_
    return int(request("POST", f"/collections/{name or collection()}/points/count", body)["count"])
