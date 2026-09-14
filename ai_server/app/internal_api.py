"""실서비스(Spring) 가 호출하는 내부 API. `/internal` 아래에만 둔다.

기존 8100 데모 API 는 문자열 ID 와 초 단위 실수를 쓴다. 실서비스 계약은 숫자 ID 와 정수
밀리초다. **추천 로직은 그대로 두고 입출력 경계에서만 형식을 바꾼다** — 이 파일은 기존
`recommend()` 결과를 공통 DTO 로 옮겨 담는 얇은 어댑터일 뿐이고 점수·가중치·필터·중복
제거에 손대지 않는다.

문자열 ID(`JAMENDO_1083659`, `JAMENDO_1083659#3`)는 출처 추적용으로 내부에만 남고 응답에
나가지 않는다. 숫자 매핑이 없으면 만들어내지 않고 오류를 낸다 → `ids.py`.
"""
from __future__ import annotations

import os
from typing import Optional

from fastapi import APIRouter, FastAPI, Request
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, StrictInt, StrictStr, model_validator

from . import ids

PREFIX = "/internal"
MODEL_VERSION = "mupid-stage1-v1"       # /feedback 이 이미 내보내는 값과 같게 둔다
BIGINT_MAX = 2 ** 63 - 1
MAX_LIMIT = 50


class ApiError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _err(code: str, message: str, status: int = 400) -> JSONResponse:
    return JSONResponse(status_code=status,
                        content={"error_code": code, "message": message})


# ------------------------------------------------------------------ 요청 DTO
class _Base(BaseModel):
    """세 경로가 공유하는 부분. Strict 타입이라 문자열 "148" 은 거부된다."""

    model_config = {"extra": "forbid"}

    recommendation_id: StrictInt = Field(..., ge=1, le=BIGINT_MAX,
                                         description="Spring 이 발급하는 BIGINT")
    user_id: StrictInt = Field(..., ge=1)
    limit: StrictInt = Field(5, ge=1, le=MAX_LIMIT)
    exclude_music_ids: list[StrictInt] = Field(default_factory=list)


class NextRequest(_Base):
    current_music_id: Optional[StrictInt] = Field(None, ge=1)
    current_music_section_id: Optional[StrictInt] = Field(None, ge=1)
    position_ms: Optional[StrictInt] = Field(
        None, ge=0, description="재생 위치. 현재 추천 로직은 이 값을 쓰지 않는다")


class DailyRequest(_Base):
    pass


class SearchRequest(_Base):
    query: StrictStr = Field(..., min_length=1, max_length=500)


# ------------------------------------------------------------------ 응답 DTO
class RecommendationItem(BaseModel):
    music_id: int
    music_section_id: int
    start_time_ms: int = Field(..., ge=0)
    end_time_ms: int = Field(..., ge=0)
    section_label: str = Field(..., description="구간 종류. ERD ENUM 과 같은 대문자")
    rank_order: int = Field(..., ge=1)
    score: float
    reason_code: str
    reason: str = Field(..., description="사람이 읽는 추천 이유")

    @model_validator(mode="after")
    def _range(self):
        if self.end_time_ms <= self.start_time_ms:
            raise ValueError("end_time_ms 는 start_time_ms 보다 커야 한다")
        return self


class RecommendationResponse(BaseModel):
    recommendation_id: int
    items: list[RecommendationItem]
    shortfall: int = Field(..., ge=0)
    model_version: str


class ErrorResponse(BaseModel):
    error_code: str
    message: str


ERRORS = {400: {"model": ErrorResponse}, 404: {"model": ErrorResponse},
          500: {"model": ErrorResponse}, 503: {"model": ErrorResponse}}


# ------------------------------------------------------------------ 어댑터
def _reason_code(item: dict, has_query: bool) -> str:
    """기존 결과가 이미 들고 있는 값에서 사유 코드를 읽어낸다. 새로 계산하지 않는다."""
    if has_query and item.get("clap_score") is not None:
        return "QUERY_SEMANTIC_MATCH"
    if item.get("slot_source") == "explore":
        return "EXPLORE_NEW"
    if item.get("matched_dna"):
        return "SIMILAR_PREFERRED_MOMENT"
    return "COLD_START"


