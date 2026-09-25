"""피처 저장소 서빙 지연 측정 — 특징 조회(후보 300)·조건 풀기의 p50/p95 (S15P21A204-455).

설계 §13 4단계 "1만 곡 합성에서 특징 조회·조건 풀기 p95 가 기준 안", §11 미결 4.

로컬 dev ClickHouse 클러스터(`api/compose.yml`, 2 노드 × `mupid_all`)에 **따로 만든 DB**
(기본 `mupid_bench455`)를 세우고 합성 1만 곡(약 9만 구간)을 넣은 뒤, 서빙 읽기 모듈
(`app/feature_store.py`)이 운영에서 던질 문장을 그대로 던진다. `default` DB 의 표에는 손대지
않는다 — 스키마는 `api/clickhouse/init/01-schema.sql` 의 해당 문장을 **DB 이름과 Keeper
경로만 바꿔** 적용한다(같은 DDL 이 실제로 도는지도 함께 확인된다).

    uv run --python 3.12 --with-requirements ai_server/requirements.txt \\
        python -m ai_server.tools.bench_feature_store --tracks 10000 --out bench.json
    ... --drop         # 측정 DB 를 지운다

비밀번호는 `MUPID_CLICKHOUSE_PASSWORD` 또는 `api/.env` 에서 읽는다.

잰 것 (각 `--runs` 번, 앞의 `--warmup` 번은 버린다)
    lookup     후보 300 구간(삭제·재생 불가·없는 ID 가 섞인다)의 특징 조회
    solve      조건 풀기 — 조건 조합 여섯 가지를 돌아가며
    client     이 PC 에서 잰 왕복(HTTP·JSON 해석 포함). 운영의 같은 노드 접속보다 느리다
    server     ClickHouse 가 응답 머리 `X-ClickHouse-Summary.elapsed_ns` 로 알려 준 실행 시간

그 밖에 합성 본문과 읽기 모듈 결과가 같은지(`equal`)도 한 번 본다 — 시험
(`tests/test_feature_store.py::test_live_clickhouse_matches_catalog`)과 같은 확인이다.
"""
from __future__ import annotations

import argparse
import json
import os
import random
import re
import statistics
import sys
import time
import urllib.parse
import urllib.request
from base64 import b64encode
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO))

from ai_server.app import catalog_body, feature_store  # noqa: E402
from ai_server.tools import feature_store_synth as synth  # noqa: E402

SCHEMA = REPO / "api" / "clickhouse" / "init" / "01-schema.sql"
OBJECTS = ("track_sections_local", "track_sections", "track_features_local", "track_features",
           "v_serving_sections", "dict_serving_sections")


class Http:
    def __init__(self, url: str, user: str, password: str, database: str) -> None:
        self.url = url.rstrip("/")
        self.auth = "Basic " + b64encode(f"{user}:{password}".encode()).decode()
        self.database = database
        self.last_server_ms: float = 0.0

    def __call__(self, sql: str, body: bytes | None = None, database: str | None = None,
                 timeout: float = 60) -> str:
        params = {"database": database or self.database}
        if body is not None:
            params["query"] = sql
        url = f"{self.url}/?{urllib.parse.urlencode(params)}"
        req = urllib.request.Request(url, data=body if body is not None else sql.encode(),
                                     method="POST")
        req.add_header("Authorization", self.auth)
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                summary = json.loads(r.headers.get("X-ClickHouse-Summary") or "{}")
                self.last_server_ms = int(summary.get("elapsed_ns", 0)) / 1e6
                return r.read().decode()
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"ClickHouse {e.code}: {e.read().decode()[:600]}") from None


