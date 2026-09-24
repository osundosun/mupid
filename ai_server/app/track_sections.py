"""배치가 읽는 구간 표. **ClickHouse `v_track_sections_current`** 에서 온다.

MySQL `music_section_analysis` 가 정본이고, Spring 이 구간을 저장할 때 같은 내용을
ClickHouse `track_sections` 에도 쓴다(S15P21A204-435). AI 배치는 ClickHouse 를 읽기
전용으로 직접 조회한다 — 연동 계약 Q8 이 정한 경로이고, 행동 데이터도 같은 곳에서
읽는다.

**Spring 을 부르지 않는다.** 예전에는 배치가 ROLE_SYSTEM 토큰으로 Spring 내부
카탈로그 API 를 불러 곡 번호와 구간 경계를 받았다. 그 토큰은 7일이면 만료되고,
배치가 Spring 에 기대는 방향 자체가 "AI 는 계산만 한다" 는 약속과 어긋났다.
라벨러가 매일 실패하던 것(S15P21A204-423)이 그 결과였다.

여기서 나오는 것은 구간 수준 값뿐이다 — 곡 번호, 구간 번호, 순서, 종류, 시각.
장르·무드·보컬처럼 배치가 쓰지 않는 곡 메타는 싣지 않는다(빈 값).

**지워진 곡도 들어 있다.** `track_sections` 는 삭제 여부를 모른다. 배치에는 그게
맞다 — 지워진 곡의 과거 청취도 사용자의 취향 신호다. 추천 후보에서 빼는 것은
Spring 이 한다(계약 A).
"""
from __future__ import annotations

import json

from . import clickhouse
from .catalog_api import Snapshot
from .log import log

VIEW = "v_track_sections_current"
# 뷰가 내놓는 열 **그대로**다(`api/clickhouse/init/01-schema.sql`). 표에는 `section_index`
# 가 있지만 뷰에는 없다 — 그걸 SELECT 해서 운영 첫 실행이 "Unknown identifier" 로
# 죽었다(2026-09-24). 구간 순번은 `section_uid` 의 `#` 뒤에서 읽는다.
COLUMNS = ("track_id", "music_section_analysis_id", "section_uid", "fn", "start_ms", "end_ms")


class TrackSectionsUnavailable(Exception):
    """구간 표를 읽지 못했거나 비었다. 배치는 여기서 멈춘다 — 빈 표로 돌면 신호가
    전부 "매핑 없음" 으로 버려지고, 라벨러는 "이벤트 로그가 없다" 로 끝난다."""


def load() -> Snapshot:
    """구간 표 전체를 한 번 읽는다. 1만 행 남짓이라 한 번에 받는다."""
    if not clickhouse.configured():
        raise TrackSectionsUnavailable(
            "MUPID_CLICKHOUSE_URL 이 없다 — 배치는 구간 표를 ClickHouse 에서 읽는다")
    sql = f"SELECT {', '.join(COLUMNS)} FROM {VIEW} FORMAT JSONEachRow"
    try:
        text = clickhouse.query(sql, timeout=30)
    except Exception as e:
        raise TrackSectionsUnavailable(
            f"{VIEW} 를 읽지 못했다 — {type(e).__name__}: {e}") from e
    return from_rows(json.loads(line) for line in text.splitlines() if line.strip())


def from_rows(rows) -> Snapshot:
    """ClickHouse 행 → 스냅샷. 시험도 이 길을 지난다.

    곡의 문자열 ID 는 `section_uid` 의 `#` 앞, 구간 순번은 `#` 뒤다. 숫자에서 문자열을 **지어내지
    않는다** — `T000001` 을 1 에서 만드는 식의 규칙은 시딩이 그렇게 했을 뿐이다.

    한 숫자 곡이 서로 다른 문자열 곡을 가리키면(또는 그 반대면) 그 곡을 통째로
    뺀다. 섞어 담으면 어느 쪽이 이겼는지 모른 채 신호가 엉뚱한 곡에 붙는다.
    """
    by_num: dict[int, str] = {}
    by_src: dict[str, int] = {}
    conflicted: set[int] = set()
    staged: list[tuple[int, str, dict]] = []
    for r in rows:
        uid = str(r.get("section_uid") or "").strip()
        src, _, idx = uid.partition("#")
        if not src or not idx.isdigit():
            continue
        num = int(r["track_id"])
        # 두 방향을 **모두** 기록한 뒤 비교한다. `or` 로 이으면 앞이 참일 때 뒤가
        # 돌지 않아 반대편 곡이 기록되지 않는다.
        seen_src = by_num.setdefault(num, src)
        seen_num = by_src.setdefault(src, num)
        if seen_src != src or seen_num != num:
            conflicted.update({num, seen_num})
            continue
        staged.append((num, uid, r))

    if conflicted:
        log(f"구간 표: 곡 번호와 문자열 곡이 1:1 이 아니다 — {len(conflicted)}곡을 뺀다 "
            f"(예: {sorted(conflicted)[:5]})")

    tracks: dict = {}
    sections: dict = {}
    for num, uid, r in staged:
        if num in conflicted:
            continue
        src = by_num[num]
        tracks.setdefault(src, {"music_id": num, "title": None, "genre_code": "",
                                "mood_codes": "", "has_vocal": None,
                                "vocal_gender": None})
        sections[uid] = {
            "track_id": src,
            "music_section_analysis_id": int(r["music_section_analysis_id"]),
            "section_idx": int(uid.partition("#")[2]),
            "section_label": str(r.get("fn") or ""),
            "start_ms": int(r["start_ms"]),
            "end_ms": int(r["end_ms"]),
            "instruments": "",
            "moment_moods": "",
        }
    if not sections:
        raise TrackSectionsUnavailable(
            f"{VIEW} 가 비었다 — Spring 구간 적재가 ClickHouse 까지 닿았는지 본다")
    return Snapshot(tracks, sections, 1, None, None)
