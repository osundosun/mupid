"""기준 풀 스냅샷 갱신 비용 — 갱신 한 번의 시간·메모리 (S15P21A204-458).

계약 v3 에서 AI 는 곡·구간 값을 요청마다 받지 않고, 백그라운드에서 색인 풀 전부를 피처 저장소에서
읽어 스냅샷으로 갈아 끼운다(`app/pool_snapshot.py`, 기본 60초마다). 요청 경로에는 없지만 풀 크기에
비례하는 일이라 설계 규모(10만 곡, 약 90만 구간)에서 얼마인지 잰다. 두 갈래다.

    (기본)          ClickHouse 없이 **같은 모양의 응답**(JSONEachRow, 구간 한 줄에 곡 열이 `f_` 로
                    붙는다)을 메모리에서 주고, 갱신 전체를 잰다 — 응답 해석 → 맞추기(`assemble`) →
                    스냅샷 → 사본·풀 색인·조건 파생값 미리 만들기(`internal_api.warm`). 벡터는 쓰지
                    않으므로 차원을 작게 둔다(시간·메모리가 차원과 무관하다).
    --clickhouse    dev ClickHouse(`api/compose.yml`)에 합성 곡을 넣고(`bench_feature_store.setup`)
                    서빙 읽기가 조각으로 전부 읽는 시간(`lookup_many`). 운영 노드와 같은 조건은 아니다

    uv run --python 3.12 --with-requirements ai_server/requirements.txt --with psutil \\
        python -m ai_server.tools.bench_pool_snapshot --tracks 100000 --out snap.json
    ... --clickhouse --tracks 10000

(기본 갈래의 `refresh_s` 는 첫 갱신만 내용이 바뀐 갱신이고 나머지는 같은 내용을 다시 읽은 것이다 — 운영은
그 경우 싼 변경 확인(`change_token`, `--clickhouse` 가 잰다)으로 읽기 자체를 건너뛴다.)

잰 것
    refresh_s       갱신 한 번 (갈아 끼우기까지). `--runs` 번의 중앙값과 최댓값
      read_s        그중 조각 응답을 받아 해석·맞추는 시간 (가짜 서버가 응답을 만드는 시간은 뺐다)
      snapshot_s    본문 모양 → `Snapshot`
      warm_s        사본·풀 색인·곡 어휘·조건 파생값
    rss_mb          갱신 전후 프로세스 메모리(psutil 이 있을 때). 스냅샷·사본 한 벌이 붙들고 있는 양
    peak_mb         갱신 중 파이썬 할당 최대치(`--trace-memory`, tracemalloc — 느려진다)
"""
from __future__ import annotations

import argparse
import gc
import json
import os
import re
import statistics
import sys
import time
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

UID = re.compile(r"'(T\d{6}#\d+)'")


