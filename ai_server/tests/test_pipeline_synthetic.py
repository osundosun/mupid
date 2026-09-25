"""합성 산출물로 추천 경로 전체를 태운다.

`test_smoke.py` 는 **실제** 색인이 있어야 하고 CI 에서는 통째로 건너뛴다. 그래서 지금까지
`recommend()` 를 한 줄도 지나가지 않는 상태로 초록불이 났다. 계약 테스트는 경계만 보고,
점수·게이트·중복 제거는 아무도 보지 않았다.

여기서는 **스키마만 진짜인** 작은 카탈로그를 만들어 그 경로를 지나간다. 값이 무작위라
추천 품질은 볼 수 없다 — 품질은 실제 색인으로 봐야 한다. 대신 "돌긴 도는가", "조건이
실제로 걸리는가", "손대지 않은 경로의 순위가 그대로인가" 를 본다.

컬럼을 하나라도 빠뜨리면 여기서 터진다. 실제로 이 파일을 만들다 `rel_start`/`rel_end`
누락이 드러났다.
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd
import pytest

from ai_server.app import catalog_body, index_contract

LABELS4 = ["intro", "verse", "chorus", "bridge"]
# DB 코드표 어휘(`codes.code_name`). 합성이지만 모양을 실제와 맞춘다.
GENRES = ["contemporary_pop", "pop_ballad", "indie_rock"]
MOODS = ["calm", "bright"]
INSTRUMENTS = ["piano", "guitar"]


def _build(root, n_tracks=6, per_track=4, emb_dim=16, feat_dim=12, seed=0):
    """실제 스키마대로 만든다. 값만 무작위다."""
    rng = np.random.default_rng(seed)
    d = root / "data" / "proc" / "mupid"
    (d / "serve").mkdir(parents=True, exist_ok=True)
    (d / "logs").mkdir(parents=True, exist_ok=True)

    # 색인에는 **벡터를 가리키는 행 번호와 오디오에서 잰 값만** 둔다. 구간 종류·
    # 시각은 MySQL 이 정본이고 요청 본문으로 온다(`_catalog_body`).
    rows, r = [], 0
    for t in range(n_tracks):
        tid = f"T{t:06d}"
        for s in range(per_track):
            rows.append({
                "moment_id": f"{tid}#{s}", "track_id": tid, "label_source": "allin1",
                "emb_row": r, "source": "flow",
                "rms": float(rng.uniform(0.05, 0.4)),
                "track_dur_sec": float(per_track * 30),
                # 곡 안에서의 상대 위치. 위치 prior 가 읽는다.
                "rel_start": s / per_track, "rel_end": (s + 1) / per_track,
            })
            r += 1
    mom = pd.DataFrame(rows)
    mom.to_parquet(d / "moments.parquet", index=False)

    n = len(mom)
    z = rng.normal(size=(n, emb_dim)).astype(np.float32)
    np.save(d / "moments_emb.npy", z / np.linalg.norm(z, axis=1, keepdims=True))

    np.savez(d / "features_v1.npz", X=rng.normal(size=(n, feat_dim)).astype(np.float32),
             moment_id=mom.moment_id.to_numpy().astype("<U80"))
    side = ["log_dur", "rel_center"]
    (d / "features_info.json").write_text(
        json.dumps({"version": "feat-v1", "pca_dim": feat_dim - len(side),
                    "side_dim": len(side), "side_cols": side}, ensure_ascii=False),
        encoding="utf-8")

    dim, hist_max = 8, 6
    s1 = rng.normal(size=(n, dim)).astype(np.float32)
    np.save(d / "stage1_section.npy", s1 / np.linalg.norm(s1, axis=1, keepdims=True))

    # 랭킹 모델(NumPy 판). 구간 벡터만 두고 실행기를 안 두면 학습 점수가 통째로 빠지므로
    # 여기서도 함께 만든다 — torch 없이 도는 경로를 실제로 태우는 것이 요점이다.
    np.savez(
        d / "stage1_weights.npz",
        enc0_w=rng.normal(size=(192, feat_dim)).astype(np.float32) * 0.1,
        enc0_b=np.zeros(192, dtype=np.float32),
        ln_w=np.ones(192, dtype=np.float32), ln_b=np.zeros(192, dtype=np.float32),
        enc1_w=rng.normal(size=(dim, 192)).astype(np.float32) * 0.1,
        enc1_b=np.zeros(dim, dtype=np.float32),
        att0_w=rng.normal(size=(64, dim)).astype(np.float32) * 0.1,
        att0_b=np.zeros(64, dtype=np.float32),
        att1_w=rng.normal(size=(1, 64)).astype(np.float32) * 0.1,
        att1_b=np.zeros(1, dtype=np.float32),
        gmean=np.zeros(dim, dtype=np.float32),
        hist_max=np.asarray(hist_max, dtype=np.int64),
        in_dim=np.asarray(feat_dim, dtype=np.int64))

    (d / "index_meta.json").write_text(json.dumps(
        index_contract.build_meta(rows=len(mom), tracks=len(set(mom.track_id)),
                                  built_at="1970-01-01T00:00:00Z"),
        ensure_ascii=False), encoding="utf-8")
    return d


def _catalog_body(n_tracks=6, per_track=4, drop=()):
    """Spring 이 추천 요청에 싣는 `catalog` 본문 그대로(연동 계약 2026-09-23 절).

    열 단위다. 값은 예전 카탈로그·`track_features` 합성값과 같게 두어 **이 변경이
    추천 결과를 바꾸지 않았다**는 것을 다른 시험들이 그대로 확인할 수 있게 한다.
    `drop` 에 준 곡은 싣지 않는다 — Spring 이 지워진 곡을 뺀 모양이다.
    """
    rng = np.random.default_rng(7)
    enum_of = {"intro": "INTRO", "verse": "VERSE", "chorus": "CHORUS",
               "bridge": "BRIDGE"}
    tracks = {k: [] for k in catalog_body.TRACK_COLS}
    sections = {k: [] for k in catalog_body.SECTION_COLS}
    for t in range(n_tracks):
        tid = f"T{t:06d}"
        bpm = float(rng.uniform(70, 150))
        if tid in drop:
            continue
        for k, v in (("track", tid), ("duration_ms", per_track * 30 * 1000),
                     ("bpm", bpm), ("bpm_requested_min", 80), ("bpm_requested_max", 120),
                     ("language", ["ko", "en"][t % 2]), ("category", GENRES[t % 3]),
                     ("genres", [GENRES[t % 3]]), ("moods", [MOODS[t % 2]]),
                     ("has_vocal", True), ("vocal_gender", ["F", "M"][t % 2])):
            tracks[k].append(v)
        for s_ in range(per_track):
            for k, v in (("external_id", f"{tid}#{s_}"),
                         ("section_label", enum_of[LABELS4[s_ % len(LABELS4)]]),
                         ("start_ms", s_ * 30 * 1000), ("end_ms", (s_ * 30 + 28) * 1000),
                         ("energy", None)):
                sections[k].append(v)
    return {"tracks": tracks, "sections": sections}


def _spring_filtered(svc, keep):
    """Spring 이 검색 조건으로 거른 뒤의 요청 전용 카탈로그. `keep(external_id)` 가 참인 구간만."""
    body = _catalog_body()
    sec = body["sections"]
    idx = [i for i, uid in enumerate(sec["external_id"]) if keep(uid)]
    body["sections"] = {k: [v[i] for i in idx] for k, v in sec.items()}
    return svc.CAT.with_catalog(catalog_body.from_body(body))


def _label_of(uid: str) -> str:
    return LABELS4[int(uid.split("#")[1]) % len(LABELS4)]


@pytest.fixture(scope="module")
def svc(tmp_path_factory):
    """합성 산출물을 가리키게 하고 모듈을 다시 읽는다.

    상수만 바꿔서는 안 된다. `app/features.py` 는 import 시점에 루트를 모듈 상수로 굳히므로
    (`paths.artifact_root()` 를 쓰지만 호출이 한 번뿐이다) recommender 쪽만 고치면 특징
    파일만 딴 곳을 본다 — 이 저장소가 `ids.py` 로 겪은 문제와 같은 모양이다.
    """
    import importlib
    import os
    import sys

    root = tmp_path_factory.mktemp("art")
    _build(root)

    saved_env = os.environ.get("MUPID_AI_ARTIFACT_DIR")
    os.environ["MUPID_AI_ARTIFACT_DIR"] = str(root)

    def _reload():
        for name in ("features", "labeler", "stage1_torch"):
            full = f"ai_server.app.{name}"
            if full in sys.modules:
                importlib.reload(sys.modules[full])
        from ai_server.app import recommender as mod
        return importlib.reload(mod)

    # 곡·순간 메타데이터는 색인이 아니라 요청 본문으로 온다. 시험은 그 본문 모양
    # 그대로를 묶는다 — 망을 타지 않을 뿐 담기는 길은 운영과 같다.
    rc = _reload()
    rc.CAT = rc.Catalog().with_catalog(catalog_body.from_body(_catalog_body()))
    yield rc

    if saved_env is None:
        os.environ.pop("MUPID_AI_ARTIFACT_DIR", None)
    else:
        os.environ["MUPID_AI_ARTIFACT_DIR"] = saved_env
    back = _reload()
    back.CAT = None


def test_catalog_loads_every_required_column(svc):
    """컬럼을 하나라도 빠뜨리면 여기서 터진다."""
    assert len(svc.CAT.pool) == 24
    assert svc.CAT.pool.track_id.nunique() == 6


def test_centering_mean_does_not_depend_on_the_request_catalog(svc):
    """중심화 평균은 **색인 풀 전체**로 잰다. 요청 후보로 재면 안 된다.

    검색은 Spring 이 조건으로 거른 후보만 보낸다. 그 후보로 평균을 재면 "발라드만" 온
    요청은 발라드의 평균으로 중심화돼, 질의마다 유사도를 재는 기준점이 달라진다 —
    오류 없이 순위만 틀어진다(2026-09-24). Pinecone 적재가 재는 범위와도 같아야 한다.
    """
    full = svc.CAT
    narrow = _spring_filtered(svc, lambda uid: int(uid[1:7]) % 3 == 0)
    assert 0 < len(narrow.pool) < len(full.pool)

    with svc.using(full):
        mu_full = svc._pool_mean().copy()
    with svc.using(narrow):
        mu_narrow = svc._pool_mean()

    expect = np.asarray(full.Z[svc.index_pool(full).emb_row.to_numpy()],
                        dtype=np.float32).mean(0)
    assert np.allclose(mu_narrow, mu_full)
    assert np.allclose(mu_full, expect, atol=1e-6)
    # 같은 구간은 어느 요청에서 중심화해도 같은 벡터가 된다.
    uid = narrow.pool.moment_id.iloc[0]
    row = int(full.pool.set_index("moment_id").emb_row[uid])
    with svc.using(full):
        a = svc.center(full.Z[[row]])
    with svc.using(narrow):
        b = svc.center(full.Z[[row]])
    assert np.allclose(a, b)


def test_model_runs_on_numpy_without_torch(svc):
    """이미지에서 torch 를 빼도 되는 근거. npz 만으로 학습 점수가 산다."""
    assert svc.CAT.model_backend == "numpy"
    assert svc.CAT.model_error is None, svc.CAT.model_error
    assert svc.CAT.ZS is not None
    assert getattr(svc.CAT, "torch", None) is None


def test_missing_runner_is_reported_not_silent(svc, tmp_path, monkeypatch):
    """구간 벡터만 있고 실행기가 없으면 학습 점수가 통째로 빠진다. 말을 해야 한다."""
    (svc.paths.data_dir() / "stage1_weights.npz").rename(tmp_path / "moved.npz")
    try:
        cat = svc.Catalog()
        assert cat.model is None
        assert cat.model_error and "stage1_weights.npz" in cat.model_error
    finally:
        (tmp_path / "moved.npz").rename(svc.paths.data_dir() / "stage1_weights.npz")


def test_recommend_returns_requested_count(svc):
    out = svc.recommend("internal:1", 3, set())
    assert len(out["results"]) == 3
    assert out["diagnostics"]["query_source"] == "none"      # 이력이 없다


def test_recommend_never_repeats_a_track(svc):
    items = svc.recommend("internal:1", 5, set())["results"]
    tracks = [r["track_id"] for r in items]
    assert len(tracks) == len(set(tracks)), tracks


def test_recommend_honours_exclusion(svc):
    first = svc.recommend("internal:1", 1, set())["results"][0]["track_id"]
    again = svc.recommend("internal:1", 3, {first})["results"]
    assert first not in [r["track_id"] for r in again]


def test_search_ranks_only_what_spring_sent(svc):
    """조건 게이트는 Spring 이 건다. 이 서버는 받은 후보 밖으로 나가지 않는다."""
    view = _spring_filtered(svc, lambda uid: int(uid[1:7]) % 3 == 0)   # GENRES[0] 곡만
    with svc.using(view):
        out = svc.recommend("internal:1", 5, set(), True,
                            {"filters": {"genre": [GENRES[0]]}})
    assert out["diagnostics"]["nlp"]["gates"] == "spring"
    assert out["results"]
    for r in out["results"]:
        assert int(r["track_id"][1:]) % 3 == 0


def test_query_plan_uses_only_matching_dna_moments(svc, monkeypatch):
    pos = pd.DataFrame([
        {"section_key": "T000000#1", "track_id": "T000000"},  # verse
        {"section_key": "T000001#2", "track_id": "T000001"},  # chorus
    ])
    monkeypatch.setattr(svc, "user_positives", lambda _user: pos)
    monkeypatch.setattr(
        svc, "other_positives",
        lambda _user: (_ for _ in ()).throw(AssertionError("검색에 타 사용자 이력을 사용했다")))

    with svc.using(_spring_filtered(svc, lambda uid: _label_of(uid) == "verse")):
        out = svc.recommend("internal:query-dna", 4, set(), True,
                            {"filters": {"flow": "verse"}})

    assert out["diagnostics"]["query_source"] == "labels"
    assert sum(x["n_members"] for x in out["dna"]) == 1
    assert {x["rep_moment_id"] for x in out["dna"]} == {"T000000#1"}
    assert out["results"] and all(x["section_label"] == "verse" for x in out["results"])


def test_query_plan_searches_without_dna_when_none_match(svc, monkeypatch):
    pos = pd.DataFrame([{"section_key": "T000001#2", "track_id": "T000001"}])
    monkeypatch.setattr(svc, "user_positives", lambda _user: pos)

    with svc.using(_spring_filtered(svc, lambda uid: _label_of(uid) == "verse")):
        out = svc.recommend("internal:no-query-dna", 4, set(), True,
                            {"filters": {"flow": "verse", "mood": [MOODS[0]]}})

    assert out["dna"] == []
    assert out["diagnostics"]["query_source"] == "none"
    assert out["results"] and all(x["section_label"] == "verse" for x in out["results"])


def test_query_plan_ignores_history_onboarding_bpm_and_explore(svc, monkeypatch):
    calls = {"positives": 0}

    def positives(_user):
        calls["positives"] += 1
        return pd.DataFrame()

    def forbidden(*_args, **_kwargs):
        raise AssertionError("자연어 검색이 무관한 사용자 상태를 읽었다")

    monkeypatch.setattr(svc, "user_positives", positives)
    for name in ("model_user_vec", "user_behavior", "onboarding_seeds", "onboarding_of"):
        monkeypatch.setattr(svc, name, forbidden)

    kwargs = {"user": "internal:isolated-query", "n": 4, "exclude": {"T000005"},
              "explore": True, "onboarding": {"genres": ["ROCK"]}}
    with svc.using(_spring_filtered(svc, lambda uid: _label_of(uid) == "verse")):
        a = svc.recommend(**kwargs, query_plan={"filters": {"flow": "verse"}},
                          beh_events=[{"type": "DISLIKE", "track_id": "T000000"}])
        b = svc.recommend(**kwargs, query_plan={"filters": {"flow": "verse"}},
                          beh_events=[{"type": "LIKE", "track_id": "T000004"}])

    assert calls["positives"] == 2  # DNA 확인만; 선호 BPM용 재조회 없음
    assert [x["moment_id"] for x in a["results"]] == [x["moment_id"] for x in b["results"]]
    assert all(x["slot_source"] != "explore" for x in a["results"])
    assert all(x["track_id"] != "T000005" for x in a["results"])
    assert len({x["track_id"] for x in a["results"]}) == len(a["results"])
    assert a["diagnostics"]["bpm_pref"] == 0
    assert a["diagnostics"]["behavior_source"] == "disabled_for_query"


def test_mood_filter_reranks_without_dropping(svc):
    plain = svc.recommend("internal:1", 6, set())
    moody = svc.recommend("internal:1", 6, set(), True,
                          {"filters": {"mood": [MOODS[0]]}})
    assert moody["diagnostics"]["mood_rerank"]["matched"] > 0
    # 후보 수는 그대로 — mood 는 게이트가 아니다.
    assert moody["diagnostics"]["candidates"] == plain["diagnostics"]["candidates"]


def test_onboarding_with_nothing_mapped_makes_no_seed(svc):
    """원문은 왔는데 어휘로 옮겨진 축이 하나도 없으면 **씨앗을 만들지 않는다.**

    `track_ok` 의 조건은 전부 `want_X and …` 라 빈 축은 건너뛴다. 모든 축이 비면
    조건이 하나도 없어 전곡이 통과하고 씨앗이 **풀 전체의 구간별 평균**이 되는데,
    그런데도 응답은 `ONBOARDING_MATCH` 라고 말한다. 실측으로 기존 회원 8명 중
    6명이 그 상태였다 — 모두 같은 값을 받으면서 근거는 "온보딩 일치" 였다.
    콜드스타트보다 나쁘다: 틀린 근거를 자신 있게 말한다.
    """
    from ai_server.app import onboarding as onb

    # 대분류 장르 + 1세대 무드. 둘 다 온보딩 축에서 내려간 값이다.
    ans = onb.from_request({"moods": ["잔잔한"], "genres": ["Rock"]}, GENRES)
    assert ans["mood"] == [] and ans["genre"] == []
    assert ans["mood_raw"] == ["잔잔한"], "원문은 남아야 되짚을 수 있다"

    out = svc.recommend("internal:404", 4, set(), False, None, None, None,
                        beh_events=[], onboarding=ans)
    d = out["diagnostics"]
    assert d["query_source"] == "none", "씨앗이 없는데 온보딩이라고 했다"
    assert d["onboarding_source"] == "empty_after_mapping"
    assert d.get("cold_start") is True
    assert out["results"], "근거가 없어도 추천은 나가야 한다"
    for r in out["results"]:
        assert not r.get("matched_dna"), "씨앗이 없는데 근거 구간이 붙었다"


def test_one_axis_left_still_makes_a_seed(svc):
    """한 축이라도 남으면 종전 그대로다. 9번 회원이 이쪽이다.

    실제 응답: 장르 `9·12·13·14 · 53·57` 중 세분류 `53·57` 만 남는다.
    """
    from ai_server.app import onboarding as onb

    ans = onb.from_request({"moods": ["잔잔한"], "genres": ["Rock", GENRES[0]]}, GENRES)
    assert ans["mood"] == [] and ans["genre"] == [GENRES[0]], "세분류만 남아야 한다"

    out = svc.recommend("internal:405", 4, set(), False, None, None, None,
                        beh_events=[], onboarding=ans)
    assert out["diagnostics"]["query_source"] == "onboarding"
    assert out["diagnostics"]["onboarding_source"] == "request"


def test_ranking_is_unchanged_when_no_mood_is_asked(svc):
    """soft rerank 를 넣기 전과 순위가 같아야 한다. 동결 약속의 실증이다."""
    a = [r["moment_id"] for r in svc.recommend("internal:1", 6, set(), False)["results"]]
    b = [r["moment_id"] for r in svc.recommend("internal:1", 6, set(), False,
                                               {"filters": {}})["results"]]
    assert a == b


def test_similar_excludes_the_source_track(svc):
    rows = svc.similar_moments("T000000#1", 3)
    assert rows and all(r["track_id"] != "T000000" for r in rows)
    # 한 곡당 하나만.
    assert len({r["track_id"] for r in rows}) == len(rows)
    assert rows == sorted(rows, key=lambda r: -r["similarity"])


def test_short_sections_are_dropped(svc):
    """MIN_MOMENT_SEC 보다 짧은 구간은 후보에서 빠진다."""
    out = svc.recommend("internal:1", 3, set())
    for r in out["results"]:
        assert r["end_sec"] - r["start_sec"] >= svc.MIN_MOMENT_SEC


def test_internal_api_end_to_end(svc):
    """어댑터까지 붙여 실제 응답 모양을 본다.

    매핑표를 세워 주지 않는다 — 이 서버는 숫자 ID 를 다루지 않으므로 그럴 것이 없다.
    """
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from ai_server.app import internal_api

    app = FastAPI()
    internal_api.install(app, svc)
    c = TestClient(app)

    # `daily` 는 개수를 받지 않는다 — 4개 고정이다(`internal_api.DAILY_LIMIT`).
    r = c.post("/internal/recommendations/daily",
               json={"recommendation_id": 1, "user_id": 7, "catalog": _catalog_body()})
    assert r.status_code == 200, r.text
    body = r.json()
    assert len(body["items"]) == internal_api.DAILY_LIMIT
    for it in body["items"]:
        assert it["external_id"] and it["reason_code"]
        assert it["rank_order"] >= 1
    # 같은 곡이 두 번 나오지 않는다 (`#` 앞이 곡 ID 다)
    tracks = {i["external_id"].split("#")[0] for i in body["items"]}
    assert len(tracks) == internal_api.DAILY_LIMIT

    # 모델 수치·내부 진단은 응답으로 새지 않는다. 문자열 ID 는 이제 계약이라 셈에서 뺀다.
    raw = r.text
    for leaked in ("moment_similarity", "prob", "matched_dna", "slot_source",
                   "diagnostics", "start_time_ms", "section_label"):
        assert leaked not in raw, leaked

    assert c.get("/internal/ready").status_code == 200
    # 조건 어휘는 Spring 이 만든다. 이 서버에는 그 경로가 없다.
    assert c.get("/internal/facets").status_code == 404


def test_pool_source_counts_are_reported(svc):
    """설정한 출처가 카탈로그에 없으면 풀이 조용히 빈다. 건수를 보여줘야 한다."""
    assert svc.CAT.pool_sources["flow"] == 24
    assert not svc.CAT.pool_sources_empty


def test_jamendo_is_not_in_the_default_pool(svc):
    """Jamendo 곡은 실서비스에서 재생하지 않는다. 설정이 없어도 후보가 되면 안 된다.

    기본값이 `jamendo` 이던 때에는 매니페스트가 `''` 로 덮어서만 막혀 있었고, 배포 직후
    받은 추천 3건이 전부 `JAMENDO_*` 였던 적이 있다.
    """
    assert "jamendo" not in svc.POOL_SOURCES


def test_own_is_not_in_the_default_pool(svc):
    """행이 0건인 출처를 기본값에 두면 '자체 음원도 추천된다' 고 잘못 읽힌다."""
    assert "own" not in svc.POOL_SOURCES


# ---------------------------------------------------------------- Q8 끝까지
def test_clickhouse_signals_become_labels(svc, monkeypatch):
    """ClickHouse 신호 → 행동 로그 → 라벨러까지 **실제로 이어지는지** 본다.

    여기가 끊기면 신호를 아무리 잘 받아 와도 추천이 바뀌지 않는다. 각 조각은 따로
    검증했지만 이어 붙인 것은 이 테스트가 처음이다.
    """
    from ai_server.tools import pull_signals as pl

    mom = svc.CAT.mom
    uid = str(mom.iloc[2].moment_id)               # T000000#2 같은 실재 구간
    tid = str(mom.iloc[2].track_id)
    bounds = {uid: (float(mom.iloc[2].start_sec), float(mom.iloc[2].end_sec))}

    class _IM:
        def source_track_id(self, n):
            return tid

    # ClickHouse 가 돌려줄 모양 그대로. 같은 구간을 두 번 받는다(중복 방어 확인).
    rows = [{"user_id": 7, "section_uid": uid, "track_id": 1, "signal": "SECTION_MARK",
             "entry_path": "AUTO", "ts": "2026-09-15 10:00:00.000",
             "play_id": "0-0", "weight": 1.5},
            {"user_id": 7, "section_uid": uid, "track_id": 1, "signal": "SECTION_MARK",
             "entry_path": "AUTO", "ts": "2026-09-15 10:00:00.000",
             "play_id": "0-0", "weight": 1.5}]

    seen: set = set()
    for r in rows:
        ev = pl.to_event(r, bounds, _IM())
        assert ev is not None
        if ev["event_id"] in seen:
            continue
        seen.add(ev["event_id"])
        svc.append_event(ev)
    assert len(seen) == 1, "같은 신호를 두 번 받으면 한 번만 쌓여야 한다"

    # 라벨러가 이 이벤트를 실제로 읽어 등급을 만드는가
    res = svc.run_labeler()
    assert res["ok"], res
    lab = pd.read_parquet(svc.paths.labels_path())
    mine = lab[lab.user_ref == "internal:7"]
    assert not mine.empty, "ClickHouse 신호가 라벨까지 도달하지 않았다"
    assert (mine.section_key == uid).any()
    assert set(mine.grade) & {"L1", "L2"}, f"등급이 붙지 않았다: {list(mine.grade)}"


def test_unmark_in_a_later_batch_removes_the_earlier_positive(svc):
    """1일 차 배치의 SECTION_MARK 를 2일 차 배치의 SECTION_UNMARK 가 지운다.

    라벨은 판 단위로 통째 다시 만들어진다. 해제가 정규화에서 버려지기만 하면 지난
    MARK 가 매 판 다시 L1 이 되어 DNA·히스토리에 영영 남는다(S15P21A204-442).
    """
    from ai_server.tools import pull_signals as pl

    mom = svc.CAT.mom
    uid = str(mom.iloc[5].moment_id)
    other = str(mom.iloc[6].moment_id)
    tid = str(mom.iloc[5].track_id)
    bounds = {u: (float(r.start_sec), float(r.end_sec))
              for u, r in mom.set_index(mom.moment_id.astype(str)).loc[[uid, other]].iterrows()}
    user = "internal:442"

    class _IM:
        def source_track_id(self, n):
            return {5: tid, 6: str(mom.iloc[6].track_id)}[n]

    def pull(sig, ts, section=uid, n=5, user_id=442):
        ev = pl.to_event({"user_id": user_id, "section_uid": section, "track_id": n,
                          "signal": sig, "entry_path": "", "ts": ts,
                          "play_id": "0-0", "weight": 1.0}, bounds, _IM())
        svc.append_event(ev)

    def positives(u=user):
        res = svc.run_labeler()
        assert res["ok"], res
        return set(svc.user_positives(u).get("section_key", []))

    # 1일 차: 두 순간 저장 + 다른 사용자도 같은 순간 저장
    pull("SECTION_MARK", "2026-09-20 10:00:00.000")
    pull("SECTION_MARK", "2026-09-20 10:01:00.000", section=other, n=6)
    pull("SECTION_MARK", "2026-09-20 10:02:00.000", user_id=443)
    assert {uid, other} <= positives(), "저장한 순간이 L1 이 되지 않았다"

    # 2일 차: 한 순간만 해제
    pull("SECTION_UNMARK", "2026-09-21 09:00:00.000")
    pos = positives()
    assert uid not in pos, "다음 배치의 해제가 지난 배치의 positive 를 지우지 못했다"
    assert other in pos, "해제하지 않은 순간까지 사라졌다"
    assert uid in positives("internal:443"), "다른 사용자의 저장이 함께 사라졌다"

    lab = pd.read_parquet(svc.paths.labels_path())
    mine = lab[(lab.user_ref == user) & (lab.section_key == uid)]
    assert mine.empty, f"해제한 순간에 라벨이 남았다(부정 포함): {list(mine.grade)}"

    # DNA 는 user_positives 를 읽는다 — 해제한 순간이 대표로 나오지 않는다
    _, _, meta = svc.build_dna(user)
    assert all(m.get("rep_moment_id") != uid for m in meta)


# ---------------------------------------------------------------- 성능 캐시
def test_facets_cache_dies_with_the_catalog(svc):
    """캐시를 전역에 두면 산출물을 교체해도 옛 어휘가 남는다. 카탈로그에 붙여 둔다."""
    a = svc.catalog_facets()
    assert svc.catalog_facets() is a, "같은 카탈로그인데 다시 만들었다"

    # 요청마다 다른 본문 → 다른 사본. 사본의 어휘가 옛 사본에서 새면 안 된다.
    other = svc.CAT.with_catalog(catalog_body.from_body(_catalog_body(drop={"T000000"})))
    with svc.using(other):
        b = svc.catalog_facets()
    assert b is not a, "후보를 바꿨는데 옛 캐시를 돌려줬다"
    assert "T000000" not in set(b["_df"].track_id)


def test_facet_rows_match_the_dataframe(svc):
    """`fac.loc[t]` 를 dict 로 바꿨다. 값이 다르면 게이트 판정이 달라진다."""
    fx = svc.catalog_facets()
    fac = fx["_df"].set_index("track_id")
    meta = svc.catalog_facet_rows()
    assert set(meta) == set(fac.index)
    for t in list(meta)[:20]:
        for col in ("genre", "style", "language", "has_vocal", "vocal_gender", "bpm"):
            assert meta[t].get(col) == fac.loc[t].get(col), (t, col)
        for col in ("moods", "instruments"):
            assert list(meta[t].get(col) or []) == list(fac.loc[t].get(col) or []), (t, col)


def test_moment_metadata_comes_from_the_catalog_not_the_index(svc):
    """구간 종류·시각·악기는 색인에 없고 카탈로그에서 붙는다."""
    mom = svc.CAT.mom.set_index(svc.CAT.mom.moment_id.astype(str))
    row = mom.loc["T000000#2"]

    assert row.section_label == "chorus", "MySQL ENUM 이 추천 어휘로 안 바뀌었다"
    assert float(row.start_sec) == 60.0 and float(row.end_sec) == 88.0
    assert float(row.dur_sec) == 28.0, "길이는 시각에서 다시 세야 한다"
    # 곡 안 상대 위치도 본문의 시각·곡 길이에서 다시 센다. 분석 때 경계로 굳어 있으면
    # MySQL 에서 경계를 고쳐도 옛 위치로 점수가 나간다.
    assert float(row.rel_start) == 60.0 / 120.0


def test_a_song_missing_from_the_catalog_leaves_the_pool(svc):
    """MySQL 에서 지워진 곡은 색인에 남아 있어도 추천 후보가 아니다.

    남겨 두면 Spring 이 `external_id` 를 못 찾아 그 줄을 버린다 — 화면에는 오류가
    아니라 "추천이 몇 개 비어 보인다" 로 나온다.
    """
    before = svc.CAT
    rebuilt = svc.CAT.with_catalog(catalog_body.from_body(_catalog_body(drop={"T000000"})))
    assert "T000000" not in set(rebuilt.pool.track_id)
    assert rebuilt.moments_outside_catalog == 4
    assert len(rebuilt.pool) == len(before.pool) - 4


def test_the_index_alone_has_no_candidates(svc):
    """본문 없이 색인만으로는 후보가 없다 — 추천은 언제나 Spring 이 보낸 후보 안에서다.

    기동에서 Spring 을 부르지 않으므로 색인만 올린 기준 판이 뜬다. 그 판의 후보가 0 이라
    본문 없는 추천이 나갈 길이 없다.
    """
    base = svc.Catalog()
    assert len(base.pool) == 0


def test_the_catalog_body_is_refused_when_its_shape_is_wrong():
    """모양이 계약과 다르면 조용히 고쳐 받지 않는다."""
    body = _catalog_body()
    body["sections"]["end_ms"] = body["sections"]["end_ms"][:-1]
    with pytest.raises(catalog_body.CatalogBodyError):
        catalog_body.from_body(body)
    orphan = _catalog_body()
    orphan["tracks"] = {k: v[1:] for k, v in orphan["tracks"].items()}
    with pytest.raises(catalog_body.CatalogBodyError, match="tracks 에 없는 곡"):
        catalog_body.from_body(orphan)


def test_concurrent_requests_do_not_see_each_others_candidates(svc):
    """요청마다 사본을 격리한다. 전역을 바꿔 끼우면 동시에 든 요청끼리 후보가 섞인다."""
    import threading

    only_a = _spring_filtered(svc, lambda uid: uid.startswith("T000001"))
    only_b = _spring_filtered(svc, lambda uid: uid.startswith("T000002"))
    seen: dict = {}
    gate = threading.Barrier(2)

    def run(name, view, want):
        with svc.using(view):
            gate.wait()
            seen[name] = {r["track_id"] for r in
                          svc.recommend("internal:1", 3, set(), True,
                                        {"filters": {"genre": ["x"]}})["results"]}
            seen[name + "_ok"] = seen[name] <= {want}

    ts = [threading.Thread(target=run, args=("a", only_a, "T000001")),
          threading.Thread(target=run, args=("b", only_b, "T000002"))]
    for t in ts:
        t.start()
    for t in ts:
        t.join()
    assert seen["a_ok"] and seen["b_ok"], seen


# ------------------------------------------- 오늘의 모멘트: 서울 날짜 단위 시드 (S15P21A204-448)

def _daily_at(svc, iso: str, user: str = "internal:1"):
    from datetime import datetime
    out = svc.recommend(user, 4, set(), True, None, None, None,
                        daily=True, now=datetime.fromisoformat(iso))
    return [(x["track_id"], x["moment_id"], x["slot_source"]) for x in out["results"]]


def test_daily_is_fixed_within_a_seoul_day(svc, monkeypatch):
    """같은 사용자·같은 서울 날짜면 곡·순서·순간이 같다. 분이 바뀌어도(시계를 옮겨도) 그대로다."""
    clock = iter([1_790_000_000.0, 1_790_000_000.0 + 3_000.0])
    monkeypatch.setattr(svc.time, "time", lambda: next(clock, 1_790_010_000.0))
    a = _daily_at(svc, "2026-09-25T00:00:00+09:00")
    b = _daily_at(svc, "2026-09-25T23:59:59+09:00")
    assert a and a == b
    # UTC 로 전날이어도 서울 날짜가 같으면 같은 날이다.
    assert _daily_at(svc, "2026-09-24T15:30:00+00:00") == a


def test_daily_explore_slot_can_change_with_the_date(svc):
    days = [_daily_at(svc, f"2026-10-{d:02d}T12:00:00+09:00") for d in range(1, 15)]
    assert len({tuple(d) for d in days}) > 1
    # 바뀌는 것은 탐색 칸뿐이다 — 점수로 뽑은 앞 칸은 날짜와 무관하다.
    assert len({tuple(d[:-1]) for d in days}) == 1


def test_daily_seed_is_per_user_and_per_seoul_date(svc):
    from datetime import datetime
    t = datetime.fromisoformat("2026-09-25T09:00:00+09:00")
    assert svc.daily_seed("internal:1", t) == svc.daily_seed("internal:1", t)
    assert svc.daily_seed("internal:1", t) != svc.daily_seed("internal:2", t)
    assert svc.daily_seed("internal:1", t) != svc.daily_seed(
        "internal:1", datetime.fromisoformat("2026-09-26T09:00:00+09:00"))


# ------------------------------------------- 오늘의 모멘트: 보컬 곡의 첫 chorus (S15P21A204-448)

def _daily_view(svc, vocal=None, labels=None):
    """`vocal[tid]` 로 `has_vocal` 을, `labels[external_id]` 로 구간 종류를 바꾼 본문."""
    body = _catalog_body()
    tr, sec = body["tracks"], body["sections"]
    for i, tid in enumerate(tr["track"]):
        if tid in (vocal or {}):
            tr["has_vocal"][i] = vocal[tid]
    for i, uid in enumerate(sec["external_id"]):
        if uid in (labels or {}):
            sec["section_label"][i] = labels[uid]
    return svc.CAT.with_catalog(catalog_body.from_body(body))


def _daily(svc, view, explore=True):
    from datetime import datetime
    with svc.using(view):
        return svc.recommend("internal:1", 4, set(), explore, None, None, None, daily=True,
                             now=datetime.fromisoformat("2026-09-25T12:00:00+09:00"))


def test_daily_keeps_the_score_order_and_plays_each_tracks_chorus(svc):
    """자격이 모두 같으면 곡 순서는 종전 점수 순서 그대로이고, 순간만 chorus 로 바뀐다."""
    view = _daily_view(svc)
    with svc.using(view):
        plain = svc.recommend("internal:1", 4, set(), False, None, None, None)["results"]
    got = _daily(svc, view, explore=False)["results"]
    assert [x["track_id"] for x in got] == [x["track_id"] for x in plain]
    assert all(x["section_label"] == "chorus" for x in got)
    # 합성 본문에서 chorus 는 곡마다 #2 하나다.
    assert [x["moment_id"] for x in got] == [f'{x["track_id"]}#2' for x in got]
    assert len({x["track_id"] for x in got}) == len(got)


def test_daily_drops_non_vocal_and_unknown_vocal_tracks(svc):
    out = _daily(svc, _daily_view(svc, vocal={"T000000": False, "T000001": None}))
    tracks = {x["track_id"] for x in out["results"]}
    assert tracks and not tracks & {"T000000", "T000001"}
    assert out["diagnostics"]["daily_moment"]["dropped_no_vocal_tracks"] == 2


def test_daily_drops_tracks_without_a_chorus_instead_of_substituting(svc):
    out = _daily(svc, _daily_view(svc, labels={"T000002#2": "VERSE", "T000003#2": "BRIDGE"}))
    tracks = {x["track_id"] for x in out["results"]}
    assert tracks and not tracks & {"T000002", "T000003"}
    assert all(x["section_label"] == "chorus" for x in out["results"])
    assert out["diagnostics"]["daily_moment"]["dropped_no_chorus_tracks"] == 2


def test_daily_picks_the_earliest_of_several_choruses(svc):
    # T000000..T000005 모두 #1(30초)·#2(60초)·#3(90초)이 chorus → 가장 이른 #1.
    labels = {f"T{t:06d}#{s}": "CHORUS" for t in range(6) for s in (1, 3)}
    out = _daily(svc, _daily_view(svc, labels=labels))["results"]
    assert out and all(x["moment_id"] == f'{x["track_id"]}#1' for x in out)
    assert all(x["start_sec"] == 30.0 for x in out)


def test_daily_returns_fewer_than_four_rather_than_filling(svc):
    """자격 있는 곡이 둘뿐이면 둘만 낸다. 무관한 곡·다른 구간으로 채우지 않는다."""
    no = {f"T{t:06d}": False for t in range(2, 6)}
    out = _daily(svc, _daily_view(svc, vocal=no))
    assert {x["track_id"] for x in out["results"]} <= {"T000000", "T000001"}
    assert len(out["results"]) <= 2
    assert out["diagnostics"]["shortfall"] >= 2
    assert all(x["section_label"] == "chorus" for x in out["results"])
