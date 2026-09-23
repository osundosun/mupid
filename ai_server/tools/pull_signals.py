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
from collections import Counter
from pathlib import Path
from typing import Optional

import pandas as pd

from ai_server.app import ids, paths, recommender
from ai_server.app.log import log
from ai_server.tools.push_sections import query

# ClickHouse `signal` 은 UI 이름 그대로다. 라벨러 이름으로 바꾸는 표가 이미 있으므로
# (`recommender.UI_TO_LABELER`) 여기서는 **원본 이름으로 적재**하고 변환은 맡긴다.
# 두 벌로 만들면 어긋난다.
KNOWN = ("SECTION_MARK", "SECTION_UNMARK", "LIKE", "UNLIKE", "DISLIKE",
         "PLAYLIST_ADD", "PLAYLIST_REMOVE", "COMMENT_ADD", "SEEK_BACK")

# 곡 단위 재생 신호. 구간 신호와 달리 원본 `listen_events` 에서 읽는다 —
# `user_section_signals` 는 구간 단위라 `reason`·`position_ms` 를 갖고 있지 않다.
PLAY_EVENTS = ("PLAY_START", "PLAY_END")

# ClickHouse `entry_path` → AI `context.surface`.
# `user_behavior()` 는 **추천 경로에서 난 스킵만** 부정 신호로 센다. 어휘가 어긋나면
# 그 판정이 통째로 빠지므로 여기서 맞춘다.
SURFACE = {"RECOMMEND": "recommendation", "AUTO": "autoplay", "AUTOPLAY": "autoplay",
           "DAILY": "daily_recommendation", "DAILY_RECOMMEND": "daily_recommendation"}

EPOCH = "1970-01-01 00:00:00.000"


def utc_iso(r: dict) -> str:
    """행동 로그의 `occurred_at` 은 **UTC 에 `Z`** 다 (`recommender.now_iso` 와 같은 규약).

    예전에는 `toString(occurred_at)` 을 그대로 쓰고 뒤에 `"Z"` 를 붙였다. ClickHouse
    서버 타임존이 `Asia/Seoul` 이라 **서울 시각에 UTC 표시를 붙인 값**이 됐고,
    `user_behavior()` 는 그것을 `fromisoformat` 으로 읽어 `time.time()` 과 비교하므로
    모든 이벤트가 **9시간 미래**가 됐다. 오류는 나지 않고 추천만 이상해진다.

      · 최근 재생 제외(4시간)가 영원히 걸린다 — 방금 들은 곡 취급이 풀리지 않는다
      · 스킵 감점의 시간 감쇠가 음수 나이로 계산된다
      · 라벨러 최근성도 같은 값을 본다

    그래서 조회에서 **UTC 로 변환해 받는다**(`ts_utc`). 내보내는 값은 이 한 가지뿐이다.
    `ts`(서버 타임존)는 워터마크와 `event_id` 에만 쓴다 — 워터마크는 저장과 비교가 양쪽
    다 서버 기준이라 지금 정상이고, `event_id` 는 내용에서 만들므로 근거 문자열을 바꾸면
    **이미 쌓인 이벤트가 전부 새 ID 로 다시 들어온다.**
    """
    return str(r.get("ts_utc") or r["ts"]).replace(" ", "T") + "Z"


def watermark_path() -> Path:
    # 색인이 아니라 **상태**다. 색인 디렉터리에 두면 판을 교체할 때마다
    # 사라져 신호를 처음부터 다시 당긴다.
    return paths.state_dir() / "signals_watermark.json"


def load_watermark() -> str:
    p = watermark_path()
    if not p.exists():
        return EPOCH
    try:
        return str(json.loads(p.read_text(encoding="utf-8")).get("occurred_at") or EPOCH)
    except Exception:
        return EPOCH


# 경계의 같은 밀리초를 다시 읽을 때만 쓰므로 많이 들고 있을 필요가 없다.
RECENT_KEEP = 2000


def save_watermark(value: str, n: int, recent: list[str] | None = None) -> None:
    p = watermark_path()
    p.parent.mkdir(parents=True, exist_ok=True)
    body = {"occurred_at": value, "last_batch": n,
            "recent_ids": (recent or [])[-RECENT_KEEP:]}
    tmp = p.with_suffix(".json.tmp")
    tmp.write_text(json.dumps(body, ensure_ascii=False), encoding="utf-8")
    tmp.replace(p)                 # 중간에 죽어도 반쪽 파일이 남지 않는다


def fetch(since: str, limit: int) -> list[dict]:
    """워터마크 **이후**만 가져온다. 같은 밀리초가 여럿이면 다음 회차에서 다시 본다 —
    중복은 이벤트 ID 로 걸러지므로 빠뜨리는 것보다 낫다."""
    sql = (
        "SELECT user_id, section_uid, track_id, signal, entry_path, "
        "toString(occurred_at) AS ts, "
        "toString(toTimeZone(occurred_at, 'UTC')) AS ts_utc, "
        "toString(play_id) AS play_id, weight "
        "FROM user_section_signals "
        f"WHERE occurred_at >= parseDateTime64BestEffort('{since}', 3) "
        "ORDER BY occurred_at "
        f"LIMIT {int(limit)} FORMAT JSONEachRow"
    )
    text = query(sql)
    return [json.loads(ln) for ln in text.splitlines() if ln.strip()]