def statements(db: str) -> list[str]:
    """01-schema.sql 에서 서빙 객체의 문장만 골라 DB 이름·Keeper 경로를 바꾼다."""
    text = SCHEMA.read_text(encoding="utf-8")
    out = []
    for raw in text.split(";\n"):
        body = "\n".join(ln for ln in raw.splitlines() if not ln.lstrip().startswith("--"))
        m = re.search(r"CREATE (?:OR REPLACE )?(?:TABLE|VIEW|DICTIONARY)(?: IF NOT EXISTS)? (\w+)",
                      body)
        if not m or m.group(1) not in OBJECTS:
            continue
        name = m.group(1)
        stmt = body.replace(f" {name} ON CLUSTER", f" {db}.{name} ON CLUSTER", 1)
        stmt = stmt.replace("'/clickhouse/tables/", f"'/clickhouse/bench/{db}/tables/")
        stmt = re.sub(r"\bAS (track_\w+_local)\n", rf"AS {db}.\1\n", stmt)
        stmt = stmt.replace("SOURCE(CLICKHOUSE(TABLE", f"SOURCE(CLICKHOUSE(DB '{db}' TABLE")
        stmt = stmt.replace("database = ''default''", f"database = ''{db}''")
        out.append(stmt.strip())
    missing = set(OBJECTS) - {re.search(r"\.(\w+) ON CLUSTER", s).group(1) for s in out}
    if missing:
        raise SystemExit(f"01-schema.sql 에서 찾지 못했다: {sorted(missing)}")
    return out


def setup(http: Http, db: str, data: synth.Db) -> dict:
    http(f"DROP DATABASE IF EXISTS {db} ON CLUSTER mupid_all SYNC", database="default")
    http(f"CREATE DATABASE {db} ON CLUSTER mupid_all", database="default")
    for stmt in statements(db):
        http(stmt)
    t0 = time.perf_counter()
    for table, rows in (("track_sections_local", synth.section_rows(data)),
                        ("track_features_local", synth.feature_rows(data))):
        for i in range(0, len(rows), 20_000):
            chunk = "\n".join(json.dumps(r) for r in rows[i:i + 20_000]).encode()
            http(f"INSERT INTO {db}.{table} FORMAT JSONEachRow", body=chunk)
    load_s = time.perf_counter() - t0
    # 복제가 다른 노드에 닿기를 기다린 뒤 사전을 읽힌다.
    http(f"SYSTEM SYNC REPLICA {db}.track_sections_local")
    http(f"SYSTEM SYNC REPLICA {db}.track_features_local")
    # 병합을 끝내 둔다. 운영은 몇 곡씩 천천히 쌓여 병합된 상태가 보통이고, 막 넣은 파트의
    # 백그라운드 병합이 측정과 겹치면 dev 서버 메모리 한도(4GiB)에 걸린다(10만 곡에서 겪었다).
    for table in ("track_sections_local", "track_features_local"):
        http(f"OPTIMIZE TABLE {db}.{table} ON CLUSTER mupid_all FINAL", timeout=600)
    http(f"SYSTEM RELOAD DICTIONARY {db}.dict_serving_sections")
    info = json.loads(http(
        f"SELECT element_count, bytes_allocated, loading_duration FROM system.dictionaries "
        f"WHERE database = '{db}' AND name = 'dict_serving_sections' FORMAT JSONEachRow"))
    return {"load_s": round(load_s, 2), "dict_rows": int(info["element_count"]),
            "dict_mb": round(int(info["bytes_allocated"]) / 1e6, 1),
            "dict_load_s": info["loading_duration"]}


def pct(xs: list, p: float) -> float:
    xs = sorted(xs)
    k = max(0, min(len(xs) - 1, int(round(p / 100 * (len(xs) - 1)))))
    return round(xs[k], 2)


def summarize(xs: list) -> dict:
    return {"p50": pct(xs, 50), "p95": pct(xs, 95), "max": round(max(xs), 2),
            "mean": round(statistics.mean(xs), 2)}


def measure(store, http: Http, call, runs: int, warmup: int) -> dict:
    client, server = [], []
    for i in range(runs + warmup):
        t0 = time.perf_counter()
        call(i)
        dt = (time.perf_counter() - t0) * 1000
        if i >= warmup:
            client.append(dt)
            server.append(http.last_server_ms)
    return {"client_ms": summarize(client), "server_ms": summarize(server)}


