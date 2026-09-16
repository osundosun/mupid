"""모델·색인 없이 도는 계약 테스트.

`test_smoke.py` 는 산출물이 있어야 의미가 있어 CI 에서 통째로 건너뛴다. 그러면 경로 해석,
오류 코드, 응답 문장 같은 **산출물과 무관한 회귀**를 아무도 못 잡는다. 여기서는 파일을
읽지 않는 것만 본다.
"""
from __future__ import annotations

import json
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
    for gone in ("music_id", "music_section_id", "start_time_ms", "section_label"):
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


def test_no_bare_print_in_startup_path():
    """진단은 log() 로만 남긴다. 맨 print 는 인코딩에 따라 예외를 낸다."""
    import ast

    app_dir = Path(__file__).resolve().parents[1] / "app"
    for f in app_dir.glob("*.py"):
        if f.name in ("log.py", "ids.py"):      # ids.py 의 print 는 CLI 전용이다
            continue
        for node in ast.walk(ast.parse(f.read_text(encoding="utf-8"))):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) \
                    and node.func.id == "print":
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
    header = header or "source_track_id\tmusic_id\tsource_section_uid\tmusic_section_id"
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

    src = _tsv(tmp_path, ["A#0\t10"], header="source_section_uid\tmusic_section_id")
    with pytest.raises(SystemExit):        # music_id 가 없다
        b.build(b.read_table(src))

    ok = _tsv(tmp_path, ["A#0\t10\t1"],
              header="source_section_uid\tmusic_section_id\tmusic_id")
    got = b.build(b.read_table(ok))
    assert got.source_track_id.iloc[0] == "A"        # 파생이 기본 동작이다

    with pytest.raises(SystemExit):        # 파생을 막으면 예전처럼 멈춘다
        b.build(b.read_table(ok), derive_track_id=False)


def test_build_id_map_reads_csv_too(tmp_path):
    """내보내는 도구마다 구분자가 다르다."""
    from ai_server.tools import build_id_map as b

    p = tmp_path / "t.csv"
    p.write_text("source_track_id,music_id,source_section_uid,music_section_id\n"
                 "A,1,A#0,10\n", encoding="utf-8")
    assert len(b.build(b.read_table(p))) == 1


# ------------------------------------------------------------------ 온보딩 수신
def _onb_body(user_id=42, submitted=True):
    return {"message": None,
            "data": {"user_id": user_id, "submitted": submitted,
                     "submitted_at": "2026-09-14T10:32:26" if submitted else None,
                     "moods": [{"code_id": 1, "name": "잔잔한"},
                               {"code_id": 3, "name": "몽환적인"}] if submitted else [],
                     "genres": [{"code_id": 15, "name": "Jazz"}] if submitted else []}}


# ------------------------------------------------------------------ 어휘 변환
_AI_GENRE = ["K-pop", "Ballad", "Alternative Rock", "Contemporary R&B", "Downtempo",
             "House", "Pop Rock", "Indie Rock", "Synthwave", "J-pop", "Cloud Rap",
             "Synth-pop", "Contemporary Jazz", "Jazzy Hip-Hop", "Neo-Classical",
             "Hard Rock", "Trap", "Folk", "Indie Pop"]
_SPRING_MOOD = ["잔잔한", "벅찬", "몽환적인", "신나는", "집중되는", "감성적인", "강렬한", "설레는"]
_SPRING_GENRE = ["Electronic", "Indie", "R&B", "Rock", "Pop", "Hip-hop", "Jazz", "Classical"]


def test_every_spring_code_reaches_the_catalog():
    """Spring 코드표와 AI 어휘는 **한 건도 그대로 겹치지 않는다**(2026-09-15 실측).
    변환이 하나라도 비면 그 답변은 추천에 전혀 반영되지 않는다."""
    from ai_server.app import onboarding as f

    dead_mood = [m for m in _SPRING_MOOD if not f.map_mood([m])]
    dead_genre = [g for g in _SPRING_GENRE if not f.map_genre([g], _AI_GENRE)]
    assert not dead_mood, f"변환되지 않는 mood: {dead_mood}"
    assert not dead_genre, f"변환되지 않는 genre: {dead_genre}"


