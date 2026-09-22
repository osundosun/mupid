"""모델·색인 없이 도는 계약 테스트.

`test_smoke.py` 는 산출물이 있어야 의미가 있어 CI 에서 통째로 건너뛴다. 그러면 경로 해석,
오류 코드, 응답 문장 같은 **산출물과 무관한 회귀**를 아무도 못 잡는다. 여기서는 파일을
읽지 않는 것만 본다.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

import pytest

# 전처리 패키지는 설치돼 있지 않다. 다른 시험 모듈(`test_analysis_batch` 등)이 이
# 줄을 넣어 주지만, **그 모듈이 먼저 수집됐을 때만** 그렇다. 여기서도 넣어 두어야
# 이 파일 하나만 돌릴 때와 전체를 돌릴 때가 같아진다.
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "music_preprocessing" / "src"))

from ai_server.app import ids, internal_api, paths    # noqa: E402


@pytest.fixture(autouse=True)
def restore_service_module():
    """`install()` 은 전역 `_SVC_MOD` 를 덮어쓴다. 가짜 모듈이 뒤 테스트로 새면
    실패 원인이 엉뚱한 곳에서 나타난다."""
    saved = internal_api._SVC_MOD
    yield
    internal_api._SVC_MOD = saved


@pytest.fixture
def clean_env(monkeypatch):
    # 상태 디렉터리도 함께 지운다. 남아 있으면 라벨·이벤트 로그만 딴 곳을 가리켜
    # **로컬에 실산출물이 있는 사람에게서만** 시험이 깨진다.
    for k in ("MUPID_AI_ARTIFACT_DIR", "MUPID_ID_MAP", "MUPID_AI_STATE_DIR"):
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


# ------------------------------------------------------------------ 응답 경계
def test_to_response_carries_only_the_contract_fields():
    """추천기 내부 문자열·점수·진단이 응답으로 새지 않는지 본다.

    숫자 ID·시간·구간 종류·문구는 **애초에 싣지 않는다** — Spring 이 `external_id` 로
    MySQL 에서 가져온다. 두 벌이 되면 어긋날 자리만 생긴다.
    """
    out = {
        "diagnostics": {"query_source": "labels", "shortfall": 0},
        "results": [{
            "rank": 1, "track_id": "JAMENDO_1083659", "moment_id": "JAMENDO_1083659#2",
            "section_label": "chorus", "start_sec": 79.49, "end_sec": 92.74,
            "final_score": 1.0, "matched_dna": "u#dna3",
            "matched_dna_rep": "JAMENDO_1083659#3", "slot_source": "dna3",
            "clap_score": None, "prob": 0.634, "model_score": 0.551,
            "moment_similarity": 0.812,
            "reason": "내 선호 구간 묶음 u#dna3(대표 …)와 유사도 0.812 · 확률 0.634",
        }],
    }
    resp = internal_api.to_response(5001, out, 10, False)
    item = resp.items[0]
    assert item.external_id == "JAMENDO_1083659#2"
    assert item.ref_external_id == "JAMENDO_1083659#3"
    assert item.reason_code == "SIMILAR_PREFERRED_MOMENT"
    assert item.rank_order == 1 and item.score == 1.0

    blob = resp.model_dump_json()
    for leaked in ("prob", "model_score", "moment_similarity", "matched_dna",
                   "slot_source", "diagnostics", "dna3",
                   "0.812", "0.634", "0.551"):     # 모델 수치는 내보내지 않는다
        assert leaked not in blob, leaked
    assert "reason" not in blob.replace("reason_code", ""), "내부 문구가 실렸다"


def test_to_response_keeps_one_section_per_track():
    """같은 곡이 두 번 나오면 목록이 한 곡으로 채워진다. 점수가 높은 구간만 남긴다."""
    out = {"diagnostics": {"shortfall": 0}, "results": [
        {"rank": 1, "moment_id": "T1#0", "final_score": 0.5},
        {"rank": 2, "moment_id": "T1#3", "final_score": 0.9},
        {"rank": 3, "moment_id": "T2#1", "final_score": 0.4},
    ]}
    resp = internal_api.to_response(1, out, 3, False)
    assert [i.external_id for i in resp.items] == ["T1#3", "T2#1"]
    assert [i.rank_order for i in resp.items] == [1, 2]
    assert resp.shortfall == 1, "떨군 만큼 부족분에 적어야 한다"


def test_track_id_is_the_part_before_the_hash():
    """곡 문자열 ID 는 구간 UID 의 `#` 앞이다 (Q3)."""
    assert internal_api._track_of("JAMENDO_1083659#2") == "JAMENDO_1083659"
    assert internal_api._track_of("T000901") == "T000901"


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


# ------------------------------------------------------------------ 태그 어휘
def _fake_facets_svc():
    """`catalog_facets()` 모양만 흉내 낸다. 산출물을 읽지 않는다."""
    from types import SimpleNamespace

    import pandas as pd

    df = pd.DataFrame([{"track_id": "T1", "bpm": 90.0}, {"track_id": "T2", "bpm": 140.0}])
    fac = {
        "language": [{"value": "ko", "label": "ko", "tracks": 2}],
        "vocal": [{"value": "female", "label": "female", "tracks": 1}],
        "style": [{"value": "ko_ballad", "label": "ko_ballad", "tracks": 1}],
        "genre": [{"value": "pop", "label": "pop", "tracks": 2}],
        "mood": [{"value": "relaxing", "label": "relaxing", "tracks": 2}],
        "instrument": [{"value": "piano", "label": "piano", "tracks": 2}],
        "tempo": [], "section_label": [{"value": "chorus", "label": "chorus", "moments": 4}],
        "_df": df,
    }
    pool = pd.DataFrame([{"track_id": "T1"}, {"track_id": "T2"}, {"track_id": "T2"}])
    return SimpleNamespace(catalog_facets=lambda: fac, CAT=SimpleNamespace(pool=pool))


@pytest.fixture
def facets_payload(monkeypatch):
    monkeypatch.setattr(internal_api, "_FACETS_CACHE", {})
    return internal_api._facets_payload(_fake_facets_svc())


def test_facets_reports_catalog_vocabulary(facets_payload):
    assert facets_payload["catalog"] == {"tracks": 2, "moments": 3}
    genre = facets_payload["fields"]["genre"]
    assert {"value": "pop", "tracks": 2} in genre["values"]
    # 장르 게이트는 style 에도 맞춰 본다 — 어휘를 함께 알려야 한다.
    assert {"value": "ko_ballad", "tracks": 1} in genre["style_values"]
    assert facets_payload["fields"]["flow"]["values"][0]["value"] == "chorus"
    assert facets_payload["fields"]["bpm_min"]["range"] == [90, 140]


def test_facets_states_how_each_field_is_enforced(facets_payload):
    """`mood` 로 거를 수 있다고 믿게 두면 조건이 조용히 사라진다."""
    f = facets_payload["fields"]
    assert f["mood"]["enforcement"] == "score_only"
    assert f["mood"]["excludable"] is True          # exclude.mood 는 게이트다
    assert f["genre"]["enforcement"] == "gate"
    assert f["activity"]["enforcement"] == "ignored"
    assert f["tempo_direction"]["enforcement"] == "gate_with_reference"


def test_facets_fields_match_the_recommender_plan_keys():
    """어휘 표가 추천기가 실제로 읽는 키와 어긋나면 LLM 이 헛 조건을 만든다."""
    src = (Path(__file__).resolve().parents[1] / "app" / "recommender.py").read_text(
        encoding="utf-8")
    body = src[src.index("def filter_query_pool"):src.index("def _flow_penalty")]
    for name in internal_api._ENFORCEMENT:
        assert f'"{name}"' in body, name


def test_facets_payload_is_json_safe(facets_payload):
    """`catalog_facets()` 는 DataFrame(`_df`)을 함께 돌려준다. 새면 직렬화가 터진다."""
    import json

    json.dumps(facets_payload)
    assert "_df" not in json.dumps(facets_payload)


def test_catalog_tags_match_service_vocal_fields_and_hide_missing_values():
    from ai_server.app import recommender

    instrumental = recommender._catalog_tags({
        "has_vocal": False, "vocal_gender": "F",
        "genre_code": "unknown", "mood_codes": "unknown,calm",
        "instrument_tags": ["그 외 악기", "드럼", "unknown", "드럼"],
    })
    assert instrumental == {
        "genre": "", "genre_full": "", "moods": ["calm"],
        "instruments": ["드럼"], "has_vocal": False, "vocal_gender": None,
    }
    assert recommender._vocal_values(False, None) == ["instrumental"]

    vocal = recommender._catalog_tags({"has_vocal": True, "vocal_gender": "F"})
    assert vocal["has_vocal"] is True and vocal["vocal_gender"] == "F"


# --------------------------------------------- 악기 코드: 한국어·영문 양쪽을 거른다

def test_nonspecific_instrument_is_dropped_under_either_code_name():
    """`그 외 악기` 가 `other` 로 바뀌어도 필터가 살아 있어야 한다.

    코드표 정리(S15P21A204-407)로 `codes` 의 INSTRUMENT 네 행이 한국어 `code_name`
    에서 영문으로 바뀐다. 한쪽만 보면 마이그레이션 전후 어느 한쪽에서 필터가 통째로
    풀리고, 실측 9,292행(`music_analysis_instruments` 의 31%)이 "구체적인 악기" 로
    검색에 노출된다. 오류는 나지 않는다 — 결과만 나빠진다.
    """
    from ai_server.app import recommender

    for name in ("그 외 악기", "other", "Other", "OTHER"):
        got = recommender._instrument_list(["드럼", name, "베이스"])
        assert got == ["드럼", "베이스"], (name, got)


def test_missing_markers_are_still_dropped_from_instruments():
    from ai_server.app import recommender

    got = recommender._instrument_list(["unknown", "nan", "null", "", "드럼", "드럼"])
    assert got == ["드럼"]


def test_instrument_other_never_touches_the_gender_axis():
    """악기 `other` 와 보컬 성별은 **다른 축이다.** 한 집합으로 합치면 안 된다.

    예전에는 성별 어휘에도 `other`(값 `ETC`)가 있어서 두 축이 이름으로 충돌했다.
    S15P21A204-402 이후 성별은 서버 enum 과 같은 `M`·`F`·`MIX` 뿐이라 그 충돌은
    구조적으로 사라졌다 — 그래서 특정 키를 검사하지 않고 **교집합이 비어 있는지**를
    본다. 어휘가 또 바뀌어도 이 시험은 그대로 뜻을 지킨다.
    """
    from ai_server.app import recommender

    assert not (recommender._NONSPECIFIC_INSTRUMENTS
                & set(recommender._VOCAL_GENDER)), "두 축이 이름으로 겹친다"
    tags = recommender._catalog_tags({"has_vocal": True, "gender_label": "mixed",
                                      "instrument_tags": ["other", "드럼"]})
    assert tags["vocal_gender"] == "MIX", "축을 섞었다"
    assert tags["instruments"] == ["드럼"]


def test_nonspecific_list_matches_the_preprocessing_canon():
    """정본은 `instruments.STEMS` 하나다. 두 벌이 어긋나면 조용히 새 코드가 생긴다."""
    from ai_server.app import recommender

    ins = pytest.importorskip(
        "music_preprocessing.analysis.instruments",
        reason="analysis 선택 의존성이 없다")

    assert ins.CODE_NAMES == ("vocals", "drums", "bass", "other")
    drop = recommender._NONSPECIFIC_INSTRUMENTS
    # 정본의 `other` 는 code_name 과 display_name 둘 다 제외 집합에 있어야 한다.
    assert "other" in drop and ins.DISPLAY_NAMES["other"] in drop
    # 나머지 셋은 구체적인 악기다. 하나라도 걸리면 검색 근거가 사라진다.
    for stem in ("vocals", "drums", "bass"):
        assert stem not in drop and ins.DISPLAY_NAMES[stem] not in drop
    assert recommender._vocal_values(True, "F") == ["voice", "female"]

    missing = recommender._catalog_tags({
        "has_vocal": "unknown", "vocal_gender": "unknown",
        "mood_tags": "<NA>", "instrument_tags": "unknown",
    })
    assert missing["has_vocal"] is None and missing["vocal_gender"] is None
    assert missing["moods"] == [] and missing["instruments"] == []


def test_register_analysis_never_stores_gender_for_instrumental():
    from ai_server.tools import register_analysis

    assert register_analysis.payload_from("instrumental", "female") == (
        {"has_vocal": False, "vocal_gender": None}, "")
    assert register_analysis.payload_from("voice", "female") == (
        {"has_vocal": True, "vocal_gender": "F"}, "")
    body, why = register_analysis.payload_from("unknown", "unknown")
    assert body is None and "unknown" in why


def test_register_analysis_maps_every_gender_label_the_erd_enum_has():
    """`ENUM('M','F','MIX')` 세 값이 모두 나갈 수 있어야 한다.

    혼성 곡이 성별 없이 등록돼 있던 원인이 `GENDER_BY_LABEL` 에 `mixed` 가 없어
    `.get()` 이 조용히 `None` 을 돌려준 것이었다(S15P21A204-414).
    """
    from ai_server.tools import register_analysis as ra

    assert ra.payload_from("voice", "female") == (
        {"has_vocal": True, "vocal_gender": "F"}, "")
    assert ra.payload_from("voice", "male") == (
        {"has_vocal": True, "vocal_gender": "M"}, "")
    assert ra.payload_from("voice", "mixed") == (
        {"has_vocal": True, "vocal_gender": "MIX"}, "")
    assert ra.payload_from("voice", "mix") == (
        {"has_vocal": True, "vocal_gender": "MIX"}, "")

    # `unknown` 은 "모른다" 다. 혼성으로 승격하지 않는다.
    assert ra.payload_from("voice", "unknown") == (
        {"has_vocal": True, "vocal_gender": None}, "")
    # `other`("남/여로 판정 못 함") 도 마찬가지다.
    assert ra.payload_from("voice", "other") == (
        {"has_vocal": True, "vocal_gender": None}, "")

    # 연주곡은 성별 분류기가 무엇을 냈든 성별을 남기지 않는다.
    for label in ("female", "male", "mixed", "unknown", ""):
        assert ra.payload_from("instrumental", label) == (
            {"has_vocal": False, "vocal_gender": None}, "")

    # 와이어는 snake_case 다. camelCase 로 보내면 400 이 난다.
    body, _ = ra.payload_from("voice", "mixed")
    assert set(body) == {"has_vocal", "vocal_gender"}

    # 이 도구가 내보내는 성별은 ERD 의 enum 안에만 있어야 한다.
    assert set(ra.GENDER_BY_LABEL.values()) <= {"M", "F", "MIX"}


def test_catalog_gender_vocabulary_matches_the_server_enum():
    """`ETC` 는 S15P21A204-402 이후 존재하지 않는다. 카탈로그도 `MIX` 를 쓴다."""
    from ai_server.app import recommender

    assert set(recommender._VOCAL_GENDER.values()) <= {"M", "F", "MIX"}
    assert recommender._catalog_tags(
        {"has_vocal": True, "gender_label": "mixed"})["vocal_gender"] == "MIX"
    # `vocal_type` 만 있는 카탈로그 행도 같은 값으로 읽힌다.
    assert recommender._catalog_tags(
        {"vocal_type": "mixed"})["vocal_gender"] == "MIX"
    # 판정하지 못한 보컬은 비운다 — 혼성으로 올리지 않는다.
    assert recommender._catalog_tags(
        {"has_vocal": True, "gender_label": "other"})["vocal_gender"] is None

    assert recommender._vocal_values(True, "MIX") == ["voice", "mixed"]
    # 검색 어휘와 category 접두어가 같아야 스타일이 남는다.
    assert recommender.parse_style(
        "mixed_korean_pop_ballad", ["voice", "mixed"], "korean") == "pop_ballad"


# ------------------------------------------------------------------ 순위 불변
def _old_blend(v, clap_active, clap_pct, bdelta, pct, CW):
    """수정 전 식 그대로. 동결 확인용 기준이다."""
    return ((1.0 - CW) * pct(v) + CW * clap_pct if clap_active else pct(v)) + bdelta


def test_soft_rerank_is_identical_without_mood():
    """mood 조건이 없으면 순위 계산이 수정 전과 **비트 단위로** 같아야 한다."""
    import numpy as np

    from ai_server.app import recommender as rc

    rng = np.random.default_rng(0)
    for clap_active in (False, True):
        v = rng.random(64).astype(np.float32)
        clap_pct = rng.random(64).astype(np.float32)
        bdelta = rng.random(64).astype(np.float32) * 0.01
        new = rc.soft_rerank(v, clap_active, clap_pct, None) + bdelta
        old = _old_blend(v, clap_active, clap_pct, bdelta, rc.pct, rc.CLAP_WEIGHT)
        assert np.array_equal(new, old), clap_active
        assert list(np.argsort(-new)) == list(np.argsort(-old))


def test_mood_rerank_lifts_near_candidates_but_cannot_overturn():
    """가까운 후보는 추월한다. 그러나 순위를 뒤집지는 않는다 — soft 의 뜻이 그것이다."""
    import numpy as np

    from ai_server.app import recommender as rc

    v = np.array([0.9, 0.8, 0.7, 0.6, 0.5], dtype=np.float32)   # pct → 1, .75, .5, .25, 0
    match = np.zeros(5, dtype=np.float32)
    match[3] = 1.0
    out = rc.soft_rerank(v, False, None, match)
    order = list(np.argsort(-out))

    assert len(out) == len(v), "재정렬은 후보를 버리지 않는다"
    assert order.index(3) < order.index(2), "바로 앞 후보는 추월한다"
    assert order[0] == 0, "가장 잘 맞던 후보를 밀어내지 않는다"
    assert order.index(3) > order.index(1), "MOOD_WEIGHT 로는 큰 격차를 뒤집지 못한다"


def test_mood_match_ratio_counts_requested_tags():
    import pandas as pd

    from ai_server.app import recommender as rc

    fac = pd.DataFrame([
        {"track_id": "T1", "moods": ["relaxing", "meditative"]},
        {"track_id": "T2", "moods": ["aggressive"]},
    ]).set_index("track_id")
    pool = pd.DataFrame([{"track_id": "T1"}, {"track_id": "T2"}, {"track_id": "T3"}])
    r = rc.mood_match_ratio(pool, ["relaxing", "meditative"], fac)
    assert list(r) == [1.0, 0.0, 0.0]          # T3 은 카탈로그에 없다 → 0
    assert list(rc.mood_match_ratio(pool, ["relaxing", "sad"], fac)) == [0.5, 0.0, 0.0]


# ------------------------------------------------------------------ query_plan
def test_search_requires_query_or_plan():
    from ai_server.app.internal_api import SearchRequest

    with pytest.raises(Exception):
        SearchRequest(recommendation_id=1, user_id=1)


def test_query_plan_keeps_only_readable_keys():
    from ai_server.app.internal_api import SearchRequest

    req = SearchRequest(recommendation_id=1, user_id=1, query_plan={
        "filters": {"genre": ["pop"], "_clap_embedding": [0.1, 0.2]},
        "exclude": {"mood": ["aggressive"]},
        "junk": 1})
    assert req.query_plan == {"filters": {"genre": ["pop"]},
                              "exclude": {"mood": ["aggressive"]}}


def test_query_plan_rejects_embedding_injection():
    """의미 점수 벡터는 내부 전용이다. 바깥에서 주입하지 못한다."""
    from ai_server.app.internal_api import SearchRequest

    req = SearchRequest(recommendation_id=1, user_id=1,
                        query_plan={"filters": {"_clap_embedding": [1.0]},
                                    "exclude": {"genre": ["rock"]}})
    assert "_clap_embedding" not in req.query_plan["filters"]


def test_query_plan_path_does_not_need_interpreter():
    """A안: 호출자가 QueryPlan 을 보내면 해석기 없이도 추천이 나간다."""
    from types import SimpleNamespace

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    seen: dict = {}

    def _recommend(user, n, exclude, explore, plan, ct, cm, **kw):
        seen["plan"] = plan
        seen.update(kw)      # 주입 인자가 늘어도 이 가짜는 깨지지 않는다
        return {"results": [], "diagnostics": {"shortfall": 0}}

    fake = SimpleNamespace(NLP_PARSE_URL="", CAT=SimpleNamespace(pool=[1]),
                           recommend=_recommend,
                           parse_nlp_query=lambda t: (None, ("INTERNAL_ERROR", "불려선 안 된다")))
    app = FastAPI()
    internal_api.install(app, fake)
    r = TestClient(app).post("/internal/recommendations/search",
                             json={"recommendation_id": 1, "user_id": 1,
                                   "query_plan": {"filters": {"genre": ["pop"]}}})
    assert r.status_code == 404, r.text          # 후보 0 → NO_CANDIDATES (501 아님)
    assert r.json()["error_code"] == "NO_CANDIDATES"
    assert seen["plan"] == {"filters": {"genre": ["pop"]}}
    # ClickHouse 주소가 없으면 None 이고, 그때는 종전의 파일 경로로 간다.
    assert "beh_events" in seen


# ------------------------------------------------------- 오늘의 모멘트는 4개다

def _daily_probe():
    """`daily` 가 추천기에 넘기는 개수를 들여다보는 가짜 서비스."""
    from types import SimpleNamespace

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    seen: dict = {}

    def _recommend(user, n, exclude, explore, plan, ct, cm, **kw):
        seen["n"] = n
        return {"results": [], "diagnostics": {"shortfall": 0}}

    fake = SimpleNamespace(NLP_PARSE_URL="", CAT=SimpleNamespace(pool=[1]),
                           recommend=_recommend)
    app = FastAPI()
    internal_api.install(app, fake)
    return TestClient(app), seen


def test_daily_asks_for_exactly_four():
    """`limit` 을 안 보내면 4다. 화면이 아니라 서버가 그날의 크기를 정한다."""
    client, seen = _daily_probe()
    client.post("/internal/recommendations/daily",
                json={"recommendation_id": 1, "user_id": 1})
    assert seen["n"] == internal_api.DAILY_LIMIT == 4


def test_daily_accepts_the_fixed_count_spelled_out():
    """이미 `limit: 4` 를 보내던 호출자를 깨뜨리지 않는다."""
    client, seen = _daily_probe()
    r = client.post("/internal/recommendations/daily",
                    json={"recommendation_id": 1, "user_id": 1, "limit": 4})
    assert r.status_code == 404, r.text          # 후보 0 → NO_CANDIDATES
    assert seen["n"] == 4


def test_daily_refuses_any_other_count():
    """조용히 4로 깎지 않는다.

    깎으면 10을 요청한 Spring 이 4를 받고도 그것이 전부인 줄 알고, 빈 6칸을 AI 쪽
    후보 부족으로 읽는다. 그 오해는 며칠 뒤 적재된 데이터에서나 드러난다.
    """
    client, seen = _daily_probe()
    for n in (1, 3, 5, 10, 50):
        r = client.post("/internal/recommendations/daily",
                        json={"recommendation_id": 1, "user_id": 1, "limit": n})
        assert r.status_code == 400, (n, r.text)
        assert r.json()["error_code"] == "INVALID_REQUEST"
    assert "n" not in seen, "거부했는데 추천을 계산했다"


def test_next_and_search_still_choose_their_own_count():
    """고정은 `daily` 만이다. 이어 듣기·검색까지 4로 묶으면 다른 화면이 망가진다."""
    assert internal_api.NextRequest(recommendation_id=1, user_id=1, limit=10).limit == 10
    assert internal_api.SearchRequest(recommendation_id=1, user_id=1, query="잔잔한",
                                      limit=20).limit == 20


# ------------------------------------------- 악기 코드: 확정 목록 밖은 만들지 않는다

def test_instrument_codes_outside_the_canon_are_refused():
    """`POST /api/v1/code` 는 없는 이름을 받으면 **새 코드를 만든다.**

    한번 생긴 코드는 사람이 지워야 한다 — 분위기에서 두 번 늘었다(98~106, 107~123).
    그래서 확정 목록 밖의 이름은 올리지 않고 멈춘다.
    """
    pytest.importorskip("music_preprocessing.analysis.instruments",
                        reason="analysis 선택 의존성이 없다")
    from ai_server.tools import register_sections as rs

    with pytest.raises(SystemExit) as e:
        rs.add_instrument_codes({"drums", "piano"})
    msg = str(e.value)
    # 무엇이 문제인지 이름으로 말한다. 정본도 함께 보여 준다.
    assert "['piano']" in msg, msg
    assert "vocals" in msg and "bass" in msg, msg


def test_batch_registrar_refuses_the_same_way():
    """도구와 배치가 같은 정본을 본다. 한쪽만 막으면 다른 길로 코드가 는다."""
    ai = pytest.importorskip("music_preprocessing.analysis.api",
                             reason="analysis 선택 의존성이 없다")

    class _T:
        track_id = "T000001"
        sections = [{"instruments": ["drums", "그 외 악기"]}]

    reg = ai.HttpRegistrar(client=object())
    with pytest.raises(ValueError) as e:
        reg.ensure_instrument_codes([_T()])
    # 한국어 표시명은 `code_name` 이 아니다 — 그대로 올리면 코드가 하나 더 생긴다.
    assert "그 외 악기" in str(e.value)


# ------------------------------------------- 무드 코드: 못 옮긴 태그를 남긴다

def test_unmapped_mood_tags_are_counted_not_swallowed():
    """빠뜨린 태그가 "분위기 없는 곡" 으로 섞이면 코드표 누락이 안 보인다."""
    from collections import Counter

    from ai_server.tools.register_sections import moods_of

    un: Counter = Counter()
    got = moods_of(["relaxing", "christmas", "film", "christmas"], un)

    assert got == ["serene"], got            # relaxing → serene
    assert un == {"christmas": 2, "film": 1}


def test_moods_of_still_works_without_a_collector():
    """세는 자리를 안 주면 종전과 똑같이 돈다 — 부르는 쪽을 다 고치지 않는다."""
    from ai_server.tools.register_sections import moods_of

    assert moods_of(["relaxing", "christmas"]) == ["serene"]


def test_mood_codes_cover_every_code_in_the_db_table():
    """코드표 17종이 전부 AI 에서 해석돼야 한다. 하나라도 빠지면 조용히 무시된다."""
    from ai_server.app import onboarding
    from ai_server.tools.register_sections import MOOD_BY_TAG

    assert set(onboarding.MOOD_MAP) == set(onboarding.MOOD_NAMES), \
        "이름표와 태그표의 코드 집합이 어긋났다"
    assert len(onboarding.MOOD_NAMES) == 17
    # 역방향 표가 코드 17종을 모두 돌려줄 수 있어야 한다.
    assert set(MOOD_BY_TAG.values()) <= set(onboarding.MOOD_MAP)


# ------------------------------------ 온보딩: 못 옮긴 축을 세어 둔다

def _onboarding_probe(catalog: list, moods: list, genres: list) -> dict:
    from types import SimpleNamespace

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from ai_server.app import metrics

    metrics.reset()
    app = FastAPI()
    internal_api.install(app, SimpleNamespace(
        NLP_PARSE_URL="", CAT=SimpleNamespace(pool=[1]),
        recommend=lambda *a, **k: {"results": [], "diagnostics": {"shortfall": 0}},
        catalog_facets=lambda: {"genre": [{"value": v} for v in catalog],
                                "style": []}))
    TestClient(app).post("/internal/recommendations/daily",
                         json={"recommendation_id": 1, "user_id": 1,
                               "onboarding": {"moods": moods, "genres": genres}})
    return metrics.snapshot()["onboarding_unmapped"]


def test_genre_axis_alone_failing_is_counted():
    """무드는 되는데 장르만 안 되는 경우. 전환 뒤에는 **대분류**가 여기 든다.

    대분류 8종(code_id 9~16)은 `music_genres` 에 곡이 0개라 카탈로그에 없다.
    그 축만 빈 채 추천은 정상으로 나가므로 응답으로는 찾을 수 없다. 둘 다 비었을
    때만 보던 예전 판은 이 경우를 놓쳤다.
    """
    got = _onboarding_probe(["alternative_rock"],
                            moods=["고요한"], genres=["Rock"])
    assert got == {"genre": 1}, got


def test_a_genre_that_does_match_is_not_counted():
    got = _onboarding_probe(["ambient", "indie_rock"],
                            moods=["고요한"], genres=["ambient"])
    assert got == {}, got


def test_both_axes_failing_are_counted_separately():
    got = _onboarding_probe([], moods=["없는무드"], genres=["없는장르"])
    # 두 축이 함께 죽은 경우는 `both` 로 한 번 더 센다 — 축별 셈만으로는 같은
    # 사용자인지 서로 다른 두 사용자인지 알 수 없고, 같은 사용자면 씨앗이 통째로 없다.
    assert got == {"mood": 1, "genre": 1, "both": 1}, got


def test_an_axis_that_was_not_asked_for_is_not_counted():
    """안 고른 축은 실패가 아니다. 세면 지표가 늘 켜져 있어 쓸모가 없어진다."""
    got = _onboarding_probe(["ambient"], moods=[], genres=["ambient"])
    assert got == {}, got


# ------------------------------------------- 행동 출처 네 상태 (합성 응답)

@pytest.fixture
def behavior_env(monkeypatch):
    """`_behavior_events` 가 보는 것만 바꾼다. 실제 ClickHouse 는 쓰지 않는다."""
    from ai_server.app import clickhouse, metrics

    metrics.reset()
    monkeypatch.setattr(clickhouse, "configured", lambda: True)
    return monkeypatch, metrics, clickhouse


def _source(metrics) -> str:
    got = metrics.snapshot()["behavior_source"]
    assert sum(got.values()) == 1, got
    return next(iter(got))


@pytest.mark.parametrize("broken", ["unavailable", "mapping_empty"])
def test_behavior_failure_falls_back_to_dna_not_to_the_stale_file(behavior_env, broken):
    """행동을 못 읽어도 **추천은 나간다.** 최근 행동만 빠지고 DNA 는 그대로다.

    두 가지를 함께 본다.

    · `None` 이 아니라 `[]` 를 넘긴다 — `None` 이면 추천기가 **옛 파일 로그**를 읽어
      ClickHouse 가 죽은 날에 몇 달 전 행동으로 개인화한다. 그쪽이 더 나쁘다.
      비어 있으면 라벨(DNA)·온보딩만으로 계산한다.
    · 그래도 `recommend` 를 부른다 — 여기서 빠져나가면 장애가 곧 추천 중단이 된다.
    """
    from types import SimpleNamespace

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    mp, metrics, ch = behavior_env
    if broken == "unavailable":
        mp.setattr(ch, "behavior_events",
                   lambda u, s: (_ for _ in ()).throw(ConnectionError("죽었다")))
    else:
        mp.setattr(ch, "behavior_events", lambda u, s: [])
        mp.setattr(ch, "raw_event_count", lambda u, s: 5)

    seen: dict = {}

    def _recommend(user, n, exclude, explore, plan, ct, cm, **kw):
        seen.update(kw)
        return {"results": [], "diagnostics": {"shortfall": 0}}

    app = FastAPI()
    internal_api.install(app, SimpleNamespace(
        NLP_PARSE_URL="", CAT=SimpleNamespace(pool=[1]), recommend=_recommend))
    TestClient(app).post("/internal/recommendations/daily",
                         json={"recommendation_id": 1, "user_id": 1})

    assert "beh_events" in seen, "추천을 아예 부르지 않았다"
    assert seen["beh_events"] == [], \
        f"{broken} 인데 {seen['beh_events']!r} 을 넘겼다 — None 이면 옛 파일을 읽는다"
    assert _source(metrics) == broken


def test_behavior_source_file_when_clickhouse_is_not_configured(behavior_env):
    mp, metrics, ch = behavior_env
    mp.setattr(ch, "configured", lambda: False)
    assert internal_api._behavior_events(1) is None
    assert _source(metrics) == "file"


def test_behavior_source_unavailable_when_the_query_blows_up(behavior_env):
    mp, metrics, ch = behavior_env

    def _boom(*a, **k):
        raise ConnectionError("ch-01 에 닿지 못했다")

    mp.setattr(ch, "behavior_events", _boom)
    assert internal_api._behavior_events(1) == []
    assert _source(metrics) == "unavailable"


def test_behavior_source_clickhouse_when_rows_come_back(behavior_env):
    """정상 경로에는 쿼리를 늘리지 않는다."""
    mp, metrics, ch = behavior_env
    called = {"raw": 0}

    mp.setattr(ch, "behavior_events", lambda u, s: [{"event_type": "PLAY_END"}])
    mp.setattr(ch, "raw_event_count", lambda u, s: called.__setitem__("raw", 1) or 9)

    assert internal_api._behavior_events(1) == [{"event_type": "PLAY_END"}]
    assert _source(metrics) == "clickhouse"
    assert called["raw"] == 0, "1행 이상인데 원본을 또 조회했다"


def test_behavior_source_clickhouse_when_the_user_really_has_nothing(behavior_env):
    """조인도 0, 원본도 0 — 이건 진짜로 안 들은 것이다."""
    mp, metrics, ch = behavior_env
    mp.setattr(ch, "behavior_events", lambda u, s: [])
    mp.setattr(ch, "raw_event_count", lambda u, s: 0)

    assert internal_api._behavior_events(1) == []
    assert _source(metrics) == "clickhouse"


def test_behavior_source_mapping_empty_when_the_join_loses_everything(behavior_env):
    """원본은 있는데 조인이 0이다. **행동 없음으로 처리하지 않는다.**

    `v_track_sections_current` 가 그 곡을 되돌리지 못한 것이고, 한 사람이 아니라
    전 사용자의 개인화가 빠진다. 콜드스타트 비율이 조금 오른 것처럼만 보인다.
    """
    mp, metrics, ch = behavior_env
    mp.setattr(ch, "behavior_events", lambda u, s: [])
    mp.setattr(ch, "raw_event_count", lambda u, s: 137)

    assert internal_api._behavior_events(1) == []
    assert _source(metrics) == "mapping_empty"


def test_mapping_empty_is_not_claimed_when_the_probe_itself_fails(behavior_env):
    """세지 못했으면 **주장하지 않는다.** 조인은 성공했으므로 clickhouse 다."""
    mp, metrics, ch = behavior_env

    def _boom(*a, **k):
        raise ConnectionError("두 번째 조회가 실패했다")

    mp.setattr(ch, "behavior_events", lambda u, s: [])
    mp.setattr(ch, "raw_event_count", _boom)

    assert internal_api._behavior_events(1) == []
    assert _source(metrics) == "clickhouse"


def test_raw_event_count_does_not_join(behavior_env):
    """원본 조회는 조인을 타면 안 된다 — 그러면 같은 0을 두 번 얻는다."""
    mp, metrics, ch = behavior_env
    seen = {}

    mp.setattr(ch, "query", lambda sql, *a, **k: seen.setdefault("sql", sql) and "" or "0")
    ch.raw_event_count(15, 1_700_000_000.0)

    sql = seen["sql"]
    assert "listen_events" in sql
    assert "JOIN" not in sql.upper(), sql
    assert "v_track_sections_current" not in sql, sql
    assert "user_id = 15" in sql


# ------------------------------------------------------------------ 산출물 상태
def test_artifact_status_names_what_is_missing(clean_env, tmp_path):
    """없어도 서버는 뜬다. 그래서 무엇이 없는지 응답에 보여야 한다."""
    clean_env.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    from ai_server.app import main

    # 경로를 손으로 끼워 넣지 않는다. 환경변수만 바꿔도 따라와야 하고, 따라오지
    # 않으면 그것이 곧 결함이다 — 예전에는 여기서 `recommender.OUT_DIR` 을 덮어
    # 썼고, 덮지 않은 `LABELS` 가 import 시점 값으로 남아 시험이 import 순서에
    # 따라 달라졌다.
    st = main.artifact_status()
    assert "labels_service.parquet" in st["missing"]
    # 왜 필요한지도 함께 — 없다는 사실만으로는 무엇이 꺼졌는지 알 수 없다.
    assert st["files"]["labels_service.parquet"]["why"]


def test_id_map_is_reported_but_is_not_a_serving_gap(clean_env, tmp_path):
    """매핑표는 적재 도구 전용이다.

    `/internal/ready` 는 더 이상 매핑표를 보지 않는다 — 번역은 Spring 이 한다. 그런데도
    `missing` 에 남겨 두면 기동 로그가 배포마다 "선택 산출물 없음" 을 찍어, **정작
    개인화가 빠진 배포와 구분이 안 된다.** 상태는 싣되 결핍으로 세지 않는다.
    """
    clean_env.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    from ai_server.app import main

    st = main.artifact_status()
    assert st["files"]["id_map.parquet"]["exists"] is False
    assert "id_map.parquet" not in st["missing"]


def test_startup_uses_lifespan_not_deprecated_hook():
    from ai_server.app import main

    src = (Path(main.__file__)).read_text(encoding="utf-8")
    assert 'on_event("startup")' not in src
    assert main.app.router.lifespan_context is not None


def test_demo_only_schemas_are_gone():
    """데모 라우트가 없는 저장소다. 요청 스키마만 남아 있으면 계약을 오해하게 된다."""
    src = (Path(__file__).resolve().parents[1] / "app" / "recommender.py").read_text(
        encoding="utf-8")
    for name in ("class NextReq", "class FeedbackReq", "class OnboardReq", "class SimReq"):
        assert name not in src, name


# ------------------------------------------------------------------ 구간 라벨 ENUM
# 라벨 검사는 **적재 경계**로 옮겼다. ENUM 밖 라벨은 `register_sections` 가 MySQL 에
# 넣지 않으므로, 추천이 그 구간을 골라도 Spring 이 external_id 를 찾지 못해 떨어진다.
# 응답 경계에서 또 거를 이유가 없다 → test_register_sections_drops_labels_outside_the_erd_enum


def test_section_enum_matches_spring():
    """Spring 의 SectionLabel 과 어긋나면 그 차이가 곧 적재 실패다."""
    from ai_server.tools.push_sections import SECTION_ENUM

    java = (Path(__file__).resolve().parents[2] / "api/src/main/java/com/osundosun"
            / "mupid/api/music/enumerate/SectionLabel.java")
    if not java.exists():
        pytest.skip("api 모듈이 없는 체크아웃")
    names = set(re.findall(r"^\s{4}([A-Z_]+)\(", java.read_text(encoding="utf-8"),
                           flags=re.M))
    assert names == set(SECTION_ENUM), names ^ set(SECTION_ENUM)


# ------------------------------------------------------------------ 이름 해석
def _unresolved_globals(module) -> list[str]:
    """함수가 참조하는 전역 이름 중 어디에도 없는 것. 호출해야만 터지는 NameError 를
    호출하지 않고 찾아낸다 — `learn_incremental()` 이 실제로 그 상태였다."""
    import builtins
    import symtable

    src = Path(module.__file__).read_text(encoding="utf-8")
    known = set(vars(module)) | set(dir(builtins))
    bad: list[str] = []

    def walk(table, path: str):
        for child in table.get_children():
            here = f"{path}.{child.get_name()}"
            if child.get_type() == "function":
                for sym in child.get_symbols():
                    if sym.is_global() and not sym.is_assigned() \
                            and sym.get_name() not in known:
                        bad.append(f"{here}: {sym.get_name()}")
            walk(child, here)

    walk(symtable.symtable(src, module.__file__, "exec"), Path(module.__file__).stem)
    return bad


@pytest.mark.parametrize("name", ["recommender", "internal_api", "ids", "main", "paths",
                                 "labeler", "features", "stage1_torch"])
def test_every_referenced_global_exists(name):
    import importlib

    mod = importlib.import_module(f"ai_server.app.{name}")
    assert _unresolved_globals(mod) == []


def test_labeler_module_ships_with_the_code(clean_env):
    """라벨러는 산출물이 아니라 코드와 함께 온다.

    `run_labeler` 가 `python -m ai_server.app.labeler` 로 부르므로, 이 모듈을 import 할 수
    없으면 subprocess 가 조용히 실패한다 — 호출부가 반환값만 보고 "라벨 생성 실패" 로
    뭉뚱그리기 때문에 원인이 드러나지 않는다.
    """
    import importlib

    labeler = importlib.import_module("ai_server.app.labeler")

    assert hasattr(labeler, "main")
    assert isinstance(labeler.FAST_SKIP_SEC, float)


def test_learn_is_callable():
    from ai_server.app import recommender

    assert callable(recommender.learn)


# ------------------------------------------------------------------ 배포 가능성
def test_no_developer_home_paths_in_app():
    """개발자 홈을 가리키는 경로가 있으면 컨테이너에서 존재하지 않는 곳을 읽는다.
    경로는 MUPID_AI_ARTIFACT_DIR 하나에서만 나와야 한다."""
    for f in (Path(__file__).resolve().parents[1] / "app").rglob("*.py"):
        body = "\n".join(ln for ln in f.read_text(encoding="utf-8").splitlines()
                         if not ln.lstrip().startswith("#"))
        assert "Path.home()" not in body, f"{f.name}: 홈 디렉터리 경로"
        assert "os.path.expanduser" not in body, f.name


def test_env_files_are_ignored():
    """실제 값이 든 .env 는 무시되고, 예시만 커밋된다."""
    import subprocess

    root = Path(__file__).resolve().parents[2]
    patterns = root / ".gitignore"
    text = patterns.read_text(encoding="utf-8")
    assert ".env" in text and "!*.env.example" in text
    try:
        r = subprocess.run(["git", "check-ignore", "-q", "infra/deploy/.env"],
                           cwd=root, capture_output=True)
    except FileNotFoundError:
        # git 바이너리가 없는 경우. 이 테스트는 원래 "git 으로 확인할 수 없으면
        # 건너뛴다" 가 의도인데 아래 128(체크아웃이 아님)만 받고 있어서, git 이
        # 아예 없는 이미지에서는 건너뛰지 못하고 죽었다. CI 의 job 이미지에
        # git 이 없어 MR 이 연달아 실패했다.
        #
        # 검사를 없애는 것이 아니다 — git 이 있는 환경에서는 그대로 확인한다.
        pytest.skip("git 이 없다")
    if r.returncode == 128:
        pytest.skip("git 체크아웃이 아니다")
    assert r.returncode == 0, "infra/deploy/.env 가 무시되지 않는다"


# ------------------------------------------------------------------ 닮은 구간
def _similar_svc(rows):
    from types import SimpleNamespace

    return SimpleNamespace(CAT=SimpleNamespace(pool=[1]),
                           similar_moments=lambda uid, n, drop: rows[:n])


def _srow(track, idx, label="chorus", sim=0.8):
    return {"rank": 1, "track_id": track, "moment_id": f"{track}#{idx}",
            "section_label": label, "start_sec": 10.0, "end_sec": 25.0,
            "similarity": sim}


@pytest.fixture
def similar_client():
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    def build(rows):
        app = FastAPI()
        internal_api.install(app, _similar_svc(rows))
        return TestClient(app)

    return build


def test_similar_returns_string_ids(similar_client):
    """숫자 ID·시간·구간 종류를 싣지 않는다 — Spring 이 external_id 로 찾는다."""
    c = similar_client([_srow("B", 1, sim=0.91), _srow("C", 2, sim=0.77)])
    r = c.post("/internal/similar", json={"external_id": "A#0", "limit": 5})
    assert r.status_code == 200, r.text
    body = r.json()
    assert [i["external_id"] for i in body["items"]] == ["B#1", "C#2"]
    assert [i["rank_order"] for i in body["items"]] == [1, 2]
    assert body["external_id"] == "A#0", "기준 구간을 그대로 돌려준다"
    assert body["embedding_version"]
    for gone in ("music_id", "music_section_id", "music_section_analysis_id",
                 "start_time_ms", "section_label"):
        assert gone not in body["items"][0], gone


def test_similar_has_no_personalization_field(similar_client):
    """개인화가 없으므로 사유·점수 필드를 만들어 붙이지 않는다."""
    c = similar_client([_srow("B", 1)])
    body = c.post("/internal/similar", json={"external_id": "A#0"}).json()
    assert "reason" not in body["items"][0]
    assert "reason_code" not in body["items"][0]


def test_similar_reports_shortfall(similar_client):
    """후보가 모자라면 임의로 채우지 않고 부족분을 적는다."""
    c = similar_client([_srow("B", 1)])
    body = c.post("/internal/similar", json={"external_id": "A#0", "limit": 3}).json()
    assert len(body["items"]) == 1 and body["shortfall"] == 2


def test_similar_rejects_user_id(similar_client):
    """개인화 API 가 아니다. user_id 를 받으면 계약을 오해한 것이다."""
    c = similar_client([_srow("B", 1)])
    r = c.post("/internal/similar", json={"external_id": "A#0", "user_id": 1})
    assert r.status_code == 400


# ------------------------------------------------------------------ 운영 지표
@pytest.fixture
def fresh_metrics():
    from ai_server.app import metrics

    metrics.reset()
    yield metrics
    metrics.reset()


def test_cold_start_rate_counts_query_source(fresh_metrics):
    for src in ("labels", "labels", "onboarding", "none"):
        fresh_metrics.observe_query_source(src)
    snap = fresh_metrics.snapshot()
    assert snap["query_source"] == {"labels": 2, "onboarding": 1, "none": 1}
    assert snap["cold_start_rate"] == 0.25


def test_onboarding_does_not_inflate_cold_start(fresh_metrics):
    """온보딩으로 뽑은 추천은 콜드스타트가 아니다. 섞이면 지표가 배치 장애처럼 보인다."""
    fresh_metrics.observe_query_source("onboarding")
    assert fresh_metrics.snapshot()["cold_start_rate"] == 0.0


def test_latency_uses_percentiles_not_mean(fresh_metrics):
    for x in [0.01] * 99 + [5.0]:
        fresh_metrics.observe_request("/internal/recommendations/daily", x)
    blk = fresh_metrics.snapshot()["latency_seconds"]["/internal/recommendations/daily"]
    assert blk["p50"] == 0.01 and blk["max"] == 5.0
    assert blk["p95"] is not None
    assert "mean" not in blk, "평균은 느린 요청을 묻어 버린다"


def test_error_rate_and_codes(fresh_metrics):
    fresh_metrics.observe_request("/internal/similar", 0.1)
    fresh_metrics.observe_request("/internal/similar", 0.1, "HTTP_404")
    snap = fresh_metrics.snapshot()
    assert snap["errors"]["by_code"] == {"HTTP_404": 1}
    assert snap["errors"]["rate"] == 0.5


def test_metrics_endpoint_includes_ready(similar_client, fresh_metrics):
    c = similar_client([_srow("B", 1)])
    c.post("/internal/similar", json={"external_id": "A#0"})
    body = c.get("/internal/metrics").json()
    assert body["requests"]["total"] >= 1
    assert "/internal/similar" in body["requests"]["by_path"]
    assert "ready" in body, "실패율만 보고는 원인을 못 가른다"
    assert body["uptime_seconds"] >= 0


def test_metrics_snapshot_is_json_safe(fresh_metrics):
    import json

    fresh_metrics.observe_request("/internal/recommendations/next", 0.2)
    fresh_metrics.observe_reason("COLD_START")
    fresh_metrics.observe_behavior(1.5)
    json.dumps(fresh_metrics.snapshot())


# ------------------------------------------------------------------ 내부 호출 보호
def test_internal_is_open_by_default(similar_client, monkeypatch):
    """기본은 꺼져 있어야 한다. 켜 두고 Spring 에 넣어 주지 않으면 추천이 통째로 막힌다."""
    monkeypatch.delenv("MUPID_AI_INTERNAL_TOKEN", raising=False)
    c = similar_client([_srow("B", 1)])
    assert c.post("/internal/similar", json={"external_id": "A#0"}).status_code == 200


def test_internal_token_is_enforced_when_set(similar_client, monkeypatch):
    monkeypatch.setenv("MUPID_AI_INTERNAL_TOKEN", "s3cret")
    c = similar_client([_srow("B", 1)])
    body = {"external_id": "A#0"}

    r = c.post("/internal/similar", json=body)
    assert r.status_code == 401 and r.json()["error_code"] == "UNAUTHORIZED"
    assert "s3cret" not in r.text, "토큰을 응답에 되돌려 주지 않는다"

    r = c.post("/internal/similar", json=body, headers={"X-Internal-Token": "wrong"})
    assert r.status_code == 401

    r = c.post("/internal/similar", json=body, headers={"X-Internal-Token": "s3cret"})
    assert r.status_code == 200, r.text


def test_health_is_not_behind_the_token(monkeypatch):
    """/health 는 오케스트레이터가 부른다. 토큰 뒤에 두면 컨테이너가 죽는다."""
    monkeypatch.setenv("MUPID_AI_INTERNAL_TOKEN", "s3cret")
    from fastapi.testclient import TestClient

    from ai_server.app.main import app

    with TestClient(app) as c:
        assert c.get("/health").status_code == 200


def test_the_readiness_probe_is_not_behind_the_token(monkeypatch):
    """**kubelet 은 토큰을 보내지 않는다.**

    `/internal/ready` 를 인증 뒤에 두면 토큰을 켜는 순간 파드가 영영 Ready 가 되지
    못한다 — 실제로 겪었다. 새 Pod 가 0/1 로 멈추고 maxUnavailable 이 0 이라 배포가
    그대로 막힌 채, 옛 Pod 가 서빙해서 겉으로는 멀쩡해 보였다.

    준비되지 않았으면 503 이어도 된다. **401 이면 안 된다** — 그건 영원히 낫지
    않는 상태다.
    """
    monkeypatch.setenv("MUPID_AI_INTERNAL_TOKEN", "s3cret")
    from fastapi.testclient import TestClient

    from ai_server.app.main import app

    with TestClient(app) as c:
        assert c.get("/internal/ready").status_code != 401


def test_only_the_probe_path_is_public(monkeypatch):
    """면제는 딱 하나여야 한다. 넓히면 추천 API 가 같이 열린다."""
    monkeypatch.setenv("MUPID_AI_INTERNAL_TOKEN", "s3cret")
    from fastapi.testclient import TestClient

    from ai_server.app import internal_api
    from ai_server.app.main import app

    assert internal_api.PUBLIC_PATHS == frozenset({"/internal/ready"})
    with TestClient(app) as c:
        for path in ("/internal/metrics", "/internal/facets"):
            assert c.get(path).status_code == 401, path


# ------------------------------------------------------------------ 배포 파일
def test_dockerfile_does_not_install_torch():
    """서빙에 torch 가 필요 없다. 넣으면 이미지의 대부분을 차지한다."""
    root = Path(__file__).resolve().parents[2]
    df = (root / "ai_server" / "Dockerfile").read_text(encoding="utf-8")
    installs = [ln for ln in df.splitlines()
                if "torch==" in ln and not ln.lstrip().startswith("#")]
    assert not installs, installs


def test_dockerfile_excludes_artifacts():
    root = Path(__file__).resolve().parents[2]
    df = (root / "ai_server" / "Dockerfile").read_text(encoding="utf-8")
    assert "MUPID_AI_ARTIFACT_DIR" in df
    assert "/health" in df and "/internal/ready" not in df.split("HEALTHCHECK")[1], \
        "ready 를 헬스체크로 쓰면 매핑표가 없는 동안 재시작을 반복한다"
    ignore = (root / ".dockerignore").read_text(encoding="utf-8")
    for pat in ("node_modules", "**/.env", "!**/.env.example"):
        assert pat in ignore, pat


# ------------------------------------------------------------------ NumPy 인코더
def _torch_or_skip():
    return pytest.importorskip("torch", reason="torch 없이는 동치를 비교할 수 없다")


def _make_pair(in_dim=37, hist_max=8, seed=0):
    """무작위 가중치로 torch 모델과 NumPy 인코더 한 쌍을 만든다.

    **산출물이 필요 없다.** 동치는 가중치 값과 무관한 성질이므로 무작위로 확인할 수 있다.
    """
    torch = _torch_or_skip()

    from ai_server.app import stage1_np, stage1_torch as s1

    torch.manual_seed(seed)
    m = s1.make_model(in_dim)
    # gmean 은 학습 중에만 갱신되는 버퍼다. 0 이 아닌 값으로 두어야 뺄셈까지 검증된다.
    with torch.no_grad():
        m.gmean.copy_(torch.randn(m.gmean.shape) * 0.1)
    m.eval()
    w = stage1_np.from_state_dict(m.state_dict(), m.gmean.detach().numpy(), in_dim)
    return m, stage1_np.UserEncoder(w), in_dim, hist_max


def test_numpy_user_encoder_matches_torch():
    import numpy as np

    torch = _torch_or_skip()
    m, enc, in_dim, hist_max = _make_pair()
    rng = np.random.default_rng(1)
    worst = 0.0
    for _ in range(32):
        n = int(rng.integers(1, hist_max + 1))
        xh = rng.normal(size=(hist_max, in_dim)).astype("float32")
        mask = np.zeros(hist_max, dtype=bool)
        mask[:n] = True
        logw = np.log(np.clip(rng.random(hist_max), 1e-3, None)).astype("float32")
        with torch.no_grad():
            zt = m.enc_user(torch.tensor(xh)[None], torch.tensor(mask)[None],
                            logw=torch.tensor(logw)[None]).numpy()[0]
        worst = max(worst, float(np.abs(zt - enc.encode(xh, mask, logw)).max()))
    assert worst < 1e-5, f"최대 오차 {worst:.3e}"


def test_numpy_encoder_gives_identical_ranking():
    """이 벡터는 후보 행렬과의 내적으로만 쓰인다. 최종 기준은 순위 일치다."""
    import numpy as np

    torch = _torch_or_skip()
    m, enc, in_dim, hist_max = _make_pair(seed=3)
    rng = np.random.default_rng(2)
    for _ in range(16):
        xh = rng.normal(size=(hist_max, in_dim)).astype("float32")
        mask = np.ones(hist_max, dtype=bool)
        with torch.no_grad():
            zt = m.enc_user(torch.tensor(xh)[None], torch.tensor(mask)[None]).numpy()[0]
        zn = enc.encode(xh, mask, None)
        cand = rng.normal(size=(500, zt.shape[0])).astype("float32")
        assert np.array_equal(np.argsort(-(cand @ zt)), np.argsort(-(cand @ zn)))


def test_numpy_encoder_handles_fully_padded_history():
    """전부 가려진 입력에서 NaN 이 나오면 안 된다. -inf 대신 -1e9 를 쓰는 이유다."""
    import numpy as np

    _torch_or_skip()
    _, enc, in_dim, hist_max = _make_pair(seed=5)
    z = enc.encode(np.zeros((hist_max, in_dim), dtype="float32"),
                   np.zeros(hist_max, dtype=bool), None)
    assert np.isfinite(z).all()


def test_gelu_is_the_erf_variant_not_tanh():
    """tanh 근사를 쓰면 4번째 자리부터 갈라져 순위가 바뀐다."""
    import numpy as np

    from ai_server.app import stage1_np

    x = np.array([-2.0, -0.5, 0.0, 0.5, 2.0], dtype=np.float64)
    tanh_approx = 0.5 * x * (1 + np.tanh(np.sqrt(2 / np.pi) * (x + 0.044715 * x ** 3)))
    exact = stage1_np._gelu(x)
    assert np.abs(exact - tanh_approx).max() > 1e-5, "근사판과 구분되지 않는다"
    assert np.allclose(exact[2], 0.0) and exact[4] > 1.9


def test_scipy_is_not_required_to_import_the_app():
    """npz 를 쓰지 않는 배포에서 import 시점에 scipy 를 요구하면 안 된다."""
    import ast

    src = (Path(__file__).resolve().parents[1] / "app" / "stage1_np.py").read_text(
        encoding="utf-8")
    # 모듈 최상단(함수 밖)의 import 만 본다. 독스트링에 적힌 단어는 세지 않는다.
    for node in ast.parse(src).body:
        if isinstance(node, ast.Import):
            assert not any(a.name.startswith("scipy") for a in node.names)
        elif isinstance(node, ast.ImportFrom):
            assert not (node.module or "").startswith("scipy"), node.module


# ------------------------------------------------------------------ 추천 근거
def _out_with_ref(rep_uid):
    return {"diagnostics": {"query_source": "labels", "shortfall": 0},
            "results": [{"rank": 1, "track_id": "A", "moment_id": "A#0",
                         "section_label": "chorus", "start_sec": 1.0, "end_sec": 2.0,
                         "final_score": 1.0, "matched_dna": "u#dna0",
                         "matched_dna_rep": rep_uid, "slot_source": "dna0",
                         "clap_score": None, "prob": 0.9, "model_score": 0.5}]}


def test_reference_section_is_returned(mapped=None):
    """'저장하신 이 구간과 닮아서' 를 만들 수 있어야 한다."""
    item = internal_api.to_response(1, _out_with_ref("B#0"), 1, False).items[0]
    assert item.ref_external_id == "B#0"


def test_missing_reference_empties_the_field_not_the_response():
    """근거가 없다고 추천 전체를 실패시키지 않는다."""
    item = internal_api.to_response(1, _out_with_ref(None), 1, False).items[0]
    assert item.ref_external_id is None
    assert item.external_id == "A#0", "추천 자체는 살아 있어야 한다"


def test_reference_does_not_reintroduce_scores():
    """근거를 노출한다고 모델 수치까지 따라 나가면 안 된다."""
    import json

    body = json.dumps(internal_api.to_response(1, _out_with_ref("B#0"), 1,
                                               False).model_dump())
    for leaked in ("0.9", "prob", "model_score", "moment_similarity", "matched_dna"):
        assert leaked not in body, leaked


# ------------------------------------------------------------------ 배치 도구
@pytest.mark.parametrize("mod", ["run_labeler", "export_stage1_npz"])
def test_tools_import_and_expose_main(mod):
    """cron 이 부르는 진입점이다. import 조차 안 되면 돌려 봐야 안다."""
    import importlib

    m = importlib.import_module(f"ai_server.tools.{mod}")
    assert callable(m.main)


def test_run_labeler_reports_failure_with_nonzero_exit(monkeypatch, tmp_path, capsys):
    """cron 이 실패를 알아차려야 한다. 조용히 0 으로 끝나면 개인화가 꺼진 줄 모른다."""
    import sys

    from ai_server.app import main as appmain
    from ai_server.tools import run_labeler

    monkeypatch.setattr(appmain, "check_integrity",
                        lambda: {"ok": False, "errors": ["색인 없음"], "checks": {}})
    monkeypatch.setattr(sys, "argv", ["run_labeler"])
    assert run_labeler.main() == 1
    assert "색인 없음" in capsys.readouterr().err


# ------------------------------------------------------------------ 문서 참조
# 브랜치에 아직 없는 문서. **숨기지 않고 여기에 적어 둔다** — 들어오면 이 목록에서 지운다.
# 2026-09-15: AI-SPRING-CONTRACT.md 가 들어와 비었다. 새로 기다리는 문서가 생기면 여기 적는다.
_PENDING_DOCS: set[str] = set()


def test_readme_does_not_point_at_missing_files():
    """없는 파일을 가리키는 문서는 읽는 사람을 막다른 길로 보낸다.
    지금까지 고친 버그가 전부 '문서와 실제가 다르다' 였다."""
    import re

    root = Path(__file__).resolve().parents[2]
    text = (root / "ai_server" / "README.md").read_text(encoding="utf-8")
    prefixes = ("ai_server/", "api/", "infra/", "frontend/", "tools/")
    pat = r"`((?:%s)[A-Za-z0-9_./-]+\.[a-z]{2,8})`" % "|".join(
        p.replace("/", r"/") for p in prefixes)
    missing = sorted({m for m in re.findall(pat, text)
                      if not (root / m).exists() and m not in _PENDING_DOCS})
    assert not missing, f"README 가 없는 파일을 가리킨다: {missing}"


def test_pending_docs_list_is_not_stale():
    """들어온 문서가 목록에 남아 있으면 다음 사람이 또 '아직 없다' 고 믿는다."""
    root = Path(__file__).resolve().parents[2]
    arrived = [d for d in _PENDING_DOCS if (root / d).exists()]
    assert not arrived, f"이미 들어왔다. _PENDING_DOCS 에서 지울 것: {arrived}"


def test_dockerfile_copy_sources_exist():
    """COPY 원본이 없으면 **빌드가 실패한다.** 글로브는 아무것도 못 맞춰도 실패다.
    도커 데몬 없이도 이 정도는 잡을 수 있어야 한다."""
    import re

    root = Path(__file__).resolve().parents[2]
    df = (root / "ai_server" / "Dockerfile").read_text(encoding="utf-8")
    bad = []
    for line in df.splitlines():
        line = line.strip()
        if not line.startswith("COPY ") or "--from=" in line:
            continue
        parts = [p for p in line.split()[1:] if not p.startswith("--")]
        for src in parts[:-1]:                      # 마지막은 목적지
            if "*" in src:
                if not list(root.glob(src)):
                    bad.append(src)
            elif not (root / src).exists():
                bad.append(src)
    assert not bad, f"Dockerfile COPY 원본이 없다: {bad}"


def test_image_ships_the_batch_entrypoints():
    """`python -m ai_server.tools.run_labeler` 가 이미지 안에서 돌아야 한다."""
    root = Path(__file__).resolve().parents[2]
    df = (root / "ai_server" / "Dockerfile").read_text(encoding="utf-8")
    assert "ai_server/tools" in df


# ------------------------------------------------------------------ 로그가 서비스를 죽이지 않는다
class _NarrowStdout:
    """cp949 처럼 한글·em dash 를 못 받는 콘솔을 흉내 낸다."""

    encoding = "ascii"

    def __init__(self):
        self.buffer = None      # buffer 도 없는 환경(파이프 래퍼 등)까지 가정한다

    def write(self, s):
        s.encode("ascii")       # 못 받으면 UnicodeEncodeError
        return len(s)

    def flush(self):
        pass


def test_log_never_raises_on_narrow_stdout(monkeypatch):
    """print 하나가 예외를 내면 그 호출이 기동 경로에 있을 때 서비스가 뜨지 못한다."""
    import sys

    from ai_server.app.log import log

    monkeypatch.setattr(sys, "stdout", _NarrowStdout())
    log("선택 산출물 없음 — id_map.parquet")          # 예외가 나면 실패다


def test_startup_survives_narrow_stdout(monkeypatch):
    """실제 기동 경로. 한글 진단 줄이 있어도 앱은 떠야 한다."""
    import sys

    from ai_server.app import main as appmain

    monkeypatch.setattr(sys, "stdout", _NarrowStdout())
    monkeypatch.setattr(appmain, "check_integrity",
                        lambda: {"ok": False, "errors": ["색인 없음 — 확인"], "checks": {}})
    appmain._startup()


# 맨 print 가 허용되는 함수. **파일 단위로 빼지 않는다** — `features.py` 를 통째로
# 면제하면 기동 경로인 `load()` 에 나중에 들어오는 print 까지 놓친다. 여기 적힌 것은
# 전부 오프라인 진입점(학습·적합·배치)이라 uvicorn 안에서 돌지 않는다.
CLI_ONLY_PRINTS: dict[str, set[str] | None] = {
    "ids.py": None,                 # 파일 전체가 CLI 다
    "log.py": None,                 # print 를 감싸는 쪽
    "features.py": {"fit", "main"},
    "labeler.py": {"main"},
    "stage1_torch.py": {"build", "selftest", "train", "export", "main", "chk"},
}


def test_no_bare_print_in_startup_path():
    """진단은 log() 로만 남긴다. 맨 print 는 인코딩에 따라 예외를 낸다.

    한국어 한 줄이면 충분하다 — 로케일이 C 면 UnicodeEncodeError 가 나고, 그 호출이
    기동 경로에 있으면 서비스가 아예 뜨지 못한다.
    """
    import ast

    app_dir = Path(__file__).resolve().parents[1] / "app"
    for f in sorted(app_dir.glob("*.py")):
        allowed = CLI_ONLY_PRINTS.get(f.name, set())
        if allowed is None:
            continue
        tree = ast.parse(f.read_text(encoding="utf-8"))
        # print 를 품은 함수 이름을 먼저 붙인다. 함수 밖(= import 시점)의 print 는
        # 이름이 없으므로 어떤 목록으로도 면제되지 않는다.
        owner: dict[int, str] = {}
        for fn in ast.walk(tree):
            if isinstance(fn, (ast.FunctionDef, ast.AsyncFunctionDef)):
                for node in ast.walk(fn):
                    owner.setdefault(id(node), fn.name)
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) \
                    and node.func.id == "print" \
                    and owner.get(id(node)) not in allowed:
                raise AssertionError(f"{f.name}:{node.lineno} 맨 print")


def test_dockerfile_pins_a_utf8_locale():
    df = (Path(__file__).resolve().parents[1] / "Dockerfile").read_text(encoding="utf-8")
    assert "LANG=C.UTF-8" in df and "PYTHONIOENCODING=utf-8" in df


# ------------------------------------------------------------------ Spring 계약 대조
def _java(rel: str):
    p = Path(__file__).resolve().parents[2] / rel
    if not p.exists():
        pytest.skip("api 모듈이 없는 체크아웃")
    return p.read_text(encoding="utf-8")


def _record_fields(src: str, name: str) -> list[str]:
    """`public record X( ... )` 의 필드 이름을 뽑는다. 주석과 애너테이션은 건너뛴다."""
    import re

    m = re.search(rf"record\s+{name}\s*\((.*?)\)\s*\{{", src, re.S)
    assert m, f"record {name} 을 찾지 못했다"
    body = re.sub(r"/\*.*?\*/|//[^\n]*", "", m.group(1), flags=re.S)
    out = []
    for part in body.split(","):
        toks = part.split()
        if len(toks) >= 2:
            out.append(toks[-1].strip())
    return out


def _snake(name: str) -> str:
    import re

    return re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower()


_DTO = "api/src/main/java/com/osundosun/mupid/api/ai/client/RecommenderDto.java"


def test_response_covers_every_field_spring_reads():
    """Spring 이 읽는 필드를 우리가 안 보내면 그 값은 조용히 0·null 이 된다.
    Jackson 은 모르는 필드를 무시하도록 돼 있어 **없는 필드도 예외가 나지 않는다.**"""
    src = _java(_DTO)
    ours = set(internal_api.RecommendationItem.model_fields)
    theirs = {_snake(f) for f in _record_fields(src, "Item")}
    assert theirs <= ours, f"우리가 안 보내는 필드: {sorted(theirs - ours)}"

    ours_r = set(internal_api.RecommendationResponse.model_fields)
    theirs_r = {_snake(f) for f in _record_fields(src, "Response")}
    assert theirs_r <= ours_r, f"우리가 안 보내는 필드: {sorted(theirs_r - ours_r)}"


def test_error_shape_matches():
    theirs = {_snake(f) for f in _record_fields(_java(_DTO), "Error")}
    assert theirs <= set(internal_api.ErrorResponse.model_fields)


def test_search_request_accepts_what_spring_sends():
    """Spring 의 SearchRequest 필드를 우리가 거부하면 400 이다 — extra=forbid 이기 때문."""
    theirs = {_snake(f) for f in _record_fields(_java(_DTO), "SearchRequest")}
    ours = set(internal_api.SearchRequest.model_fields)
    assert theirs <= ours, f"우리가 거부하는 필드: {sorted(theirs - ours)}"


def test_next_request_accepts_what_spring_sends():
    theirs = {_snake(f) for f in _record_fields(_java(_DTO), "NextRequest")}
    ours = set(internal_api.NextRequest.model_fields)
    assert theirs <= ours, f"우리가 거부하는 필드: {sorted(theirs - ours)}"


def test_spring_tolerates_fields_we_added():
    """`ref_music_id` 처럼 우리가 늘린 필드가 저쪽 역직렬화를 깨면 안 된다.
    AiJson 이 FAIL_ON_UNKNOWN_PROPERTIES 를 끈 것에 의존한다 — 되돌리면 여기서 걸린다."""
    src = _java("api/src/main/java/com/osundosun/mupid/api/ai/client/AiJson.java")
    assert "FAIL_ON_UNKNOWN_PROPERTIES" in src and ".disable(" in src


# ------------------------------------------------------------------ 매핑표 생성
def _tsv(tmp_path, rows, header=None):
    header = header or (
        "source_track_id\tmusic_id\texternal_id\tmusic_section_analysis_id")
    p = tmp_path / "t.tsv"
    p.write_text(header + "\n" + "\n".join(rows) + "\n", encoding="utf-8")
    return p


def test_build_id_map_writes_a_loadable_table(tmp_path, clean_env):
    from ai_server.app import ids as idsmod
    from ai_server.tools import build_id_map as b

    src = _tsv(tmp_path, ["A\t1\tA#0\t10", "A\t1\tA#1\t11", "B\t2\tB#0\t20"])
    out = tmp_path / "id_map.parquet"
    df = b.build(b.read_table(src))
    assert b.check(df) == []
    df.to_parquet(out, index=False)

    im = idsmod.IdMap(path=out, strict=True)
    assert im.loaded and im.track_to_num == {"A": 1, "B": 2}
    assert im.sec_to_num["A#1"] == 11


def test_build_id_map_refuses_a_broken_mapping(tmp_path):
    """같은 곡이 music_id 두 개면 적재가 막힌다. 파일을 만들기 전에 알아야 한다."""
    from ai_server.app import ids as idsmod
    from ai_server.tools import build_id_map as b

    src = _tsv(tmp_path, ["A\t1\tA#0\t10", "A\t2\tA#1\t11"])
    out = tmp_path / "bad.parquet"
    b.build(b.read_table(src)).to_parquet(out, index=False)
    with pytest.raises(idsmod.MapIntegrityError):
        idsmod.IdMap(path=out, strict=True)


def test_build_id_map_warns_on_oversized_uid(tmp_path):
    """MySQL 이 조용히 잘라 저장하면 1:1 검사를 통과하면서 매핑이 어긋난다."""
    from ai_server.tools import build_id_map as b

    long_uid = "X" * (b.UID_MAX + 5) + "#0"
    src = _tsv(tmp_path, [f"A\t1\t{long_uid}\t10"])
    problems = b.check(b.build(b.read_table(src)))
    assert problems and "길이" in problems[0]


def test_build_id_map_derives_track_id_but_never_music_id(tmp_path):
    """Q3: 곡 문자열 ID 는 DB 에 없다 — 구간 UID 에서 파생하는 것이 정식이다.
    반대로 숫자 music_id 는 Spring 이 발급하므로 지어내지 않고 멈춘다."""
    from ai_server.tools import build_id_map as b

    src = _tsv(tmp_path, ["A#0\t10"],
               header="external_id\tmusic_section_analysis_id")
    with pytest.raises(SystemExit):        # music_id 가 없다
        b.build(b.read_table(src))

    ok = _tsv(tmp_path, ["A#0\t10\t1"],
              header="external_id\tmusic_section_analysis_id\tmusic_id")
    got = b.build(b.read_table(ok))
    assert got.source_track_id.iloc[0] == "A"        # 파생이 기본 동작이다

    with pytest.raises(SystemExit):        # 파생을 막으면 예전처럼 멈춘다
        b.build(b.read_table(ok), derive_track_id=False)


def test_build_id_map_reads_csv_too(tmp_path):
    """내보내는 도구마다 구분자가 다르다."""
    from ai_server.tools import build_id_map as b

    p = tmp_path / "t.csv"
    p.write_text("source_track_id,music_id,external_id,music_section_analysis_id\n"
                 "A,1,A#0,10\n", encoding="utf-8")
    assert len(b.build(b.read_table(p))) == 1


def test_id_map_reads_legacy_preprocessing_columns(tmp_path):
    """외부 전처리 산출물은 AI 입력 경계에서 실제 DB 이름으로 정규화한다."""
    import pandas as pd

    p = tmp_path / "legacy.parquet"
    pd.DataFrame([{
        "source_track_id": "A", "music_id": 1,
        "source_section_uid": "A#0", "music_section_id": 10,
    }]).to_parquet(p, index=False)
    im = ids.IdMap(path=p, strict=True)
    assert im.external_id(10) == "A#0"
    assert im.music_section_analysis_id("A#0") == 10


# ------------------------------------------------------------------ 온보딩 수신
def _onb_body(user_id=42, submitted=True):
    return {"message": None,
            "data": {"user_id": user_id, "submitted": submitted,
                     "submitted_at": "2026-09-14T10:32:26" if submitted else None,
                     "moods": [{"code_id": 1, "name": "잔잔한"},
                               {"code_id": 3, "name": "몽환적인"}] if submitted else [],
                     "genres": [{"code_id": 15, "name": "Jazz"}] if submitted else []}}


# ------------------------------------------------------------------ 어휘 변환
# 2026-09-17 운영 색인 실측. **수를 손으로 적지 않고 목록으로 고정한다** — 예전에는
# 8종만 있어서 코드표가 늘어도 이 테스트가 조용히 통과했다. 이제 Spring 코드표
# 전량을 넣는다.
#
# `genre` 축 57종 · `style` 축 37종은 `GET /internal/facets` 가 준 값 그대로다
# (`style` 은 `category` 에서 보컬·언어 접두어를 뗀 것). taxonomy 45종은 그
# `style` 축의 상위집합이고, genre 축과는 **한 건도 겹치지 않는다**(실측 0/45).
_AI_GENRE = [
    "K-pop", "Ballad", "Soundtrack", "Contemporary R&B", "Alternative Rock",
    "Ambient", "Synthwave", "Instrumental", "Neo Soul", "Downtempo", "Pop Rock",
    "J-pop", "House", "Tropical House", "Heavy Metal", "Cloud Rap", "Dubstep",
    "Synth-pop", "Drum n Bass", "New Age", "Symphonic Rock", "Dance-pop",
    "Disco Polo", "Soul", "Pop Punk", "Post-Metal", "Trap", "Reggaeton", "Folk",
    "Jazzy Hip-Hop", "Nu-Disco", "Hard Rock", "Disco", "Power Metal",
    "Contemporary", "Smooth Jazz", "Modern", "Chillwave", "Italo-Disco",
    "Horrorcore", "Gangsta", "UK Garage", "Progressive Breaks", "Flamenco",
    "Hands Up", "City Pop", "Funk", "Vocal", "Indie Rock", "Indie Pop", "Punk",
    "Boom Bap", "RnB/Swing", "Tech Trance", "Electro House", "Trance",
    "Neo-Classical",
]

# **`.split()` 로 쓰지 않는다.** 이 축에는 공백이 든 값(`Contemporary R&B`·
# `Alternative Rock`·`Drum n Bass`·`Jazzy Hip-Hop`)이 있어서 공백 분해가 값을
# 조각낸다 — `'Jazzy'` 만 남아 `Jazz` 질의가 엉뚱한 곳에 걸린다. 아래 두 축은
# snake_case 라 안전하지만, 새 축을 추가할 때 같은 함정을 밟지 않도록 여기 적어 둔다.
_AI_GENRE_HAS_SPACES = [g for g in _AI_GENRE if " " in g]

_AI_STYLE = """
contemporary_pop synthpop lofi_hiphop dance_pop poprock rnb indie_pop city_pop
pop_ballad alternative_rock game_battle_bgm indie_rock hard_rock
atmospheric_post_rock alternative_rnb neo_soul boom_bap_hiphop
modern_trap_hiphop singing_rap_melodic_hiphop synthwave drum_and_bass chillhop
adventure_fantasy emotional_drama mystery_suspense casual_light_game_bgm
punk_power_pop cinematic_orchestral future_bass breakbeat_uk_garage
ambient_electronic downtempo unknown solo_piano pop hiphop_rnb
melodic_electronic
""".split()

# `_genre_catalog` 가 넘기는 어휘. 장르 이름 포함 매칭이 `style` 축에도 걸리게
# 하려면 두 축이 다 있어야 한다 (internal_api.py `_genre_catalog`).
_CATALOG = _AI_GENRE + _AI_STYLE
_CATALOG_SET = set(_CATALOG)

_TAXONOMY_GENRE = """
contemporary_pop dance_pop synthpop lofi_hiphop indie_pop alternative_rock
poprock city_pop pop_ballad rnb solo_piano singing_rap_melodic_hiphop
modern_trap_hiphop synthwave game_battle_bgm indie_rock hard_rock
punk_power_pop atmospheric_post_rock alternative_rnb neo_soul boom_bap_hiphop
house future_bass drum_and_bass chillhop downtempo
chillwave_dreamy_electronica modern_jazz funk cinematic_orchestral
adventure_fantasy emotional_drama mystery_suspense casual_light_game_bgm
neo_classical acoustic_guitar_folk ambient breakbeat_uk_garage
ambient_electronic acoustic_chill jazzhop jazz_fusion bossa_smooth_jazz
chamber_classical
""".split()

# taxonomy 9종. 색인 `mood` 축에 이름이 하나도 없어서 **뜻으로** 이어야 한다.
_TAXONOMY_MOOD = ["melancholic", "warm", "dreamy", "bright", "hopeful",
                  "powerful", "dark", "calm", "exciting"]

# Spring `codes(MOOD).code_name` 17종 — 영문 내부 코드다 (2026-09-21 정리).
_SPRING_MOOD = ["serene", "uplifting", "ethereal", "energetic", "focused", "emotional",
                "intense", "romantic",
                "melancholic", "warm", "dreamy", "bright", "hopeful", "powerful", "dark",
                "calm", "exciting"]
# 같은 17종의 화면용 한국어. 정리 전 요청·기록에 남아 있을 수 있어 별칭으로도 받는다.
_SPRING_MOOD_KO = ["잔잔한", "벅찬", "몽환적인", "신나는", "집중되는", "감성적인", "강렬한", "설레는",
                   "애상적인", "따뜻한", "몽글한", "화사한", "희망찬", "웅장한", "어두운", "고요한", "짜릿한"]
_SPRING_GENRE = ["Electronic", "Indie", "R&B", "Rock", "Pop", "Hip-hop", "Jazz", "Classical"]

# `flow_meta.mood_tags` 의 실제 태그 어휘 **38종**(2026-09-17 실측). `MOOD_MAP` 이
# 내놓는 값은 전부 여기 안에 있어야 한다.
#
# `GET /internal/facets` 의 `mood` 필드는 **27종**뿐이다 — `catalog_facets` 의
# `cnt_multi` 가 곡 2개 미만인 태그를 선택지에서 빼기 때문이다. `slow`·`party`·
# `sad`·`dark` 4종이 그래서 필드 목록에는 없지만 태그로는 살아 있다. **둘을
# 섞으면 안 된다** — 이 4종은 역방향 표에서 감성적인·강렬한 라벨을 지탱하는
# 유일한 근거라, 필드 목록을 기준으로 "죽은 값" 이라며 지우면 곡이 라벨을 잃는다.
_AI_MOOD_TAGS = """
energetic love happy melodic relaxing epic film deep background action christmas
ballad motivational emotional space summer inspiring corporate romantic uplifting
meditative advertising positive drama heavy adventure sad trailer dark slow party
dream dramatic funny soundscape documentary melancholic sport
""".split()

# 선택지로 노출되는 27종 (위 38종에서 곡 2개 미만을 뺀 것). 문서·대조용이다.
_AI_MOOD_FIELD = """
energetic love melodic epic relaxing film happy action christmas background deep
space motivational emotional ballad inspiring summer corporate romantic positive
heavy drama meditative trailer adventure uplifting advertising
""".split()

# taxonomy 45종 중 **색인에 곡이 없어 변환되지 않는 9종.** 버그가 아니다 —
# 색인이 763곡이라 그 style 의 곡이 아직 안 들어왔다. 곡이 들어오면 정확히
# 일치로 풀린다. 수가 늘면 그때는 원인을 봐야 한다.
_TAXONOMY_GENRE_NOT_IN_INDEX = [
    "chillwave_dreamy_electronica", "modern_jazz", "neo_classical",
    "acoustic_guitar_folk", "acoustic_chill", "jazzhop", "jazz_fusion",
    "bossa_smooth_jazz", "chamber_classical",
]


# 어휘 전환(2026-09-21) 뒤의 카탈로그. `flow_meta.genre_code` 는 DB 코드 이름이다.
_CODE_CATALOG = _TAXONOMY_GENRE            # 세분류 45종이 곧 카탈로그 값이다


# 곡 무드 9종(code_id 40~48). 온보딩이 받는 유일한 무드 축이다.
_TRACK_MOOD = ["melancholic", "warm", "dreamy", "bright", "hopeful", "powerful",
               "dark", "calm", "exciting"]
_TRACK_MOOD_KO = ["애상적인", "따뜻한", "몽글한", "화사한", "희망찬", "웅장한",
                  "어두운", "고요한", "짜릿한"]
# 1세대 8종(32~39). 순간 무드 축이고 **온보딩에서 내렸다**(2026-09-21 BE 합의).
_LEGACY_MOOD = ["serene", "uplifting", "ethereal", "energetic", "focused",
                "emotional", "intense", "romantic"]


def test_every_code_genre_reaches_the_catalog():
    """세분류 45종이 **전부** 걸린다.

    전환 전에는 0/45 였다. 카탈로그가 `Rock---Punk` 꼴이고 코드가
    `alternative_rock` 이라 부분일치가 하나도 안 걸렸고, 그 축은 조용히 비었다.
    """
    from ai_server.app import onboarding as f

    dead = [g for g in _TAXONOMY_GENRE if not f.map_genre([g], _TAXONOMY_GENRE)]
    assert not dead, f"카탈로그에 있는데 안 걸리는 장르: {dead}"
    assert len(_TAXONOMY_GENRE) == 45


def test_broad_genres_never_map():
    """대분류 8종(9~16)은 온보딩에서 내렸다. 하나도 걸리면 안 된다.

    `music_genres` 에 그 8종을 쓰는 곡이 0개다. 억지로 세분류에 이어 붙이면
    근거 없는 매핑표가 하나 더 생긴다 — 그 연결은 코드표가 정할 일이다.
    """
    from ai_server.app import onboarding as f

    assert not hasattr(f, "GENRE_EXTRA"), "손으로 이어 두던 표가 되살아났다"
    alive = [g for g in _SPRING_GENRE if f.map_genre([g], _TAXONOMY_GENRE)]
    assert alive == [], f"대분류가 매핑됐다: {alive}"


def test_track_moods_map_by_code_name_and_by_display_name():
    """곡 무드 9종은 영문 코드로도, 한국어 표시명으로도 들어온다.

    한국어를 받는 것이 중요하다 — **운영의 중복 무드 124~132 는 `code_name` 자체가
    한국어**(`고요한`)라, 이름으로 오면 이 표가 40~48 의 영문 코드로 되돌린다.
    그래서 AI 쪽은 그 중복에 영향을 받지 않는다.
    """
    from ai_server.app import onboarding as f

    assert f.map_mood(_TRACK_MOOD) == _TRACK_MOOD                 # 9/9 그대로
    assert f.map_mood(_TRACK_MOOD_KO) == _TRACK_MOOD              # 9/9 되돌림
    assert f.map_mood(["몽글몽글한", "차분한"]) == ["dreamy", "calm"]   # 정리 전 철자


def test_legacy_moods_never_map():
    """1세대 8종(32~39)은 순간 무드 축이다. 온보딩으로 들어오면 버린다.

    통과시키면 `flow_meta.mood_codes` 에 없는 값으로 곡을 거르게 되어 **조건이
    아무 곡도 고르지 못한다.** `MOOD_MAP` 은 17종을 그대로 들고 있지만 그쪽은
    `register_sections` 가 순간 무드를 붙일 때 쓰는 표라 건드리지 않는다.
    """
    from ai_server.app import onboarding as f

    alive = [m for m in _LEGACY_MOOD if f.map_mood([m])]
    assert alive == [], f"1세대 무드가 매핑됐다: {alive}"
    assert [m for m in _SPRING_MOOD_KO[:8] if f.map_mood([m])] == [], "한국어로도 막아야 한다"
    # 순간 무드 표는 그대로여야 한다 — 이쪽을 같이 줄이면 순간 무드가 통째로 빈다.
    assert set(_LEGACY_MOOD) <= set(f.MOOD_MAP)


def test_values_outside_the_catalog_do_not_pass():
    """지어낸 값을 넣으면 필터가 아무것도 고르지 못한다. 그래서 통과시키지 않는다."""
    from ai_server.app import onboarding as f

    assert f.map_genre(["없는장르", "Rock---Punk"], _TAXONOMY_GENRE) == []
    assert f.map_mood(["없는무드"]) == []


def test_existing_mood_tags_still_map_to_the_same_korean_name():
    """이미 라벨이 붙은 곡의 무드가 바뀌지 않는다 — 역방향 표가 불변이어야 한다.

    `MOOD_BY_TAG` 는 `MOOD_MAP` 을 뒤집어 `setdefault` 로 만든다. 앞선 항목이
    이기므로 **9종을 뒤에 붙인 한** 기존 27개 태그의 대응이 그대로다.
    """
    from ai_server.app import onboarding as f

    reverse: dict[str, str] = {}
    for ko, tags in f.MOOD_MAP.items():
        for tag in tags:
            reverse.setdefault(tag, ko)
    # 9종이 추가한 태그는 0개다 — 역방향 표가 27개 그대로다.
    assert len(reverse) == 27, f"역방향 표가 {len(reverse)}개로 바뀌었다"
    assert set(reverse) <= set(_AI_MOOD_TAGS)
    # 대표값 몇 개를 못 박는다. 여기가 흔들리면 기존 사용자 화면이 바뀐다.
    assert reverse["relaxing"] == "serene"        # 잔잔한
    assert reverse["sad"] == "emotional"          # 감성적인
    assert reverse["dark"] == "intense"           # 강렬한
    assert reverse["party"] == "energetic"        # 신나는


def test_raw_answer_is_kept_for_tracing():
    """변환이 어긋났을 때 되짚을 근거가 없으면 원인을 못 찾는다."""
    from ai_server.app import onboarding as f

    # 원문은 사용자가 고른 그대로다 — 한국어든 코드 이름이든 손대지 않는다.
    ans = f.from_request({"moods": ["애상적인", "고요한"], "genres": ["modern_jazz"]},
                         _CODE_CATALOG)
    assert ans["mood_raw"] == ["애상적인", "고요한"]
    assert ans["genre_raw"] == ["modern_jazz"]
    # 통과한 값은 코드 이름이다. 옮기지 않으므로 원문과 철자만 다르다.
    assert ans["genre"] == ["modern_jazz"]
    assert ans["mood"] == ["melancholic", "calm"]

    # 카탈로그에 없는 대분류는 걸리지 않고, 그 사실이 원문에 남는다.
    broad = f.from_request({"moods": [], "genres": ["Jazz"]}, _CODE_CATALOG)
    assert broad["genre"] == [] and broad["genre_raw"] == ["Jazz"]


# ------------------------------------------------------------------ 구간 적재
class _FakeMap:
    loaded = True

    def music_id(self, s):
        if s == "T000999":
            raise KeyError("매핑 없음")
        return {"T001001": 1001, "T001002": 1002}[s]

    def music_section_analysis_id(self, uid):
        # `T001002#3` 은 일부러 빼 둔다 — 곡은 매핑됐는데 구간만 없는 경우다.
        return {"T001001#4": 50004}[uid]


def _moments():
    import pandas as pd

    return pd.DataFrame([
        {"track_id": "T001001", "moment_id": "T001001#4", "section_idx": 4,
         "section_label": "verse", "start_sec": 30.0, "end_sec": 60.0},
        {"track_id": "T001002", "moment_id": "T001002#9", "section_idx": 9,
         "section_label": "weird", "start_sec": 80.0, "end_sec": 90.0},
        {"track_id": "T000999", "moment_id": "T000999#1", "section_idx": 1,
         "section_label": "chorus", "start_sec": 10.0, "end_sec": 20.0},
        {"track_id": "T001001", "moment_id": "T001001#5", "section_idx": 5,
         "section_label": "chorus", "start_sec": 70.0, "end_sec": 70.0},
        {"track_id": "T001002", "moment_id": "T001002#3", "section_idx": 3,
         "section_label": "intro", "start_sec": 0.0, "end_sec": 20.0},
    ])


def test_push_sections_never_invents_a_numeric_id():
    """매핑에 없는 곡을 넣으면 실제 DB 와 어긋난 순간 엉뚱한 곡을 추천한다."""
    from ai_server.tools import push_sections as ps

    rows, stat = ps.build_rows(_moments(), _FakeMap(), "v1")
    assert stat["unmapped_track"] == 1
    assert "T000999" in stat["unmapped_sample"]
    assert all(r["track_id"] in (1001, 1002) for r in rows)


def test_push_sections_carries_the_mysql_section_id():
    """구간 표에 MySQL PK 를 함께 싣는다 (S15P21A204-435).

    ClickHouse 쪽에서 구간을 가리키는 값이 문자열 `section_uid` 뿐이면 분석 결과를
    MySQL 구간 행에 붙이려고 매번 문자열로 조인해야 한다. 스프링이 구간을 적재할 때
    넣는 것과 같은 컬럼이므로 이쪽도 채운다.
    """
    from ai_server.tools import push_sections as ps

    rows, _ = ps.build_rows(_moments(), _FakeMap(), "v1")
    by_uid = {r["section_uid"]: r for r in rows}
    assert by_uid["T001001#4"]["music_section_analysis_id"] == 50004


def test_push_sections_keeps_sections_whose_number_is_unknown():
    """**구간은 매핑이 없어도 넣는다.** 곡과 다르다.

    이 표의 첫 번째 쓰임은 `dictGet` 으로 재생 위치를 구간에 붙이는 것이고 거기에는
    `section_uid` 면 충분하다. 번호가 없다고 빼면 그 구간의 이벤트가 통째로 버려지고,
    그건 이 도구가 막으려는 바로 그 침묵이다.
    """
    from ai_server.tools import push_sections as ps

    rows, stat = ps.build_rows(_moments(), _FakeMap(), "v1")
    by_uid = {r["section_uid"]: r for r in rows}
    assert "T001002#3" in by_uid, "번호를 모른다고 구간을 버리면 안 된다"
    assert by_uid["T001002#3"]["music_section_analysis_id"] == 0
    assert stat["unmapped_section"] == 1


def test_push_sections_drops_labels_outside_the_erd_enum():
    """한쪽에만 있는 라벨을 넣으면 두 저장소를 맞출 때 어느 쪽이 맞는지 알 수 없다."""
    from ai_server.tools import push_sections as ps

    rows, stat = ps.build_rows(_moments(), _FakeMap(), "v1")
    assert stat["unknown_label"] == 1
    assert all(r["fn"] in ps.SECTION_ENUM for r in rows)


def test_push_sections_drops_empty_ranges():
    """RANGE_HASHED 딕셔너리는 start==end 를 어느 쪽으로도 못 고른다."""
    from ai_server.tools import push_sections as ps

    rows, stat = ps.build_rows(_moments(), _FakeMap(), "v1")
    assert stat["bad_range"] == 1
    assert all(r["end_ms"] > r["start_ms"] for r in rows)


def test_push_sections_never_writes_a_local_clock_timestamp():
    """`created_at` 은 `ReplacingMergeTree` 의 **버전 컬럼**이다.

    예전에는 `pd.Timestamp.now("UTC")` 를 타임존 표시 없이 넣었는데, 서버 타임존이
    `Asia/Seoul` 이라 **9시간 과거로 저장된다.** 그러면 이 도구가 고친 구간이
    `DEFAULT now()` 로 들어간 행에 밀려 조용히 옛 값으로 되돌아간다.

    비우면 아예 싣지 않아 ClickHouse 가 `DEFAULT now()` 로 채운다 — 어느 쪽이든
    서버 시계다.
    """
    import inspect

    from ai_server.tools import push_sections as ps

    rows, _ = ps.build_rows(_moments(), _FakeMap(), "v1")
    assert all("created_at" not in r for r in rows), (
        "시각을 싣지 않기로 했으면 서버 기본값에 맡긴다")

    rows, _ = ps.build_rows(_moments(), _FakeMap(), "v1", "2026-09-16 12:06:49")
    assert {r["created_at"] for r in rows} == {"2026-09-16 12:06:49"}

    # 로컬 시계로 돌아가지 않았는지 본다. 되돌아가도 시험은 다 통과하기 때문이다.
    src = inspect.getsource(ps.build_rows)
    assert "Timestamp.now" not in src and "datetime.now" not in src


def test_push_sections_asks_the_server_for_the_time():
    """시각은 ClickHouse 에 물어서 얻는다. 로컬 시계를 쓰지 않는다."""
    from ai_server.tools import push_sections as ps

    asked = {}

    def fake_query(sql, body=None):
        asked["sql"] = sql
        return "2026-09-16 12:06:49\n"

    orig, ps.query = ps.query, fake_query
    try:
        assert ps.server_now() == "2026-09-16 12:06:49"
    finally:
        ps.query = orig
    assert "now()" in asked["sql"] and "UTC" not in asked["sql"]


def test_push_sections_uses_the_version_the_dictionary_reads():
    """v_track_sections_current 가 'v1' 만 고른다. 다른 값은 표에는 들어가지만
    구간 판정에 전혀 쓰이지 않는다."""
    from ai_server.tools import push_sections as ps

    assert ps.DICT_VERSION == "v1"
    rows, _ = ps.build_rows(_moments(), _FakeMap(), ps.DICT_VERSION)
    assert {r["analysis_version"] for r in rows} == {"v1"}


def test_push_sections_accepts_the_jdbc_url_shape(monkeypatch):
    """.env 가 jdbc: 형태로 적혀 있다. 그대로 넣어도 붙어야 한다."""
    from ai_server.tools import push_sections as ps

    monkeypatch.setenv("MUPID_CLICKHOUSE_URL", "jdbc:clickhouse://localhost:18123/default")
    assert ps._http_base() == "http://localhost:18123"
    monkeypatch.setenv("MUPID_CLICKHOUSE_URL", "http://ch:8123")
    assert ps._http_base() == "http://ch:8123"
    monkeypatch.delenv("MUPID_CLICKHOUSE_URL")
    with pytest.raises(SystemExit):
        ps._http_base()


# ------------------------------------------------------------------ 증분 적재
def _remote(row: dict) -> tuple:
    """`remote_state()` 가 돌려주는 모양. 컬럼이 늘면 여기만 고친다."""
    return (row["start_ms"], row["end_ms"], row["fn"], row["music_section_analysis_id"])


def test_push_sections_incremental_only_sends_changes():
    """구간 1만 개를 매번 밀면 파트가 쌓이고 머지가 따라오지 못한다."""
    from ai_server.tools import push_sections as ps

    rows, _ = ps.build_rows(_moments(), _FakeMap(), "v1")
    same = {r["section_uid"]: _remote(r) for r in rows}
    todo, d = ps.diff(rows, same)
    assert todo == [] and d["new"] == 0 and d["changed"] == 0

    moved = dict(same)
    k = rows[0]["section_uid"]
    moved[k] = (moved[k][0], moved[k][1] + 5000, moved[k][2], moved[k][3])
    todo, d = ps.diff(rows, moved)
    assert d["changed"] == 1 and [r["section_uid"] for r in todo] == [k]

    # 경계는 그대로인데 MySQL 구간 번호만 비어 있는 행. 이것도 다시 보내야 한다 —
    # 03 마이그레이션으로 되살렸거나 id_map 이 늦게 갖춰진 회차가 이 모양이다.
    blank = dict(same)
    blank[k] = (blank[k][0], blank[k][1], blank[k][2], 0)
    todo, d = ps.diff(rows, blank)
    assert d["changed"] == 1 and [r["section_uid"] for r in todo] == [k]

    todo, d = ps.diff(rows, {})
    assert d["new"] == len(rows) and len(todo) == len(rows)


def test_push_sections_audit_spots_orphans():
    """색인에서 사라진 구간이 ClickHouse 에 남아 있으면 옛 경계로 판정된다."""
    from ai_server.tools import push_sections as ps

    rows, _ = ps.build_rows(_moments(), _FakeMap(), "v1")
    remote = {r["section_uid"]: _remote(r) for r in rows}
    remote["T009999#0"] = (0, 1000, "INTRO", 0)
    _, d = ps.diff(rows, remote)
    assert d["orphan"] == 1 and "T009999#0" in d["orphan_sample"]


# ------------------------------------------------------------------ Q8 읽기
def _signal(ts="2026-09-15 14:35:00.000", sig="SECTION_MARK"):
    return {"user_id": 42, "section_uid": "T001001#3", "track_id": 1001,
            "signal": sig, "entry_path": "AUTO", "ts": ts,
            "play_id": "0-0", "weight": 1.5}


class _IdMap:
    def source_track_id(self, n):
        return {1001: "T001001"}[n]


def test_pull_signals_event_id_comes_from_content():
    """두 번 받아도 한 번만 쌓여야 한다. 워터마크만으로는 경계의 같은 밀리초를 못 막는다."""
    from ai_server.tools import pull_signals as pl

    assert pl.event_id(_signal()) == pl.event_id(_signal())
    assert pl.event_id(_signal()) != pl.event_id(_signal(sig="LIKE"))
    assert pl.event_id(_signal()) != pl.event_id(_signal(ts="2026-09-15 14:35:00.001"))


def test_pull_signals_keeps_the_ui_name_for_the_labeler():
    """등급 판정은 라벨러 한 곳에서만 한다. 여기서 이름을 바꾸면 변환표가 두 벌이 된다."""
    from ai_server.app import recommender
    from ai_server.tools import pull_signals as pl

    ev = pl.to_event(_signal(), {"T001001#3": (90.0, 120.0)}, _IdMap())
    assert ev["event_type"] == "SECTION_MARK"
    assert ev["event_type"] in recommender.UI_TO_LABELER
    assert ev["user_ref"] == "internal:42"
    assert (ev["start_sec"], ev["end_sec"]) == (90.0, 120.0)


def test_pull_signals_drops_sections_the_index_does_not_know():
    """구간 표와 AI 색인이 어긋나면 경계 없이 이벤트를 만들 수 없다."""
    from ai_server.tools import pull_signals as pl

    assert pl.to_event(_signal(), {}, _IdMap()) is None
    assert pl.to_event(_signal(sig="PLAY_PROGRESS"), {"T001001#3": (1.0, 2.0)}, _IdMap()) is None


def test_pull_signals_watermark_survives_a_missing_file(tmp_path, monkeypatch):
    """처음 돌 때와 파일이 깨졌을 때 모두 처음부터 읽어야 한다 — 건너뛰면 조용히 샌다."""
    from ai_server.tools import pull_signals as pl

    monkeypatch.setattr(pl, "watermark_path", lambda: tmp_path / "wm.json")
    assert pl.load_watermark() == pl.EPOCH
    (tmp_path / "wm.json").write_text("깨진 내용", encoding="utf-8")
    assert pl.load_watermark() == pl.EPOCH
    pl.save_watermark("2026-09-15 00:00:00.000", 3)
    assert pl.load_watermark() == "2026-09-15 00:00:00.000"


# ------------------------------------------------------------------ 온보딩 결함 회귀
def test_missing_genre_catalog_does_not_kill_the_request():
    """장르 어휘를 못 읽어도 추천은 나가야 한다.

    배치 시절에는 어휘를 못 읽으면 **멈추는** 것이 옳았다 — 빈 값을 parquet 에 적으면
    그 사용자가 영원히 개인화를 못 받기 때문이다. 요청마다 받는 지금은 다르다. 한 요청의
    장르가 빠질 뿐이라 멈추는 쪽이 더 나쁘다. 대신 mood 는 살아 있어야 하고, 무슨 일이
    있었는지는 로그로 남는다.
    """
    from types import SimpleNamespace

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    seen: dict = {}

    def _recommend(user, n, exclude, explore, plan, ct, cm, **kw):
        seen.update(kw)
        return {"results": [], "diagnostics": {"shortfall": 0}}

    def _boom():
        raise RuntimeError("색인 없음")

    fake = SimpleNamespace(NLP_PARSE_URL="", CAT=SimpleNamespace(pool=[1]),
                           recommend=_recommend, catalog_facets=_boom)
    app = FastAPI()
    internal_api.install(app, fake)
    r = TestClient(app).post("/internal/recommendations/daily",
                             json={"recommendation_id": 1, "user_id": 1,
                                   "onboarding": {"moods": ["고요한"],
                                                  "genres": ["Jazz"]}})
    assert r.status_code == 404, r.text          # 후보 0 → NO_CANDIDATES (500 아님)
    assert "calm" in seen["onboarding"]["mood"], "mood 까지 잃으면 안 된다"
    assert seen["onboarding"]["genre"] == []
    assert seen["onboarding"]["genre_raw"] == ["Jazz"], "원문은 되짚을 수 있어야 한다"


def test_empty_genre_is_reported_and_raw_answer_survives():
    """어휘가 안 맞아 비더라도 사람이 고른 원문은 남아야 되짚을 수 있다."""
    from ai_server.app import onboarding as onb

    ans = onb.from_request({"moods": ["고요한"], "genres": ["Jazz"]}, [])
    assert ans["genre"] == [] and ans["genre_raw"] == ["Jazz"]


# ------------------------------------------------------------------ 5대 결함 회귀
def test_clickhouse_url_scheme_is_never_downgraded(monkeypatch):
    """`https://` 로 적어 둔 ClickHouse 에 평문으로 붙으면 자격증명이 그대로 샌다."""
    from ai_server.tools import push_sections as ps

    monkeypatch.setenv("MUPID_CLICKHOUSE_URL", "https://ch.example.com:8443")
    assert ps._http_base() == "https://ch.example.com:8443"

    monkeypatch.setenv("MUPID_CLICKHOUSE_URL", "jdbc:clickhouse://ch:8443/default")
    monkeypatch.setenv("MUPID_CLICKHOUSE_SECURE", "1")
    assert ps._http_base() == "https://ch:8443"

    monkeypatch.setenv("MUPID_CLICKHOUSE_SECURE", "")
    assert ps._http_base() == "http://ch:8443"


def test_play_events_reach_the_behaviour_signals():
    """`user_behavior()` 는 PLAY_START 로 최근 재생을, PLAY_END 로 완주·초기 스킵을
    센다. 두 이벤트가 들어오지 않으면 그 보정이 통째로 0 이 된다."""
    from ai_server.tools import pull_signals as pl

    class _IM:
        def source_track_id(self, n):
            return "T000001"

    start = pl.to_play_event(
        {"user_id": 7, "track_id": 1, "signal": "PLAY_START", "entry_path": "RECOMMEND",
         "reason": "", "position_ms": 0, "play_id": "p1",
         "ts": "2026-09-15 10:00:00.000"}, _IM())
    assert start["event_type"] == "PLAY_START"
    assert start["context"]["surface"] == "recommendation"

    end = pl.to_play_event(
        {"user_id": 7, "track_id": 1, "signal": "PLAY_END", "entry_path": "RECOMMEND",
         "reason": "skip_next", "position_ms": 3200, "play_id": "p1",
         "ts": "2026-09-15 10:00:03.200"}, _IM())
    assert end["reason"] == "skip_next"
    assert end["position_sec"] == 3.2        # 초기 스킵 판정이 초 단위다
    assert end["event_id"] != start["event_id"]


def test_play_surface_matches_the_recommendation_vocabulary():
    """어휘가 어긋나면 추천 경로 스킵 판정이 통째로 빠진다."""
    from ai_server.app import recommender
    from ai_server.tools import pull_signals as pl

    for entry, surface in pl.SURFACE.items():
        if entry in ("RECOMMEND", "AUTO", "AUTOPLAY", "DAILY", "DAILY_RECOMMEND"):
            assert surface in recommender.BEH_REC_SURFACES, (entry, surface)


def test_behaviour_is_not_rescanned_for_every_request(tmp_path, monkeypatch):
    """추천은 사용자가 기다리는 경로다. 요청마다 로그 전체를 훑으면 안 된다."""
    from ai_server.app import recommender

    log = tmp_path / "events.jsonl"
    log.write_text(json.dumps({
        "user_ref": "u1", "track_id": "T1", "event_type": "PLAY_START",
        "occurred_at": "2026-09-15T10:00:00Z"}) + "\n", encoding="utf-8")
    monkeypatch.setattr(paths, "events_path", lambda: log)
    recommender._BEH_CACHE.clear()

    reads = {"n": 0}
    real_open = type(log).open

    def counting_open(self, *a, **k):
        if self == log:
            reads["n"] += 1
        return real_open(self, *a, **k)

    monkeypatch.setattr(type(log), "open", counting_open)
    now = 1_800_000_000.0
    a = recommender.user_behavior("u1", now=now)
    b = recommender.user_behavior("u1", now=now)
    assert reads["n"] == 1, f"로그를 {reads['n']}번 읽었다"
    assert a["recent_play"] == b["recent_play"]

    # 로그가 자라면 캐시를 버려야 한다
    with real_open(log, "a", encoding="utf-8") as fh:
        fh.write(json.dumps({"user_ref": "u1", "track_id": "T2",
                             "event_type": "PLAY_START",
                             "occurred_at": "2026-09-15T10:00:01Z"}) + "\n")
    recommender.user_behavior("u1", now=now)
    assert reads["n"] == 2, "로그가 바뀌었는데 다시 읽지 않았다"


def test_pull_signals_dedup_does_not_read_the_whole_log(tmp_path, monkeypatch):
    """중복 확인 때문에 로그 전체를 읽던 것을 워터마크 파일로 옮겼다."""
    from ai_server.tools import pull_signals as pl

    wm = tmp_path / "wm.json"
    monkeypatch.setattr(pl, "watermark_path", lambda: wm)
    assert pl.recent_ids() == set()

    pl.save_watermark("2026-09-15 10:00:00.000", 2, ["ch_a", "ch_b"])
    assert pl.recent_ids() == {"ch_a", "ch_b"}
    assert not wm.with_suffix(".json.tmp").exists(), "임시 파일이 남았다"


# ------------------------------------------------------- 구간 적재 · id_map 생성
def _moments_frame():
    """스키마만 진짜인 구간 표. 산출물이 없어도 돈다."""
    import pandas as pd

    return pd.DataFrame([
        # track_id, moment_id, section_idx, section_label, start_sec, end_sec
        ("T000001", "T000001#0", 0, "intro", 0.0, 15.5),
        ("T000001", "T000001#1", 1, "chorus", 15.5, 45.0),
        # ERD ENUM 에 없는 라벨. 억지로 끼워 넣지 않고 빠져야 한다.
        ("T000001", "T000001#2", 2, "other", 45.0, 60.0),
        # 길이가 0 인 구간. 보내면 Spring 이 400 을 준다.
        ("T000001", "T000001#3", 3, "verse", 60.0, 60.0),
    ], columns=["track_id", "moment_id", "section_idx", "section_label",
                "start_sec", "end_sec"])


def test_register_sections_drops_labels_outside_the_erd_enum():
    """추천기는 `other` 를 만들 수 있는데 ERD ENUM 에는 없다.

    다른 라벨에 끼워 넣으면 사용자에게 틀린 구간 유형을 말하게 된다. 빼고 센다."""
    from ai_server.tools import register_sections as rs

    rows, stat = rs.build_payload(_moments_frame())
    assert [r["section_label"] for r in rows] == ["INTRO", "CHORUS"]
    assert stat["unknown_label"] == 1
    assert stat["bad_range"] == 1


def test_register_sections_converts_seconds_to_int_ms():
    """계약은 정수 밀리초다. 초 단위 실수를 그대로 보내면 400 이다."""
    from ai_server.tools import register_sections as rs

    rows, _ = rs.build_payload(_moments_frame())
    assert rows[0] == {"section_index": 0, "external_id": "T000001#0",
                       "section_label": "INTRO", "start_time_ms": 0,
                       "end_time_ms": 15500, "moods": []}
    assert all(isinstance(r["start_time_ms"], int) for r in rows)


def test_register_sections_drops_uid_longer_than_the_column():
    """컬럼이 VARCHAR(64) 다. 넘기면 MySQL 이 조용히 잘라 저장하고, 잘린 값끼리는
    1:1 검사까지 통과해 매핑이 어긋난 채 서비스가 올라온다."""
    import pandas as pd

    from ai_server.tools import register_sections as rs

    long_uid = "T" + "x" * 70 + "#0"
    df = pd.DataFrame([("T1", long_uid, 0, "verse", 0.0, 10.0)],
                      columns=["track_id", "moment_id", "section_idx",
                               "section_label", "start_sec", "end_sec"])
    rows, stat = rs.build_payload(df)
    assert rows == []
    assert stat["long_uid"] == 1


def test_register_sections_track_map_refuses_to_guess(tmp_path):
    """`track_id` → `music_id` 는 받은 표에서만 읽는다. 지어내지 않는다."""
    from ai_server.tools import register_sections as rs

    good = tmp_path / "map.csv"
    good.write_text("source_track_id,music_id\nT000001,1001\n", encoding="utf-8")
    assert rs.read_track_map(good) == {"T000001": 1001}

    # 곡 하나에 번호가 둘이면 어느 쪽이 맞는지 알 수 없다. 멈춘다.
    bad = tmp_path / "dup.csv"
    bad.write_text("source_track_id,music_id\nT1,1\nT1,2\n", encoding="utf-8")
    with pytest.raises(SystemExit):
        rs.read_track_map(bad)

    # 컬럼 이름이 다르면 무엇을 읽어야 할지 알 수 없다.
    wrong = tmp_path / "wrong.csv"
    wrong.write_text("track,id\nT1,1\n", encoding="utf-8")
    with pytest.raises(SystemExit):
        rs.read_track_map(wrong)


def test_register_sections_writes_id_map_from_issued_numbers(tmp_path):
    """응답으로 받은 번호 짝이 그대로 매핑표가 된다. 숫자를 만들지 않는다."""
    import pandas as pd

    from ai_server.app import ids
    from ai_server.tools import build_id_map

    pairs = [
        {"source_track_id": "T000001", "music_id": 1001,
         "external_id": "T000001#0", "music_section_analysis_id": 4101},
        {"source_track_id": "T000001", "music_id": 1001,
         "external_id": "T000001#1", "music_section_analysis_id": 4102},
    ]
    dst = tmp_path / "id_map.parquet"
    tracks, sections = build_id_map.write_map(
        pd.DataFrame(pairs)[list(ids.COLS)], dst)
    assert (tracks, sections) == (1, 2)

    im = ids.IdMap(path=dst, strict=True)
    assert im.music_id("T000001") == 1001
    assert im.music_section_analysis_id("T000001#1") == 4102
    assert im.external_id(4101) == "T000001#0"


def test_register_sections_id_map_rejects_two_songs_sharing_a_number(tmp_path):
    """`music_id` 하나에 원본이 둘이면 곡이 중복 노출된다. 쓰지 않는다."""
    import pandas as pd

    from ai_server.app import ids
    from ai_server.tools import build_id_map

    pairs = [
        {"source_track_id": "T1", "music_id": 7,
         "external_id": "T1#0", "music_section_analysis_id": 1},
        {"source_track_id": "T2", "music_id": 7,
         "external_id": "T2#0", "music_section_analysis_id": 2},
    ]
    dst = tmp_path / "id_map.parquet"
    with pytest.raises(SystemExit):
        build_id_map.write_map(pd.DataFrame(pairs)[list(ids.COLS)], dst)
    assert not dst.exists(), "1:1 이 깨졌는데 파일을 남겼다"


# ------------------------------------------- ClickHouse 행동 조회 (요청마다 직접)
def _ch_row(event_type, src="T000001", ts_ms=1_700_000_000_000, position_ms=0,
            entry_path="RECOMMEND", play_id="p1"):
    return {"event_type": event_type, "src_track": src, "ts_ms": str(ts_ms),
            "position_ms": str(position_ms), "entry_path": entry_path,
            "play_id": play_id}


def test_clickhouse_maps_track_skip_to_early_skip():
    """파일 로그는 Early Skip 을 `PLAY_END` + `reason='skip_next'` 로 적었는데
    ClickHouse 에는 `TRACK_SKIP` 이 따로 있다. 옮겨 주지 않으면 스킵 감점이 통째로
    빠진 채 조용히 돈다."""
    from ai_server.app import clickhouse as ch

    e = ch._to_recommender_shape(_ch_row("TRACK_SKIP", position_ms=2000))
    assert e["event_type"] == "PLAY_END"
    assert e["reason"] == "skip_next"
    assert e["position_sec"] == 2.0
    assert e["surface"] == "recommendation"


def test_clickhouse_maps_play_end_to_completion():
    """ClickHouse 의 PLAY_END 는 곡이 끝난 것이다 — 의도한 스킵은 TRACK_SKIP 이다."""
    from ai_server.app import clickhouse as ch

    e = ch._to_recommender_shape(_ch_row("PLAY_END"))
    assert (e["event_type"], e["reason"]) == ("PLAY_END", "track_done")


def test_clickhouse_surface_vocabulary_matches_the_recommender():
    """`entry_path` 를 그대로 넘기면 `BEH_REC_SURFACES` 와 한 건도 안 맞아
    스킵 감점이 전부 버려진다."""
    from ai_server.app import clickhouse as ch
    from ai_server.app import recommender

    mapped = ch.SURFACE["RECOMMEND"]
    assert mapped in recommender.BEH_REC_SURFACES


def test_behavior_aggregation_is_the_same_from_either_source():
    """출처가 파일이든 ClickHouse 든 같은 점수가 나와야 한다.

    집계가 두 벌이 되면 한쪽만 고쳐져 같은 사용자에게 다른 추천이 나간다."""
    from ai_server.app import clickhouse as ch
    from ai_server.app import recommender

    now = 1_700_000_100.0
    ts_ms = 1_700_000_000_000

    # ClickHouse 판: 완주 1회 + LIKE
    ch_events = [ch._to_recommender_shape(_ch_row("PLAY_END", ts_ms=ts_ms)),
                 ch._to_recommender_shape(_ch_row("LIKE", ts_ms=ts_ms + 1000))]

    # 파일 판: 같은 내용을 파일 로그 어휘로
    file_events = [
        {"track_id": "T000001", "event_type": "PLAY_END", "ts": ts_ms / 1000,
         "reason": "track_done", "position_sec": 1e9,
         "surface": "recommendation", "play_id": "p1"},
        {"track_id": "T000001", "event_type": "LIKE", "ts": ts_ms / 1000 + 1,
         "reason": "", "position_sec": 1e9, "surface": "recommendation",
         "play_id": "p1"},
    ]

    a = recommender._aggregate_behavior(ch_events, now)
    b = recommender._aggregate_behavior(file_events, now)
    assert a["delta"] == b["delta"]
    assert a["like"] == b["like"] == {"T000001"}
    assert a["complete"] == b["complete"] == {"T000001": 1}


def test_early_skip_outside_recommendation_is_not_a_negative_signal():
    """검색으로 들어와 넘긴 것은 추천의 잘못이 아니다. 감점하지 않는다."""
    from ai_server.app import clickhouse as ch
    from ai_server.app import recommender

    now = 1_700_000_100.0
    from_search = [ch._to_recommender_shape(
        _ch_row("TRACK_SKIP", position_ms=1000, entry_path="SEARCH"))]
    from_rec = [ch._to_recommender_shape(
        _ch_row("TRACK_SKIP", position_ms=1000, entry_path="RECOMMEND"))]

    assert recommender._aggregate_behavior(from_search, now)["skip_pen"] == {}
    assert recommender._aggregate_behavior(from_rec, now)["skip_pen"], "추천 경로 스킵이 무시됐다"


def test_behavior_injection_skips_the_file_entirely(tmp_path, monkeypatch):
    """`events` 를 주면 파일을 읽지 않는다 — 파일이 없어도 돌아야 한다."""
    from ai_server.app import recommender

    monkeypatch.setattr(paths, "events_path", lambda: tmp_path / "없는파일.jsonl")
    out = recommender.user_behavior("internal:1", now=1_700_000_100.0, events=[])
    assert out["events"] == 0 and out["delta"] == {}


# ------------------------------------------------ 온보딩을 요청으로 받기
def test_onboarding_passes_code_names_through_unchanged():
    """Spring 코드표와 카탈로그가 **같은 어휘**가 됐다. 옮길 것이 없다.

    전환 전에는 둘이 한 건도 겹치지 않아 변환표가 필요했고, 그 표가 세분류 45종을
    하나도 잇지 못해 장르 축이 조용히 비었다.
    """
    from ai_server.app import onboarding as onb

    ans = onb.from_request({"moods": ["고요한"], "genres": ["modern_jazz"]},
                           ["modern_jazz", "indie_rock"])
    assert ans["mood"] == ["calm"]              # 한국어 표시명 → 코드 이름
    assert ans["genre"] == ["modern_jazz"]      # 코드 이름 그대로
    assert ans["mood_raw"] == ["고요한"] and ans["genre_raw"] == ["modern_jazz"]


def test_onboarding_from_request_fills_every_axis_the_recommender_reads():
    """빈 축을 키째 빼면 NaN 이 되고, NaN 은 truthy 라 `x not in NaN` 에서 터진다."""
    from ai_server.app import onboarding as onb
    from ai_server.app import recommender

    ans = onb.from_request({"moods": ["고요한"], "genres": []}, [])
    for axis in recommender.ONB_LIST_FIELDS:
        assert axis in ans, axis
        assert isinstance(ans[axis], list)


def test_onboarding_from_request_is_none_when_nothing_was_answered():
    """답이 없으면 None 이다 — 빈 답변을 온보딩으로 치면 콜드스타트를 숨긴다."""
    from ai_server.app import onboarding as onb

    assert onb.from_request(None) is None
    assert onb.from_request({"moods": [], "genres": []}) is None


def test_onboarding_batch_is_gone():
    """온보딩은 요청으로 온다. 아무도 읽지 않는 parquet 를 채우는 배치를 남기지 않는다."""
    import importlib

    with pytest.raises(ModuleNotFoundError):
        importlib.import_module("ai_server.tools.fetch_onboarding")


def test_injected_onboarding_wins_over_the_file():
    """요청으로 온 답변이 우선이다. 파일은 없을 수도 있다."""
    from ai_server.app import recommender

    injected = {"mood": ["relaxing"], "genre": []}
    assert recommender.onboarding_of("internal:1", injected) is injected


def test_request_carries_onboarding_into_recommend():
    """DTO 에 실린 온보딩이 추천기까지 닿는지. 중간에서 떨어지면 개인화가 0 이다."""
    from types import SimpleNamespace

    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    seen: dict = {}

    def _recommend(user, n, exclude, explore, plan, ct, cm, **kw):
        seen.update(kw)
        return {"results": [], "diagnostics": {"shortfall": 0}}

    fake = SimpleNamespace(
        NLP_PARSE_URL="", CAT=SimpleNamespace(pool=[1]), recommend=_recommend,
        catalog_facets=lambda: {"genre": [{"value": "modern_jazz"}]})
    app = FastAPI()
    internal_api.install(app, fake)
    TestClient(app).post("/internal/recommendations/daily",
                         json={"recommendation_id": 1, "user_id": 1,
                               "onboarding": {"moods": ["고요한"],
                                              "genres": ["modern_jazz"]}})
    assert seen["onboarding"] is not None, "온보딩이 추천기까지 닿지 않았다"
    # 한국어 표시명은 코드 이름으로, 코드 이름은 그대로 — 어휘가 하나다.
    assert seen["onboarding"]["mood"] == ["calm"]
    assert seen["onboarding"]["genre"] == ["modern_jazz"]


# ------------------------------------------------------------------ 로컬 실행 CLI
def test_serve_env_file_does_not_override_the_shell(tmp_path, monkeypatch):
    """셸에서 준 값이 파일보다 세다 — 한 번만 다르게 띄울 때 파일을 고치지 않는다."""
    import os

    from ai_server.tools import serve

    env = tmp_path / ".env"
    env.write_text("MUPID_AI_PORT=8100\nMUPID_POOL=flow\n", encoding="utf-8")
    monkeypatch.setenv("MUPID_AI_PORT", "9999")
    monkeypatch.delenv("MUPID_POOL", raising=False)

    serve.load_env_file(env)
    assert os.environ["MUPID_AI_PORT"] == "9999", "셸 값을 덮었다"
    assert os.environ["MUPID_POOL"] == "flow"


def test_serve_env_file_parses_comments_and_quotes(tmp_path, monkeypatch):
    """주석·빈 줄·따옴표를 그대로 환경에 올리면 경로가 조용히 틀어진다."""
    import os

    from ai_server.tools import serve

    env = tmp_path / ".env"
    env.write_text('# 주석\n\nMUPID_POOL="flow"\nMUPID_COMBINER=a.json  \n'
                   'BROKEN_LINE\n', encoding="utf-8")
    for key in ("MUPID_POOL", "MUPID_COMBINER"):
        monkeypatch.delenv(key, raising=False)

    assert serve.load_env_file(env) == 2
    assert os.environ["MUPID_POOL"] == "flow"
    assert os.environ["MUPID_COMBINER"] == "a.json"


def test_serve_env_file_is_optional(tmp_path):
    """없어도 돈다 — 배포는 환경변수로만 준다."""
    from ai_server.tools import serve

    assert serve.load_env_file(tmp_path / "없는파일") == 0


def test_serve_reports_what_is_missing_instead_of_starting(clean_env, tmp_path):
    """산출물이 없으면 띄우지 않고 무엇이 없는지 말한다.

    이 저장소가 반복해서 밟은 함정이 "없어도 정상처럼 뜨고 기능만 조용히 꺼지는" 것이다.
    """
    from ai_server.tools import serve

    clean_env.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    ok, lines = serve.preflight()
    assert not ok, "색인이 없는데 뜰 수 있다고 했다"
    assert any("data/proc/mupid" in line for line in lines), lines


def test_serve_is_listed_as_a_tool():
    """cron·문서가 `python -m ai_server.tools.<이름>` 으로 부른다. import 가 돼야 한다."""
    import importlib

    m = importlib.import_module("ai_server.tools.serve")
    assert callable(m.main) and callable(m.preflight)


def test_register_sections_maps_ai_mood_tags_to_code_names():
    """번호는 보내지 않는다 — 코드 번호는 MySQL 이 발급한다. 이름으로 보내면 Spring 이 붙인다.

    옮기지 못한 태그는 버린다. 지어내서 보내면 화면이 틀린 분위기를 말한다.
    """
    from ai_server.tools import register_sections as rs

    # Spring `code_name`(영문)으로 보낸다. Spring 은 표시명(한국어)도 받지만 코드명이 기준이다.
    assert rs.moods_of("relaxing, meditative") == ["serene"]
    assert rs.moods_of(["energetic", "sad"]) == ["energetic", "emotional"]
    assert rs.moods_of("없는태그") == []
    assert rs.moods_of(None) == []


def test_register_sections_attaches_track_moods_to_every_section():
    """AI 카탈로그는 구간별 무드를 갖고 있지 않다. 곡의 것을 그 곡의 모든 구간에 붙인다."""
    from ai_server.tools import register_sections as rs

    rows, _ = rs.build_payload(_moments_frame(), ["serene"])
    assert rows, "구간이 비었다"
    assert all(r["moods"] == ["serene"] for r in rows)
# ── pull_signals 의 시각 규약 ────────────────────────────────────────────────

class _BackMap:
    """숫자 → 문자열 되돌리기만 하는 최소 매핑."""

    def source_track_id(self, n):
        return f"T{int(n):06d}"


def _ch_signal_row(now: float, age_sec: float) -> dict:
    """ClickHouse 가 돌려주는 한 줄. `ts` 는 서버(Asia/Seoul), `ts_utc` 는 UTC 다.

    **고정 시각으로 만든다.** 로컬 시계를 쓰면 이 시험 자체가 타임존에 의존한다.
    """
    from datetime import datetime, timedelta, timezone

    utc = datetime.fromtimestamp(now - age_sec, timezone.utc)
    seoul = utc + timedelta(hours=9)          # 서버 타임존이 Asia/Seoul 이다
    return {"user_id": 4_200_000_999, "track_id": 1001, "signal": "PLAY_START",
            "entry_path": "RECOMMEND", "play_id": "p-age",
            "ts": seoul.strftime("%Y-%m-%d %H:%M:%S"),
            "ts_utc": utc.strftime("%Y-%m-%d %H:%M:%S")}


def _behavior_for(events: list, user: str, now: float, tmp_path, monkeypatch) -> dict:
    from ai_server.app import recommender as rc

    log = tmp_path / f"events_{abs(hash(json.dumps(events)))}.jsonl"
    log.write_text("".join(json.dumps(e, ensure_ascii=False) + "\n" for e in events),
                   encoding="utf-8")
    monkeypatch.setattr(paths, "events_path", lambda: log)
    rc._BEH_CACHE.clear()
    return rc.user_behavior(user, now=now)


def test_pull_signals_event_age_matches_real_elapsed(tmp_path, monkeypatch):
    """5시간 전 재생은 **5시간 전**으로 읽혀야 한다.

    예전에는 서버 타임존(`Asia/Seoul`) 문자열 뒤에 `"Z"` 를 붙여 내보냈다. 그러면
    `user_behavior()` 가 `fromisoformat` 으로 읽어 `time.time()` 과 비교할 때 모든
    이벤트가 **9시간 미래**가 된다. 오류는 나지 않고 추천만 이상해진다 — 최근 재생
    제외(4시간)가 풀리지 않고, 스킵 감점의 시간 감쇠가 음수 나이로 계산된다.
    """
    from datetime import datetime

    from ai_server.tools import pull_signals as pu

    NOW = 1_789_000_000.0                      # 고정. 로컬 시계에 의존하지 않는다
    AGE = 5 * 3600                             # RECENT_EXCLUDE_SEC(4시간)보다 오래됐다
    row = _ch_signal_row(NOW, AGE)

    ev = pu.to_play_event(row, _BackMap())
    assert ev is not None
    got = datetime.fromisoformat(ev["occurred_at"].replace("Z", "+00:00")).timestamp()
    assert abs((NOW - got) - AGE) < 1.0, (
        f"나이가 {(NOW - got) / 3600:.1f}시간으로 읽힌다 (실제 {AGE / 3600:.0f}시간)")

    beh = _behavior_for([ev], ev["user_ref"], NOW, tmp_path, monkeypatch)
    assert beh["events"] == 1
    assert ev["track_id"] not in beh["recent_play"], (
        "4시간이 넘었는데 최근 재생으로 잡힌다 — 시각이 미래로 읽혔다")
    assert ev["track_id"] not in beh["recent_pen"]


def test_pull_signals_seoul_string_with_z_would_be_read_as_future(tmp_path, monkeypatch):
    """고치기 전 형태를 그대로 넣어 **증상이 재현되는지** 본다.

    이 시험이 없으면 누가 `ts_utc` 를 지우고 `ts` 로 되돌려도 값 검사만으로는 통과한다.
    """
    from ai_server.tools import pull_signals as pu

    NOW = 1_789_000_000.0
    AGE = 5 * 3600
    row = _ch_signal_row(NOW, AGE)

    ev = pu.to_play_event(row, _BackMap())
    broken = dict(ev, occurred_at=str(row["ts"]).replace(" ", "T") + "Z")   # 예전 방식
    beh = _behavior_for([broken], broken["user_ref"], NOW, tmp_path, monkeypatch)

    # 5시간 전인데 4시간 미래로 읽혀 "방금 들은 곡" 이 된다.
    assert broken["track_id"] in beh["recent_play"]
    assert beh["recent_pen"].get(broken["track_id"]) == pytest.approx(
        __import__("ai_server.app.recommender", fromlist=["x"]).BEH_W_RECENT)

    # 고친 쪽은 같은 사건을 제대로 읽는다.
    ok = _behavior_for([ev], ev["user_ref"], NOW, tmp_path, monkeypatch)
    assert ev["track_id"] not in ok["recent_play"]


# ---------------------------------------- 매핑 근거가 색인 안에 있다 (파일 없이)

def _plate_with_music_id(root, pairs, *, with_col=True):
    """`flow_meta.parquet` 만 있는 판 하나. `pairs` 는 (track_id, music_id) 다."""
    import pandas as pd

    data = root / "data" / "proc" / "mupid"
    data.mkdir(parents=True, exist_ok=True)
    frame = {"track_id": [t for t, _ in pairs], "title": ["x"] * len(pairs)}
    if with_col:
        frame["music_id"] = pd.array([m for _, m in pairs], dtype="Int64")
    pd.DataFrame(frame).to_parquet(data / "flow_meta.parquet", index=False)
    return data


def test_id_map_falls_back_to_the_index(clean_env, tmp_path, monkeypatch):
    """별도 매핑 파일을 두지 않는다 — 근거는 `flow_meta.music_id` 다.

    숫자를 쓰는 곳은 배치 도구 둘뿐이고 둘 다 곡 수준만 쓴다. 그 값은 색인과 함께
    버전이 매겨져 배포되므로 판과 매핑이 어긋날 자리가 없다.
    """
    monkeypatch.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    from ai_server.app import ids as idsmod

    _plate_with_music_id(tmp_path, [("T000001", 1), ("T000002", 2)])
    im = idsmod.IdMap(strict=True)

    assert im.loaded and im.stats()["source"] == "flow_meta"
    assert im.music_id("T000001") == 1
    assert im.source_track_id(2) == "T000002"


def test_section_conversion_stays_unavailable(clean_env, tmp_path, monkeypatch):
    """구간 PK 를 환산하는 코드는 저장소에 없다. 없는 채로 두고 오류를 낸다."""
    monkeypatch.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    from ai_server.app import ids as idsmod

    _plate_with_music_id(tmp_path, [("T000001", 1)])
    im = idsmod.IdMap(strict=True)

    assert im.stats()["sections"] == 0
    with pytest.raises(idsmod.MappingError):
        im.music_section_analysis_id("T000001#0")


def test_missing_music_id_column_is_not_guessed(clean_env, tmp_path, monkeypatch):
    """`T000001` 에서 1 을 뽑지 않는다.

    시딩이 그렇게 만들었을 뿐 계약이 아니다 — `T000847_1` 처럼 규칙 밖 값이 실제로
    있었다. 어긋나면 오류 없이 엉뚱한 곡을 추천하게 된다.
    """
    monkeypatch.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    from ai_server.app import ids as idsmod

    _plate_with_music_id(tmp_path, [("T000001", 1)], with_col=False)
    im = idsmod.IdMap(strict=False)

    assert not im.loaded
    assert "music_id" in (im.error or "")
    with pytest.raises(idsmod.MappingError):
        im.music_id("T000001")


def test_a_stray_map_file_is_ignored_not_obeyed(clean_env, tmp_path, monkeypatch,
                                                capsys):
    """판 자리의 매핑표는 규칙상 없어야 한다. 있어도 읽지 않는다.

    조용히 그쪽이 이기면 낡은 표가 색인을 덮어 **오류 없이** 엉뚱한 곡을 가리킨다.
    """
    import pandas as pd

    monkeypatch.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    from ai_server.app import ids as idsmod

    data = _plate_with_music_id(tmp_path, [("T000001", 1)])
    pd.DataFrame({"source_track_id": ["T000001"], "music_id": [999],
                  "external_id": ["T000001#0"],
                  "music_section_analysis_id": [9330]}
                 ).to_parquet(data / "id_map.parquet", index=False)

    im = idsmod.IdMap(strict=True)

    assert im.stats()["source"] == "flow_meta"
    assert im.music_id("T000001") == 1              # 표의 999 가 아니다
    assert "규칙상 없어야 할 매핑표" in capsys.readouterr().err


def test_an_explicitly_given_path_is_still_read(clean_env, tmp_path):
    """도구가 만든 표를 그 자리에서 검사하는 길은 남는다 — `build_id_map` 이 쓴다."""
    import pandas as pd
    from ai_server.app import ids as idsmod

    out = tmp_path / "made.parquet"
    pd.DataFrame({"source_track_id": ["T000001"], "music_id": [1],
                  "external_id": ["T000001#0"],
                  "music_section_analysis_id": [9330]}).to_parquet(out, index=False)

    im = idsmod.IdMap(path=out, strict=True)
    assert im.stats()["source"] == "id_map"
    assert im.music_section_analysis_id("T000001#0") == 9330


def test_retag_puts_music_id_in_the_index(tmp_path):
    """`retag_flow_meta` 가 DB 표의 열을 그대로 싣는다 — 쪼개서 만들지 않는다."""
    import pandas as pd
    from ai_server.tools import retag_flow_meta as rt

    snap, plate = tmp_path / "snap", tmp_path / "plate"
    snap.mkdir(); plate.mkdir()
    pd.DataFrame({"code_id": [49, 40], "code_type": ["GENRE", "MOOD"],
                  "code_name": ["ambient", "calm"]}).to_parquet(snap / "codes.parquet")
    pd.DataFrame({"external_id": ["T000001#0", "T000847_1#0"],
                  "music_id": [1, 847]}).to_parquet(
        snap / "music_section_analysis.parquet")
    pd.DataFrame({"music_id": [1], "genre_code_id": [49]}).to_parquet(
        snap / "music_genres.parquet")
    pd.DataFrame({"music_id": [1], "mood_code_id": [40]}).to_parquet(
        snap / "music_moods.parquet")
    pd.DataFrame({"track_id": ["T000001", "T000847_1"],
                  "genre_top1": ["x", "y"]}).to_parquet(plate / "flow_meta.parquet")

    rt.build(plate, snap)
    out = pd.read_parquet(plate / "flow_meta.parquet")

    assert list(out.music_id) == [1, 847]
    # 규칙 밖 이름도 표를 통해 제 번호를 받는다 — 쪼갰다면 847 이 나올 수 없다
    assert out.loc[out.track_id == "T000847_1", "music_id"].iloc[0] == 847
