"""곡·순간 메타데이터를 **Spring 내부 API 에서** 읽어 메모리에 둔다.

    MySQL ──(정본)──> Spring GET /api/v1/internal/ai/catalog (ROLE_SYSTEM)
                          └──> 이 모듈 ──> recommender.Catalog

## 왜 색인에서 걷어냈나

예전에는 같은 값이 색인 안에도 있었다 — `flow_meta.parquet` 의 제목·장르·무드·보컬,
`moments.parquet` 의 악기·구간 종류. 그래서 MySQL 에서 곡을 지우거나 장르를 고쳐도
추천은 옛 값으로 계속 돌았고, **색인을 다시 굽기 전까지 아무 오류도 나지 않았다.**
지워진 곡이 계속 추천되는 것도 그 상태에서는 정상으로 보인다.

기준 데이터는 MySQL 하나다. AI 는 DB 에 직접 붙지 않고 이 API 로만 읽는다 — 삭제
여부와 코드표 해석이 서버 한 곳에서만 정해진다.

## 파일을 만들지 않는다

받은 것을 parquet 이나 json 으로 떨구지 않는다. 떨구는 순간 그것이 두 번째 사본이
되고, 다음 사람은 "API 가 안 되면 그 파일을 쓰면 되지" 로 간다 — 그러면 걷어낸 문제가
이름만 바꿔 돌아온다. 카탈로그는 프로세스 메모리에만 있다.

## 실패를 어떻게 다루나

  · **최초 적재 실패는 실패다.** 카탈로그 없이 뜨면 장르·무드·보컬 조건이 통째로
    빈 채 추천이 나간다. 오류가 아니라 "조건이 안 걸린 결과" 라 응답만 봐서는
    정상과 구분되지 않는다. 그래서 `/internal/ready` 를 막는다.
  · **갱신 실패는 실패가 아니다.** 이미 들고 있는 카탈로그가 조금 낡았을 뿐이고,
    그것은 카탈로그가 없는 것보다 낫다. 옛 스냅샷을 그대로 두고 오류만 기록한다.
    **부분 스냅샷으로 갈아 끼우지 않는다** — 중간에 끊긴 페이지까지만 담고 바꾸면
    카탈로그가 조용히 잘린 채로 굳는다.

## 환경변수

    MUPID_API_BASE_URL          Spring 주소 (`/api/v1` 앞까지)   ← 적재 도구와 같은 값
    MUPID_API_REFRESH_TOKEN     ROLE_SYSTEM 계정의 리프레시 토큰  ← 적재 도구와 같은 값
    MUPID_AI_CATALOG_PAGE_SIZE  한 번에 받을 곡 수 (기본 200, 서버 상한 500)
    MUPID_AI_CATALOG_TTL_SEC    이 시간이 지나면 다음 요청이 갱신을 건다 (기본 0 = 안 함)
    MUPID_AI_CATALOG_TIMEOUT    한 번의 호출 제한 시간 (기본 15초)
"""
from __future__ import annotations

import os
import threading
import time
import urllib.error
from typing import Optional

import pandas as pd

from . import spring_api
from .log import log

PATH = "/api/v1/internal/ai/catalog"
DEFAULT_PAGE_SIZE = 200
# Spring 쪽 `AiCatalogController.MAX_SIZE` 와 같은 값이다. 여기서 먼저 막는 것은
# 400 을 받고 나서야 알면 원인이 "카탈로그를 못 읽는다" 로만 보이기 때문이다.
MAX_PAGE_SIZE = 500
# 페이지가 이보다 많으면 무언가 잘못됐다고 본다. `total_pages` 를 믿고 도는데 그 값이
# 이상하면 영원히 돌 수 있다 — 상한 없이 도는 고리를 기동 경로에 두지 않는다.
MAX_PAGES = 10_000


class CatalogUnavailable(Exception):
    """카탈로그를 한 벌로 받지 못했다. 최초 적재에서는 기동 실패, 갱신에서는 무시."""


