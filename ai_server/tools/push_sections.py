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
    만들 수 있는 분석 결과**라 여기서 직접 넣는다.
    행동 데이터(`listen_events`·`user_section_signals`)는 **읽기만** 한다.

이제 이 표를 쓰는 곳이 둘이다
    2026-09-22(S15P21A204-435)부터 스프링도 같은 표에 넣는다 — 전처리 배치가 부르는
    `PUT /api/v1/music/{musicId}/section` 이 MySQL 과 ClickHouse 양쪽에 적재한다.
    배치가 도는 경로에서는 그쪽이 정상 경로이고, **이 도구는 일괄 시드와 복구용**이다
    (`--full`, 03 마이그레이션 뒤 되채우기, `--audit`). 컬럼은 같은 모양이라 어느 쪽이
    넣어도 결과가 같고, `ReplacingMergeTree` 라 나중 것이 남는다.

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
from typing import Optional

import pandas as pd

from ai_server.app import clickhouse as _ch
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


# 접속부는 서빙과 공유한다 (`app/clickhouse.py`). 배치와 서빙이 각자 들고 있으면
# 한쪽만 고쳐져 접속 방식이 갈린다 — 스킴을 낮추지 않는 처리가 특히 그렇다.
_http_base = _ch._http_base
_auth = _ch._auth


def query(sql: str, body: bytes | None = None) -> str:
    return _ch.query(sql, body, timeout=TIMEOUT)


def server_now() -> str:
    """**ClickHouse 의 시계**로 현재 시각을 얻는다. 로컬 시계를 쓰지 않는다.

    서버 타임존이 `Asia/Seoul` 인데 예전에는 `pd.Timestamp.now("UTC")` 를 타임존 표시
    없이 넣었다. 그러면 `created_at` 이 **9시간 과거로 저장된다.**

    `created_at` 은 `ReplacingMergeTree` 의 **버전 컬럼**이다. 이 도구가 쓴 행(9시간
    과거)과 `DEFAULT now()` 로 들어간 행이 섞이면 항상 후자가 이겨, 이 도구로 고친
    구간이 조용히 옛 값으로 되돌아간다.

        SELECT toString(now())          → '2026-09-16 10:51:12'  (서버 타임존)
        SELECT toString(now('UTC'))     → '2026-09-16 01:51:12'
    """
    return query("SELECT toString(now())").strip()


def build_rows(mom: pd.DataFrame, im, version: str,
               created_at: Optional[str] = None) -> tuple[list[dict], dict]:
    """구간 표 한 벌을 만든다. 넣지 못한 것은 이유별로 센다.

    `created_at` 은 호출자가 **서버에서 받아** 넘긴다 (`server_now()`). 비우면 아예
    싣지 않아 ClickHouse 의 `DEFAULT now()` 가 채운다 — 어느 쪽이든 서버 시계다.
    """
    stat = {"unmapped_track": 0, "unmapped_section": 0, "unknown_label": 0, "bad_range": 0}
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
        uid = str(r.moment_id)
        try:
            sec_id = im.music_section_analysis_id(uid)
        except Exception:
            # 곡과 달리 **구간은 매핑이 없어도 넣는다.** 이 표의 첫 번째 쓰임은
            # `dictGet` 으로 재생 위치를 구간에 붙이는 것이고, 거기에는 `section_uid`
            # 면 충분하다. 빼 버리면 그 구간의 이벤트가 통째로 버려진다 — 이 도구가
            # 막으려는 바로 그 침묵이다. 숫자만 비워 두고 센다.
            stat["unmapped_section"] += 1
            sec_id = 0
        row = {
            "track_id": int(num),
            # MySQL `music_section_analysis` 의 PK. 스프링이 구간을 적재할 때 함께
            # 넣는 값이고(S15P21A204-435), 여기서는 `id_map` 이 그것을 알고 있다.
            "music_section_analysis_id": int(sec_id),
            "analysis_version": version,
            "section_index": int(r.section_idx),
            "section_uid": uid,
            "fn": fn,
            "start_ms": s_ms,
            "end_ms": e_ms,
        }
        if created_at:
            row["created_at"] = created_at
        rows.append(row)
    stat["unmapped_sample"] = sorted(unmapped)[:5]
    return rows, stat


def remote_state() -> dict:
    """ClickHouse 에 이미 있는 구간. 키는 section_uid, 값은 (시작·끝·라벨·구간번호).

    `music_section_analysis_id` 까지 비교해야 **숫자만 비어 있는 행**이 다시 채워진다.
    `id_map` 이 늦게 갖춰진 회차나 03 마이그레이션으로 되살린 행이 0 으로 남아 있는데,
    셋만 보면 "바뀐 것 없음" 으로 지나가 영영 0 인 채로 남는다.
    """
    sql = ("SELECT section_uid, start_ms, end_ms, fn, music_section_analysis_id "
           f"FROM track_sections WHERE analysis_version = '{DICT_VERSION}' "
           "FORMAT JSONEachRow")
    out = {}
    for ln in query(sql).splitlines():
        if not ln.strip():
            continue
        r = json.loads(ln)
        out[str(r["section_uid"])] = (int(r["start_ms"]), int(r["end_ms"]), str(r["fn"]),
                                      int(r["music_section_analysis_id"]))
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
        elif cur != (r["start_ms"], r["end_ms"], r["fn"], r["music_section_analysis_id"]):
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

    # 시각은 ClickHouse 에 물어서 얻는다. 로컬 시계를 쓰면 서버 타임존과 어긋난다.
    created_at = server_now()
    log(f"서버 시각 {created_at} (created_at 으로 쓴다)")
    rows, stat = build_rows(mom, im, a.version, created_at)
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
