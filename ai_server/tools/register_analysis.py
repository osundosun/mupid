"""보컬 판정 → Spring `music_analysis` (`PUT /api/v1/music/{musicId}/analysis`).

**`music_analysis` 가 0행이었다.** 조회 쪽은 이미 이 표를 읽고 있었다 —
`GET /api/v1/music/{musicId}/analysis` 의 `hasVocal`·`vocalGender` 와 MuBTI 리포트
A축(`findVocalShare`)이 여기서 온다. 그런데 **넣는 경로가 없었다.**

원천은 태깅 산출물(`tracks.parquet`)의 `voice_label`·`gender_label` 이다. 구간 분석
(①~⑥)이 만드는 값이 아니라 별도 NLP 태깅이 붙인 값이라, 구간 적재와 다른 도구다.

    tracks.parquet ──PUT /api/v1/music/{musicId}/analysis──> music_analysis

옮기는 규칙

    voice_label      has_vocal    gender_label   vocal_gender
    ---------------  -----------  -------------  ------------
    voice            True         female         F
    instrumental     False        male           M
    unknown          **(제외)**   unknown        None (비움)

`unknown` 을 뺀 이유
    `has_vocal` 은 ERD 에서 `NOT NULL` 이다. `unknown` 135곡(13%)은 넣을 값이 없다.
    `true` 로 밀면 연주곡을 보컬곡으로 만들고, `false` 로 밀면 그 반대다 —
    둘 다 **추측을 사실처럼** 넣는 것이다. 판정이 나오면 그때 넣는다.
    (`--include-unknown` 으로 강제할 수 있지만 기본은 제외다.)

`vocal_gender` 의 `unknown` 은 **비운다.** `ETC` 로 뭉개지 않는다 — `ETC` 는
"기타로 판정했다" 이고 `None` 은 "모른다" 다.

환경변수

    MUPID_API_BASE_URL       Spring 주소 (`/api/v1` 앞까지)
    MUPID_API_REFRESH_TOKEN  ROLE_SYSTEM 계정의 리프레시 토큰
    MUPID_AI_ARTIFACT_DIR    산출물 루트

    python -m ai_server.tools.register_analysis --dry-run
"""
from __future__ import annotations

import argparse
import sys
import urllib.error
from pathlib import Path

import pandas as pd

from ai_server.app import paths
from ai_server.app.log import log
from ai_server.tools import _api_auth

TIMEOUT = 30

# 원천 열 → (has_vocal, gender 열)
VOICE_TRUE = "voice"
VOICE_FALSE = "instrumental"
VOICE_UNKNOWN = "unknown"

GENDER_BY_LABEL = {"female": "F", "male": "M"}
GENDER_UNKNOWN = "unknown"


def read_track_map(path: Path) -> dict[str, int]:
    """`source_track_id` → `music_id`. 구분자는 추측한다.

    **숫자를 지어내지 않는다.** 이 표가 유일한 출처다.
    """
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


def payload_from(voice: str, gender: str, *,
                 include_unknown: bool = False) -> tuple[dict | None, str]:
    """`(voice_label, gender_label)` → 요구 1 본문. 넣을 수 없으면 `(None, 이유)`."""
    if voice == VOICE_TRUE:
        has_vocal = True
    elif voice == VOICE_FALSE:
        has_vocal = False
    elif voice == VOICE_UNKNOWN and include_unknown:
        # 강제 옵션. **추측이다** — 화면에 사실처럼 뜬다.
        has_vocal = True
    else:
        return None, f"voice_label={voice or '(빈값)'}"

    vg = GENDER_BY_LABEL.get(gender)      # 모르면 None (비운다. ETC 로 뭉개지 않는다)
    # **와이어는 snake_case 다.** Spring 이
    # `spring.jackson.property-naming-strategy: SNAKE_CASE` 로 돌아서 DTO 의
    # `hasVocal`·`vocalGender` 는 JSON 에서 `has_vocal`·`vocal_gender` 다.
    # camelCase 로 보내면 두 필드가 **매핑되지 않아** `has_vocal` 이 null 이 되고
    # `@NotNull` 에 걸려 400 `MUPID_INVALID_REQUEST_BODY` 가 난다 — 실제로 그렇게
    # 400 을 받고 찾았다. 형제 도구(`register_sections.py`)도 같은 철자를 쓴다.
    return {"has_vocal": has_vocal, "vocal_gender": vg}, ""