class Snapshot:
    """한 시점의 카탈로그. **만들고 나면 바꾸지 않는다.**

    통째로 새로 만들어 참조 하나를 바꾸는 방식이라, 추천을 계산하는 도중에 값이
    반쯤 바뀌는 일이 없다. 잠금 없이 읽을 수 있는 이유이기도 하다.
    """

    def __init__(self, tracks: dict, sections: dict, pages: int,
                 generated_first: Optional[str], generated_last: Optional[str]) -> None:
        self.tracks = tracks              # track_id(str) -> dict
        self.sections = sections          # external_id(str) -> dict
        self.pages = pages
        self.generated_first = generated_first
        self.generated_last = generated_last
        self.loaded_at = time.time()

    # ---------------------------------------------------------------- 조회
    def music_id(self, track_id: str) -> Optional[int]:
        row = self.tracks.get(str(track_id))
        return None if row is None else row.get("music_id")

    def track_id_of(self, external_id: str) -> Optional[str]:
        row = self.sections.get(str(external_id))
        return None if row is None else row.get("track_id")

    # ------------------------------------------------------------ 표로 내보내기
    def track_frame(self) -> pd.DataFrame:
        """`track_id` 를 인덱스로 하는 곡 메타.

        열 이름은 **예전 `flow_meta.parquet` 과 같게 둔다.** 추천 계산부가 읽는
        이름이라, 여기서 바꾸면 점수·필터에 손을 대게 된다 — 이번 작업은 값의
        출처만 바꾸는 것이고 추천 로직은 건드리지 않는다.
        """
        rows = []
        for tid, r in self.tracks.items():
            rows.append({
                "track_id": tid,
                "music_id": r.get("music_id"),
                "title": r.get("title") or tid,
                "genre_code": r.get("genre_code") or "",
                "mood_codes": r.get("mood_codes") or "",
                "has_vocal": r.get("has_vocal"),
                "vocal_gender": r.get("vocal_gender"),
            })
        frame = pd.DataFrame(rows, columns=[
            "track_id", "music_id", "title", "genre_code", "mood_codes",
            "has_vocal", "vocal_gender"])
        return frame.set_index("track_id")

    def section_frame(self) -> pd.DataFrame:
        """`moment_id`(= `external_id`) 를 열로 갖는 구간 메타."""
        rows = []
        for uid, r in self.sections.items():
            rows.append({
                "moment_id": uid,
                "track_id": r.get("track_id"),
                "music_section_analysis_id": r.get("music_section_analysis_id"),
                "section_idx": r.get("section_idx"),
                "section_label": r.get("section_label") or "",
                "start_ms": r.get("start_ms"),
                "end_ms": r.get("end_ms"),
                "instruments": r.get("instruments") or "",
                "moment_moods": r.get("moment_moods") or "",
            })
        return pd.DataFrame(rows, columns=[
            "moment_id", "track_id", "music_section_analysis_id", "section_idx",
            "section_label", "start_ms", "end_ms", "instruments", "moment_moods"])

    def stats(self) -> dict:
        return {"tracks": len(self.tracks), "sections": len(self.sections),
                "pages": self.pages,
                "generated_first": self.generated_first,
                "generated_last": self.generated_last,
                "loaded_at": self.loaded_at,
                "age_seconds": round(time.time() - self.loaded_at, 1)}


# ------------------------------------------------------------------ 설정 읽기

def configured() -> bool:
    return spring_api.configured()


def page_size() -> int:
    raw = os.environ.get("MUPID_AI_CATALOG_PAGE_SIZE", "").strip()
    try:
        size = int(raw) if raw else DEFAULT_PAGE_SIZE
    except ValueError:
        size = DEFAULT_PAGE_SIZE
    return max(1, min(size, MAX_PAGE_SIZE))