def to_response(rec_id: int, out: dict, limit: int,
                has_query: bool) -> RecommendationResponse:
    """`recommend()` 결과를 공통 DTO 로 옮긴다. 순서·점수는 건드리지 않는다."""
    im = ids.id_map()
    items: list[RecommendationItem] = []
    for r in out.get("results", []):
        src_t, src_s = str(r["track_id"]), str(r["moment_id"])
        mid = im.music_id(src_t)                      # 없으면 MappingError
        sid = im.music_section_id(src_s)
        s_ms, e_ms = ids.to_ms(r["start_sec"]), ids.to_ms(r["end_sec"])
        if e_ms <= s_ms:
            raise ApiError("INVALID_TIME_RANGE",
                           f"구간 시간이 뒤집혔거나 길이가 0 입니다: {src_s}")
        items.append(RecommendationItem(
            music_id=mid, music_section_id=sid,
            start_time_ms=s_ms, end_time_ms=e_ms,
            # ERD ENUM 은 대문자다. 추천기는 소문자를 만들므로 경계에서만 맞춘다.
            section_label=str(r.get("section_label", "")).upper(),
            rank_order=int(r["rank"]), score=float(r["final_score"]),
            reason_code=_reason_code(r, has_query),
            reason=str(r.get("reason", ""))))
    items, dropped = _dedupe_by_music_id(items)
    if dropped:
        # 여기서 걸렸다는 것은 매핑표가 N:1 이라는 뜻이다. 조용히 넘기면 원인이 묻힌다.
        print(f"[internal] music_id 중복 {dropped}건 제거 — 매핑표 1:1 확인 필요",
              flush=True)
    if not items:
        raise ApiError("NO_CANDIDATES", "해당되는 노래가 없습니다.")
    # shortfall 은 추천기가 이미 센 값을 쓰되, 없으면 요청 수와의 차로 메운다.
    short = out.get("diagnostics", {}).get("shortfall")
    short = int(short) if short is not None else max(0, limit - len(items))
    return RecommendationResponse(recommendation_id=rec_id, items=items,
                                  shortfall=short, model_version=MODEL_VERSION)



def _dedupe_by_music_id(
        items: list["RecommendationItem"]) -> tuple[list["RecommendationItem"], int]:
    """곡 중복을 숫자 ID 로 최종 확인한다. 제목·문자열 ID 로는 거르지 않는다.

    같은 곡의 여러 구간이 있으면 **최종 점수가 가장 높은 구간 하나만** 남긴다.
    남은 것들의 상대 순서는 그대로 두고 rank_order 만 1부터 다시 붙인다.
    """
    best: dict[int, "RecommendationItem"] = {}
    order: list[int] = []
    for it in items:
        cur = best.get(it.music_id)
        if cur is None:
            best[it.music_id] = it
            order.append(it.music_id)
        elif it.score > cur.score:
            best[it.music_id] = it
    kept = [best[m] for m in order]
    for rank, it in enumerate(kept, 1):
        it.rank_order = rank
    return kept, len(items) - len(kept)


_SVC_MOD = None          # install() 이 넣어 준다. 이름으로 재import 하지 않는다.


def _svc():
    svc = _SVC_MOD
    if svc is None:                       # 직접 호출 등 예외 상황의 최후 수단
        from . import recommender as svc  # noqa: PLC0415
    if getattr(svc, "CAT", None) is None or len(svc.CAT.pool) == 0:
        raise ApiError("INDEX_NOT_READY", "추천 색인이 아직 준비되지 않았습니다.")
    return svc


def _user_ref(user_id: int) -> str:
    """데모 저장소는 문자열 사용자 키를 쓴다. 숫자 user_id 를 그 이름공간에 얹는다."""
    return f"internal:{int(user_id)}"


def _exclude(req: _Base) -> set[str]:
    im = ids.id_map()
    return {im.source_track_id(x) for x in req.exclude_music_ids}


# ------------------------------------------------------------------ 라우터
router = APIRouter(prefix=PREFIX, tags=["internal"])


@router.post("/recommendations/next", response_model=RecommendationResponse,
             responses=ERRORS, summary="이어 듣기 추천")
def next_(req: NextRequest) -> RecommendationResponse:
    svc = _svc()
    im = ids.id_map()
    cur_t = im.source_track_id(req.current_music_id) if req.current_music_id else None
    cur_m = (im.source_section_uid(req.current_music_section_id)
             if req.current_music_section_id else None)
    out = svc.recommend(_user_ref(req.user_id), req.limit, _exclude(req),
                        True, None, cur_t, cur_m)
    return to_response(req.recommendation_id, out, req.limit, False)


@router.post("/recommendations/daily", response_model=RecommendationResponse,
             responses=ERRORS, summary="오늘의 추천 (곡 목록)")