def put_analysis(base: str, token: str, music_id: int, body: dict) -> dict:
    """한 곡의 보컬 판정을 넣는다. 응답의 `data` 를 돌려준다."""
    got = _api_auth.request_json(
        f"{base}/api/v1/music/{int(music_id)}/analysis",
        method="PUT", token=token, timeout=TIMEOUT, body=body)
    return got.get("data") or {}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--track-map", required=True,
                    help="source_track_id,music_id 표 (CSV/TSV)")
    ap.add_argument("--tracks", default=None,
                    help="기본: <ARTIFACT_DIR>/data/test_pipeline/tracks.parquet")
    ap.add_argument("--include-unknown", action="store_true",
                    help="voice_label=unknown 을 has_vocal=true 로 넣는다 (추측)")
    ap.add_argument("--limit", type=int, default=0, help="곡 수 상한 (시험용)")
    ap.add_argument("--dry-run", action="store_true",
                    help="보내지 않고 무엇을 보낼지만 센다")
    a = ap.parse_args()

    # `paths.data_dir()` 는 `<artifact>/data/proc/mupid` 다. 태깅 산출물은
    # `<artifact>/data/test_pipeline/tracks.parquet` — **두 단계 위**다.
    # (실측: `/home/j-j15a204/rec_mupid/data/test_pipeline/tracks.parquet`)
    src = Path(a.tracks) if a.tracks else (paths.data_dir().parent.parent
                                          / "test_pipeline" / "tracks.parquet")
    if not src.exists():
        print(f"태깅 산출물이 없다: {src}", file=sys.stderr)
        return 2
    df = pd.read_parquet(src)
    for col in ("track_id", "voice_label", "gender_label"):
        if col not in df.columns:
            print(f"{src.name} 에 {col} 이 없다 (있는 것: {list(df.columns)})",
                  file=sys.stderr)
            return 2
    df["_tid"] = df["track_id"].astype(str)

    tmap = read_track_map(Path(a.track_map))
    if a.limit:
        df = df.head(a.limit)

    stat = {"voice": 0, "instrumental": 0, "unknown": 0, "기타": 0}
    skipped: list[dict] = []
    unmapped: list[str] = []
    failed: list[dict] = []
    sent = 0
    gender_seen = {"F": 0, "M": 0, "(없음)": 0}

    token = "" if a.dry_run else _api_auth.access_token()
    base = _api_auth.base()

    # `itertuples` 는 밑줄로 시작하는 열 이름을 `Pandas` 같은 무효 식별자로 바꾼다
    # (실제로 `AttributeError: 'Pandas' object has no attribute '_tid'` 로 죽었다).
    # 그래서 열을 직접 zip 으로 돈다 — 열 이름에 의존하지 않는다.
    for tid_raw, voice_raw, gender_raw in zip(
            df["track_id"], df["voice_label"], df["gender_label"]):
        tid = str(tid_raw)
        voice = str(voice_raw).strip().lower()
        gender = str(gender_raw).strip().lower()
        if voice in stat:
            stat[voice] += 1
        else:
            stat["기타"] += 1
        mid = tmap.get(tid)
        if mid is None:
            unmapped.append(tid)
            continue
        body, why = payload_from(voice, gender, include_unknown=a.include_unknown)
        if body is None:
            skipped.append({"track_id": tid, "why": why})
            continue
        key = body["vocal_gender"] or "(없음)"
        gender_seen[key] = gender_seen.get(key, 0) + 1
        if a.dry_run:
            sent += 1
            continue
        try:
            put_analysis(base, token, mid, body)
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:300]
            log(f"적재 실패 {tid} (music_id={mid}) {e.code}: {detail}")
            failed.append({"track_id": tid, "code": e.code})
            continue
        sent += 1
        if (sent + len(skipped)) % 200 == 0:
            log(f"  ...{sent}곡 넣음 (건너뜀 {len(skipped)} · 실패 {len(failed)})")

    log(f"곡 {len(df)}개 중 {sent}개 적재 · 건너뜀 {len(skipped)} · 실패 {len(failed)}")
    log(f"  voice_label 집계 {stat}")
    log(f"  vocal_gender 분포 {gender_seen}")
    if unmapped:
        log(f"  --track-map 에 없는 곡 {len(unmapped)}개 (예: {unmapped[:5]})")
    if skipped:
        log(f"  건너뛴 이유 (예: {skipped[:3]})")
    if a.dry_run:
        log("보내지 않았다 (--dry-run).")
        return 0
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