def fetch_plays(since: str, limit: int) -> list[dict]:
    """재생·스킵 원본. 완주 가점·초기 스킵 감점·최근 4시간 제외가 여기서 나온다."""
    types = ",".join(f"'{t}'" for t in PLAY_EVENTS)
    sql = (
        "SELECT user_id, track_id, event_type AS signal, entry_path, reason, "
        "position_ms, from_ms, toString(play_id) AS play_id, "
        "toString(occurred_at) AS ts, "
        "toString(toTimeZone(occurred_at, 'UTC')) AS ts_utc "
        "FROM listen_events "
        f"WHERE event_type IN ({types}) AND track_id > 0 "
        f"AND occurred_at >= parseDateTime64BestEffort('{since}', 3) "
        "ORDER BY occurred_at "
        f"LIMIT {int(limit)} FORMAT JSONEachRow"
    )
    return [json.loads(ln) for ln in query(sql).splitlines() if ln.strip()]


def to_play_event(r: dict, im, why: Optional[Counter] = None) -> dict | None:
    """곡 단위 재생 신호 한 건. 구간 경계가 없으므로 `section_uid` 도 없다."""
    sig = str(r["signal"])
    if sig not in PLAY_EVENTS:
        _count(why, "unknown_signal")
        return None
    try:
        track = im.source_track_id(int(r["track_id"]))
    except Exception:
        _count(why, "music_id_unmapped")
        return None
    ep = str(r.get("entry_path") or "").upper()
    ev = {"event_id": event_id(r),
          "user_ref": f"internal:{int(r['user_id'])}",
          "track_id": track,
          "event_type": sig,
          "occurred_at": utc_iso(r),
          "entry_path": r.get("entry_path") or "",
          # `user_behavior()` 가 context.surface 로 추천 경로를 가린다
          "context": {"surface": SURFACE.get(ep, ep.lower())},
          "play_id": r.get("play_id") or "",
          "source": "clickhouse"}
    if sig == "PLAY_END":
        ev["reason"] = str(r.get("reason") or "")
        # 초기 스킵 판정은 초 단위로 본다 (FAST_SKIP_SEC)
        ev["position_sec"] = round(int(r.get("position_ms") or 0) / 1000.0, 3)

        # 어디부터 어디까지 들었는지를 함께 싣는다.
        #
        # 라벨러는 `intervals` 로 구간별 커버리지를 재고, 그 결과가 임계에 닿아야
        # `reached` 가 선다. 도달하지 않은 구간에는 부정 라벨을 붙이지 않으므로,
        # 이 칸이 비면 **모든 명시 신호(좋아요·되감기)가 "미도달" 로 마스킹되어**
        # 라벨이 한 건도 나오지 않는다. 오류는 나지 않고 결과만 비어서, 증상이
        # "개인화가 안 된다" 로만 보인다.
        #
        # `listen_events` 는 한 번의 재생을 play_id 로 묶고 from_ms·position_ms 로
        # 그 범위를 알려 준다. 이어 붙인 구간이 아니라 **연속 재생 한 칸**이므로
        # 지어내는 값이 아니다. 세션도 play_id 를 그대로 쓴다 — 라벨러는 세션을
        # 몇 번째 청취인지 세는 데만 쓰고, 한 번의 재생이 곧 한 세션이다.
        start_sec = round(int(r.get("from_ms") or 0) / 1000.0, 3)
        end_sec = ev["position_sec"]
        if end_sec > start_sec:
            ev["intervals"] = [{"start_sec": start_sec, "end_sec": end_sec}]
        ev["session_id"] = ev["play_id"]
    return ev


def _count(why: Optional[Counter], reason: str) -> None:
    if why is not None:
        why[reason] += 1


def event_id(r: dict) -> str:
    """내용에서 만든다. 같은 신호를 두 번 받아도 한 번만 쌓인다.

    시각은 **서버 타임존 문자열(`ts`)** 을 쓴다. UTC 로 바꾸면 같은 신호가 다른 ID 가
    되어 이미 쌓인 이벤트를 전부 다시 적재한다.
    """
    key = (f"{r['user_id']}|{r.get('section_uid') or r.get('track_id')}"
           f"|{r['signal']}|{r['ts']}|{r.get('play_id', '')}")
    return "ch_" + hashlib.sha1(key.encode()).hexdigest()[:22]