CONDITIONS = [
    feature_store.Conditions(),
    feature_store.Conditions(genres=("hip_hop", "rnb_soul")),
    feature_store.Conditions(moods=("serene",), languages=("ko",)),
    feature_store.Conditions(has_vocal=False, section_labels=("CHORUS",)),
    feature_store.Conditions(genres=("edm",), vocal_genders=("F",), section_labels=("CHORUS", "BRIDGE")),
    feature_store.Conditions(languages=("en", "ja"), moods=("energetic", "intense")),
]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--url", default=os.environ.get("MUPID_BENCH_CLICKHOUSE_URL",
                                                    "http://localhost:18123"))
    ap.add_argument("--user", default="mupid")
    ap.add_argument("--db", default="mupid_bench455")
    ap.add_argument("--tracks", type=int, default=10_000)
    ap.add_argument("--candidates", type=int, default=300)
    ap.add_argument("--runs", type=int, default=200)
    ap.add_argument("--warmup", type=int, default=10)
    ap.add_argument("--seed", type=int, default=455)
    ap.add_argument("--out", type=Path)
    ap.add_argument("--keep", action="store_true", help="측정 뒤 DB 를 남긴다 (시험이 읽는다)")
    ap.add_argument("--drop", action="store_true", help="측정 DB 를 지우고 끝낸다")
    args = ap.parse_args()

    password = os.environ.get("MUPID_CLICKHOUSE_PASSWORD", "")
    if not password and (REPO / "api" / ".env").exists():
        for line in (REPO / "api" / ".env").read_text(encoding="utf-8").splitlines():
            if line.startswith("MUPID_CLICKHOUSE_PASSWORD="):
                password = line.split("=", 1)[1].strip()
    http = Http(args.url, args.user, password, args.db)
    if args.drop:
        http(f"DROP DATABASE IF EXISTS {args.db} ON CLUSTER mupid_all SYNC", database="default")
        print(f"{args.db} 를 지웠다")
        return

    data = synth.synth(args.tracks, seed=args.seed)
    print(f"합성 {len(data.music)}곡 {len(data.sections)}구간 → {args.url} {args.db}")
    info = setup(http, args.db, data)
    print(f"적재 {info}")

    store = feature_store.ClickHouseFeatureStore(query=http, database=args.db)
    uids = [s.external_id for s in data.sections]
    rng = random.Random(args.seed)
    batches = []
    for _ in range(args.runs + args.warmup):
        batch = rng.sample(uids, args.candidates - 5) + [f"T999999#{k}" for k in range(5)]
        batches.append(batch)

    # 같은 값인가 — 본문과 읽기 모듈.
    probe = batches[0]
    got = store.lookup(probe)
    want = synth.spring_catalog_body(data, keep=set(probe).__contains__)
    equal = got.body() == want
    snap_equal = (got.snapshot().tracks == catalog_body.from_body(want).tracks
                  and got.snapshot().sections == catalog_body.from_body(want).sections)
    print(f"본문과 같은가: body={equal} snapshot={snap_equal} "
          f"(구간 {len(got.sections)} · 없음 {len(got.missing)} · 뺌 {len(got.dropped)})")

    lookup = measure(store, http, lambda i: store.lookup(batches[i]), args.runs, args.warmup)
    counts = [store.solve(c).count for c in CONDITIONS]
    solve = measure(store, http, lambda i: store.solve(CONDITIONS[i % len(CONDITIONS)]),
                    args.runs, args.warmup)
    result = {
        "tracks": len(data.music), "sections": len(data.sections),
        "candidates": args.candidates, "runs": args.runs, **info,
        "equal_body": equal, "equal_snapshot": snap_equal,
        "lookup": lookup, "solve": solve, "solve_counts": counts,
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if args.out:
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n",
                            encoding="utf-8")
    if not args.keep:
        http(f"DROP DATABASE IF EXISTS {args.db} ON CLUSTER mupid_all SYNC", database="default")


if __name__ == "__main__":
    main()
