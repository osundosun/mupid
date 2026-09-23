"""ClickHouse 직접 조회. **서빙 경로에서 쓴다.**

행동 데이터는 배치로 파일에 옮겨 담지 않고 요청 때 바로 읽는다. ClickHouse 는 많은 행을
한 번에 훑는 데 특화돼 있고, `listen_events` 의 정렬키가 `(user_id, occurred_at, event_id)`
라 사용자 한 명 범위 조회가 1차 접근 경로다.

접속부(`_http_base` · `_auth` · `query`)는 `tools/push_sections.py` 에 있던 것을 그대로
옮겨 왔다. 배치와 서빙이 각자 들고 있으면 한쪽만 고쳐져 접속 방식이 갈린다.

어휘가 다르다
    Spring 이 수집하는 이벤트(`EventType` 22종)와 추천기가 읽던 파일 로그의 어휘가
    다르다. 예컨대 파일 로그는 Early Skip 을 `PLAY_END` + `reason="skip_next"` 로 적지만
    ClickHouse 에는 `TRACK_SKIP` 이라는 별도 이벤트가 있다. 여기서 **추천기가 아는
    어휘로 옮겨** 넘긴다 — 추천기의 집계 코드는 건드리지 않는다.

숫자 ID 를 어떻게 문자열로 되돌리나
    `listen_events.track_id` 는 MySQL 숫자다. 추천 풀은 문자열 키를 쓴다. 번역은
    **ClickHouse 안에서** 한다 — `track_sections.section_uid` 의 `#` 앞이 곧 문자열 곡
    ID 이고, 그 표는 ai_server 가 직접 넣은 것이다. 매핑 파일을 서빙이 들고 있을 이유가
    없다.

    MUPID_CLICKHOUSE_URL / _SECURE / _USER / _PASSWORD
"""
from __future__ import annotations

import os
import re
import urllib.error
import urllib.parse
import urllib.request
from base64 import b64encode

TIMEOUT = 10

# 추천기가 아는 어휘로 옮길 이벤트만 읽는다. 나머지(PLAY_PROGRESS·SEARCH 등)는
# `user_behavior()` 가 어차피 버린다 — 네트워크로 실어 올 이유가 없다.
#
# `PLAYBACK_ABANDON`(앱이 닫혀 끊김)과 `PLAY_ERROR` 는 **세지 않는다.** 의도한 스킵이
# 아니라서 부정 신호로 읽으면 안 된다 — Spring 의 `MubtiScorer` 도 같은 판단이다.
WANTED = ("PLAY_START", "PLAY_END", "TRACK_SKIP",
          "LIKE", "UNLIKE", "DISLIKE", "UNDISLIKE")

# 추천 경로에서 난 재생만 부정 신호로 본다. `EntryPath` 에 `AUTOPLAY` 는 없고
# `RECOMMEND` 하나다 — 추천기 쪽 `BEH_REC_SURFACES` 의 이름으로 옮겨 준다.
SURFACE = {"RECOMMEND": "recommendation", "SEARCH": "search", "PLAYLIST": "playlist"}