def test_mapped_values_exist_in_the_catalog():
    """지어낸 값을 넣으면 필터가 아무것도 고르지 못한다."""
    from ai_server.app import onboarding as f

    for g in _SPRING_GENRE:
        for v in f.map_genre([g], _AI_GENRE):
            assert v in _AI_GENRE, v


def test_raw_answer_is_kept_for_tracing():
    """변환이 어긋났을 때 되짚을 근거가 없으면 원인을 못 찾는다."""
    from ai_server.app import onboarding as f

    ans = f.from_request({"moods": ["잔잔한", "몽환적인"], "genres": ["Jazz"]}, _AI_GENRE)
    assert ans["mood_raw"] == ["잔잔한", "몽환적인"]
    assert ans["genre_raw"] == ["Jazz"]
    assert ans["genre"] == ["Contemporary Jazz", "Jazzy Hip-Hop"]
    assert "relaxing" in ans["mood"]


# ------------------------------------------------------------------ 구간 적재
class _FakeMap:
    loaded = True

    def music_id(self, s):
        if s == "T000999":
            raise KeyError("매핑 없음")
        return {"T001001": 1001, "T001002": 1002}[s]


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
    ])


def test_push_sections_never_invents_a_numeric_id():
    """매핑에 없는 곡을 넣으면 실제 DB 와 어긋난 순간 엉뚱한 곡을 추천한다."""
    from ai_server.tools import push_sections as ps

    rows, stat = ps.build_rows(_moments(), _FakeMap(), "v1")
    assert stat["unmapped_track"] == 1
    assert "T000999" in stat["unmapped_sample"]
    assert all(r["track_id"] in (1001, 1002) for r in rows)


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
def test_push_sections_incremental_only_sends_changes():
    """구간 1만 개를 매번 밀면 파트가 쌓이고 머지가 따라오지 못한다."""
    from ai_server.tools import push_sections as ps

    rows, _ = ps.build_rows(_moments(), _FakeMap(), "v1")
    same = {r["section_uid"]: (r["start_ms"], r["end_ms"], r["fn"]) for r in rows}
    todo, d = ps.diff(rows, same)
    assert todo == [] and d["new"] == 0 and d["changed"] == 0

    moved = dict(same)
    k = rows[0]["section_uid"]
    moved[k] = (moved[k][0], moved[k][1] + 5000, moved[k][2])
    todo, d = ps.diff(rows, moved)
    assert d["changed"] == 1 and [r["section_uid"] for r in todo] == [k]

    todo, d = ps.diff(rows, {})
    assert d["new"] == len(rows) and len(todo) == len(rows)


def test_push_sections_audit_spots_orphans():
    """색인에서 사라진 구간이 ClickHouse 에 남아 있으면 옛 경계로 판정된다."""
    from ai_server.tools import push_sections as ps

    rows, _ = ps.build_rows(_moments(), _FakeMap(), "v1")
    remote = {r["section_uid"]: (r["start_ms"], r["end_ms"], r["fn"]) for r in rows}
    remote["T009999#0"] = (0, 1000, "INTRO")
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
                                   "onboarding": {"moods": ["잔잔한"],
                                                  "genres": ["Jazz"]}})
    assert r.status_code == 404, r.text          # 후보 0 → NO_CANDIDATES (500 아님)
    assert "relaxing" in seen["onboarding"]["mood"], "mood 까지 잃으면 안 된다"
    assert seen["onboarding"]["genre"] == []
    assert seen["onboarding"]["genre_raw"] == ["Jazz"], "원문은 되짚을 수 있어야 한다"


