"""이식된 추천 서비스가 실제로 추천을 돌려주는지 확인한다.

모델·색인이 있어야 의미가 있다. 없으면 통과로 위장하지 않고 건너뛴다.

    export MUPID_AI_ARTIFACT_DIR=/경로/artifacts
    pytest ai_server/tests -q

숫자 ID 매핑은 **시험용 임시 파일**을 만들어 쓴다. 운영 경로에 남기지 않는다 —
실제 매핑은 MySQL 이 발급한 값이어야 하고, 여기서 만든 번호는 형식만 같은 시험 데이터다.
"""
from __future__ import annotations

import os
import tempfile
from pathlib import Path

import pytest

ART = os.environ.get("MUPID_AI_ARTIFACT_DIR", "")
MOM = Path(ART) / "data/proc/mupid/moments.parquet" if ART else None

pytestmark = pytest.mark.skipif(
    not ART or not (MOM and MOM.exists()),
    reason="MUPID_AI_ARTIFACT_DIR 이 없거나 moments.parquet 을 찾지 못했다",
)

POOL_SOURCES = ("flow", "jamendo", "own")
MUSIC_BASE, SECTION_BASE = 900_000, 9_000_000


@pytest.fixture(scope="session")
def client():
    """임시 매핑표를 만들고 앱을 띄운다. 매핑표는 세션이 끝나면 지운다."""
    import numpy as np  # noqa: F401  (색인 적재에 필요)
    import pandas as pd

    mom = pd.read_parquet(MOM)
    pool = mom[mom.source.isin(POOL_SOURCES)]
    tracks = {t: MUSIC_BASE + i for i, t in enumerate(sorted(pool.track_id.astype(str).unique()))}
    rows = [{"source_track_id": str(r.track_id), "music_id": tracks[str(r.track_id)],
             "source_section_uid": str(r.moment_id), "music_section_id": SECTION_BASE + i}
            for i, r in enumerate(pool.sort_values(["track_id", "section_idx"]).itertuples())]

    tmp = Path(tempfile.mkdtemp(prefix="mupid_test_idmap_")) / "id_map.parquet"
    pd.DataFrame(rows).to_parquet(tmp, index=False)
    os.environ["MUPID_ID_MAP"] = str(tmp)

    from fastapi.testclient import TestClient

    from ai_server.app.main import app

    with TestClient(app) as c:
        yield c
    tmp.unlink(missing_ok=True)


def test_health(client):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["status"] == "ok"
    assert body["catalog_loaded"] is True, body["error"]
    assert body["catalog"]["moments"] > 0


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
    body = {"recommendation_id": 5001, "user_id": 15, "limit": 10, **extra}
    r = client.post(path, json=body)
    assert r.status_code == 200, r.json()
    return r.json()


def test_daily_returns_items(client):
    out = _items(client)
    assert out["recommendation_id"] == 5001
    assert out["items"], "추천이 한 건도 없다"
    assert out["model_version"]


def test_item_fields(client):
    for it in _items(client)["items"]:
        for k in ("music_id", "music_section_id", "start_time_ms", "end_time_ms",
                  "rank_order"):
            assert isinstance(it[k], int), (k, it[k])
        assert it["end_time_ms"] > it["start_time_ms"] >= 0
        assert it["section_label"] and it["section_label"].isupper()
        assert it["reason_code"]
        assert it["reason"]
        assert not any(k in it for k in ("track_id", "moment_id", "source_track_id"))


def test_no_duplicate_music_id(client):
    ids_ = [i["music_id"] for i in _items(client)["items"]]
    assert len(ids_) == len(set(ids_)), ids_


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
                          "exclude_music_ids": []})
    assert r.status_code in (200, 404)
    if r.status_code == 404:
        assert r.json()["error_code"] == "NO_CANDIDATES"


def test_bad_request_is_rejected(client):
    r = client.post("/internal/recommendations/daily",
                    json={"recommendation_id": "5001", "user_id": 15})
    assert r.status_code == 400
    assert r.json()["error_code"] == "INVALID_REQUEST"


def test_search_without_interpreter_is_explicit(client):
    """해석기가 없으면 조건을 조용히 무시하지 않고 준비되지 않음을 알린다."""
    r = client.post("/internal/recommendations/search",
                    json={"recommendation_id": 5003, "user_id": 15, "query": "잔잔한 노래"})
    if os.environ.get("MUPID_AI_NLP_URL", "").strip():
        assert r.status_code in (200, 404)
    else:
        assert r.status_code == 501
        assert r.json()["error_code"] == "SEARCH_NOT_CONFIGURED"
