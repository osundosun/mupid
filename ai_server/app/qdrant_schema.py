"""Qdrant 순간 벡터 컬렉션의 정의를 읽는 곳 (S15P21A204-456, 설계 §6.1).

정의 자체는 옆의 `qdrant_collection.json` 에 있다. 파이썬 상수로 두지 않은 것은 **쓰는 쪽이
Spring(457)이기 때문이다** — 두 언어가 같은 값을 각자 적으면 한쪽만 고친 날 차원·거리·포인트
id 규칙이 갈리고, 그것은 오류가 아니라 "검색은 되는데 엉뚱한 구간" 으로만 드러난다.

    collection_body(versions)   PUT /collections/{name} 본문 (모델 벡터 포함)
    model_vector(version)       모델 버전 N 의 벡터 이름 `stage1_vN`
    point_id(external_id)       포인트 id — UUIDv5(namespace, external_id)
    payload_indexes()           [(필드, 색인 종류)] — 만들 때 한 번 건다

컬렉션을 만드는 도구는 `tools/qdrant_collection.py` 다(멱등). 서빙은 **만들지 않는다** —
없으면 준비 판정이 실패한다(§10.1 장애 표).
"""
from __future__ import annotations

import copy
import json
import re
import uuid
from functools import lru_cache
from pathlib import Path

SCHEMA_PATH = Path(__file__).with_name("qdrant_collection.json")

# 모델 버전 표기. `models.lock` 의 `stage1.version` 과 같은 모양(v1, v2 …)만 받는다 — 벡터 이름에
# 그대로 들어가므로 다른 글자가 섞이면 이름이 새로 생긴다.
_VERSION = re.compile(r"^v[0-9]+$")


@lru_cache(maxsize=1)
def schema() -> dict:
    return json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))


def _strip(node):
    """`_` 로 시작하는 설명 키를 뺀다. Qdrant 는 모르는 키를 거절한다."""
    if isinstance(node, dict):
        return {k: _strip(v) for k, v in node.items() if not str(k).startswith("_")}
    if isinstance(node, list):
        return [_strip(v) for v in node]
    return node


def collection_name() -> str:
    return str(schema()["collection"])


def embedding_version() -> str:
    return str(schema()["embedding_version"])


def mert_dim() -> int:
    return int(schema()["vectors"]["mert"]["size"])


def model_vector(version: str) -> str:
    """모델 버전 → 벡터 이름. `v3` → `stage1_v3`."""
    v = str(version)
    if not _VERSION.match(v):
        raise ValueError(f"모델 버전은 v<숫자> 여야 한다: {v!r}")
    return schema()["model_vector"]["name"].format(version=v)


def model_vector_params() -> dict:
    return _strip(copy.deepcopy(schema()["model_vector"]["params"]))


def vectors(model_versions=()) -> dict:
    """named vector 설정. 모델 벡터는 준 버전만 넣는다."""
    out = {k: _strip(copy.deepcopy(v)) for k, v in schema()["vectors"].items()}
    for v in model_versions:
        out[model_vector(v)] = model_vector_params()
    return out


def collection_body(model_versions=()) -> dict:
    return {"vectors": vectors(model_versions), **_strip(copy.deepcopy(schema()["params"]))}


def payload_indexes() -> list[tuple[str, str]]:
    return [(k, v["type"]) for k, v in schema()["payload"].items() if v.get("index")]


def payload_fields() -> list[str]:
    return list(schema()["payload"])


@lru_cache(maxsize=1)
def _namespace() -> uuid.UUID:
    return uuid.UUID(schema()["point_id"]["namespace"])


def point_id(external_id: str) -> str:
    """포인트 id. Spring 도 같은 식으로 만든다 — `UUID v5(namespace, UTF-8 external_id)`."""
    return str(uuid.uuid5(_namespace(), str(external_id)))
