"""ClickHouse `user_section_signals` → AI 행동 로그 (Q8 구현).

AI 정기 배치가 ClickHouse 를 **읽기 전용으로 직접 조회**한다. Spring 이 밀어 주거나
Kafka 를 구독하지 않는다 — 라벨 규칙이 바뀌면 과거 구간을 다시 훑어야 하는데, 밀어
주는 쪽은 되짚을 방법이 없다.

    user_section_signals ──(워터마크 증분)──> events_service.jsonl ──> run_labeler

라벨을 여기서 만들지 않는다
    ClickHouse 가 이미 구간을 판정하고 가중치까지 매겼지만, **등급 판정은 라벨러
    한 곳에서만 한다.** 같은 구간을 들은 전체 사용자 평균·위치 편향·표본 보정이 함께
    들어가야 하기 때문이다(`run_labeler` 문서). 여기서는 이벤트 이름만 맞춰 넘긴다.

두 번 돌려도 같다
    워터마크는 **성공했을 때만** 전진한다. 그리고 이벤트 ID 를 내용에서 만들어
    (사용자·구간·신호·시각) 겹치면 건너뛴다. 중간에 죽어도 다시 돌리면 된다.

    MUPID_CLICKHOUSE_URL / _USER / _PASSWORD
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from pathlib import Path

import pandas as pd

from ai_server.app import ids, paths, recommender
from ai_server.app.log import log
from ai_server.tools.push_sections import query

# ClickHouse `signal` 은 UI 이름 그대로다. 라벨러 이름으로 바꾸는 표가 이미 있으므로
# (`recommender.UI_TO_LABELER`) 여기서는 **원본 이름으로 적재**하고 변환은 맡긴다.
# 두 벌로 만들면 어긋난다.
KNOWN = ("SECTION_MARK", "SECTION_UNMARK", "LIKE", "UNLIKE", "DISLIKE",
         "PLAYLIST_ADD", "PLAYLIST_REMOVE", "COMMENT_ADD", "SEEK_BACK")

EPOCH = "1970-01-01 00:00:00.000"


def watermark_path() -> Path:
    return paths.data_dir() / "signals_watermark.json"


def load_watermark() -> str:
    p = watermark_path()
    if not p.exists():
        return EPOCH
    try:
        return str(json.loads(p.read_text(encoding="utf-8")).get("occurred_at") or EPOCH)
    except Exception:
        return EPOCH


def save_watermark(value: str, n: int) -> None:
    p = watermark_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps({"occurred_at": value, "last_batch": n},
                            ensure_ascii=False), encoding="utf-8")


def fetch(since: str, limit: int) -> list[dict]:
    """워터마크 **이후**만 가져온다. 같은 밀리초가 여럿이면 다음 회차에서 다시 본다 —
    중복은 이벤트 ID 로 걸러지므로 빠뜨리는 것보다 낫다."""
    sql = (
        "SELECT user_id, section_uid, track_id, signal, entry_path, "
        "toString(occurred_at) AS ts, toString(play_id) AS play_id, weight "
        "FROM user_section_signals "
        f"WHERE occurred_at >= parseDateTime64BestEffort('{since}', 3) "
        "ORDER BY occurred_at "
        f"LIMIT {int(limit)} FORMAT JSONEachRow"
    )
    text = query(sql)
    return [json.loads(ln) for ln in text.splitlines() if ln.strip()]


def event_id(r: dict) -> str:
    """내용에서 만든다. 같은 신호를 두 번 받아도 한 번만 쌓인다."""
    key = f"{r['user_id']}|{r['section_uid']}|{r['signal']}|{r['ts']}"
    return "ch_" + hashlib.sha1(key.encode()).hexdigest()[:22]


def seen_ids() -> set:
    p = recommender.EVENTS
    if not p.exists():
        return set()
    out = set()
    for ln in p.open(encoding="utf-8"):
        ln = ln.strip()
        if not ln:
            continue
        try:
            out.add(json.loads(ln).get("event_id"))
        except Exception:
            continue
    return out


def to_event(r: dict, bounds: dict, im) -> dict | None:
    """신호 한 건을 AI 행동 로그 한 줄로 옮긴다."""
    sig = str(r["signal"])
    if sig not in KNOWN:
        return None
    uid = str(r["section_uid"])
    b = bounds.get(uid)
    if b is None:                     # 구간 표에는 있는데 AI 색인에 없다 — 버전이 어긋났다
        return None
    try:
        track = im.source_track_id(int(r["track_id"]))
    except Exception:
        return None                   # 숫자 ID 를 문자열로 못 되돌린다
    return {"event_id": event_id(r),
            "user_ref": f"internal:{int(r['user_id'])}",
            "track_id": track,
            "event_type": sig,
            "start_sec": float(b[0]),
            "end_sec": float(b[1]),
            "occurred_at": str(r["ts"]).replace(" ", "T") + "Z",
            "entry_path": r.get("entry_path") or "",
            "source": "clickhouse"}


def section_bounds() -> dict:
    """구간 경계는 AI 색인이 정본이다. ClickHouse 는 판정만 했지 초 단위를 주지 않는다."""
    p = paths.moments_path()
    if not p.exists():
        raise SystemExit(f"구간 파일이 없다: {p}")
    mom = pd.read_parquet(p)
    return {str(r.moment_id): (float(r.start_sec), float(r.end_sec))
            for r in mom.itertuples()}


def run(limit: int, dry_run: bool) -> dict:
    since = load_watermark()
    rows = fetch(since, limit)
    log(f"워터마크 {since} 이후 {len(rows)}건")
    if not rows:
        return {"fetched": 0, "appended": 0, "watermark": since}

    bounds, im = section_bounds(), ids.id_map()
    known = seen_ids()
    appended, dup, dropped = 0, 0, 0
    last = since
    for r in rows:
        ev = to_event(r, bounds, im)
        if ev is None:
            dropped += 1
        elif ev["event_id"] in known:
            dup += 1
        elif not dry_run:
            recommender.append_event(ev)
            known.add(ev["event_id"])
            appended += 1
        else:
            appended += 1
        last = max(last, str(r["ts"]))

    log(f"적재 {appended} · 중복 {dup} · 제외 {dropped}")
    if dropped:
        log("  제외는 구간 표와 AI 색인이 어긋났다는 뜻이다 — push_sections 버전을 본다")
    if not dry_run:
        save_watermark(last, len(rows))      # 성공했을 때만 전진한다
        log(f"워터마크 → {last}")
    return {"fetched": len(rows), "appended": appended, "duplicate": dup,
            "dropped": dropped, "watermark": last}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=100_000, help="한 회차 최대 건수")
    ap.add_argument("--dry-run", action="store_true", help="받아만 보고 쌓지 않는다")
    ap.add_argument("--reset", action="store_true", help="워터마크를 처음으로 되돌린다")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    if a.reset:
        save_watermark(EPOCH, 0)
        log("워터마크를 처음으로 되돌렸다")

    try:
        res = run(a.limit, a.dry_run)
    except SystemExit:
        raise
    except Exception as e:
        print(f"실패: {type(e).__name__} {e}", file=sys.stderr)
        return 1
    if a.json:
        print(json.dumps(res, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
