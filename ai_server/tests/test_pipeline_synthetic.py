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

LABELS4 = ["intro", "verse", "chorus", "bridge"]
GENRES = ["pop", "ballad", "rock"]
MOODS = ["relaxing", "happy"]
INSTRUMENTS = ["piano", "guitar"]


def _build(root, n_tracks=6, per_track=4, emb_dim=16, feat_dim=12, seed=0):
    """실제 스키마대로 만든다. 값만 무작위다."""
    rng = np.random.default_rng(seed)
    d = root / "data" / "proc" / "mupid"
    (d / "serve").mkdir(parents=True, exist_ok=True)
    (d / "logs").mkdir(parents=True, exist_ok=True)

    rows, r = [], 0
    for t in range(n_tracks):
        tid = f"T{t:06d}"
        for s in range(per_track):
            rows.append({
                "moment_id": f"{tid}#{s}", "track_id": tid, "section_idx": s,
                "section_label": LABELS4[s % len(LABELS4)], "label_source": "allin1",
                "start_sec": float(s * 30), "end_sec": float(s * 30 + 28),
                "dur_sec": 28.0, "emb_row": r, "source": "flow",
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

    tids = sorted(mom.track_id.unique())
    pd.DataFrame([{"track_id": t, "bpm": float(rng.uniform(70, 150)),
                   "dur_sec": float(per_track * 30)} for t in tids]
                 ).to_parquet(d / "content.parquet", index=False)
    pd.DataFrame([{
        "track_id": t, "title": f"곡 {t}", "category": GENRES[i % 3],
        "vocal_type": ["female", "male"][i % 2], "language": ["ko", "en"][i % 2],
        "bpm_lo": 80, "bpm_hi": 120, "sound_raw": "",
        # 장르는 `genre` 가 아니라 `genre_top1` 에서 온다(_catalog_tags). 이름을 틀리면
        # 패싯이 조용히 빈 목록이 되고, LLM 이 고를 어휘가 사라진다.
        "genre_top1": GENRES[i % 3],
        "mood_tags": MOODS[i % 2], "instrument_tags": INSTRUMENTS[i % 2],
    } for i, t in enumerate(tids)]).to_parquet(d / "flow_meta.parquet", index=False)
    return d


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

    rc = _reload()
    rc.CAT = rc.Catalog()
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


def test_model_runs_on_numpy_without_torch(svc):
    """이미지에서 torch 를 빼도 되는 근거. npz 만으로 학습 점수가 산다."""
    assert svc.CAT.model_backend == "numpy"
    assert svc.CAT.model_error is None, svc.CAT.model_error
    assert svc.CAT.ZS is not None
    assert getattr(svc.CAT, "torch", None) is None


def test_missing_runner_is_reported_not_silent(svc, tmp_path, monkeypatch):
    """구간 벡터만 있고 실행기가 없으면 학습 점수가 통째로 빠진다. 말을 해야 한다."""
    (svc.OUT_DIR / "stage1_weights.npz").rename(tmp_path / "moved.npz")
    try:
        cat = svc.Catalog()
        assert cat.model is None
        assert cat.model_error and "stage1_weights.npz" in cat.model_error
    finally:
        (tmp_path / "moved.npz").rename(svc.OUT_DIR / "stage1_weights.npz")


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


def test_query_plan_actually_gates(svc):
    """조건이 걸리지 않으면 자연어 검색이 무의미하다."""
    out = svc.recommend("internal:1", 5, set(), True, {"filters": {"genre": ["pop"]}})
    assert out["diagnostics"]["nlp"]["applied"] == ["filters.genre"]
    for r in out["results"]:
        assert svc.CAT.track_info(r["track_id"])["category"] == "pop"


def test_unsatisfiable_condition_returns_nothing_not_anything(svc):
    """어휘에 없는 조건을 조용히 풀고 아무 곡이나 주면 안 된다."""
    out = svc.recommend("internal:1", 5, set(), True,
                        {"filters": {"genre": ["존재하지않는장르"]}})
    assert out["results"] == []
    assert out["diagnostics"]["nlp"]["unsatisfiable"]


def test_mood_filter_reranks_without_dropping(svc):
    plain = svc.recommend("internal:1", 6, set())
    moody = svc.recommend("internal:1", 6, set(), True,
                          {"filters": {"mood": ["relaxing"]}})
    assert moody["diagnostics"]["mood_rerank"]["matched"] > 0
    # 후보 수는 그대로 — mood 는 게이트가 아니다.
    assert moody["diagnostics"]["candidates"] == plain["diagnostics"]["candidates"]


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

    r = c.post("/internal/recommendations/daily",
               json={"recommendation_id": 1, "user_id": 7, "limit": 3})
    assert r.status_code == 200, r.text
    body = r.json()
    assert len(body["items"]) == 3
    for it in body["items"]:
        assert it["external_id"] and it["reason_code"]
        assert it["rank_order"] >= 1
    # 같은 곡이 두 번 나오지 않는다 (`#` 앞이 곡 ID 다)
    tracks = {i["external_id"].split("#")[0] for i in body["items"]}
    assert len(tracks) == 3

    # 모델 수치·내부 진단은 응답으로 새지 않는다. 문자열 ID 는 이제 계약이라 셈에서 뺀다.
    raw = r.text
    for leaked in ("moment_similarity", "prob", "matched_dna", "slot_source",
                   "diagnostics", "start_time_ms", "section_label"):
        assert leaked not in raw, leaked

    assert c.get("/internal/ready").status_code == 200
    assert c.get("/internal/facets").json()["fields"]["genre"]["values"]


def test_pool_source_counts_are_reported(svc):
    """설정한 출처가 카탈로그에 없으면 풀이 조용히 빈다. 건수를 보여줘야 한다."""
    assert svc.CAT.pool_sources["flow"] == 24
    # 기본값에 있는 jamendo 는 이 합성 카탈로그에 없다 → 0 으로 드러난다.
    assert svc.CAT.pool_sources.get("jamendo") == 0
    assert "jamendo" in svc.CAT.pool_sources_empty


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
    lab = pd.read_parquet(svc.LABELS)
    mine = lab[lab.user_ref == "internal:7"]
    assert not mine.empty, "ClickHouse 신호가 라벨까지 도달하지 않았다"
    assert (mine.section_key == uid).any()
    assert set(mine.grade) & {"L1", "L2"}, f"등급이 붙지 않았다: {list(mine.grade)}"


# ---------------------------------------------------------------- 성능 캐시
def test_facets_cache_dies_with_the_catalog(svc):
    """캐시를 전역에 두면 산출물을 교체해도 옛 어휘가 남는다. 카탈로그에 붙여 둔다."""
    a = svc.catalog_facets()
    assert svc.catalog_facets() is a, "같은 카탈로그인데 다시 만들었다"

    old = svc.CAT
    svc.CAT = svc.Catalog()                 # 산출물 교체 흉내
    try:
        b = svc.catalog_facets()
        assert b is not a, "카탈로그를 바꿨는데 옛 캐시를 돌려줬다"
    finally:
        svc.CAT = old


def test_facet_rows_match_the_dataframe(svc):
    """`fac.loc[t]` 를 dict 로 바꿨다. 값이 다르면 게이트 판정이 달라진다."""
    fx = svc.catalog_facets()
    fac = fx["_df"].set_index("track_id")
    meta = svc.catalog_facet_rows()
    assert set(meta) == set(fac.index)
    for t in list(meta)[:20]:
        for col in ("genre", "style", "language", "vocal_type", "bpm"):
            assert meta[t].get(col) == fac.loc[t].get(col), (t, col)
        for col in ("moods", "instruments"):
            assert list(meta[t].get(col) or []) == list(fac.loc[t].get(col) or []), (t, col)


def test_query_gate_result_is_unchanged_by_the_cache(svc):
    """두 번 불러도 같은 후보·같은 진단이어야 한다."""
    fx = svc.catalog_facets()
    pick = lambda k, n=2: [x["value"] for x in fx.get(k, [])[:n]]
    plan = {"filters": {"genre": pick("genre", 3), "language": pick("language", 2)},
            "exclude": {"mood": pick("mood", 1)}}
    a_pool, a_diag = svc.filter_query_pool(svc.CAT.pool, dict(plan), None, None)
    b_pool, b_diag = svc.filter_query_pool(svc.CAT.pool, dict(plan), None, None)
    assert a_diag == b_diag
    assert a_pool.moment_id.tolist() == b_pool.moment_id.tolist()