def _http_base() -> str:
    """`jdbc:clickhouse://host:port/db` 도 받는다 — .env 가 그 형태로 적혀 있다.

    **스킴을 낮추지 않는다.** 무조건 `http://` 를 붙이면 `https://` 로 적어 둔
    ClickHouse 에도 평문으로 붙어 자격증명이 헤더에 실려 나간다.
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
        return f"{m.group(1)}://{m.group(2)}"
    return f"http://{raw.split('/')[0]}"


def _auth() -> str:
    u = os.environ.get("MUPID_CLICKHOUSE_USER", "").strip()
    p = os.environ.get("MUPID_CLICKHOUSE_PASSWORD", "")
    if not u:
        raise SystemExit("MUPID_CLICKHOUSE_USER 가 비어 있다.")
    return "Basic " + b64encode(f"{u}:{p}".encode()).decode()


def query(sql: str, body: bytes | None = None, timeout: int = TIMEOUT) -> str:
    url = f"{_http_base()}/?query={urllib.parse.quote(sql)}" if body else _http_base() + "/"
    req = urllib.request.Request(url, data=body if body else sql.encode(), method="POST")
    req.add_header("Authorization", _auth())
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.read().decode()
    except urllib.error.HTTPError as e:
        raise ClickHouseError(f"ClickHouse 오류 {e.code}: {e.read().decode()[:400]}") from None


class ClickHouseError(Exception):
    """조회에 실패했다. **추천을 죽이지 않는다** — 행동 없이 계산하고 그 사실을 알린다."""


def configured() -> bool:
    return bool(os.environ.get("MUPID_CLICKHOUSE_URL", "").strip())


# 문자열 곡 ID 를 되돌리는 조인. `v_track_sections_current` 가 현재 분석 버전만 고른다.
_SRC_TRACK = """(
    SELECT track_id, any(splitByChar('#', section_uid)[1]) AS src_track
      FROM v_track_sections_current GROUP BY track_id
)"""


def behavior_events(user_id: int, since_sec: float) -> list[dict]:
    """사용자 한 명의 행동 이벤트를 **추천기가 아는 모양**으로 돌려준다.

    `user_behavior()` 가 파일에서 읽던 것과 같은 키를 쓴다 — 집계 코드를 두 벌로
    만들지 않기 위해서다.
    """
    sql = f"""
        SELECT e.event_type AS event_type,
               t.src_track AS src_track,
               toUnixTimestamp64Milli(e.occurred_at) AS ts_ms,
               e.position_ms AS position_ms,
               e.entry_path AS entry_path,
               toString(e.play_id) AS play_id
          FROM listen_events e
         INNER JOIN {_SRC_TRACK} t ON e.track_id = t.track_id
         WHERE e.user_id = {int(user_id)}
           AND e.event_type IN ({','.join("'%s'" % x for x in WANTED)})
           AND e.occurred_at >= fromUnixTimestamp64Milli(toInt64({int(since_sec * 1000)}))
         ORDER BY e.occurred_at
        FORMAT TSVWithNames
    """
    text = query(sql)
    lines = text.splitlines()
    if len(lines) < 2:
        return []
    cols = lines[0].split("\t")
    out: list[dict] = []
    for ln in lines[1:]:
        parts = ln.split("\t")
        if len(parts) != len(cols):
            continue
        row = dict(zip(cols, parts))
        out.append(_to_recommender_shape(row))
    return out


def raw_event_count(user_id: int, since_sec: float) -> int:
    """조인 **없이** 원본 `listen_events` 만 센다.

    `behavior_events()` 가 0행일 때 그것이 "이 사용자가 안 들었다" 인지 "들었는데
    `v_track_sections_current` 로 되돌리지 못했다" 인지 가른다. 둘은 같은 빈 결과로
    보이지만 뜻이 정반대다 — 뒤쪽은 색인과 ClickHouse 딕셔너리가 어긋났다는
    신호이고, 그대로 두면 전 사용자의 개인화가 조용히 빠진다.

    **정상 경로에서는 부르지 않는다.** 조인 결과가 0행일 때만 한 번 더 묻는다
    (`internal_api._behavior_events`) — 추천은 사용자가 기다리는 경로다.
    """
    sql = f"""
        SELECT count() FROM listen_events
         WHERE user_id = {int(user_id)}
           AND event_type IN ({','.join("'%s'" % x for x in WANTED)})
           AND occurred_at >= fromUnixTimestamp64Milli(toInt64({int(since_sec * 1000)}))
        FORMAT TabSeparated
    """
    text = query(sql).strip()
    return int(text.splitlines()[0]) if text else 0


def _to_recommender_shape(row: dict) -> dict:
    """ClickHouse 한 줄 → 추천기가 읽는 이벤트 한 건.

    Early Skip 이 핵심이다. 파일 로그는 `PLAY_END` + `reason="skip_next"` 로 적었는데
    ClickHouse 에는 `TRACK_SKIP` 이 따로 있다. 옮겨 주지 않으면 스킵 감점이 통째로
    빠진 채 조용히 돈다.
    """
    et = row["event_type"]
    ts = int(row["ts_ms"]) / 1000.0
    pos_sec = int(row["position_ms"] or 0) / 1000.0
    surface = SURFACE.get(row.get("entry_path", ""), "")

    reason = ""
    if et == "TRACK_SKIP":
        et, reason = "PLAY_END", "skip_next"
    elif et == "PLAY_END":
        # ClickHouse 의 PLAY_END 는 곡이 끝난 것이다 (의도한 스킵은 TRACK_SKIP).
        reason = "track_done"

    return {"track_id": row["src_track"], "event_type": et, "ts": ts,
            "reason": reason, "position_sec": pos_sec,
            "surface": surface, "play_id": row.get("play_id") or ""}
