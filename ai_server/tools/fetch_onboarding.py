"""Spring 온보딩 조회 API → `serve/onboarding.parquet`.

감상 이력이 없는 사용자의 첫 추천은 온보딩 답변으로 만든다(`onboarding_seeds()`).
그 답변이 있는 곳은 Spring 이므로 여기서 받아 온다.

    GET {MUPID_API_BASE_URL}/api/v1/user/{userId}/onboarding
    → { "data": { "user_id", "submitted", "submitted_at", "moods":[...], "genres":[...] } }

**단건 조회다.** 사용자 수만큼 두드린다. 벌크 조회가 생기면 이 도구의 `fetch_many` 만
바꾸면 된다.

인증
    시스템 계정의 **리프레시 토큰만** 환경변수로 받는다. 액세스 토큰은 그때그때
    `GET /api/v1/auth` 로 받아 메모리에만 둔다 — 파일·로그·응답 어디에도 남기지 않는다.

    MUPID_API_BASE_URL       Spring 주소 (`/api/v1` 앞까지)
    MUPID_API_REFRESH_TOKEN  ROLE_SYSTEM 계정의 리프레시 토큰

미제출은 오류가 아니다
    `submitted=false` 는 "아직 안 한 사람"이고 404 는 "없는 사용자"다. 앞의 것은 건너뛰고
    뒤의 것은 센다. 둘을 같이 묶으면 데이터가 어긋난 것을 눈치채지 못한다.

**받아 오는 축은 둘뿐이다.** `onboarding_seeds()` 는 language·vocal_type·style·tempo·
genre·mood·instrument 일곱 축을 읽지만 `onboarding_survey` 에는 mood·genre 밖에 없다.
나머지 다섯은 빈 값으로 두고, 빈 축은 필터를 건너뛴다. 나머지 축을 넣으려면 ERD 변경이
먼저다.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

import pandas as pd

from ai_server.app import paths, recommender
from ai_server.app.log import log

TIMEOUT = 10

# ---------------------------------------------------------------- 어휘 변환
# Spring 코드표는 사람이 고르는 8+8 개의 큰 덩어리고, AI 카탈로그는 태그에서 자란
# 31/91 개의 잔 어휘다. **두 어휘는 한 글자도 겹치지 않는다**(2026-09-15 실측).
# 변환 없이 넘기면 씨앗이 비고, 씨앗이 비면 조사 답변이 추천에 전혀 반영되지 않는다.
#
# mood 는 뜻으로 이어야 해서 표로 적는다. genre 는 대분류 → 세분류라 이름 포함으로
# 자동 확장한다(`Rock` → `Alternative Rock`·`Pop Rock`·`Indie Rock` …).
MOOD_MAP = {
    "잔잔한": ["relaxing", "meditative", "slow", "background"],
    "벅찬": ["epic", "uplifting", "inspiring", "motivational"],
    "몽환적인": ["space", "deep", "drama"],
    "신나는": ["energetic", "happy", "party", "positive"],
    "집중되는": ["background", "corporate", "meditative"],
    "감성적인": ["emotional", "melodic", "sad", "ballad"],
    "강렬한": ["heavy", "action", "trailer", "dark"],
    "설레는": ["love", "romantic", "summer"],
}

# 이름 포함으로는 못 잇는 것만 적는다.
GENRE_EXTRA = {
    "Electronic": ["House", "Downtempo", "Synthwave", "Tropical House", "Dubstep",
                   "Drum n Bass", "Electro House", "Chillwave", "Nu-Disco", "Synth-pop"],
    "Hip-hop": ["Cloud Rap", "Trap"],
    "Indie": ["Folk"],
}


def map_mood(names: list[str]) -> list[str]:
    out: list[str] = []
    for n in names:
        for v in MOOD_MAP.get(n, []):
            if v not in out:
                out.append(v)
    return out


def map_genre(names: list[str], catalog: list[str]) -> list[str]:
    """대분류 이름이 세분류 안에 들어 있으면 같은 무리로 본다."""
    out: list[str] = []
    for n in names:
        hit = [c for c in catalog if n.lower() in c.lower()]
        hit += [c for c in GENRE_EXTRA.get(n, []) if c in catalog]
        for v in hit:
            if v not in out:
                out.append(v)
    return out


class CatalogUnavailable(RuntimeError):
    """장르 어휘를 읽지 못했다. 조용히 빈 값을 쓰면 취향이 사라진다."""


def genre_catalog() -> list[str]:
    """AI 카탈로그의 장르 어휘. 없으면 **멈춘다.**

    예전에는 빈 리스트를 돌려주고 계속 갔는데, 그러면 사용자가 고른 장르가 통째로
    사라진 채 "성공" 으로 끝난다. 온보딩은 콜드스타트의 유일한 입력이라 조용히 비면
    그 사용자는 영원히 개인화를 못 받는다.
    """
    try:
        vals = [str(x["value"]) for x in recommender.catalog_facets().get("genre", [])]
    except Exception as e:
        raise CatalogUnavailable(f"장르 어휘를 읽지 못했다 ({type(e).__name__}: {e})") from None
    if not vals:
        raise CatalogUnavailable("장르 어휘가 비어 있다 — 색인이 적재됐는지 본다")
    return vals


# `onboarding_seeds()` 가 읽는 축. 지금 채울 수 있는 것은 mood·genre 뿐이다.
EMPTY_AXES = ("language", "vocal_type", "style", "tempo", "instrument",
              "energy", "section_label", "exclude_style")


def _base() -> str:
    b = os.environ.get("MUPID_API_BASE_URL", "").strip().rstrip("/")
    if not b:
        raise SystemExit("MUPID_API_BASE_URL 이 비어 있다.")
    return b


def _get(url: str, token: str | None = None, cookie: str | None = None) -> dict:
    req = urllib.request.Request(url, method="GET")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    if cookie:
        req.add_header("Cookie", cookie)
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        return json.loads(r.read().decode() or "{}")


def access_token() -> str:
    """리프레시 토큰으로 액세스 토큰을 받는다. 값은 돌려주기만 하고 남기지 않는다."""
    refresh = os.environ.get("MUPID_API_REFRESH_TOKEN", "").strip()
    if not refresh:
        raise SystemExit("MUPID_API_REFRESH_TOKEN 이 비어 있다. "
                         "POST /api/v1/user/system 으로 발급한다(관리자 권한).")
    body = _get(f"{_base()}/api/v1/auth", cookie=f"refresh_token={refresh}")
    tok = (body.get("data") or {}).get("access_token", "")
    if not tok:
        raise SystemExit("액세스 토큰을 받지 못했다. 리프레시 토큰이 만료됐는지 본다.")
    return tok


def to_row(data: dict, genres: list[str] | None = None) -> dict | None:
    """응답 한 건을 `onboarding.parquet` 한 줄로 옮긴다. 미제출이면 None.

    `genres` 는 AI 카탈로그의 장르 어휘다. 주지 않으면 변환 없이 원문을 넣는다 —
    그 경우 필터가 걸리지 않는다는 것을 알고 쓰는 것이다(테스트·점검용)."""
    if not data.get("submitted"):
        return None
    names = lambda key: [str(x["name"]) for x in (data.get(key) or []) if x.get("name")]
    raw_mood, raw_genre = names("moods"), names("genres")
    mood = map_mood(raw_mood)
    genre = map_genre(raw_genre, genres) if genres else []
    if raw_mood and not mood:
        log(f"mood 변환 결과가 비었다 — MOOD_MAP 에 없는 값 {raw_mood}")
    if raw_genre and not genre:
        # 카탈로그를 못 받았거나 어휘가 안 맞는다. 어느 쪽이든 **그 사용자의 장르는
        # 추천에 반영되지 않는다.** 원문은 genre_raw 에 남으니 나중에 되짚을 수 있다.
        log(f"genre 가 비었다 — 원문 {raw_genre}"
            + ("" if genres else " (장르 어휘를 받지 못했다)"))
    row = {"user_ref": f"internal:{int(data['user_id'])}",
           "mood": json.dumps(mood, ensure_ascii=False),
           "genre": json.dumps(genre, ensure_ascii=False),
           # 사람이 고른 원문. 변환이 어긋났을 때 되짚을 근거가 된다.
           "mood_raw": json.dumps(raw_mood, ensure_ascii=False),
           "genre_raw": json.dumps(raw_genre, ensure_ascii=False),
           "submitted_at": data.get("submitted_at") or ""}
    # 빈 축은 필터를 건너뛴다. 키 자체를 빼면 옛 행과 concat 할 때 NaN 이 된다.
    for k in EMPTY_AXES:
        row[k] = "[]"
    return row


def fetch_one(user_id: int, token: str, genres: list[str] | None = None) -> dict | None:
    try:
        body = _get(f"{_base()}/api/v1/user/{int(user_id)}/onboarding", token=token)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            log(f"없는 사용자 {user_id} — 매핑이 어긋났는지 본다")
            return None
        raise
    return to_row(body.get("data") or {}, genres)


def fetch_many(user_ids: list[int], token: str,
               allow_missing_catalog: bool = False) -> pd.DataFrame:
    try:
        genres = genre_catalog()
    except CatalogUnavailable:
        if not allow_missing_catalog:
            raise
        log("경고: 장르 어휘 없이 진행한다 — 고른 장르가 추천에 반영되지 않는다")
        genres = []
    rows, skipped = [], 0
    for uid in user_ids:
        r = fetch_one(uid, token, genres)
        if r is None:
            skipped += 1
            continue
        rows.append(r)
    log(f"받음 {len(rows)}건 · 건너뜀 {skipped}건 (미제출·없는 사용자)")
    return pd.DataFrame(rows)


def merge_into(new: pd.DataFrame, dst: Path) -> pd.DataFrame:
    """같은 사용자는 **마지막 줄이 이긴다**(`onboarding_of` 가 `iloc[-1]` 을 쓴다).
    제출은 계정당 한 번이라 값이 바뀌지는 않지만, 다시 받아도 줄만 늘지 않게 한다."""
    if dst.exists():
        old = pd.read_parquet(dst)
        if not new.empty:
            old = old[~old.user_ref.isin(set(new.user_ref))]
        new = pd.concat([old, new], ignore_index=True)
    return new


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("user_ids", nargs="*", type=int, help="조회할 사용자 번호")
    # id_map 은 **곡** 매핑표라 사용자 번호가 없다. 예전 `--from-id-map` 은 선언만
    # 되어 있고 읽는 곳도 없었다. 배치가 실제로 쓸 수 있는 형태로 바꾼다.
    ap.add_argument("--users-file", default=None,
                    help="사용자 번호 목록 파일 (한 줄에 하나). '-' 이면 표준입력")
    ap.add_argument("--out", default=None, help="기본: <ARTIFACT_DIR>/serve/onboarding.parquet")
    ap.add_argument("--dry-run", action="store_true", help="받아만 보고 쓰지 않는다")
    ap.add_argument("--allow-missing-catalog", action="store_true",
                    help="장르 어휘 없이도 진행한다. **고른 장르가 반영되지 않는다**")
    a = ap.parse_args()

    user_ids = list(a.user_ids)
    if a.users_file:
        src = sys.stdin if a.users_file == "-" else open(a.users_file, encoding="utf-8")
        with src as fh:
            for ln in fh:
                ln = ln.strip()
                if ln and not ln.startswith("#"):
                    user_ids.append(int(ln))
    if not user_ids:
        print("사용자 번호를 하나 이상 준다 (인자 또는 --users-file).", file=sys.stderr)
        return 2

    try:
        df = fetch_many(user_ids, access_token(), allow_missing_catalog=a.allow_missing_catalog)
    except CatalogUnavailable as e:
        print(f"{e}\n장르 없이 진행하려면 --allow-missing-catalog 를 준다.", file=sys.stderr)
        return 1
    if df.empty:
        log("쓸 것이 없다.")
        return 0

    dst = Path(a.out) if a.out else recommender.ONBOARD
    if a.dry_run:
        log(f"(dry-run) {len(df)}건 — {dst}")
        print(df.to_string(index=False))
        return 0

    dst.parent.mkdir(parents=True, exist_ok=True)
    out = merge_into(df, dst)
    tmp = dst.with_suffix(".parquet.tmp")
    out.to_parquet(tmp, index=False)
    tmp.replace(dst)
    log(f"기록 {len(out)}줄 → {dst}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