def daily(req: DailyRequest) -> RecommendationResponse:
    svc = _svc()
    out = svc.recommend(_user_ref(req.user_id), req.limit, _exclude(req),
                        True, None, None, None)
    return to_response(req.recommendation_id, out, req.limit, False)


# 자연어 검색은 외부 해석기가 있어야 조건을 뽑는다. 해석기 없이 열어 두면 사용자가 적은
# 조건을 말없이 무시한 채 아무 곡이나 돌려주게 된다 — 조건을 몰래 푸는 것과 같다.
# 그래서 해석기 주소가 없으면 501 로 "아직 준비되지 않았다"고 답한다. 확장은 S15P21A204-219.
NLP_URL = os.environ.get("MUPID_AI_NLP_URL", "").strip()


@router.post("/recommendations/search", response_model=RecommendationResponse,
             responses=ERRORS, summary="자연어 검색 추천")
def search(req: SearchRequest) -> RecommendationResponse:
    if not NLP_URL:
        raise ApiError("SEARCH_NOT_CONFIGURED",
                       "자연어 해석기가 설정되지 않았습니다. "
                       "MUPID_AI_NLP_URL 을 지정하세요. (S15P21A204-219)")
    svc = _svc()
    plan, _error = svc.parse_nlp_query(req.query.strip())
    out = svc.recommend(_user_ref(req.user_id), req.limit, _exclude(req),
                        True, plan, None, None)
    return to_response(req.recommendation_id, out, req.limit, True)


def _pool_keys():
    pool = _svc().CAT.pool
    return (set(pool.track_id.astype(str)), set(pool.moment_id.astype(str)))


@router.get("/id-map", summary="숫자 ID 매핑 적재 상태")
def id_map_status() -> dict:
    st = ids.id_map().stats()
    try:
        tracks, uids = _pool_keys()
        st.update(ids.id_map().readiness(tracks, uids))
    except Exception as e:                       # 색인 미적재 등
        st.update({"ready": False, "readiness_error": f"{type(e).__name__}: {e}"})
    return st


@router.get("/ready", summary="실서비스 준비 완료 여부")
def ready() -> dict:
    """매핑이 하나라도 빠져 있으면 준비 완료가 아니다. 8100 데모와는 무관하다."""
    st = id_map_status()
    if not st.get("ready"):
        raise ApiError("INDEX_NOT_READY",
                       f"숫자 ID 매핑 미완료 — 곡 {st.get('tracks_unmapped', '?')}건, "
                       f"구간 {st.get('sections_unmapped', '?')}건 미매핑")
    return {"ready": True, "tracks": st["tracks"], "sections": st["sections"]}


# ------------------------------------------------------------------ 설치
def install(app: FastAPI, service_module=None) -> None:
    """라우터와 오류 형식을 붙인다. `/internal` 밖 동작은 바꾸지 않는다.

    `service_module` 은 app 을 만든 그 모듈 객체다. 이름으로 다시 import 하면 실행
    방식(`src.m16_service` / `m16_service`)에 따라 다른 사본을 잡는다.
    """
    global _SVC_MOD
    if service_module is not None:
        _SVC_MOD = service_module
    app.include_router(router)
    prev = app.exception_handlers.get(RequestValidationError)

    @app.exception_handler(ids.MappingError)
    async def _mapping(request: Request, exc: ids.MappingError):
        return _err(exc.code, exc.message, 404)

    @app.exception_handler(ApiError)
    async def _api(request: Request, exc: ApiError):
        status = {"NO_CANDIDATES": 404, "INDEX_NOT_READY": 503,
                  "SEARCH_NOT_CONFIGURED": 501, "INTERNAL_ERROR": 500}.get(exc.code, 400)
        return _err(exc.code, exc.message, status)

    @app.exception_handler(RequestValidationError)
    async def _validation(request: Request, exc: RequestValidationError):
        # 데모 경로의 오류 형식은 그대로 둔다. UI 가 그 형식을 읽고 있다.
        if not request.url.path.startswith(PREFIX):
            if prev is not None:
                return await prev(request, exc)
            return await request_validation_exception_handler(request, exc)
        first = (exc.errors() or [{}])[0]
        loc = ".".join(str(x) for x in first.get("loc", ())[1:]) or "(본문)"
        msg = first.get("msg", "형식이 올바르지 않습니다")
        return _err("INVALID_REQUEST", f"{loc}: {msg}")
