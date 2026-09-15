"""실서비스(Spring) 가 호출하는 내부 API. `/internal` 아래에만 둔다.

기존 8100 데모 API 는 문자열 ID 와 초 단위 실수를 쓴다. 실서비스 계약은 숫자 ID 와 정수
밀리초다. **추천 로직은 그대로 두고 입출력 경계에서만 형식을 바꾼다** — 이 파일은 기존
`recommend()` 결과를 공통 DTO 로 옮겨 담는 얇은 어댑터일 뿐이고 점수·가중치·필터·중복
제거에 손대지 않는다.

문자열 ID(`JAMENDO_1083659`, `JAMENDO_1083659#3`)는 출처 추적용으로 내부에만 남고 응답에
나가지 않는다. 숫자 매핑이 없으면 만들어내지 않고 오류를 낸다 → `ids.py`.
"""
from __future__ import annotations

from typing import Optional

from fastapi import APIRouter, FastAPI, Request
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, StrictInt, StrictStr, model_validator

from . import ids, paths

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
def _reason_code(item: dict, has_query: bool, query_source: str) -> str:
    """기존 결과가 이미 들고 있는 값에서 사유 코드를 읽어낸다. 새로 계산하지 않는다.

    `query_source` 는 추천기가 무엇으로 질의 벡터를 만들었는지다(`labels`/`onboarding`/
    `none`). 이걸 보지 않으면 온보딩으로 뽑은 추천까지 `COLD_START` 로 집계되어,
    "콜드스타트 비율" 지표가 온보딩이 정상 작동할 때도 높게 나온다.
    """
    if has_query:
        return ("QUERY_SEMANTIC_MATCH" if item.get("clap_score") is not None
                else "QUERY_FILTER_MATCH")
    if item.get("slot_source") == "explore":
        return "EXPLORE_NEW"
    if item.get("matched_dna"):
        return ("ONBOARDING_MATCH" if query_source == "onboarding"
                else "SIMILAR_PREFERRED_MOMENT")
    return "COLD_START"


# 사용자에게 보이는 문장은 **코드에서 만든다.** 추천기 내부 `reason` 문자열에는 내부
# ID(`dna3`, `JAMENDO_1083659#2`)와 모델 수치(유사도·확률·점수)가 들어 있다. 프론트가 그대로
# 표시하므로 응답에 실으면 그게 곧 노출이다. 내부 문자열은 진단용으로 두고 내보내지 않는다.
#
# Spring 이 자체 문구를 쓰고 싶으면 `reason_code` 로 갈아끼우면 된다. 여기 문장은 그때까지의
# 안전한 기본값이고, 모르는 코드에서도 빈 문자열이 나가지 않게 폴백을 둔다.
_SECTION_KO = {"INTRO": "인트로", "VERSE": "벌스", "CHORUS": "후렴", "BRIDGE": "브릿지",
               "INST": "연주", "SOLO": "솔로", "OUTRO": "아웃트로", "OTHER": "이"}

_REASON_TEXT = {
    "QUERY_SEMANTIC_MATCH": "요청하신 분위기에 가까운 {sec} 구간이에요.",
    "QUERY_FILTER_MATCH": "요청하신 조건에 맞는 {sec} 구간이에요.",
    "SIMILAR_PREFERRED_MOMENT": "최근 좋아한 구간과 비슷한 {sec} 구간이에요.",
    "ONBOARDING_MATCH": "처음 고른 취향에 맞는 {sec} 구간이에요.",
    "EXPLORE_NEW": "새로 들어볼 만한 {sec} 구간이에요.",
    "COLD_START": "많이 듣는 {sec} 구간부터 들려드려요.",
}
_REASON_FALLBACK = "취향에 맞을 것 같은 구간이에요."


def _reason_text(code: str, section_label: str) -> str:
    tpl = _REASON_TEXT.get(code)
    if tpl is None:
        return _REASON_FALLBACK
    return tpl.format(sec=_SECTION_KO.get(section_label, "이"))