def ttl_seconds() -> float:
    """0 이면 시간으로는 갱신하지 않는다 (명시적 갱신만).

    기본값을 0 으로 두는 것은, 갱신이 곧 Spring 호출이라 **켜는 쪽이 값을 넣는
    쪽이어야** 하기 때문이다. 기본으로 도는 주기 호출은 설정한 기억 없이 생긴다.
    """
    raw = os.environ.get("MUPID_AI_CATALOG_TTL_SEC", "").strip()
    try:
        return max(0.0, float(raw)) if raw else 0.0
    except ValueError:
        return 0.0


def timeout_seconds() -> int:
    raw = os.environ.get("MUPID_AI_CATALOG_TIMEOUT", "").strip()
    try:
        return max(1, int(raw)) if raw else spring_api.TIMEOUT
    except ValueError:
        return spring_api.TIMEOUT


# ------------------------------------------------------------------ 받아오기

def _tags(values) -> str:
    """코드 이름 목록을 쉼표 문자열로. 추천 계산부가 읽는 모양이다."""
    if not values:
        return ""
    return ", ".join(str(v).strip() for v in values if str(v).strip())


def fetch() -> Snapshot:
    """전체를 한 벌로 받는다. 한 페이지라도 실패하면 **아무것도 돌려주지 않는다.**"""
    size = page_size()
    timeout = timeout_seconds()
    try:
        token = spring_api.access_token(timeout=timeout)
        base = spring_api.base()
    except spring_api.SpringApiError as e:
        raise CatalogUnavailable(f"인증 실패: {e}") from e

    tracks: dict = {}
    sections: dict = {}
    generated_first = generated_last = None
    page = 0
    total_pages = 1

    while page < total_pages:
        if page >= MAX_PAGES:
            raise CatalogUnavailable(f"페이지가 {MAX_PAGES} 를 넘는다 — 응답을 의심한다")
        url = f"{base}{PATH}?page={page}&size={size}"
        try:
            body = spring_api.request_json(url, token=token, timeout=timeout)
        except urllib.error.HTTPError as e:
            raise CatalogUnavailable(
                f"{PATH} page={page} 가 HTTP {e.code} 다 — "
                f"ROLE_SYSTEM 계정인지 확인한다") from e
        except Exception as e:
            raise CatalogUnavailable(
                f"{PATH} page={page} 호출 실패: {type(e).__name__}: {e}") from e

        # **키는 snake_case 다.** Spring 이 응답 전체에 SNAKE_CASE 를 건다
        # (`spring.jackson.property-naming-strategy`). camelCase 로 읽어서 시험은
        # 통과하고 실서버에서만 "total_pages 가 없다" 로 막힌 적이 있다(2026-09-23).
        data = body.get("data")
        if not isinstance(data, dict):
            raise CatalogUnavailable(f"{PATH} 응답에 data 가 없다 (page={page})")
        try:
            total_pages = int(data["total_pages"])
        except (KeyError, TypeError, ValueError) as e:
            # 개수로 끝을 판정하지 않는다. 중간 페이지가 비면 카탈로그가 조용히 잘린다.
            raise CatalogUnavailable("응답에 total_pages 가 없다 — 끝을 판정할 수 없다") from e

        stamp = data.get("generated_at")
        if generated_first is None:
            generated_first = stamp
        generated_last = stamp or generated_last

        for t in data.get("tracks") or []:
            _ingest_track(t, tracks, sections)
        page += 1

    if not tracks:
        # 빈 카탈로그로 갈아 끼우면 모든 조건이 아무 곡도 못 고른다. 오류가 나지
        # 않고 "추천이 안 나온다" 로만 보이므로 여기서 실패로 못 박는다.
        raise CatalogUnavailable("카탈로그가 0곡이다 — 빈 카탈로그로는 추천할 수 없다")

    return Snapshot(tracks, sections, page, generated_first, generated_last)


