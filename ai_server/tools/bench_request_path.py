"""요청 경로 합성 부하 측정 — daily·next·search·similar 의 지연 (S15P21A204-452).

설계 §10.3 병목 1위("ai_server 코드의 O(풀) 연산")가 실제로 사라졌는지 **시간으로** 본다.
시험(`ai_server/tests`)과 따로 둔다 — 1만 곡(약 9만 구간, 1536차) 판을 만들면 수 GB 의
디스크와 수십 초가 들어 CI 에서 돌릴 것이 아니다.

    python -m ai_server.tools.bench_request_path --tracks 10000 --per-track 9 \\
        --workdir /tmp/mupid-bench --out bench.json

같은 스크립트를 바꾸기 전 코드에서도 돌려 전·후를 비교한다(스크립트는 추천 모듈의 공개
함수만 부른다 — `recommend`·`similar_moments`·`Catalog.with_catalog`).

    git archive <기준 커밋> ai_server | tar -x -C /tmp/before
    PYTHONPATH=/tmp/before python ai_server/tools/bench_request_path.py --workdir /tmp/mupid-bench

**값은 무작위다.** 추천 품질은 볼 수 없고 계산량만 본다. 판 모양(열·차원·파일)은 운영
판과 같게 만든다 — `ai_server/tests/test_pipeline_synthetic.py::_build` 를 키운 것이다.

잰 것
    cold    그 사본의 첫 요청 (사본마다 한 번 세는 준비가 들어간다)
    warm    같은 사본으로 거듭 부른 요청의 중앙값 — 운영에서 대부분의 요청이 이쪽이다
    p95     cold 한 번과 warm `--repeat` 번을 합친 표본의 95 백분위 (설계 §13 5단계: < 1초)
    view    본문 `catalog` 로 요청 사본을 만드는 시간 (본문이 바뀔 때마다 한 번)

**요청 경계까지** (`--api`, S15P21A204-458) — 같은 시나리오를 FastAPI 앱에 HTTP 본문으로 보내
잰다(프로세스 안 ASGI, 망 없음). 본문 JSON 해석·검증·사본 찾기가 들어간다. 계약 v2 코드(458 전)
에는 요청마다 본문 `catalog`(1만 곡 약 5MB)를 싣고, v3 코드에는 싣지 않는다 — 어느 쪽인지는
`app/pool_snapshot.py` 가 있는지로 가른다. v3 는 기준 풀 스냅샷을 본문과 같은 값으로 미리 둔다
(`install_static` — 피처 저장소 읽기는 `bench_pool_snapshot.py` 가 따로 잰다).

    PYTHONPATH=/tmp/before python ai_server/tools/bench_request_path.py --api --label before
    python -m ai_server.tools.bench_request_path --api --label after

**Qdrant 갈래** (S15P21A204-456) — 로컬 Qdrant 를 띄우고 판의 벡터를 넣은 뒤(개발 전용 적재
`tools/qdrant_dev_load.py`) 같은 시나리오를 `MUPID_VECTOR_BACKEND=qdrant` 로 잰다:

    docker run -d --name bench-qdrant -p 127.0.0.1:6333:6333         -e QDRANT__SERVICE__API_KEY=w -e QDRANT__SERVICE__READ_ONLY_API_KEY=r qdrant/qdrant:v1.19.1
    python -m ai_server.tools.bench_request_path --backend qdrant --qdrant-url http://127.0.0.1:6333         --qdrant-write-key w --qdrant-read-key r --repeat 30
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
from pathlib import Path

import numpy as np
import pandas as pd

LABELS = ["intro", "verse", "chorus", "bridge", "outro", "inst", "solo"]
ENUM = {"intro": "INTRO", "verse": "VERSE", "chorus": "CHORUS", "bridge": "BRIDGE",
        "outro": "OUTRO", "inst": "INST", "solo": "SOLO"}
GENRES = ["contemporary_pop", "pop_ballad", "indie_rock", "modern_jazz", "hip_hop",
          "edm", "rnb", "folk"]
MOODS = ["calm", "bright", "sad", "energetic", "dreamy"]
FEATS = (["rule_sim", "model_sim", "log_dur", "rel_center", "rms_pct", "bpm_log2_ratio"]
         + [f"lab_{x}" for x in ["intro", "verse", "chorus", "bridge", "inst", "solo",
                                  "outro", "other"]])


def build(root: Path, tracks: int, per_track: int, dim: int, feat_dim: int,
          s1_dim: int, seed: int = 0) -> None:
    """운영 판 모양의 합성 판. 있으면 다시 만들지 않는다(같은 인자일 때)."""
    d = root / "data" / "proc" / "mupid"
    stamp = d / "bench_stamp.json"
    want = {"tracks": tracks, "per_track": per_track, "dim": dim, "feat_dim": feat_dim,
            "s1_dim": s1_dim, "seed": seed}
    if stamp.exists() and json.loads(stamp.read_text(encoding="utf-8")) == want:
        return
    rng = np.random.default_rng(seed)
    (d / "serve").mkdir(parents=True, exist_ok=True)
    (d / "logs").mkdir(parents=True, exist_ok=True)
    n = tracks * per_track
    tid = np.repeat([f"T{t:06d}" for t in range(tracks)], per_track)
    sidx = np.tile(np.arange(per_track), tracks)
    mom = pd.DataFrame({
        "moment_id": [f"{t}#{s}" for t, s in zip(tid, sidx)], "track_id": tid,
        "label_source": "allin1", "emb_row": np.arange(n), "source": "flow",
        "rms": rng.uniform(0.05, 0.4, n), "track_dur_sec": float(per_track * 30),
        "rel_start": sidx / per_track, "rel_end": (sidx + 1) / per_track})
    mom.to_parquet(d / "moments.parquet", index=False)

    # 원본 벡터는 조각으로 쓴다 — 9만 × 1536 이면 한 번에 만들 때 임시 행렬이 두 배로 든다.
    Z = np.lib.format.open_memmap(d / "moments_emb.npy", mode="w+", dtype=np.float32,
                                  shape=(n, dim))
    centers = rng.normal(size=(64, dim)).astype(np.float32)
    for a in range(0, n, 8192):
        b = min(a + 8192, n)
        z = centers[rng.integers(0, 64, b - a)] + 0.7 * rng.normal(size=(b - a, dim))
        Z[a:b] = (z / np.linalg.norm(z, axis=1, keepdims=True)).astype(np.float32)
    Z.flush()
    del Z

    np.savez(d / "features_v1.npz", X=rng.normal(size=(n, feat_dim)).astype(np.float32),
             moment_id=mom.moment_id.to_numpy().astype("<U80"))
    side = ["log_dur", "rel_center"]
    (d / "features_info.json").write_text(json.dumps(
        {"version": "feat-v1", "pca_dim": feat_dim - len(side), "side_dim": len(side),
         "side_cols": side}), encoding="utf-8")
    s1 = rng.normal(size=(n, s1_dim)).astype(np.float32)
    np.save(d / "stage1_section.npy", s1 / np.linalg.norm(s1, axis=1, keepdims=True))
    np.savez(
        d / "stage1_weights.npz",
        enc0_w=rng.normal(size=(192, feat_dim)).astype(np.float32) * 0.1,
        enc0_b=np.zeros(192, np.float32), ln_w=np.ones(192, np.float32),
        ln_b=np.zeros(192, np.float32),
        enc1_w=rng.normal(size=(s1_dim, 192)).astype(np.float32) * 0.1,
        enc1_b=np.zeros(s1_dim, np.float32),
        att0_w=rng.normal(size=(64, s1_dim)).astype(np.float32) * 0.1,
        att0_b=np.zeros(64, np.float32),
        att1_w=rng.normal(size=(1, 64)).astype(np.float32) * 0.1,
        att1_b=np.zeros(1, np.float32), gmean=np.zeros(s1_dim, np.float32),
        hist_max=np.asarray(32, np.int64), in_dim=np.asarray(feat_dim, np.int64))
    # 운영처럼 로지스틱 결합기를 둔다 — 결합기 경로가 묶음마다 특징을 다시 만든다.
    (d / "logistic_flow_stage1.json").write_text(json.dumps({
        "features": FEATS, "scaler_mean": [0.0] * len(FEATS),
        "scaler_scale": [1.0] * len(FEATS),
        "coef": rng.normal(size=len(FEATS)).round(3).tolist(), "intercept": -1.0,
        "suggested_threshold": 0.3}), encoding="utf-8")
    # 이력이 있는 사용자 한 명 (DNA 경로). 파일 라벨 경로로 읽힌다.
    picks = rng.choice(n, 40, replace=False)
    pd.DataFrame({
        "label_id": [f"L{i}" for i in range(40)], "user_ref": "internal:1",
        "track_id": tid[picks], "section_key": mom.moment_id.to_numpy()[picks],
        "is_merged": False, "grade": "L1", "evidence": "{}",
        "occurred_at": pd.Timestamp("2026-09-20", tz="UTC")}).to_parquet(
        d / "labels_service.parquet", index=False)
    stamp.write_text(json.dumps(want), encoding="utf-8")


def catalog_body(tracks: int, per_track: int, keep=None, seed: int = 7) -> dict:
    """Spring 이 싣는 `catalog` 본문. `keep(track_index)` 가 거짓인 곡은 싣지 않는다(검색)."""
    rng = np.random.default_rng(seed)
    t_cols = {k: [] for k in ("track", "duration_ms", "bpm", "bpm_requested_min",
                              "bpm_requested_max", "language", "category", "genres",
                              "moods", "has_vocal", "vocal_gender")}
    s_cols = {k: [] for k in ("external_id", "section_label", "start_ms", "end_ms", "energy")}
    for t in range(tracks):
        bpm = float(rng.uniform(70, 150))
        if keep is not None and not keep(t):
            continue
        tid = f"T{t:06d}"
        g = GENRES[t % len(GENRES)]
        for k, v in (("track", tid), ("duration_ms", per_track * 30_000), ("bpm", bpm),
                     ("bpm_requested_min", None), ("bpm_requested_max", None),
                     ("language", ["ko", "en", "ja"][t % 3]), ("category", g),
                     ("genres", [g]), ("moods", [MOODS[t % len(MOODS)], MOODS[(t // 5) % 5]]),
                     ("has_vocal", t % 4 != 0), ("vocal_gender", ["F", "M", "MIX"][t % 3])):
            t_cols[k].append(v)
        for s in range(per_track):
            for k, v in (("external_id", f"{tid}#{s}"),
                         ("section_label", ENUM[LABELS[(s + t) % len(LABELS)]]),
                         ("start_ms", s * 30_000), ("end_ms", s * 30_000 + 28_000),
                         ("energy", None)):
                s_cols[k].append(v)
    return {"tracks": t_cols, "sections": s_cols}


def _api_scenarios(rc, body: dict, s_body: dict, seed: str, cur: str) -> tuple[dict, dict]:
    """HTTP 본문으로 부르는 시나리오. 계약 v2 코드면 본문에 `catalog` 를 싣는다."""
    from fastapi import FastAPI
    from fastapi.testclient import TestClient

    from ai_server.app import internal_api

    try:
        from ai_server.app import pool_snapshot
    except ImportError:                          # 458 전 코드 — 본문으로 후보를 보낸다
        pool_snapshot = None
    info: dict = {"contract": "v2" if pool_snapshot is None else "v3"}
    if pool_snapshot is not None:
        t0 = time.perf_counter()
        pool_snapshot.install_static(body, on_swap=internal_api.warm(rc))
        info["snapshot_install_s"] = round(time.perf_counter() - t0, 2)
    app = FastAPI()
    internal_api.install(app, rc)
    client = TestClient(app)
    client.get("/internal/ready")                # 첫 호출의 준비(스레드 풀 등)를 재지 않게
    genre = GENRES[0]
    plan = {"filters": {"mood": [MOODS[1]]}}
    if pool_snapshot is not None:
        # v3 는 조건을 여기서 푼다 — v2 에서 Spring 이 장르로 거른 본문(`s_body`)과 같은 구간이 남는다.
        plan = {"filters": {"genre": [genre], "mood": [MOODS[1]]}}
    reqs = {
        "daily/cold": ("/internal/recommendations/daily", {"user_id": 900001}, body),
        "daily/onboarding": ("/internal/recommendations/daily",
                             {"user_id": 900002, "onboarding": {"moods": [], "genres": [GENRES[1]]}},
                             body),
        "daily/dna": ("/internal/recommendations/daily", {"user_id": 1}, body),
        "next/dna": ("/internal/recommendations/next",
                     {"user_id": 1, "limit": 10, "current_track": cur.split("#")[0],
                      "current_section": cur}, body),
        "search/mood": ("/internal/recommendations/search",
                        {"user_id": 900001, "limit": 10, "query_plan": plan}, s_body),
        "similar": ("/internal/similar", {"external_id": seed, "limit": 10}, body),
    }
    out = {}
    sizes = {}
    for name, (path, extra, cat) in reqs.items():
        payload = dict(extra)
        if not path.endswith("similar"):
            payload["recommendation_id"] = 1
        if pool_snapshot is None:
            payload["catalog"] = cat
        raw = json.dumps(payload, separators=(",", ":")).encode()
        sizes[name] = len(raw)

        def run(path=path, raw=raw):
            r = client.post(path, content=raw, headers={"content-type": "application/json"})
            if r.status_code != 200:
                raise SystemExit(f"{path} → {r.status_code} {r.text[:300]}")
            return r.json()["items"]

        out[name] = run
    info["request_bytes"] = sizes
    return out, info


def _time(fn, repeat: int) -> dict:
    t0 = time.perf_counter()
    first = fn()
    cold = time.perf_counter() - t0
    warm = []
    for _ in range(repeat):
        t0 = time.perf_counter()
        fn()
        warm.append(time.perf_counter() - t0)
    every = sorted([cold] + warm)
    p95 = every[min(len(every) - 1, int(np.ceil(0.95 * len(every))) - 1)]
    return {"cold_ms": round(cold * 1000, 1),
            "warm_ms": round(statistics.median(warm) * 1000, 1) if warm else None,
            "warm_max_ms": round(max(warm) * 1000, 1) if warm else None,
            "p95_ms": round(p95 * 1000, 1),
            "returned": len(first.get("results", first) if isinstance(first, dict) else first)}


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--tracks", type=int, default=10_000)
    ap.add_argument("--per-track", type=int, default=9)
    ap.add_argument("--dim", type=int, default=1536)
    ap.add_argument("--feat-dim", type=int, default=285)
    ap.add_argument("--s1-dim", type=int, default=128)
    ap.add_argument("--repeat", type=int, default=5)
    ap.add_argument("--workdir", default="bench_artifacts")
    ap.add_argument("--label", default="")
    ap.add_argument("--out", default=None)
    ap.add_argument("--backend", choices=("local", "qdrant"), default="local")
    ap.add_argument("--qdrant-url", default="http://127.0.0.1:6333")
    ap.add_argument("--qdrant-write-key", default="")
    ap.add_argument("--qdrant-read-key", default="")
    ap.add_argument("--api", action="store_true",
                    help="HTTP 본문으로 요청 경계까지 잰다 (계약 v2 는 catalog 를 싣는다)")
    a = ap.parse_args()

    root = Path(a.workdir).resolve()
    t0 = time.perf_counter()
    build(root, a.tracks, a.per_track, a.dim, a.feat_dim, a.s1_dim)
    build_s = time.perf_counter() - t0
    os.environ["MUPID_AI_ARTIFACT_DIR"] = str(root)
    os.environ.pop("MUPID_PINECONE_ENABLED", None)
    os.environ.pop("MUPID_CLICKHOUSE_URL", None)
    os.environ["MUPID_VECTOR_BACKEND"] = a.backend
    qdrant_load = None
    if a.backend == "qdrant":
        from ai_server.tools import qdrant_dev_load
        qdrant_load = qdrant_dev_load.load(a.qdrant_url, a.qdrant_write_key,
                                           root / "data" / "proc" / "mupid")
        qdrant_load.pop("mu", None)
        os.environ["MUPID_QDRANT_URL"] = a.qdrant_url
        os.environ["MUPID_QDRANT_READ_ONLY_API_KEY"] = a.qdrant_read_key

    from ai_server.app import catalog_body as cb
    from ai_server.app import recommender as rc

    t0 = time.perf_counter()
    rc.CAT = rc.Catalog()
    load_s = time.perf_counter() - t0

    body = catalog_body(a.tracks, a.per_track)
    t0 = time.perf_counter()
    view = rc.CAT.with_catalog(cb.from_body(body))
    view_s = time.perf_counter() - t0
    # 검색: Spring 이 조건(장르 하나)으로 거른 본문 — 약 1/8.
    s_body = catalog_body(a.tracks, a.per_track, keep=lambda t: t % len(GENRES) == 0)
    t0 = time.perf_counter()
    s_view = rc.CAT.with_catalog(cb.from_body(s_body))
    s_view_s = time.perf_counter() - t0

    onboarding = {"language": ["ko"], "vocal_type": [], "style": [], "genre": [GENRES[1]],
                  "mood": [MOODS[0]], "instrument": [], "tempo": [], "energy": [],
                  "section_label": [], "exclude_style": [], "mood_raw": [], "genre_raw": []}
    seed = f"T{a.tracks // 2:06d}#1"
    cur = f"T{a.tracks // 3:06d}#2"
    from datetime import datetime
    now = datetime.fromisoformat("2026-09-25T12:00:00+09:00")

    def on(v, fn):
        def run():
            with rc.using(v):
                return fn()
        return run

    scenarios = {
        "daily/cold": on(view, lambda: rc.recommend(
            "internal:new", 4, set(), True, None, daily=True, now=now, beh_events=[])),
        "daily/onboarding": on(view, lambda: rc.recommend(
            "internal:onb", 4, set(), True, None, daily=True, now=now, beh_events=[],
            onboarding=onboarding)),
        "daily/dna": on(view, lambda: rc.recommend(
            "internal:1", 4, set(), True, None, daily=True, now=now, beh_events=[])),
        "next/dna": on(view, lambda: rc.recommend(
            "internal:1", 10, set(), True, None, cur.split("#")[0], cur, beh_events=[])),
        "search/mood": on(s_view, lambda: rc.recommend(
            "internal:new", 10, set(), True, {"filters": {"mood": [MOODS[1]]}})),
        "similar": on(view, lambda: rc.similar_moments(seed, 10)),
    }
    if a.backend == "qdrant":
        with rc.using(view):
            problem = rc.vector_store_problem()
        if problem:
            raise SystemExit(f"Qdrant 준비 안 됨: {problem}")
    api = None
    if a.api:
        scenarios, api = _api_scenarios(rc, body, s_body, seed, cur)
    results = {name: _time(fn, a.repeat) for name, fn in scenarios.items()}
    report = {
        "label": a.label, "backend": a.backend, "qdrant_load": qdrant_load,
        "tracks": a.tracks, "moments": a.tracks * a.per_track,
        "dim": a.dim, "repeat": a.repeat,
        "build_s": round(build_s, 1), "catalog_load_s": round(load_s, 2),
        "view_build_s": round(view_s, 2), "search_view_build_s": round(s_view_s, 2),
        "body_bytes": len(json.dumps(body, separators=(",", ":"))),
        **({"api": api} if api else {}),
        "results": results,
    }
    text = json.dumps(report, ensure_ascii=False, indent=1)
    print(text)
    if a.out:
        Path(a.out).write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
