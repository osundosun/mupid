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

언제 돌리나
    **재생할 때마다 돌리는 것이 아니다.** 구간 표는 곡이 늘거나 재분석으로 경계가
    바뀔 때만 변한다. 재생은 아무것도 바꾸지 않는다.

      최초       `--full`          10,007 구간 일괄 (id_map 이 갖춰진 뒤 한 번)
      평소       (기본)            바뀐 것만. 신규 곡·재분석 뒤에 돌린다
      하루 1회   `--audit`         넣지 않고 차이만 센다. 차이가 있으면 종료 코드 3

    Kubernetes 로 가면 전처리 완료 이벤트로 Job 을 띄우거나 짧은 주기 CronJob 으로
    같은 명령을 돌리면 된다. 증분이라 자주 돌려도 비용이 거의 없다.
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
    """`jdbc:clickhouse://host:port/db` 도 받는다 — .env 가 그 형태로 적혀 있다.

    **스킴을 낮추지 않는다.** 예전에는 무조건 `http://` 를 붙여서, `https://` 로 적어 둔
    ClickHouse 에도 평문으로 붙었다. 자격증명이 헤더에 실려 나가므로 그대로 새는 길이다.
    jdbc 형태는 스킴 정보가 없어 `MUPID_CLICKHOUSE_SECURE=1` 로 표시한다.
    """
    raw = os.environ.get("MUPID_CLICKHOUSE_URL", "").strip()
    if not raw:
        raise SystemExit("MUPID_CLICKHOUSE_URL 이 비어 있다.")
    m = re.match(r"^jdbc:clickhouse://([^/?]+)", raw)
    if m:
        secure = os.environ.get("MUPID_CLICKHOUSE_SECURE", "").strip().lower()
        scheme = "https" if secure in ("1", "true", "yes") else "http"
        return f"{scheme}://{m.group(1)}"
    m = re.match(r"^(https?)://([^/?]+)", raw)
    if m:
        return f"{m.group(1)}://{m.group(2)}"          # 적어 준 스킴을 그대로 쓴다
    return f"http://{raw.split('/')[0]}"               # 스킴이 없으면 host 만 온 것이다


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


def remote_state() -> dict:
    """ClickHouse 에 이미 있는 구간. 키는 section_uid, 값은 (시작·끝·라벨)."""
    sql = ("SELECT section_uid, start_ms, end_ms, fn FROM track_sections "
           f"WHERE analysis_version = '{DICT_VERSION}' FORMAT JSONEachRow")
    out = {}
    for ln in query(sql).splitlines():
        if not ln.strip():
            continue
        r = json.loads(ln)
        out[str(r["section_uid"])] = (int(r["start_ms"]), int(r["end_ms"]), str(r["fn"]))
    return out


def diff(rows: list[dict], remote: dict) -> tuple[list[dict], dict]:
    """넣어야 할 것만 고른다.

    `ReplicatedReplacingMergeTree` 라 전량을 다시 넣어도 결과는 같지만, 구간 1만 개를
    매번 밀면 파트가 계속 쌓이고 머지가 따라오지 못한다. 바뀐 것만 넣는다.
    """
    add, changed = [], []
    for r in rows:
        cur = remote.get(r["section_uid"])
        if cur is None:
            add.append(r)
        elif cur != (r["start_ms"], r["end_ms"], r["fn"]):
            changed.append(r)
    local_uids = {r["section_uid"] for r in rows}
    orphan = sorted(set(remote) - local_uids)
    return add + changed, {"new": len(add), "changed": len(changed),
                           "unchanged": len(rows) - len(add) - len(changed),
                           "orphan": len(orphan), "orphan_sample": orphan[:5]}


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
    ap.add_argument("--full", action="store_true",
                    help="바뀐 것만이 아니라 전량을 다시 넣는다 (최초 적재·복구용)")
    ap.add_argument("--audit", action="store_true",
                    help="넣지 않고 차이만 본다. 하루 한 번 도는 대사용")
    ap.add_argument("--json", dest="json_out", action="store_true",
                    help="대사 결과를 JSON 한 줄로 출력")
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

    # ---- 대사: 넣지 않고 차이만 본다 (하루 한 번) ----
    if a.audit:
        _, d = diff(rows, remote_state())
        log(f"대사: 신규 {d['new']} · 변경 {d['changed']} · 동일 {d['unchanged']} "
            f"· 고아 {d['orphan']}")
        if d["orphan_sample"]:
            log(f"  ClickHouse 에만 있는 구간 예: {d['orphan_sample']}")
            log("  색인에서 사라진 구간이다. 재분석으로 경계가 바뀌었는지 본다.")
        if a.json_out:
            print(json.dumps(d, ensure_ascii=False))
        # 차이가 있으면 0 이 아닌 코드로 끝낸다 — cron 이 알아차려야 한다
        return 0 if (d["new"] == 0 and d["changed"] == 0) else 3

    # ---- 증분: 바뀐 것만 넣는다 ----
    if not a.full:
        rows, d = diff(rows, remote_state())
        log(f"증분: 신규 {d['new']} · 변경 {d['changed']} · 동일 {d['unchanged']}")
        if not rows:
            log("바뀐 구간이 없다. 넣지 않는다.")
            return 0

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
