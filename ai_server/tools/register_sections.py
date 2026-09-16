"""AI 구간 분석 결과 → Spring `music_section_analysis`, 그리고 `id_map.parquet`.

**이것이 없으면 추천이 통째로 503 이다.** 추천 응답은 구간을 숫자 `music_section_id` 로
가리키는데, 그 번호는 MySQL 이 발급한다. 표가 비어 있으면 매핑을 만들 수 없고,
매핑이 없으면 `/internal/ready` 가 영구 503 이다.

    moments.parquet ──PUT /api/v1/music/{musicId}/section──> music_section_analysis
                                     │
                                     └─(발급된 번호)──> id_map.parquet

숫자 ID 를 지어내지 않는다
    `track_id` → `music_id` 는 **`--track-map` 으로 받은 표에서만** 읽는다. 문자열을
    해시하거나 순번을 붙이면 그럴듯하게 돌다가 실제 DB 와 어긋난 순간 엉뚱한 곡을
    추천한다 — 이 저장소가 색인 불일치로 두 번 겪은 실패다. 표에 없는 곡은 건너뛰고
    **몇 건을 왜 건너뛰었는지 센다.**

    표 형식(CSV/TSV, 컬럼 이름만 맞으면 된다):
        source_track_id,music_id
        T000148,1001

곡 단위로 통째로 바꾼다
    한 곡의 구간은 분석 한 번의 결과로 함께 정해진다. 구간마다 넣으면 재분석 중간에
    옛 구간과 새 구간이 섞이고, 그 상태로도 조회가 정상으로 보인다.

환경변수

    MUPID_API_BASE_URL       Spring 주소 (`/api/v1` 앞까지)
    MUPID_API_REFRESH_TOKEN  ROLE_SYSTEM 계정의 리프레시 토큰
    MUPID_AI_ARTIFACT_DIR    산출물 루트

    MUPID_AI_ARTIFACT_DIR=/경로 python -m ai_server.tools.register_sections
        --track-map track_map.csv
"""
from __future__ import annotations

import argparse
import sys
import urllib.error
from pathlib import Path

import pandas as pd

from ai_server.app import ids, paths, recommender
from ai_server.app.log import log
from ai_server.tools import _api_auth, build_id_map
from ai_server.tools.push_sections import SECTION_ENUM

ANALYSIS_VERSION = "v1"
TIMEOUT = 30


def read_track_map(path: Path) -> dict[str, int]:
    """`source_track_id` → `music_id`. 구분자는 추측한다."""
    df = pd.read_csv(path, sep=None, engine="python", dtype=str)
    missing = [c for c in ("source_track_id", "music_id") if c not in df.columns]
    if missing:
        raise SystemExit(f"컬럼이 없다: {missing} (있는 것: {list(df.columns)})")
    out: dict[str, int] = {}
    for r in df.itertuples(index=False):
        tid = str(r.source_track_id).strip()
        if not tid:
            continue
        try:
            mid = int(str(r.music_id).strip())
        except ValueError:
            raise SystemExit(f"music_id 가 정수가 아니다: {tid} -> {r.music_id!r}") from None
        if mid <= 0:
            raise SystemExit(f"music_id 가 0 이하다: {tid} -> {mid}")
        if out.get(tid, mid) != mid:
            raise SystemExit(f"곡 하나에 music_id 가 둘이다: {tid} -> {out[tid]}, {mid}")
        out[tid] = mid
    if not out:
        raise SystemExit(f"표가 비어 있다: {path}")
    return out


def build_payload(group: pd.DataFrame) -> tuple[list[dict], dict]:
    """한 곡의 구간 목록. 보낼 수 없는 것은 이유별로 센다."""
    stat = {"unknown_label": 0, "bad_range": 0, "long_uid": 0}
    rows = []
    for r in group.itertuples():
        label = str(r.section_label).upper()
        if label not in SECTION_ENUM:
            # 추천기는 `other` 를 만들 수 있는데 ERD ENUM 에는 없다. 억지로 다른
            # 라벨에 끼워 넣으면 사용자에게 틀린 구간 유형을 말하게 된다.
            stat["unknown_label"] += 1
            continue
        s_ms, e_ms = ids.to_ms(r.start_sec), ids.to_ms(r.end_sec)
        if e_ms <= s_ms:
            stat["bad_range"] += 1
            continue
        uid = str(r.moment_id)
        if len(uid) > build_id_map.UID_MAX:
            # 컬럼이 VARCHAR(64) 다. 넘기면 MySQL 이 조용히 잘라 저장하고, 잘린
            # 값끼리는 1:1 검사까지 통과해 매핑이 어긋난 채 서비스가 올라온다.
            stat["long_uid"] += 1
            continue
        rows.append({
            "section_index": int(r.section_idx),
            "external_id": uid,
            "section_label": label,
            "start_time_ms": s_ms,
            "end_time_ms": e_ms,
        })
    rows.sort(key=lambda x: x["section_index"])
    return rows, stat


