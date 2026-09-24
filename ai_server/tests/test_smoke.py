"""이식된 추천 서비스가 실제로 추천을 돌려주는지 확인한다.

모델·색인이 있어야 의미가 있다. 없으면 통과로 위장하지 않고 건너뛴다.

    export MUPID_AI_ARTIFACT_DIR=/경로/artifacts
    pytest ai_server/tests -q

숫자 ID 매핑표는 **만들지 않는다.** 응답이 문자열 `external_id` 로 나가고 번역은 Spring
이 하므로(`3847f95`), 이 서버가 확인할 매핑이 없다 — `/internal/ready` 도 보지 않는다.
"""
from __future__ import annotations

import os
from pathlib import Path

import pytest

ART = os.environ.get("MUPID_AI_ARTIFACT_DIR", "")
MOM = Path(ART) / "data/proc/mupid/moments.parquet" if ART else None

# 추천 요청에는 Spring 이 싣는 `catalog` 본문이 필수다(연동 계약 2026-09-23 절). 실제
# 후보 값은 MySQL 에만 있으므로, Spring 이 보낸 본문을 JSON 으로 떠 둔 파일을 준다.
CATALOG_FILE = os.environ.get("MUPID_SMOKE_CATALOG", "")

pytestmark = pytest.mark.skipif(
    not ART or not (MOM and MOM.exists()) or not (CATALOG_FILE and Path(CATALOG_FILE).exists()),
    reason="MUPID_AI_ARTIFACT_DIR · moments.parquet · MUPID_SMOKE_CATALOG(본문 JSON) 중 하나가 없다",
)


def _catalog() -> dict:
    import json

    return json.loads(Path(CATALOG_FILE).read_text(encoding="utf-8"))

POOL_SOURCES = ("flow", "jamendo", "own")


@pytest.fixture(scope="session")
def client():
    import numpy as np  # noqa: F401  (색인 적재에 필요)

    from fastapi.testclient import TestClient

    from ai_server.app.main import app

    with TestClient(app) as c:
        yield c


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert body["index_loaded"] is True, body["error"]
    assert body["index"]["moments"] > 0
    assert body["metadata_source"] == "request"


def test_integrity_checks_ran(client):
    checks = client.get("/health").json()["integrity"]
    assert checks is not None
    assert len(set(checks["rows"].values())) == 1, checks["rows"]
    assert checks["emb_row_contiguous"] is True
    assert checks["duplicate_moment_id"] == 0


def test_ready(client):
    r = client.get("/internal/ready")
    assert r.status_code == 200, r.json()
    assert r.json()["ready"] is True


def _items(client, path="/internal/recommendations/daily", **extra):
    # `limit` 을 넣지 않는다. `daily` 는 4개로 고정이라 다른 값을 보내면 400 이고,
    # `next` 는 제 기본값(5)으로 간다.
    body = {"recommendation_id": 5001, "user_id": 15, "catalog": _catalog(), **extra}
    r = client.post(path, json=body)
    assert r.status_code == 200, r.json()
    return r.json()


def test_daily_returns_items(client):
    out = _items(client)
    assert out["recommendation_id"] == 5001
    assert out["items"], "추천이 한 건도 없다"
    assert out["model_version"]


def test_daily_is_four_moments(client):
    """오늘의 모멘트는 **4개**다. 진짜 색인으로 세어 본다."""
    from ai_server.app.internal_api import DAILY_LIMIT

    assert DAILY_LIMIT == 4
    assert len(_items(client)["items"]) == DAILY_LIMIT


def test_daily_refuses_a_different_count(client):
    """조용히 4로 깎지 않는다. 10을 요청한 쪽이 4를 받고 모르면 안 된다."""
    r = client.post("/internal/recommendations/daily",
                    json={"recommendation_id": 5001, "user_id": 15, "limit": 10})
    assert r.status_code == 400
    assert r.json()["error_code"] == "INVALID_REQUEST"


def test_item_fields(client):
    """응답은 **문자열 `external_id`** 다.

    숫자 ID·시간·구간 종류·사람이 읽는 문장은 싣지 않는다 — Spring 이
    `music_section_analysis.external_id` 로 MySQL 에서 가져오는 값이라 두 벌이 되면
    어긋날 자리만 생긴다(`app/internal_api.py`).
    """
    for it in _items(client)["items"]:
        assert isinstance(it["external_id"], str) and "#" in it["external_id"]
        assert isinstance(it["rank_order"], int) and it["rank_order"] >= 1
        assert isinstance(it["score"], (int, float))
        assert isinstance(it["reason_code"], str) and it["reason_code"]
        ref = it["ref_external_id"]
        assert ref is None or (isinstance(ref, str) and ref)
        assert not any(k in it for k in ("track_id", "moment_id", "source_track_id",
                                         "music_id", "music_section_id",
                                         "music_section_analysis_id", "reason",
                                         "start_time_ms", "end_time_ms",
                                         "section_label"))


def test_no_duplicate_track(client):
    """한 곡에서 두 구간이 함께 나오지 않는다. 같은 노래가 두 번 걸린 것처럼 보인다."""
    tracks = [i["external_id"].split("#", 1)[0] for i in _items(client)["items"]]
    assert len(tracks) == len(set(tracks)), tracks


def test_rank_order_is_contiguous(client):
    items = _items(client)["items"]
    assert [i["rank_order"] for i in items] == list(range(1, len(items) + 1))


def test_next_returns_items(client):
    out = _items(client, path="/internal/recommendations/next")
    assert out["items"]


def test_no_candidates_is_not_a_server_error(client):
    """조건에 맞는 곡이 없을 때 아무거나 채우지 않고 사유를 돌려준다."""
    r = client.post("/internal/recommendations/next",
                    json={"recommendation_id": 5002, "user_id": 15, "limit": 3,
                          "exclude_tracks": [], "catalog": _catalog()})
    assert r.status_code in (200, 404)
    if r.status_code == 404:
        assert r.json()["error_code"] == "NO_CANDIDATES"


def test_bad_request_is_rejected(client):
    r = client.post("/internal/recommendations/daily",
                    json={"recommendation_id": "5001", "user_id": 15})
    assert r.status_code == 400
    assert r.json()["error_code"] == "INVALID_REQUEST"


def test_search_needs_a_query_plan(client):
    """조건은 Spring 이 건다. 원문만 오면 조건으로 바꿀 길이 없어 거절한다."""
    r = client.post("/internal/recommendations/search",
                    json={"recommendation_id": 5003, "user_id": 15, "query": "잔잔한 노래",
                          "catalog": _catalog()})
    assert r.status_code == 400
    assert r.json()["error_code"] == "INVALID_REQUEST"
