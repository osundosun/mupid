"""모델·색인 없이 도는 계약 테스트.

`test_smoke.py` 는 산출물이 있어야 의미가 있어 CI 에서 통째로 건너뛴다. 그러면 경로 해석,
오류 코드, 응답 문장 같은 **산출물과 무관한 회귀**를 아무도 못 잡는다. 여기서는 파일을
읽지 않는 것만 본다.
"""
from __future__ import annotations

import re
from pathlib import Path

import pytest

from ai_server.app import ids, internal_api, paths


@pytest.fixture(autouse=True)
def restore_service_module():
    """`install()` 은 전역 `_SVC_MOD` 를 덮어쓴다. 가짜 모듈이 뒤 테스트로 새면
    실패 원인이 엉뚱한 곳에서 나타난다."""
    saved = internal_api._SVC_MOD
    yield
    internal_api._SVC_MOD = saved


@pytest.fixture
def clean_env(monkeypatch):
    for k in ("MUPID_AI_ARTIFACT_DIR", "MUPID_ID_MAP"):
        monkeypatch.delenv(k, raising=False)
    return monkeypatch


# ------------------------------------------------------------------ 경로 단일화
def test_artifact_dir_is_honored(clean_env):
    clean_env.setenv("MUPID_AI_ARTIFACT_DIR", str(Path("/tmp/art")))
    assert paths.artifact_root() == Path("/tmp/art")
    assert paths.data_dir() == Path("/tmp/art") / "data" / "proc" / "mupid"


def test_id_map_follows_artifact_dir(clean_env):
    """`ids` 가 제 경로를 따로 계산하면 매핑표만 딴 곳을 보게 된다 — 이 저장소의 실제 버그."""
    clean_env.setenv("MUPID_AI_ARTIFACT_DIR", str(Path("/tmp/art")))
    assert paths.id_map_path() == paths.data_dir() / "id_map.parquet"
    assert paths.moments_path().parent == paths.id_map_path().parent


def test_empty_id_map_env_is_treated_as_unset(clean_env):
    """`.env` 에 `MUPID_ID_MAP=` 로 비워 두는 경우가 흔하다. Path("") 는 현재 디렉터리다."""
    clean_env.setenv("MUPID_AI_ARTIFACT_DIR", str(Path("/tmp/art")))
    clean_env.setenv("MUPID_ID_MAP", "")
    assert paths.id_map_path() == paths.data_dir() / "id_map.parquet"


def test_id_map_env_override_wins(clean_env):
    clean_env.setenv("MUPID_ID_MAP", str(Path("/tmp/custom.parquet")))
    assert paths.id_map_path() == Path("/tmp/custom.parquet")


def test_id_map_path_is_read_at_construction(clean_env, tmp_path):
    """import 시점에 굳히면 테스트·배포가 import 순서에 의존한다."""
    clean_env.setenv("MUPID_ID_MAP", str(tmp_path / "later.parquet"))
    assert ids.IdMap(strict=False).path == tmp_path / "later.parquet"


def test_check_reports_paths_without_env_values(clean_env):
    """진단에는 경로만 싣는다. 해석기 주소 같은 값 자체는 내보내지 않는다."""
    clean_env.setenv("MUPID_AI_NLP_URL", "http://secret-host:8200/parse")
    blob = repr(paths.check())
    assert "secret-host" not in blob
    assert "artifact_dir" in blob and "id_map" in blob


# ------------------------------------------------------------------ 사유 코드
def _item(**kw) -> dict:
    base = {"clap_score": None, "slot_source": "dna0", "matched_dna": None}
    return {**base, **kw}


@pytest.mark.parametrize("item,has_query,src,expected", [
    (_item(matched_dna="u#dna1"), False, "labels", "SIMILAR_PREFERRED_MOMENT"),
    (_item(matched_dna="u#dna1"), False, "onboarding", "ONBOARDING_MATCH"),
    (_item(slot_source="explore"), False, "labels", "EXPLORE_NEW"),
    (_item(), False, "none", "COLD_START"),
    (_item(clap_score=0.4), True, "labels", "QUERY_SEMANTIC_MATCH"),
    (_item(), True, "labels", "QUERY_FILTER_MATCH"),
])
def test_reason_code(item, has_query, src, expected):
    assert internal_api._reason_code(item, has_query, src) == expected


def test_onboarding_is_not_counted_as_cold_start():
    """온보딩으로 뽑았는데 COLD_START 로 집계되면 콜드스타트 비율 지표가 틀린다."""
    item = _item(matched_dna="u#dna0")
    assert internal_api._reason_code(item, False, "onboarding") != "COLD_START"


# ------------------------------------------------------------------ 문장 노출
_LEAK = re.compile(r"#|dna\d|\d+\.\d+|JAMENDO_|T\d{6}")


@pytest.mark.parametrize("code", sorted(internal_api._REASON_TEXT))
def test_reason_text_has_no_internal_values(code):
    for label in ("CHORUS", "VERSE", "OTHER", ""):
        text = internal_api._reason_text(code, label)
        assert text, (code, label)
        assert not _LEAK.search(text), text


def test_unknown_reason_code_falls_back():
    assert internal_api._reason_text("SOMETHING_NEW_v2", "CHORUS") == \
        internal_api._REASON_FALLBACK