def put_sections(base: str, token: str, music_id: int, sections: list[dict],
                 version: str) -> list[dict]:
    """한 곡을 적재하고 발급된 번호 짝을 돌려받는다."""
    body = _api_auth.request_json(
        f"{base}/api/v1/music/{int(music_id)}/section",
        method="PUT", token=token, timeout=TIMEOUT,
        body={"analysis_version": version, "sections": sections})
    return (body.get("data") or {}).get("sections") or []


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--track-map", required=True,
                    help="source_track_id,music_id 표 (CSV/TSV)")
    ap.add_argument("--moments", default=None,
                    help="기본: <ARTIFACT_DIR>/.../moments.parquet")
    ap.add_argument("--out", default=None,
                    help="기본: <ARTIFACT_DIR>/.../id_map.parquet")
    ap.add_argument("--version", default=ANALYSIS_VERSION,
                    help=f"analysis_version (기본 {ANALYSIS_VERSION})")
    ap.add_argument("--all-sources", action="store_true",
                    help="추천 풀 밖(fma 등)까지 적재한다")
    ap.add_argument("--limit", type=int, default=0, help="곡 수 상한 (시험용)")
    ap.add_argument("--dry-run", action="store_true",
                    help="보내지 않고 무엇을 보낼지만 센다")
    ap.add_argument("--force", action="store_true",
                    help="실패한 곡이 있어도 id_map 을 쓴다")
    a = ap.parse_args()

    src = Path(a.moments) if a.moments else paths.moments_path()
    if not src.exists():
        print(f"구간 파일이 없다: {src}", file=sys.stderr)
        return 2
    mom = pd.read_parquet(src)
    if "source" not in mom.columns:
        mom["source"] = "fma"
    if not a.all_sources:
        # `/internal/ready` 는 **추천 풀** 전량이 매핑됐는지 본다. 풀 밖의 곡까지
        # 넣을 이유가 없고, 넣으면 MySQL 만 커진다.
        mom = mom[mom.source.isin(recommender.POOL_SOURCES)]
    if mom.empty:
        print(f"적재할 구간이 없다 (풀 출처 {sorted(recommender.POOL_SOURCES)})",
              file=sys.stderr)
        return 1

    tmap = read_track_map(Path(a.track_map))
    groups = list(mom.groupby("track_id", sort=True))
    if a.limit:
        groups = groups[:a.limit]

    stat = {"unknown_label": 0, "bad_range": 0, "long_uid": 0}
    unmapped: list[str] = []
    empty: list[str] = []
    failed: list[str] = []
    pairs: list[dict] = []
    sent = 0

    token = "" if a.dry_run else _api_auth.access_token()
    base = _api_auth.base()

    for tid, group in groups:
        tid = str(tid)
        music_id = tmap.get(tid)
        if music_id is None:
            unmapped.append(tid)
            continue
        sections, s = build_payload(group)
        for k in stat:
            stat[k] += s[k]
        if not sections:
            empty.append(tid)
            continue
        if a.dry_run:
            sent += 1
            continue
        try:
            got = put_sections(base, token, music_id, sections, a.version)
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:300]
            log(f"적재 실패 {tid} (music_id={music_id}) {e.code}: {detail}")
            failed.append(tid)
            continue
        sent += 1
        for row in got:
            pairs.append({
                "source_track_id": tid,
                "music_id": int(music_id),
                "source_section_uid": str(row["external_id"]),
                "music_section_id": int(row["music_section_analysis_id"]),
            })

    log(f"곡 {len(groups)}개 중 {sent}개 적재 · 구간 {len(pairs)}개")
    for k in ("unknown_label", "bad_range", "long_uid"):
        if stat[k]:
            log(f"  제외 구간 {k}={stat[k]}")
    if unmapped:
        log(f"  --track-map 에 없는 곡 {len(unmapped)}개 (예: {unmapped[:5]})")
    if empty:
        log(f"  보낼 구간이 하나도 없던 곡 {len(empty)}개 (예: {empty[:5]})")
    if failed:
        log(f"  적재 실패 {len(failed)}개 (예: {failed[:5]})")

    if a.dry_run:
        log("보내지 않았다 (--dry-run).")
        return 0
    if not pairs:
        print("적재된 구간이 없다. id_map 을 쓰지 않는다.", file=sys.stderr)
        return 1

    dst = Path(a.out) if a.out else paths.id_map_path()
    if failed and dst.exists() and not a.force:
        # 부분 매핑으로 멀쩡한 표를 덮으면 그 곡들이 소리 없이 사라진다.
        print(f"실패한 곡이 {len(failed)}개인데 매핑표가 이미 있다. 덮지 않는다. "
              "고쳐서 다시 돌리거나 --force 를 준다.", file=sys.stderr)
        return 1

    out = pd.DataFrame(pairs)[list(ids.COLS)]
    tracks, sections_n = build_id_map.write_map(out, dst, a.force)
    log(f"저장 {dst} · 곡 {tracks} · 구간 {sections_n}")
    # 하나라도 실패했으면 초록불로 끝내지 않는다. cron 이 알아차려야 한다.
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