def recent_ids() -> set:
    """직전 회차에 넣은 이벤트 ID.

    예전에는 중복을 막으려고 **로그 전체**를 읽었다. 로그가 커질수록 배치가 같이
    느려지는데, 얻는 것은 경계의 같은 밀리초를 거르는 일뿐이다. 워터마크가 그 앞은
    이미 걸러 주므로 직전 회차 ID 만 들고 있으면 충분하다.
    """
    p = watermark_path()
    if not p.exists():
        return set()
    try:
        return set(json.loads(p.read_text(encoding="utf-8")).get("recent_ids") or [])
    except Exception:
        return set()


def to_event(r: dict, bounds: dict, im, why: Optional[Counter] = None) -> dict | None:
    """신호 한 건을 AI 행동 로그 한 줄로 옮긴다.

    버릴 때는 **왜 버렸는지 센다**(`why`). 예전에는 세 가지 원인이 한 숫자로
    뭉쳐 있었고 로그는 그중 하나라고 단정했다 — 매핑표에 색인 밖 곡까지 담은
    뒤로는 "색인에 없는 곡" 이 가장 흔한 원인이라, 그 단정이 사람을
    `push_sections` 버전 쪽으로 잘못 보낸다.
    """
    sig = str(r["signal"])
    if sig not in KNOWN:
        _count(why, "unknown_signal")
        return None
    uid = str(r["section_uid"])
    b = bounds.get(uid)
    if b is None:
        # 구간 표에는 있는데 **AI 색인에 없다.** 지금은 대개 아직 임베딩하지 않은
        # 곡이다(매핑표는 DB 전량을 담는다). 버전 불일치일 수도 있어 둘을 가르는
        # 것은 사람이 한다 — 여기서는 수만 정확히 남긴다.
        _count(why, "section_not_in_index")
        return None
    try:
        track = im.source_track_id(int(r["track_id"]))
    except Exception:
        _count(why, "music_id_unmapped")
        return None                   # 숫자 ID 를 문자열로 못 되돌린다
    return {"event_id": event_id(r),
            "user_ref": f"internal:{int(r['user_id'])}",
            "track_id": track,
            "event_type": sig,
            "start_sec": float(b[0]),
            "end_sec": float(b[1]),
            "occurred_at": utc_iso(r),
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
    sig_rows = fetch(since, limit)
    play_rows = fetch_plays(since, limit)
    log(f"워터마크 {since} 이후 구간신호 {len(sig_rows)}건 · 재생신호 {len(play_rows)}건")
    if not sig_rows and not play_rows:
        return {"fetched": 0, "appended": 0, "watermark": since}

    bounds, im = section_bounds(), ids.id_map()
    known = recent_ids()
    appended, dup, dropped = 0, 0, 0
    why: Counter = Counter()
    last = since
    fresh: list[str] = []

    for r, maker in ([(r, "section") for r in sig_rows]
                     + [(r, "play") for r in play_rows]):
        ev = (to_event(r, bounds, im, why) if maker == "section"
              else to_play_event(r, im, why))
        if ev is None:
            dropped += 1
        elif ev["event_id"] in known:
            dup += 1
        else:
            if not dry_run:
                recommender.append_event(ev)
            known.add(ev["event_id"])
            fresh.append(ev["event_id"])
            appended += 1
        # 워터마크는 **서버 타임존** 문자열이다. 저장한 값을 그대로
        # `parseDateTime64BestEffort` 에 되돌려 넣고 서버가 같은 기준으로 읽으므로
        # 일관적이다. UTC 로 바꾸면 그 시점부터 9시간이 건너뛰어진다.
        last = max(last, str(r["ts"]))

    log(f"적재 {appended} · 중복 {dup} · 제외 {dropped}")
    if dropped:
        log("  제외 원인: " + " · ".join(f"{k}={v}" for k, v in why.most_common()))
        if why.get("section_not_in_index"):
            log(f"    section_not_in_index {why['section_not_in_index']}건 — 그 곡이 아직"
                " 색인에 없거나 push_sections 버전이 어긋났다")
        if why.get("music_id_unmapped"):
            log(f"    music_id_unmapped {why['music_id_unmapped']}건 — id_map 을"
                " 다시 뽑는다 (매핑에 없는 곡의 청취가 통째로 사라진다)")
    if not dry_run:
        # 성공했을 때만 전진한다. 경계의 같은 밀리초를 다시 읽으므로 그때 쓸
        # ID 목록을 함께 남긴다 — 로그 전체를 다시 훑지 않기 위해서다.
        save_watermark(last, len(sig_rows) + len(play_rows), fresh)
        log(f"워터마크 → {last}")
    return {"fetched": len(sig_rows) + len(play_rows), "appended": appended,
            "duplicate": dup, "dropped": dropped, "dropped_by": dict(why),
            "watermark": last}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=100_000, help="한 회차 최대 건수")
    ap.add_argument("--dry-run", action="store_true", help="받아만 보고 쌓지 않는다")
    ap.add_argument("--reset", action="store_true", help="워터마크를 처음으로 되돌린다")
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()

    if a.reset:
        save_watermark(EPOCH, 0, [])
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