def to_response(rec_id: int, out: dict, limit: int,
                has_query: bool) -> RecommendationResponse:
    """`recommend()` 결과를 공통 DTO 로 옮긴다. 순서·점수는 건드리지 않는다."""
    im = ids.id_map()
    query_source = str(out.get("diagnostics", {}).get("query_source", ""))
    items: list[RecommendationItem] = []
    for r in out.get("results", []):
        src_t, src_s = str(r["track_id"]), str(r["moment_id"])
        mid = im.music_id(src_t)                      # 없으면 MappingError
        sid = im.music_section_id(src_s)
        s_ms, e_ms = ids.to_ms(r["start_sec"]), ids.to_ms(r["end_sec"])
        if e_ms <= s_ms:
            raise ApiError("INVALID_TIME_RANGE",
                           f"구간 시간이 뒤집혔거나 길이가 0 입니다: {src_s}")
        # ERD ENUM 은 대문자다. 추천기는 소문자를 만들므로 경계에서만 맞춘다.
        label = str(r.get("section_label", "")).upper()
        code = _reason_code(r, has_query, query_source)
        items.append(RecommendationItem(
            music_id=mid, music_section_id=sid,
            start_time_ms=s_ms, end_time_ms=e_ms,
            section_label=label,
            rank_order=int(r["rank"]), score=float(r["final_score"]),
            reason_code=code, reason=_reason_text(code, label)))
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


def _svc_mod():
    """색인 준비 여부와 무관하게 서비스 모듈만 가져온다."""
    svc = _SVC_MOD
    if svc is None:                       # 직접 호출 등 예외 상황의 최후 수단
        from . import recommender as svc  # noqa: PLC0415
    return svc


def _svc():
    svc = _svc_mod()
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
# 설정 여부 판단과 실제 호출은 **같은 환경변수**(MUPID_AI_NLP_URL)를 본다. 확장은
# S15P21A204-219.


@router.post("/recommendations/search", response_model=RecommendationResponse,
             responses=ERRORS, summary="자연어 검색 추천")
def search(req: SearchRequest) -> RecommendationResponse:
    svc = _svc_mod()
    if not getattr(svc, "NLP_PARSE_URL", ""):
        raise ApiError("SEARCH_NOT_CONFIGURED",
                       "자연어 해석기가 설정되지 않았습니다. "
                       "MUPID_AI_NLP_URL 을 지정하세요. (S15P21A204-219)")
    _svc()                                  # 색인 준비 확인
    plan, error = svc.parse_nlp_query(req.query.strip())
    if error is not None:
        # 조건을 뽑지 못했다. 일반 추천으로 조용히 넘어가지 않는다.
        raise ApiError(*error)
    out = svc.recommend(_user_ref(req.user_id), req.limit, _exclude(req),
                        True, plan, None, None)
    return to_response(req.recommendation_id, out, req.limit, True)


# ------------------------------------------------------------------ 태그 어휘
# 대화형 추천은 자연어를 QueryPlan 으로 바꿔야 한다. 그 변환을 누가 하든(외부 해석기든
# Spring 의 LLM 이든) **이 카탈로그에 실제로 있는 말**을 써야 조건이 걸린다. 없는 태그를
# 지어내면 `filter_query_pool` 이 "어휘에 없음" 으로 후보를 0 으로 만든다.
#
# 필드별 적용 방식이 다르다는 점이 중요하다. 이걸 알려주지 않으면 LLM 이 `mood` 로 거를 수
# 있다고 믿고 조건을 만드는데, 실제로는 걸리지 않는다 — 조건이 조용히 사라지는 것과 같다.
_ENFORCEMENT = {
    "genre": "gate", "language": "gate", "vocal": "gate", "instrument": "gate",
    "flow": "gate",
    # filters.mood 는 게이트가 아니다. 무드는 추론 폭이 커서 의미 점수로만 반영한다.
    # 반대로 exclude.mood 는 게이트다 ("이건 빼줘" 는 명시 조건으로 본다).
    "mood": "score_only",
    "bpm_min": "gate", "bpm_max": "gate",
    # 기준 곡이 있어야 방향을 정할 수 있다. 없으면 무시되고 진단에 남는다.
    "tempo_direction": "gate_with_reference", "energy_direction": "gate_with_reference",
    # 카탈로그에 해당 필드가 없다. 받기는 하지만 아무 일도 하지 않는다.
    "activity": "ignored", "goal": "ignored",
}
_EXCLUDABLE = {"genre", "language", "vocal", "instrument", "mood", "flow"}
_FACETS_CACHE: dict = {}


