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


def to_row(data: dict) -> dict | None:
    """응답 한 건을 `onboarding.parquet` 한 줄로 옮긴다. 미제출이면 None."""
    if not data.get("submitted"):
        return None
    names = lambda key: [str(x["name"]) for x in (data.get(key) or []) if x.get("name")]
    row = {"user_ref": f"internal:{int(data['user_id'])}",
           "mood": json.dumps(names("moods"), ensure_ascii=False),
           "genre": json.dumps(names("genres"), ensure_ascii=False),
           "submitted_at": data.get("submitted_at") or ""}
    # 빈 축은 필터를 건너뛴다. 키 자체를 빼면 옛 행과 concat 할 때 NaN 이 된다.
    for k in EMPTY_AXES:
        row[k] = "[]"
    return row


def fetch_one(user_id: int, token: str) -> dict | None:
    try:
        body = _get(f"{_base()}/api/v1/user/{int(user_id)}/onboarding", token=token)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            log(f"없는 사용자 {user_id} — 매핑이 어긋났는지 본다")
            return None
        raise
    return to_row(body.get("data") or {})


def fetch_many(user_ids: list[int], token: str) -> pd.DataFrame:
    rows, skipped = [], 0
    for uid in user_ids:
        r = fetch_one(uid, token)
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
    ap.add_argument("--from-id-map", action="store_true",
                    help="id_map.parquet 에 있는 사용자 대신 인자로 받은 번호를 쓴다")
    ap.add_argument("--out", default=None, help="기본: <ARTIFACT_DIR>/serve/onboarding.parquet")
    ap.add_argument("--dry-run", action="store_true", help="받아만 보고 쓰지 않는다")
    a = ap.parse_args()

    if not a.user_ids:
        print("사용자 번호를 하나 이상 준다.", file=sys.stderr)
        return 2

    df = fetch_many(a.user_ids, access_token())
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