def test_every_reason_code_has_text():
    """코드를 늘리면서 문장을 빠뜨리면 폴백으로 조용히 뭉개진다."""
    produced = set()
    for has_query in (False, True):
        for src in ("labels", "onboarding", "none"):
            for item in (_item(), _item(matched_dna="d"), _item(slot_source="explore"),
                         _item(clap_score=0.1)):
                produced.add(internal_api._reason_code(item, has_query, src))
    assert produced <= set(internal_api._REASON_TEXT), produced - set(internal_api._REASON_TEXT)


# ------------------------------------------------------------------ 응답 경계
def test_to_response_drops_internal_fields(monkeypatch):
    """추천기 내부 문자열·ID·점수가 응답으로 새지 않는지 본다."""
    im = ids.IdMap(path=Path("/nonexistent.parquet"), strict=False)
    im.track_to_num = {"JAMENDO_1083659": 900108}
    im.sec_to_num = {"JAMENDO_1083659#2": 9001318}
    monkeypatch.setattr(ids, "_MAP", im)

    out = {
        "diagnostics": {"query_source": "labels", "shortfall": 0},
        "results": [{
            "rank": 1, "track_id": "JAMENDO_1083659", "moment_id": "JAMENDO_1083659#2",
            "section_label": "chorus", "start_sec": 79.49, "end_sec": 92.74,
            "final_score": 1.0, "matched_dna": "u#dna3",
            "matched_dna_rep": "JAMENDO_1083659#2", "slot_source": "dna3",
            "clap_score": None, "prob": 0.634, "model_score": 0.551,
            "moment_similarity": 0.812,
            "reason": "내 선호 구간 묶음 u#dna3(대표 JAMENDO_1083659#2)와 이 곡 2번 "
                      "chorus 구간 79~93초가 유사도 0.812 · 선호 확률 0.634",
        }],
    }
    resp = internal_api.to_response(5001, out, 10, False)
    item = resp.items[0]
    assert item.music_id == 900108 and item.music_section_id == 9001318
    assert item.section_label == "CHORUS"
    assert item.reason_code == "SIMILAR_PREFERRED_MOMENT"
    assert not _LEAK.search(item.reason), item.reason
    assert "dna" not in item.reason and "0.812" not in item.reason


# ------------------------------------------------------------------ 자연어 검색
def test_parse_nlp_query_reports_not_configured(monkeypatch):
    """해석기가 없으면 일반 추천으로 조용히 넘어가지 않는다."""
    from ai_server.app import recommender

    monkeypatch.setattr(recommender, "NLP_PARSE_URL", "")
    plan, error = recommender.parse_nlp_query("조용한 피아노 음악")
    assert plan is None
    assert error is not None and error[0] == "SEARCH_NOT_CONFIGURED"


def test_parse_nlp_query_reports_call_failure(monkeypatch):
    from ai_server.app import recommender

    monkeypatch.setattr(recommender, "NLP_PARSE_URL", "http://127.0.0.1:9/parse")
    plan, error = recommender.parse_nlp_query("조용한 피아노 음악")
    assert plan is None
    assert error is not None and error[0] == "INTERNAL_ERROR"
    assert "127.0.0.1" not in error[1], "내부 주소를 메시지에 싣지 않는다"


def test_nlp_env_var_is_single():
    """게이트와 호출부가 같은 변수를 봐야 한다. 예전엔 MUPID_NLP_URL 로 갈라져 있었다."""
    for f in (Path(__file__).resolve().parents[1] / "app").glob("*.py"):
        assert "MUPID_NLP_URL" not in f.read_text(encoding="utf-8"), f.name


def test_search_without_interpreter_returns_501():
    """해석기 미설정은 400 이 아니라 501 이어야 한다 (Spring 재시도 정책이 코드를 본다)."""
    from types import SimpleNamespace

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    fake = SimpleNamespace(NLP_PARSE_URL="", CAT=None)
    app = FastAPI()
    internal_api.install(app, fake)
    r = TestClient(app).post("/internal/recommendations/search",
                             json={"recommendation_id": 1, "user_id": 1,
                                   "query": "조용한 피아노 음악"})
    assert r.status_code == 501, r.text
    assert r.json()["error_code"] == "SEARCH_NOT_CONFIGURED"


def test_search_parse_failure_is_not_silently_downgraded():
    """파싱에 실패했는데 200 + 일반 추천이 나가면 조건을 몰래 푼 것이다."""
    from types import SimpleNamespace

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    called: list[str] = []

    def _recommend(*a, **k):
        called.append("recommend")
        return {"results": [], "diagnostics": {}}

    fake = SimpleNamespace(
        NLP_PARSE_URL="http://interpreter/parse",
        CAT=SimpleNamespace(pool=[1]),
        parse_nlp_query=lambda t: (None, ("INTERNAL_ERROR", "자연어 해석기 호출 실패")),
        recommend=_recommend)
    app = FastAPI()
    internal_api.install(app, fake)
    r = TestClient(app).post("/internal/recommendations/search",
                             json={"recommendation_id": 1, "user_id": 1,
                                   "query": "조용한 피아노 음악"})
    assert r.status_code == 500, r.text
    assert r.json()["error_code"] == "INTERNAL_ERROR"
    assert not called, "조건을 못 뽑았는데 추천을 계산했다"
