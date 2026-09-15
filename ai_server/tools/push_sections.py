"""AI 구간 분석 결과 → ClickHouse `track_sections`.

**이것이 없으면 행동 데이터가 통째로 버려진다.** `mv_user_section_signals` 는
`dictGet(dict_track_sections, ...)` 로 재생 위치가 어느 구간인지 찾고, 못 찾으면
`WHERE ... != ''` 에서 그 이벤트를 버린다. 구간 표가 비어 있으면 좋아요를 눌러도
`user_section_signals` 에 한 줄도 안 남는다(2026-09-15 에 실제로 재현했다).

    moments.parquet ──(id_map)──> track_sections ──> dict_track_sections
                                                          ↑
                              listen_events ──> mv_user_section_signals

읽기 전용 원칙의 예외
    AI 는 어떤 DB 에도 쓰지 않는다(decision/0011). 다만 `track_sections` 는 **AI 만이
    만들 수 있는 분석 결과**이고 Spring 을 거칠 이유가 없어 여기서 직접 넣는다.
    행동 데이터(`listen_events`·`user_section_signals`)는 **읽기만** 한다.

숫자 ID 를 지어내지 않는다
    `track_id` 는 MySQL 이 발급한 숫자다. `id_map.parquet` 에 없는 곡은 넣지 않고 센다.
    문자열을 해시하거나 순번을 붙이면 그럴듯하게 돌다가 실제 DB 와 어긋난 순간 엉뚱한
    곡을 추천한다.

    MUPID_CLICKHOUSE_URL       jdbc:clickhouse://host:port/db  또는 http://host:port
    MUPID_CLICKHOUSE_USER
    MUPID_CLICKHOUSE_PASSWORD
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from base64 import b64encode
from pathlib import Path

import pandas as pd

from ai_server.app import ids, paths
from ai_server.app.log import log

TIMEOUT = 60
BATCH = 5000

# `v_track_sections_current` 가 이 값만 고른다. 다른 값을 넣으면 표에는 들어가지만
# 딕셔너리가 보지 않아 **구간 판정에 전혀 쓰이지 않는다.**
DICT_VERSION = "v1"

# MySQL `section_label` ENUM 과 같은 어휘만 내보낸다. 한쪽에만 있는 라벨을 넣으면
# 나중에 두 저장소를 맞출 때 어느 쪽이 맞는지 알 수 없게 된다.
SECTION_ENUM = ("INTRO", "VERSE", "PRE_CHORUS", "CHORUS", "BRIDGE",
                "BREAK", "INST", "SOLO", "OUTRO")


def _http_base() -> str:
    """`jdbc:clickhouse://host:port/db` 도 받는다 — .env 가 그 형태로 적혀 있다."""
    raw = os.environ.get("MUPID_CLICKHOUSE_URL", "").strip()
    if not raw:
        raise SystemExit("MUPID_CLICKHOUSE_URL 이 비어 있다.")
    m = re.match(r"^jdbc:clickhouse://([^/]+)", raw)
    host = m.group(1) if m else re.sub(r"^https?://", "", raw).split("/")[0]
    return f"http://{host}"


def _auth() -> str:
    u = os.environ.get("MUPID_CLICKHOUSE_USER", "").strip()
    p = os.environ.get("MUPID_CLICKHOUSE_PASSWORD", "")
    if not u:
        raise SystemExit("MUPID_CLICKHOUSE_USER 가 비어 있다.")
    return "Basic " + b64encode(f"{u}:{p}".encode()).decode()


def query(sql: str, body: bytes | None = None) -> str:
    url = f"{_http_base()}/?query={urllib.parse.quote(sql)}" if body else _http_base() + "/"
    req = urllib.request.Request(url, data=body if body else sql.encode(), method="POST")
    req.add_header("Authorization", _auth())
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return r.read().decode()
    except urllib.error.HTTPError as e:
        raise SystemExit(f"ClickHouse 오류 {e.code}: {e.read().decode()[:400]}") from None


def build_rows(mom: pd.DataFrame, im, version: str) -> tuple[list[dict], dict]:
    """구간 표 한 벌을 만든다. 넣지 못한 것은 이유별로 센다."""
    stat = {"unmapped_track": 0, "unknown_label": 0, "bad_range": 0}
    unmapped: set[str] = set()
    rows = []
    for r in mom.itertuples():
        tid = str(r.track_id)
        try:
            num = im.music_id(tid)
        except Exception:                      # 매핑에 없는 곡은 지어내지 않는다
            stat["unmapped_track"] += 1
            unmapped.add(tid)
            continue
        fn = str(r.section_label).upper()
        if fn not in SECTION_ENUM:
            stat["unknown_label"] += 1
            continue
        s_ms, e_ms = ids.to_ms(r.start_sec), ids.to_ms(r.end_sec)
        if e_ms <= s_ms:
            stat["bad_range"] += 1
            continue
        rows.append({
            "track_id": int(num),
            "analysis_version": version,
            "section_index": int(r.section_idx),
            "section_uid": str(r.moment_id),
            "fn": fn,
            "start_ms": s_ms,
            "end_ms": e_ms,
            # 구간 안 최대 에너지 지점을 아직 산출하지 않는다. 0 은 "구간 밖" 과
            # 구분이 안 되므로 시작점을 넣는다 — 추천도 구간 시작부터 튼다.
            "peak_ms": s_ms,
            "created_at": pd.Timestamp.now("UTC").strftime("%Y-%m-%d %H:%M:%S"),
        })
    stat["unmapped_sample"] = sorted(unmapped)[:5]
    return rows, stat


def insert(rows: list[dict]) -> None:
    """`ReplicatedReplacingMergeTree(created_at)` 라 같은 (버전·곡·순번) 은 덮어쓴다."""
    sql = "INSERT INTO track_sections FORMAT JSONEachRow"
    for i in range(0, len(rows), BATCH):
        chunk = rows[i:i + BATCH]
        body = "\n".join(json.dumps(x, ensure_ascii=False) for x in chunk).encode()
        query(sql, body)
        log(f"  적재 {i + len(chunk)}/{len(rows)}")


def reload_dictionary() -> None:
    """딕셔너리는 LIFETIME(300~600초) 로 알아서 갱신되지만, 그때까지는 새 구간이
    판정에 쓰이지 않는다. **두 노드 모두** 갱신해야 한다 — 한쪽만 하면 그 샤드로 간
    조회가 구간을 못 찾는다(2026-09-15 에 ch-02 만 NOT_LOADED 였다)."""
    query("SYSTEM RELOAD DICTIONARY ON CLUSTER mupid_all default.dict_track_sections")
    log("딕셔너리 갱신 (ON CLUSTER mupid_all)")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--moments", default=None, help="기본: <ARTIFACT_DIR>/.../moments.parquet")
    ap.add_argument("--version", default=DICT_VERSION,
                    help=f"analysis_version (기본 {DICT_VERSION}). "
                         "다른 값은 딕셔너리가 보지 않는다")
    ap.add_argument("--dry-run", action="store_true", help="만들어만 보고 넣지 않는다")
    ap.add_argument("--no-reload", action="store_true", help="딕셔너리 갱신을 건너뛴다")
    a = ap.parse_args()

    src = Path(a.moments) if a.moments else paths.moments_path()
    if not src.exists():
        print(f"구간 파일이 없다: {src}", file=sys.stderr)
        return 2
    mom = pd.read_parquet(src)

    im = ids.id_map()
    if not getattr(im, "loaded", False):
        print("id_map 이 적재되지 않았다. 숫자 ID 없이는 넣을 수 없다.", file=sys.stderr)
        return 1

    rows, stat = build_rows(mom, im, a.version)
    log(f"구간 {len(mom)}개 중 {len(rows)}개 적재 대상")
    for k in ("unmapped_track", "unknown_label", "bad_range"):
        if stat[k]:
            log(f"  제외 {k}={stat[k]}")
    if stat["unmapped_sample"]:
        log(f"  매핑 없는 곡 예: {stat['unmapped_sample']}")
    if not rows:
        log("넣을 것이 없다.")
        return 1

    if a.version != DICT_VERSION:
        log(f"경고: analysis_version 이 '{a.version}' 이라 "
            f"v_track_sections_current('{DICT_VERSION}') 가 보지 않는다.")

    if a.dry_run:
        print(json.dumps(rows[:3], ensure_ascii=False, indent=2))
        log(f"(dry-run) {len(rows)}건")
        return 0

    insert(rows)
    if not a.no_reload:
        reload_dictionary()
    got = query("SELECT count() FROM track_sections").strip()
    log(f"track_sections 총 {got}행")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
