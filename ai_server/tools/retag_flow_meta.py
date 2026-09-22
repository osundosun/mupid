"""`flow_meta` 의 장르·무드 축을 **DB 코드표 어휘로** 바꾼다.

    MUPID_AI_ARTIFACT_DIR=/경로 python -m ai_server.tools.retag_flow_meta \
        --plate data/proc/mupid_v<stamp> --snapshot C:/…/mupid-db-snapshot

## 서빙에는 더 이상 필요하지 않다 (S15P21A204-416)

장르·무드·`music_id` 는 이제 **Spring 내부 카탈로그 API** 가 준다
(`app/catalog_api.py`). 서빙용 배송 묶음에는 `flow_meta.parquet` 이 아예 들어가지
않고(`tools/slice_index.py` 의 `NOT_SHIPPED`), 숫자 환산 근거도 이 파일이 아니라
MySQL 이다.

**그래도 파일과 도구를 남겨 둔다.** 전체 색인 쪽 `flow_meta.parquet` 은 특징을 다시
굽는 오프라인 경로와 `track_features.parquet` 을 뽑는 자리가 계속 읽는다. 다만 거기서
쓰는 것은 `language`·`category`·`bpm_lo`·`bpm_hi`·`sound_raw` 뿐이고, **아래가 채우는
`genre_code`·`mood_codes`·`music_id` 는 이제 아무도 읽지 않는다.**

그러니 새로 돌릴 이유가 없다. DB 코드표가 바뀌어도 추천은 API 로 즉시 따라간다 —
색인을 다시 굽지 않아도 된다는 것이 이번 변경의 요점이다.

## 변환표를 만들지 않는다

예전에는 카탈로그가 제 어휘(`Electronic---Ambient` · `relaxing`)를 쓰고, 온보딩이
Spring 코드 이름을 그 어휘로 **옮겼다**(`onboarding.MOOD_MAP`). 옮기는 표가 있는 한
코드가 늘 때마다 표가 밀리고, 못 옮긴 값은 조용히 빈 목록이 된다 — 세분류 장르
45종이 실제로 그 상태였다(0건 매칭).

그래서 옮기지 않고 **카탈로그 쪽을 코드 이름으로 바꾼다.** 값의 출처는 DB 하나다.

    track_id ──(external_id 의 '#' 앞)── music_section_analysis ── music_id
             └─ music_genres · music_moods ── codes.code_name

`external_id` 를 쪼개 `music_id` 를 **얻지 않는다.** 쪼개는 것은 `track_id` 를 찾을
때뿐이고, `music_id` 는 표의 열을 그대로 읽는다.

## 축

    music_id     MySQL 이 발급한 곡 번호. **표의 열을 그대로 읽는다**
    genre_code   GENRE 49~93 의 code_name 하나 (곡당 1개. music_genres 가 1:1 이다)
    mood_codes   MOOD 40~48 의 code_name 목록, 쉼표로 이은 문자열
    제거         genre_top1 · mood_tags · lyrics_types

## 숫자 환산을 색인이 들고 간다

별도 매핑 파일을 두지 않는다(팀 규칙). 숫자가 필요한 곳은 배치 도구 둘뿐이고 둘 다
**곡 수준만** 쓴다 — `push_sections` 의 `im.music_id(tid)`, `pull_signals` 의
`im.source_track_id(...)`. 구간 PK 를 환산하는 코드는 저장소에 하나도 없다.

순간 무드(32~39)는 **건드리지 않는다.** 별건이다.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import pandas as pd

from ai_server.app import paths

GENRE_RANGE = (49, 93)          # 곡 장르. 대분류 9~16 은 곡이 0개라 여기 없다
MOOD_RANGE = (40, 48)           # 곡 무드. 순간 무드 32~39 와 다른 축이다
DROP_COLS = ("genre_top1", "mood_tags", "lyrics_types")


def log(msg: str) -> None:
    print(f"[retag] {msg}", flush=True)


def track_to_music_id(sec: pd.DataFrame) -> dict[str, int]:
    """`track_id` → `music_id`. **표의 `music_id` 열을 쓴다.**

    `external_id` 는 `T000001#0` 꼴이라 `#` 앞이 곡 문자열 ID 다. 거기서 숫자를
    뽑지 않는다 — `T000847_1` 처럼 규칙에서 벗어난 값이 섞여 있고, 뽑은 숫자가
    실제 PK 와 맞는다는 보장이 어디에도 없다.
    """
    out: dict[str, int] = {}
    bad: list[str] = []
    for ext, mid in zip(sec.external_id.astype(str), ints(sec, "music_id")):
        tid = ext.split("#", 1)[0]
        prev = out.setdefault(tid, int(mid))
        if prev != int(mid):
            bad.append(tid)
    if bad:
        raise SystemExit(f"한 곡이 여러 music_id 를 가리킨다: {sorted(set(bad))[:5]}")
    return out


def ints(df: pd.DataFrame, col: str) -> pd.Series:
    """스냅샷은 열이 문자열이다. 비교·색인 전에 한 번만 숫자로 맞춘다."""
    return pd.to_numeric(df[col], errors="raise").astype("int64")


def code_names(codes: pd.DataFrame, kind: str, lo: int, hi: int) -> dict[int, str]:
    cid = ints(codes, "code_id")
    sel = codes[(codes.code_type.astype(str) == kind) & cid.between(lo, hi)]
    return {int(i): str(n) for i, n in zip(ints(sel, "code_id"),
                                           sel.code_name.astype(str))}


def build(plate: Path, snap: Path) -> dict:
    codes = pd.read_parquet(snap / "codes.parquet")
    sec = pd.read_parquet(snap / "music_section_analysis.parquet")
    genres = pd.read_parquet(snap / "music_genres.parquet")
    moods = pd.read_parquet(snap / "music_moods.parquet")

    t2m = track_to_music_id(sec)
    gname = code_names(codes, "GENRE", *GENRE_RANGE)
    mname = code_names(codes, "MOOD", *MOOD_RANGE)
    log(f"코드표 — GENRE {len(gname)}종 · MOOD {len(mname)}종")

    # 코드표에 없는 번호가 참조되면 **추측하지 않고 멈춘다.**
    for df, col, table, known in ((genres, "genre_code_id", "music_genres", gname),
                                  (moods, "mood_code_id", "music_moods", mname)):
        unknown = sorted(set(ints(df, col)) - set(known))
        if unknown:
            raise SystemExit(
                f"{table} 이 범위 밖 코드를 가리킨다: {unknown[:10]} "
                f"(기대 {min(known)}~{max(known)})")

    g_by_music = dict(zip(ints(genres, "music_id"),
                          (gname[c] for c in ints(genres, "genre_code_id"))))
    m_by_music: dict[int, list[str]] = {}
    for m, c in zip(ints(moods, "music_id"), ints(moods, "mood_code_id")):
        m_by_music.setdefault(int(m), []).append(mname[int(c)])

    fm = pd.read_parquet(plate / "flow_meta.parquet")
    tid = fm.track_id.astype(str)
    mid = tid.map(t2m)

    # `music_id` 를 **색인에 싣는다.** 별도 매핑 파일(`id_map.parquet`)을 두지
    # 않기로 한 팀 규칙 때문이다 — 숫자 환산이 필요한 곳은 배치 도구 둘뿐이고
    # (`push_sections` · `pull_signals`, 둘 다 곡 수준만 쓴다), 그 값은 지금 이
    # 자리에서 DB 표를 읽어 이미 손에 있다. 색인과 함께 버전이 매겨져 함께
    # 배포되므로 판과 매핑이 어긋날 자리도 없어진다.
    #
    # 규칙으로 **유도하지 않는다.** `T000001` 에서 1 을 뽑는 것은 시딩이 그렇게
    # 만들었을 뿐 계약이 아니다 — `T000847_1` 처럼 규칙 밖 값이 실제로 있었다.
    fm["music_id"] = pd.array([int(m) if pd.notna(m) else None for m in mid],
                              dtype="Int64")

    fm["genre_code"] = [g_by_music.get(int(m), "") if pd.notna(m) else ""
                        for m in mid]
    # 순서를 코드 번호 차례로 고정한다 — 곡마다 흔들리면 응답이 안정적이지 않다.
    order = {v: k for k, v in mname.items()}
    fm["mood_codes"] = [
        ", ".join(sorted(m_by_music.get(int(m), []), key=lambda x: order[x]))
        if pd.notna(m) else "" for m in mid]

    dropped = [c for c in DROP_COLS if c in fm.columns]
    fm = fm.drop(columns=dropped)
    fm.to_parquet(plate / "flow_meta.parquet", index=False)

    no_db = int(mid.isna().sum())
    rep = {"tracks": len(fm), "db_에_없는_곡": no_db,
           "genre_code_채움": int((fm.genre_code != "").sum()),
           "genre_code_빈곡": int((fm.genre_code == "").sum()),
           "mood_codes_채움": int((fm.mood_codes != "").sum()),
           "mood_codes_빈곡": int((fm.mood_codes == "").sum()),
           "제거한_열": dropped,
           "genre_어휘": sorted(set(fm.genre_code) - {""}),
           "mood_어휘": sorted({m for s in fm.mood_codes for m in
                              (x.strip() for x in s.split(",")) if m})}
    log(f"곡 {rep['tracks']} · genre_code {rep['genre_code_채움']}채움/"
        f"{rep['genre_code_빈곡']}빈칸 · mood_codes {rep['mood_codes_채움']}채움/"
        f"{rep['mood_codes_빈곡']}빈칸 · DB 에 없는 곡 {no_db}")
    log(f"제거: {dropped}")
    return rep


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="flow_meta 어휘를 DB 코드표로 바꾼다")
    ap.add_argument("--plate", required=True, help="새 판 경로 (기존 판을 주지 않는다)")
    ap.add_argument("--snapshot", default=r"C:\Users\SSAFY\ssafy\mupid-db-snapshot")
    a = ap.parse_args(argv)
    plate = Path(a.plate)
    if plate.resolve() == paths.data_dir().resolve():
        print("현재 판을 제자리에서 고치지 않는다. 새 판 경로를 준다.", file=sys.stderr)
        return 1
    print(json.dumps(build(plate, Path(a.snapshot)), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