def _facets_payload(svc) -> dict:
    """카탈로그가 기동 후 바뀌지 않으므로 한 번만 만든다."""
    if _FACETS_CACHE:
        return _FACETS_CACHE
    fac = svc.catalog_facets()
    df = fac["_df"]
    bpm = [float(v) for v in df.bpm if float(v or 0) > 0]

    def vals(key):
        return [{"value": x["value"], "tracks": x.get("tracks", x.get("moments", 0))}
                for x in fac.get(key, [])]

    fields: dict = {}
    for name in _ENFORCEMENT:
        fields[name] = {"enforcement": _ENFORCEMENT[name],
                        "excludable": name in _EXCLUDABLE}
    fields["genre"]["values"] = vals("genre")
    # 장르 게이트는 genre 와 style 양쪽에 맞춰 본다. 둘 다 골라도 된다.
    fields["genre"]["style_values"] = vals("style")
    fields["language"]["values"] = vals("language")
    fields["vocal"]["values"] = vals("vocal_type")
    fields["instrument"]["values"] = vals("instrument")
    fields["mood"]["values"] = vals("mood")
    fields["flow"]["values"] = [{"value": x["value"], "moments": x.get("moments", 0)}
                                for x in fac.get("section_label", [])]
    fields["bpm_min"]["range"] = fields["bpm_max"]["range"] = (
        [round(min(bpm)), round(max(bpm))] if bpm else None)
    fields["tempo_direction"]["values"] = [{"value": "up"}, {"value": "down"}]
    fields["energy_direction"]["values"] = [{"value": "up"}, {"value": "down"}]

    _FACETS_CACHE.update({
        "catalog": {"tracks": int(svc.CAT.pool.track_id.nunique()),
                    "moments": int(len(svc.CAT.pool))},
        "fields": fields,
        "example": {"filters": {"genre": ["pop"], "instrument": ["piano"],
                                "vocal": ["female"], "flow": ["chorus"]},
                    "exclude": {"mood": ["aggressive"]}},
    })
    return _FACETS_CACHE


@router.get("/facets", summary="QueryPlan 에 쓸 수 있는 태그 어휘")
def facets() -> dict:
    """자연어를 QueryPlan 으로 바꾸는 쪽이 읽는다. 카탈로그에서 직접 만든다."""
    return _facets_payload(_svc())


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
        pc = paths.check()
        idm = pc["id_map"]
        # 경로가 틀린 것인지 적재가 덜 된 것인지 응답만 보고 갈라야 한다.
        where = ("매핑표 파일이 없습니다" if not idm["exists"] else "매핑이 부족합니다")
        raise ApiError("INDEX_NOT_READY",
                       f"숫자 ID 매핑 미완료 — {where}. "
                       f"곡 {st.get('tracks_unmapped', '?')}건, "
                       f"구간 {st.get('sections_unmapped', '?')}건 미매핑. "
                       f"id_map={idm['path']} (존재 {idm['exists']}, "
                       f"MUPID_ID_MAP 지정 {idm['overridden']}), "
                       f"artifact_dir={pc['artifact_dir']}")
    return {"ready": True, "tracks": st["tracks"], "sections": st["sections"],
            "paths": paths.check()}


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