def _ingest_track(t: dict, tracks: dict, sections: dict) -> None:
    """곡 하나를 담는다. **구간이 없는 곡은 담지 않는다.**

    `track_id` 는 `external_id` 의 `#` 앞이다. 곡 자체는 문자열 ID 를 들고 있지
    않으므로 구간에서 얻는 수밖에 없고, 그래서 구간이 없으면 이을 자리가 없다
    (Spring 도 그런 곡은 실어 보내지 않는다).

    숫자에서 문자열을 **지어내지 않는다** — `music_id` 1 에서 `T000001` 을 만드는
    식의 규칙은 시딩이 그렇게 했을 뿐 계약이 아니고, 실제로 규칙 밖 값이 있었다.
    """
    raw_sections = t.get("sections") or []
    if not raw_sections:
        return
    music_id = t.get("music_id")
    track_id = None
    staged = []
    for s in raw_sections:
        uid = str(s.get("external_id") or "").strip()
        if not uid:
            continue
        tid = uid.split("#", 1)[0]
        if track_id is None:
            track_id = tid
        elif tid != track_id:
            # 한 곡의 구간이 서로 다른 문자열 곡을 가리킨다. 섞어 담으면 어느
            # 쪽이 이겼는지 모른 채 추천이 돈다.
            log(f"카탈로그: music_id {music_id} 의 구간이 두 곡을 가리킨다 "
                f"({track_id}, {tid}) — 이 곡을 건너뛴다")
            return
        staged.append((uid, s))
    if track_id is None:
        return

    tracks[track_id] = {
        "music_id": int(music_id) if music_id is not None else None,
        "title": t.get("title"),
        "genre_code": _tags(t.get("genres")),
        "mood_codes": _tags(t.get("moods")),
        "has_vocal": t.get("has_vocal"),
        "vocal_gender": t.get("vocal_gender"),
    }
    for uid, s in staged:
        sections[uid] = {
            "track_id": track_id,
            "music_section_analysis_id": s.get("music_section_analysis_id"),
            "section_idx": s.get("index"),
            "section_label": s.get("section_label"),
            "start_ms": s.get("start_time_ms"),
            "end_ms": s.get("end_time_ms"),
            "instruments": _tags(s.get("instruments")),
            "moment_moods": _tags(s.get("moods")),
        }


# ------------------------------------------------------------------ 보관

_LOCK = threading.Lock()
_SNAPSHOT: Optional[Snapshot] = None
_LAST_ERROR: Optional[str] = None
_LAST_ERROR_AT: Optional[float] = None
_LAST_ATTEMPT_AT: Optional[float] = None


def snapshot() -> Optional[Snapshot]:
    """지금 쓰는 카탈로그. 없으면 None — 부르는 쪽이 준비 안 됨으로 다룬다."""
    return _SNAPSHOT


def load() -> Snapshot:
    """**최초 적재.** 실패하면 예외다 — 카탈로그 없이 뜨면 안 된다."""
    global _SNAPSHOT, _LAST_ERROR, _LAST_ERROR_AT, _LAST_ATTEMPT_AT
    if not configured():
        raise CatalogUnavailable(
            "MUPID_API_BASE_URL · MUPID_API_REFRESH_TOKEN 이 필요하다 — "
            "카탈로그 없이는 장르·무드·보컬 조건이 통째로 빈 채 추천이 나간다")
    with _LOCK:
        _LAST_ATTEMPT_AT = time.time()
        try:
            snap = fetch()
        except CatalogUnavailable as e:
            _LAST_ERROR, _LAST_ERROR_AT = str(e), time.time()
            raise
        _SNAPSHOT = snap
        _LAST_ERROR, _LAST_ERROR_AT = None, None
        log(f"카탈로그 적재 — {len(snap.tracks)}곡 {len(snap.sections)}구간 "
            f"({snap.pages}쪽)")
        return snap


