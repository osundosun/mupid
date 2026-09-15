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


# ------------------------------------------------------------------ 태그 어휘
def _fake_facets_svc():
    """`catalog_facets()` 모양만 흉내 낸다. 산출물을 읽지 않는다."""
    from types import SimpleNamespace

    import pandas as pd

    df = pd.DataFrame([{"track_id": "T1", "bpm": 90.0}, {"track_id": "T2", "bpm": 140.0}])
    fac = {
        "language": [{"value": "ko", "label": "ko", "tracks": 2}],
        "vocal_type": [{"value": "female", "label": "female", "tracks": 1}],
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

    def _recommend(user, n, exclude, explore, plan, ct, cm):
        seen["plan"] = plan
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


# ------------------------------------------------------------------ 산출물 상태
def test_artifact_status_names_what_is_missing(clean_env, tmp_path):
    """없어도 서버는 뜬다. 그래서 무엇이 없는지 응답에 보여야 한다."""
    clean_env.setenv("MUPID_AI_ARTIFACT_DIR", str(tmp_path))
    from ai_server.app import main, recommender

    clean_env.setattr(recommender, "OUT_DIR", tmp_path / "data/proc/mupid")
    st = main.artifact_status()
    assert "labels_service.parquet" in st["missing"]
    assert "id_map.parquet" in st["missing"]
    # 왜 필요한지도 함께 — 없다는 사실만으로는 무엇이 꺼졌는지 알 수 없다.
    assert st["files"]["labels_service.parquet"]["why"]


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
def _out_with_label(label: str) -> dict:
    return {"diagnostics": {"query_source": "labels", "shortfall": 0},
            "results": [{"rank": 1, "track_id": "A", "moment_id": "A#0",
                         "section_label": label, "start_sec": 1.0, "end_sec": 2.0,
                         "final_score": 1.0, "matched_dna": "u#dna0",
                         "slot_source": "dna0", "clap_score": None},
                        {"rank": 2, "track_id": "B", "moment_id": "B#0",
                         "section_label": "chorus", "start_sec": 1.0, "end_sec": 2.0,
                         "final_score": 0.9, "matched_dna": "u#dna0",
                         "slot_source": "dna0", "clap_score": None}]}


@pytest.fixture
def mapped(monkeypatch):
    im = ids.IdMap(path=Path("/nonexistent.parquet"), strict=False)
    im.track_to_num = {"A": 1, "B": 2}
    im.sec_to_num = {"A#0": 11, "B#0": 22}
    monkeypatch.setattr(ids, "_MAP", im)
    return im


def test_section_label_outside_erd_enum_is_not_sent(mapped):
    """추천기는 `other` 를 만들 수 있지만 ERD ENUM 에는 없다.
    그대로 보내면 Spring 의 SectionLabel 역직렬화가 터진다."""
    resp = internal_api.to_response(1, _out_with_label("other"), 2, False)
    assert [i.section_label for i in resp.items] == ["CHORUS"]
    assert resp.items[0].rank_order == 1, "남은 것의 순번은 다시 붙인다"


def test_dropped_label_is_counted_in_shortfall(mapped):
    """떨궜으면 부족분에 적어야 한다. 아니면 BE 가 왜 적게 왔는지 알 수 없다."""
    resp = internal_api.to_response(1, _out_with_label("other"), 2, False)
    assert resp.shortfall == 1


def test_known_labels_pass_through(mapped):
    for label in ("intro", "verse", "pre_chorus", "chorus", "bridge",
                  "break", "inst", "solo", "outro"):
        resp = internal_api.to_response(1, _out_with_label(label), 2, False)
        assert len(resp.items) == 2, label


def test_section_enum_matches_spring(mapped):
    """Spring 의 SectionLabel 과 어긋나면 그 차이가 곧 500 이다."""
    java = (Path(__file__).resolve().parents[2] / "api/src/main/java/com/osundosun"
            / "mupid/api/music/enumerate/SectionLabel.java")
    if not java.exists():
        pytest.skip("api 모듈이 없는 체크아웃")
    names = set(re.findall(r"^\s{4}([A-Z_]+)\(", java.read_text(encoding="utf-8"),
                           flags=re.M))
    assert names == set(internal_api._SECTION_ENUM), names ^ set(internal_api._SECTION_ENUM)


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


@pytest.mark.parametrize("name", ["recommender", "internal_api", "ids", "main", "paths"])
def test_every_referenced_global_exists(name):
    import importlib

    mod = importlib.import_module(f"ai_server.app.{name}")
    assert _unresolved_globals(mod) == []


def test_labeler_script_ships_with_the_code(clean_env):
    """라벨러는 산출물이 아니라 코드와 함께 온다. 경로가 틀리면 subprocess 가 조용히 실패한다."""
    from ai_server.app import recommender

    assert (recommender.LEGACY / "m09_labels.py").exists()


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
    r = subprocess.run(["git", "check-ignore", "-q", "infra/deploy/.env"],
                       cwd=root, capture_output=True)
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
def similar_client(monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    im = ids.IdMap(path=Path("/nonexistent.parquet"), strict=False)
    im.track_to_num = {"A": 1, "B": 2, "C": 3}
    im.num_to_track = {v: k for k, v in im.track_to_num.items()}
    im.sec_to_num = {"A#0": 10, "B#1": 21, "C#2": 32}
    im.num_to_sec = {v: k for k, v in im.sec_to_num.items()}
    monkeypatch.setattr(ids, "_MAP", im)

    def build(rows):
        app = FastAPI()
        internal_api.install(app, _similar_svc(rows))
        return TestClient(app)

    return build


def test_similar_returns_numeric_ids(similar_client):
    c = similar_client([_srow("B", 1, sim=0.91), _srow("C", 2, sim=0.77)])
    r = c.post("/internal/similar", json={"music_section_id": 10, "limit": 5})
    assert r.status_code == 200, r.text
    body = r.json()
    assert [i["music_id"] for i in body["items"]] == [2, 3]
    assert [i["music_section_id"] for i in body["items"]] == [21, 32]
    assert [i["rank_order"] for i in body["items"]] == [1, 2]
    assert body["items"][0]["start_time_ms"] == 10_000
    assert body["embedding_version"]


def test_similar_has_no_personalization_field(similar_client):
    """개인화가 없으므로 사유·점수 필드를 만들어 붙이지 않는다."""
    c = similar_client([_srow("B", 1)])
    body = c.post("/internal/similar", json={"music_section_id": 10}).json()
    assert "reason" not in body["items"][0]
    assert "reason_code" not in body["items"][0]


def test_similar_rejects_unknown_section(similar_client):
    c = similar_client([_srow("B", 1)])
    r = c.post("/internal/similar", json={"music_section_id": 999})
    assert r.status_code == 404
    assert r.json()["error_code"] == "SECTION_ID_MAPPING_NOT_FOUND"


def test_similar_drops_labels_outside_erd_enum(similar_client):
    c = similar_client([_srow("B", 1, label="other"), _srow("C", 2)])
    body = c.post("/internal/similar", json={"music_section_id": 10, "limit": 2}).json()
    assert [i["section_label"] for i in body["items"]] == ["CHORUS"]
    assert body["shortfall"] == 1


def test_similar_rejects_user_id(similar_client):
    """개인화 API 가 아니다. user_id 를 받으면 계약을 오해한 것이다."""
    c = similar_client([_srow("B", 1)])
    r = c.post("/internal/similar", json={"music_section_id": 10, "user_id": 1})
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
    c.post("/internal/similar", json={"music_section_id": 10})
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
    assert c.post("/internal/similar", json={"music_section_id": 10}).status_code == 200


def test_internal_token_is_enforced_when_set(similar_client, monkeypatch):
    monkeypatch.setenv("MUPID_AI_INTERNAL_TOKEN", "s3cret")
    c = similar_client([_srow("B", 1)])
    body = {"music_section_id": 10}

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


# ------------------------------------------------------------------ 배포 파일
def test_dockerfile_uses_cpu_torch_and_excludes_artifacts():
    root = Path(__file__).resolve().parents[2]
    df = (root / "ai_server" / "Dockerfile").read_text(encoding="utf-8")
    assert "download.pytorch.org/whl/cpu" in df, "기본 PyPI torch 는 CUDA 를 끌고 온다"
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
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app" / "legacy"))
    import m11_stage1 as s1

    from ai_server.app import stage1_np

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


def test_reference_section_is_returned_as_numeric_ids(mapped):
    """'당신이 저장한 이 구간과 닮아서' 를 만들 수 있어야 한다."""
    item = internal_api.to_response(1, _out_with_ref("B#0"), 1, False).items[0]
    assert item.ref_music_id == 2 and item.ref_music_section_id == 22


def test_unmapped_reference_empties_the_field_not_the_response(mapped):
    """근거 구간이 매핑표에 없다고 추천 전체를 실패시키지 않는다."""
    item = internal_api.to_response(1, _out_with_ref("Z#9"), 1, False).items[0]
    assert item.ref_music_id is None and item.ref_music_section_id is None
    assert item.music_id == 1, "추천 자체는 살아 있어야 한다"


def test_reference_does_not_reintroduce_scores(mapped):
    """근거를 노출한다고 모델 수치까지 따라 나가면 3번을 되돌리는 것이다."""
    import json

    body = json.dumps(internal_api.to_response(1, _out_with_ref("B#0"), 1,
                                               False).model_dump())
    for leaked in ("0.9", "prob", "model_score", "moment_similarity", "matched_dna_rep",
                   "A#0", "B#0"):
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