def _rows(body: dict, deleted_every: int = 40) -> tuple[list, dict]:
    """본문 모양 → ClickHouse 사본 행. 곡 `deleted_every` 개마다 하나는 삭제, 그 다음은 재생 불가."""
    from ai_server.app import catalog_body

    tr, sec = body["tracks"], body["sections"]
    features = {}
    for i, t in enumerate(tr["track"]):
        mid = 1000 + int(t[1:])
        features[mid] = {"track_id": mid, "track": t,
                         **{c: tr[c][i] for c in catalog_body.TRACK_COLS if c != "track"},
                         "is_deleted": i % deleted_every == 0,
                         "playable": i % deleted_every != 1, "analysis_version": "v1"}
    instruments = ["piano", "guitar", "drums", "bass", "other"]
    sections = []
    for i, u in enumerate(sec["external_id"]):
        sections.append({"section_uid": u, "track_id": 1000 + int(u[1:7]),
                         **{c: sec[c][i] for c in catalog_body.SECTION_COLS if c != "external_id"},
                         "analysis_version": "v1",
                         "instruments": [instruments[i % 5], instruments[(i // 5) % 5]]})
    return sections, features


class FakeServing:
    """`lookup_sql` 한 조각에 ClickHouse 처럼 답한다. 응답을 만드는 데 든 시간은 따로 센다."""

    def __init__(self, sections: list, features: dict) -> None:
        from ai_server.app import feature_store as fs

        self.cols = fs._FEATURE_COLS
        self.by_uid = {r["section_uid"]: r for r in sections}
        self.features = features
        self.spent = 0.0
        self.calls = 0

    def __call__(self, sql: str) -> str:
        t0 = time.perf_counter()
        lines = []
        for uid in UID.findall(sql):
            r = self.by_uid.get(uid)
            if r is None:
                continue
            f = self.features[r["track_id"]]
            lines.append(json.dumps({**r, **{f"f_{c}": f[c] for c in self.cols}}))
        self.calls += 1
        out = "\n".join(lines) + "\n"
        self.spent += time.perf_counter() - t0
        return out


def _rss_mb() -> float | None:
    try:
        import psutil
    except ImportError:
        return None
    return round(psutil.Process(os.getpid()).memory_info().rss / 1e6, 1)


def in_process(a) -> dict:
    from ai_server.tools import bench_request_path as brp

    root = Path(a.workdir).resolve()
    t0 = time.perf_counter()
    brp.build(root, a.tracks, a.per_track, dim=8, feat_dim=12, s1_dim=8)
    build_s = time.perf_counter() - t0
    os.environ["MUPID_AI_ARTIFACT_DIR"] = str(root)
    os.environ["MUPID_VECTOR_BACKEND"] = "local"
    for k in ("MUPID_CLICKHOUSE_URL", "MUPID_CLICKHOUSE_SERVING_URL"):
        os.environ.pop(k, None)

    from ai_server.app import feature_store as fs
    from ai_server.app import internal_api, pool_snapshot
    from ai_server.app import recommender as rc

    rc.CAT = rc.Catalog()
    sections, features = _rows(brp.catalog_body(a.tracks, a.per_track))
    fake = FakeServing(sections, features)
    store = fs.ClickHouseFeatureStore(query=fake)
    ids = rc.index_pool(rc.CAT).moment_id.astype(str).tolist()
    del sections
    gc.collect()

    split = {"read": [], "snapshot": [], "warm": []}
    real_lookup, real_from_lookup, real_warm = store.lookup_many, pool_snapshot.from_lookup, \
        internal_api.warm(rc)

    def timed_lookup(uids, chunk=fs.CHUNK):
        spent0, t = fake.spent, time.perf_counter()
        got = real_lookup(uids, chunk)
        split["read"].append(time.perf_counter() - t - (fake.spent - spent0))
        return got

    def timed_from_lookup(lk):
        t = time.perf_counter()
        got = real_from_lookup(lk)
        split["snapshot"].append(time.perf_counter() - t)
        return got

    def timed_warm(p):
        t = time.perf_counter()
        real_warm(p)
        split["warm"].append(time.perf_counter() - t)

    store.lookup_many = timed_lookup
    pool_snapshot.from_lookup = timed_from_lookup
    holder = pool_snapshot.Holder(pool_snapshot.feature_store_loader(store, lambda: ids),
                                  ttl=3600, on_swap=timed_warm)
    pool_snapshot.install(holder)

    rss0 = _rss_mb()
    totals, rss = [], []
    for _ in range(a.runs):
        spent0, t = fake.spent, time.perf_counter()
        if not holder.refresh():
            raise SystemExit(f"갱신 실패: {holder.last_error}")
        totals.append(time.perf_counter() - t - (fake.spent - spent0))
        gc.collect()
        rss.append(_rss_mb())
    peak = None
    if a.trace_memory:
        import tracemalloc

        tracemalloc.start()
        holder.refresh()
        peak = round(tracemalloc.get_traced_memory()[1] / 1e6, 1)
        tracemalloc.stop()
    st = holder.status()

    def med(xs):
        return round(statistics.median(xs), 2)

    return {"mode": "in_process", "tracks": a.tracks, "sections": len(ids), "runs": a.runs,
            "build_s": round(build_s, 1), "fake_server_calls_per_refresh": fake.calls // max(a.runs, 1),
            "snapshot": {k: st[k] for k in ("tracks", "sections", "dropped", "missing", "stale")},
            "refresh_s": {"median": med(totals), "max": round(max(totals), 2)},
            "read_s": med(split["read"]), "snapshot_s": med(split["snapshot"]),
            "warm_s": med(split["warm"]),
            "rss_mb": {"before": rss0, "after_each": rss},
            "peak_mb": peak}


def with_clickhouse(a) -> dict:
    from ai_server.app import feature_store as fs
    from ai_server.tools import bench_feature_store as bfs
    from ai_server.tools import feature_store_synth as synth

    password = os.environ.get("MUPID_CLICKHOUSE_PASSWORD", "")
    env = REPO / "api" / ".env"
    if not password and env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            if line.startswith("MUPID_CLICKHOUSE_PASSWORD="):
                password = line.split("=", 1)[1].strip()
    http = bfs.Http(a.url, a.user, password, a.db)
    data = synth.synth(a.tracks, seed=458)
    info = bfs.setup(http, a.db, data)
    try:
        store = fs.ClickHouseFeatureStore(query=http, database=a.db)
        uids = [s.external_id for s in data.sections]
        times = []
        for _ in range(a.runs):
            t = time.perf_counter()
            got = store.lookup_many(uids)
            times.append(time.perf_counter() - t)
        equal = got.body() == synth.spring_catalog_body(data)
        probes = []
        for _ in range(max(a.runs, 5)):
            t = time.perf_counter()
            token = store.change_token()
            probes.append(time.perf_counter() - t)
        return {"mode": "clickhouse", "url": a.url, "tracks": len(data.music),
                "sections": len(uids), "runs": a.runs, **info, "equal_body": equal,
                "chunks": len(fs._chunks(uids, fs.CHUNK)),
                "lookup_many_s": {"median": round(statistics.median(times), 2),
                                  "max": round(max(times), 2)},
                "change_token_ms": {"median": round(statistics.median(probes) * 1000, 1),
                                    "max": round(max(probes) * 1000, 1)},
                "change_token": token,
                "kept_sections": len(got.sections), "dropped": len(got.dropped),
                "instruments_read": any(got.instruments.values())}
    finally:
        if not a.keep:
            http(f"DROP DATABASE IF EXISTS {a.db} ON CLUSTER mupid_all SYNC", database="default")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n", 1)[0])
    ap.add_argument("--tracks", type=int, default=100_000)
    ap.add_argument("--per-track", type=int, default=9)
    ap.add_argument("--runs", type=int, default=3)
    ap.add_argument("--workdir", default="bench_pool_snapshot")
    ap.add_argument("--trace-memory", action="store_true")
    ap.add_argument("--clickhouse", action="store_true")
    ap.add_argument("--url", default=os.environ.get("MUPID_BENCH_CLICKHOUSE_URL",
                                                    "http://localhost:18123"))
    ap.add_argument("--user", default="mupid")
    ap.add_argument("--db", default="mupid_bench458")
    ap.add_argument("--keep", action="store_true")
    ap.add_argument("--out", type=Path)
    a = ap.parse_args()
    report = with_clickhouse(a) if a.clickhouse else in_process(a)
    text = json.dumps(report, ensure_ascii=False, indent=1)
    print(text)
    if a.out:
        a.out.write_text(text + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