def test_empty_genre_is_reported_and_raw_answer_survives():
    """어휘가 안 맞아 비더라도 사람이 고른 원문은 남아야 되짚을 수 있다."""
    from ai_server.app import onboarding as onb

    ans = onb.from_request({"moods": ["잔잔한"], "genres": ["Jazz"]}, [])
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
    monkeypatch.setattr(recommender, "EVENTS", log)
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
         "source_section_uid": "T000001#0", "music_section_id": 4101},
        {"source_track_id": "T000001", "music_id": 1001,
         "source_section_uid": "T000001#1", "music_section_id": 4102},
    ]
    dst = tmp_path / "id_map.parquet"
    tracks, sections = build_id_map.write_map(
        pd.DataFrame(pairs)[list(ids.COLS)], dst)
    assert (tracks, sections) == (1, 2)

    im = ids.IdMap(path=dst, strict=True)
    assert im.music_id("T000001") == 1001
    assert im.music_section_id("T000001#1") == 4102
    assert im.source_section_uid(4101) == "T000001#0"


def test_register_sections_id_map_rejects_two_songs_sharing_a_number(tmp_path):
    """`music_id` 하나에 원본이 둘이면 곡이 중복 노출된다. 쓰지 않는다."""
    import pandas as pd

    from ai_server.app import ids
    from ai_server.tools import build_id_map

    pairs = [
        {"source_track_id": "T1", "music_id": 7,
         "source_section_uid": "T1#0", "music_section_id": 1},
        {"source_track_id": "T2", "music_id": 7,
         "source_section_uid": "T2#0", "music_section_id": 2},
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

    monkeypatch.setattr(recommender, "EVENTS", tmp_path / "없는파일.jsonl")
    out = recommender.user_behavior("internal:1", now=1_700_000_100.0, events=[])
    assert out["events"] == 0 and out["delta"] == {}


# ------------------------------------------------ 온보딩을 요청으로 받기
def test_onboarding_from_request_maps_spring_vocabulary():
    """Spring 코드표와 AI 태그는 한 건도 겹치지 않는다. 옮기지 않으면 씨앗이 빈다."""
    from ai_server.app import onboarding as onb

    ans = onb.from_request({"moods": ["잔잔한"], "genres": ["Jazz"]},
                           ["Contemporary Jazz", "Rock"])
    assert "relaxing" in ans["mood"]
    assert ans["genre"] == ["Contemporary Jazz"]
    # 원문은 남긴다 — 변환이 어긋났을 때 되짚을 근거다.
    assert ans["mood_raw"] == ["잔잔한"] and ans["genre_raw"] == ["Jazz"]


def test_onboarding_from_request_fills_every_axis_the_recommender_reads():
    """빈 축을 키째 빼면 NaN 이 되고, NaN 은 truthy 라 `x not in NaN` 에서 터진다."""
    from ai_server.app import onboarding as onb
    from ai_server.app import recommender

    ans = onb.from_request({"moods": ["잔잔한"], "genres": []}, [])
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
        catalog_facets=lambda: {"genre": [{"value": "Contemporary Jazz"}]})
    app = FastAPI()
    internal_api.install(app, fake)
    TestClient(app).post("/internal/recommendations/daily",
                         json={"recommendation_id": 1, "user_id": 1,
                               "onboarding": {"moods": ["잔잔한"], "genres": ["Jazz"]}})
    assert seen["onboarding"] is not None, "온보딩이 추천기까지 닿지 않았다"
    assert "relaxing" in seen["onboarding"]["mood"]
    assert seen["onboarding"]["genre"] == ["Contemporary Jazz"]


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

    assert rs.moods_of("relaxing, meditative") == ["잔잔한"]
    assert rs.moods_of(["energetic", "sad"]) == ["신나는", "감성적인"]
    assert rs.moods_of("없는태그") == []
    assert rs.moods_of(None) == []


def test_register_sections_attaches_track_moods_to_every_section():
    """AI 카탈로그는 구간별 무드를 갖고 있지 않다. 곡의 것을 그 곡의 모든 구간에 붙인다."""
    from ai_server.tools import register_sections as rs

    rows, _ = rs.build_payload(_moments_frame(), ["잔잔한"])
    assert rows, "구간이 비었다"
    assert all(r["moods"] == ["잔잔한"] for r in rows)