def refresh() -> dict:
    """**갱신.** 실패해도 들고 있던 카탈로그를 그대로 둔다.

    돌려주는 것은 사람이 읽을 결과다. 실패를 예외로 올리지 않는 이유는, 부르는
    자리가 주기 갱신과 운영자 호출이라 어느 쪽도 서비스를 멈춰서는 안 되기
    때문이다. 처음부터 없는 경우에만 `ok=False` 와 함께 그 사실을 알린다.
    """
    global _SNAPSHOT, _LAST_ERROR, _LAST_ERROR_AT, _LAST_ATTEMPT_AT
    if not configured():
        return {"ok": False, "changed": False,
                "error": "MUPID_API_BASE_URL · MUPID_API_REFRESH_TOKEN 이 비어 있다",
                "kept_previous": _SNAPSHOT is not None}
    with _LOCK:
        _LAST_ATTEMPT_AT = time.time()
        previous = _SNAPSHOT
        try:
            snap = fetch()
        except CatalogUnavailable as e:
            _LAST_ERROR, _LAST_ERROR_AT = str(e), time.time()
            log(f"카탈로그 갱신 실패 — {e}"
                + (" (기존 카탈로그를 그대로 쓴다)" if previous is not None else ""))
            return {"ok": False, "changed": False, "error": str(e),
                    "kept_previous": previous is not None,
                    "catalog": previous.stats() if previous is not None else None}
        _SNAPSHOT = snap
        _LAST_ERROR, _LAST_ERROR_AT = None, None
        changed = (previous is None
                   or previous.tracks.keys() != snap.tracks.keys()
                   or previous.sections.keys() != snap.sections.keys())
        log(f"카탈로그 갱신 — {len(snap.tracks)}곡 {len(snap.sections)}구간"
            + ("" if changed else " (구성 동일)"))
        return {"ok": True, "changed": changed, "error": None,
                "kept_previous": False, "catalog": snap.stats()}


def refresh_if_stale() -> bool:
    """TTL 이 지났으면 갱신한다. 껐으면(0) 아무것도 하지 않는다."""
    ttl = ttl_seconds()
    snap = _SNAPSHOT
    if not ttl or snap is None:
        return False
    if time.time() - snap.loaded_at < ttl:
        return False
    refresh()
    return True


def status() -> dict:
    """`/health` 와 `/internal/ready` 가 그대로 내보낸다. **비밀값은 싣지 않는다.**"""
    snap = _SNAPSHOT
    return {
        "configured": configured(),
        # 주소는 설정 여부만 알린다. 값 자체는 내보내지 않는다.
        "base_url_set": bool(os.environ.get("MUPID_API_BASE_URL", "").strip()),
        "loaded": snap is not None,
        "page_size": page_size(),
        "ttl_seconds": ttl_seconds(),
        "last_error": _LAST_ERROR,
        "last_error_at": _LAST_ERROR_AT,
        "last_attempt_at": _LAST_ATTEMPT_AT,
        "catalog": snap.stats() if snap is not None else None,
    }


def reset() -> None:
    """시험 전용. 모듈 전역을 비운다."""
    global _SNAPSHOT, _LAST_ERROR, _LAST_ERROR_AT, _LAST_ATTEMPT_AT
    with _LOCK:
        _SNAPSHOT = None
        _LAST_ERROR = _LAST_ERROR_AT = _LAST_ATTEMPT_AT = None


def snapshot_from_tracks(tracks: list) -> Snapshot:
    """**응답 모양 그대로** 받은 목록으로 스냅샷을 만든다. 시험·오프라인 전용.

    사전을 직접 채우지 않고 `_ingest_track` 을 지나가게 하는 이유는, 시험이 쓰는
    모양과 운영이 받는 모양을 하나로 묶기 위해서다. 따로 만들면 계약이 바뀌었을 때
    시험만 통과한다.
    """
    built: dict = {}
    sections: dict = {}
    for t in tracks:
        _ingest_track(t, built, sections)
    return Snapshot(built, sections, 1, None, None)


def install(snap: Snapshot) -> None:
    """시험·오프라인 전용. 이미 만든 스냅샷을 그대로 꽂는다."""
    global _SNAPSHOT, _LAST_ERROR, _LAST_ERROR_AT
    with _LOCK:
        _SNAPSHOT = snap
        _LAST_ERROR = _LAST_ERROR_AT = None
