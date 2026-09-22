"""실서비스(Spring) 가 호출하는 내부 API. `/internal` 아래에만 둔다.

**이 서버는 숫자 ID 를 다루지 않는다.** 요청도 응답도 AI 문자열 ID(`T000901#2`)를 쓰고,
`music_id`·`music_section_analysis_id` 로의 번역은 Spring 이
`music_section_analysis.external_id`
로 한다 — 제목·아티스트·이미지를 붙이려고 어차피 하는 조회라 비용이 같다.

예외는 `user_id` 하나다. ClickHouse `listen_events` 의 조회 키가 숫자라 번역 없이 쓴다.

추천 로직은 그대로 두고 입출력 경계에서만 형식을 바꾼다 — 이 파일은 `recommend()` 결과를
계약 DTO 로 옮겨 담는 얇은 어댑터일 뿐이고 점수·가중치·필터·중복 제거에 손대지 않는다.

상태는 들고 있지 않는다. 온보딩은 요청에 실려 오고(`app/onboarding.py` 가 어휘를 옮긴다),
행동은 요청마다 ClickHouse 에서 직접 읽는다(`app/clickhouse.py`).
"""
from __future__ import annotations

import hmac
import os
import time
from typing import Optional

from fastapi import APIRouter, FastAPI, Request
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field, StrictInt, StrictStr, model_validator

from . import (catalog_api, clickhouse, metrics, onboarding as onb, paths,
               pinecone_store)
from .log import log

PREFIX = "/internal"
MODEL_VERSION = "mupid-stage1-v1"       # /feedback 이 이미 내보내는 값과 같게 둔다
EMBEDDING_VERSION = "mert-v1-95m"       # 구간 임베딩. 교체하면 닮은 구간 결과가 바뀐다
AUTH_HEADER = "X-Internal-Token"

# **kubelet 은 토큰을 보내지 않는다.** readinessProbe 가 부르는 경로를 인증 뒤에
# 두면 토큰을 켜는 순간 파드가 영영 Ready 가 되지 못한다 — 실제로 겪었다. 새 Pod 가
# 0/1 로 멈추고 maxUnavailable 이 0 이라 배포가 그대로 막힌다.
#
# 이 경로만 연다. 돌려주는 것은 색인 행 수·곡 수·저장소 접속 여부뿐이고 추천
# 결과나 사용자 데이터가 없다. 나머지 `/internal` 은 그대로 토큰을 요구한다.
#
# 프로브 경로를 `/internal` 밖으로 옮기는 방법도 있지만, 그러면 Spring 이 쓰는
# 준비 판정과 오케스트레이터가 보는 것이 갈린다 — 한쪽만 고쳐지는 자리가 생긴다.
PUBLIC_PATHS = frozenset({PREFIX + "/ready"})


# `/internal` 은 Spring 만 부르는 전제이고 지금은 네트워크 격리가 유일한 방어선이다.
# 설정 실수 한 번이면 그대로 열린다. 공유 비밀을 넣어 두면 두 겹이 된다.
#
# **기본은 꺼져 있다.** 켜 두고 Spring 에 넣어 주지 않으면 추천이 통째로 401 이 되므로,
# 값을 넣는 쪽이 켜는 쪽이 되게 한다. 값은 읽기만 하고 응답·로그에 싣지 않는다.
def _expected_token() -> str:
    return os.environ.get("MUPID_AI_INTERNAL_TOKEN", "").strip()
BIGINT_MAX = 2 ** 63 - 1
MAX_LIMIT = 50
# 오늘의 모멘트는 **하루 4개**다. 화면이 네 칸이라서가 아니라, 하루치로 내보내는 수가
# 곧 그날의 추천 품질이 평가되는 단위이기 때문이다 — 개수를 호출자가 정하면 같은 날
# 사용자마다 다른 크기의 "오늘" 이 나가고, 적재된 `daily_recommendations` 끼리 비교가
# 안 된다. `next`·`search` 는 그 단위가 아니므로 종전대로 `limit` 을 받는다.
DAILY_LIMIT = 4
# `music_section_analysis.external_id` 가 VARCHAR(64) 다. 그보다 긴 값은
# MySQL 에 있을 수 없으므로 받아도 번역되지 않는다.
UID_MAX = 64


class ApiError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


def _err(code: str, message: str, status: int = 400) -> JSONResponse:
    return JSONResponse(status_code=status,
                        content={"error_code": code, "message": message})


# ------------------------------------------------------------------ 요청 DTO
class OnboardingInput(BaseModel):
    """요청에 실려 오는 온보딩 답변. **Spring 코드표의 이름 그대로** 보낸다.

    AI 카탈로그 태그로 옮기는 것은 이쪽 일이다(`app/onboarding.py`) — 두 어휘는 한
    건도 겹치지 않아서, 옮기지 않으면 조건이 조용히 사라진다.
    """

    model_config = {"extra": "forbid"}

    moods: list[StrictStr] = Field(default_factory=list)
    genres: list[StrictStr] = Field(default_factory=list)


class _Base(BaseModel):
    """세 경로가 공유하는 부분. Strict 타입이라 문자열 "148" 은 거부된다."""

    model_config = {"extra": "forbid"}

    recommendation_id: StrictInt = Field(..., ge=1, le=BIGINT_MAX,
                                         description="Spring 이 발급하는 BIGINT")
    user_id: StrictInt = Field(..., ge=1)
    limit: StrictInt = Field(5, ge=1, le=MAX_LIMIT)
    exclude_tracks: list[StrictStr] = Field(
        default_factory=list, description="이미 보여 준 곡의 문자열 ID")
    onboarding: Optional[OnboardingInput] = Field(
        None, description="이력이 없는 사용자의 첫 추천 근거. 없으면 콜드스타트다")


class NextRequest(_Base):
    """지금 듣는 곡을 기준으로 이어 듣기.

    곡·구간을 **문자열 ID** 로 받는다. Spring 이 `music_section_analysis.external_id`
    에서 꺼내 보낸다 — 숫자 ID 는 이 서버가 모른다.
    """

    current_track: Optional[StrictStr] = Field(
        None, min_length=1, max_length=UID_MAX, description="예: T000901")
    current_section: Optional[StrictStr] = Field(
        None, min_length=1, max_length=UID_MAX, description="예: T000901#2")
    position_ms: Optional[StrictInt] = Field(
        None, ge=0, description="재생 위치. 현재 추천 로직은 이 값을 쓰지 않는다")


class DailyRequest(_Base):
    """오늘의 모멘트. 개수는 **4개로 고정**이다(`DAILY_LIMIT`).

    `limit` 을 안 보내면 4다. 다른 값을 보내면 **400 으로 거부한다** — 조용히 4로
    깎지 않는다. 깎으면 10개를 요청한 Spring 이 4개를 받고도 그것이 전부인 줄 알아,
    "왜 6개가 비나" 가 AI 쪽 후보 부족(`shortfall`)으로 잘못 읽힌다. 거절은 배포
    한 번에 드러나고, 침묵은 데이터를 며칠 오염시킨 뒤에 드러난다.
    """

    limit: StrictInt = Field(DAILY_LIMIT, ge=1, le=MAX_LIMIT,
                             description=f"보낸다면 {DAILY_LIMIT} 이어야 한다")

    @model_validator(mode="after")
    def _limit_is_fixed(self):
        if self.limit != DAILY_LIMIT:
            raise ValueError(
                f"오늘의 모멘트는 {DAILY_LIMIT}개로 고정이다 — limit 을 빼고 보내거나 "
                f"{DAILY_LIMIT} 로 보낸다 (개수를 바꾸려면 next·search 를 쓴다)")
        return self


class SearchRequest(_Base):
    """자연어 검색. 조건을 **누가 뽑았느냐**에 따라 둘 중 하나를 채운다.

    · `query_plan` — 호출자(Spring 의 LLM)가 이미 QueryPlan 으로 바꿔 보낸 경우.
      해석기가 필요 없다. 쓸 수 있는 태그는 `GET /internal/facets` 가 알려준다.
    · `query` — 원문만 보낸 경우. AI 서버가 `MUPID_AI_NLP_URL` 해석기를 부른다.
      해석기가 없으면 501 이다.

    둘 다 없으면 400 이다. 조건 없는 요청은 `daily`·`next` 로 보내야 한다 — 여기서
    받아 주면 "검색했는데 조건이 안 걸린 결과" 가 나가고, 그게 이 API 의 실패 모드다.
    """

    query: Optional[StrictStr] = Field(None, min_length=1, max_length=500)
    query_plan: Optional[dict] = Field(
        None, description="QueryPlan v1. filters/exclude 만 읽는다")

    @model_validator(mode="after")
    def _one_of(self):
        if not (self.query and self.query.strip()) and self.query_plan is None:
            raise ValueError("query 또는 query_plan 중 하나는 있어야 합니다")
        if self.query_plan is not None:
            self.query_plan = _clean_plan(self.query_plan)
        return self


# ------------------------------------------------------------------ 응답 DTO
class RecommendationItem(BaseModel):
    """추천된 구간 하나.

    **숫자 ID·시간·구간 종류를 싣지 않는다.** 전부 Spring 이 `external_id` 로 MySQL 에서
    가져오는 값이라 두 번 보낼 이유가 없고, 두 벌이 되면 어긋날 자리만 생긴다.

    사람이 읽는 문구도 싣지 않는다 — `reason_code` 를 주고 문장은 Spring 이 만든다.
    """

    external_id: str = Field(..., description="AI 문자열 구간 ID. 예: T000901#2")
    rank_order: int = Field(..., ge=1)
    score: float
    reason_code: str
    # "저장하신 이 구간과 닮아서" 를 만들 수 있는 근거. **사용자 자신의** 구간이라
    # 보여 줘도 새는 것이 없다. 모델 점수·확률은 계속 내보내지 않는다.
    ref_external_id: Optional[str] = Field(
        None, description="이 추천의 근거가 된, 사용자가 좋아한 구간")


class SimilarRequest(BaseModel):
    """기준 구간과 닮은 구간 찾기. 개인화가 없어 `user_id` 를 받지 않는다."""

    model_config = {"extra": "forbid"}

    external_id: StrictStr = Field(..., min_length=1, max_length=UID_MAX)
    limit: StrictInt = Field(5, ge=1, le=MAX_LIMIT)
    exclude_tracks: list[StrictStr] = Field(default_factory=list)


class SimilarItem(BaseModel):
    external_id: str
    rank_order: int = Field(..., ge=1)
    similarity: float = Field(..., description="코사인. 개인 취향이 섞이지 않은 값이다")


class SimilarResponse(BaseModel):
    external_id: str = Field(..., description="기준 구간. 요청 값을 그대로 돌려준다")
    items: list[SimilarItem]
    shortfall: int = Field(..., ge=0)
    embedding_version: str


class RecommendationResponse(BaseModel):
    recommendation_id: int
    items: list[RecommendationItem]
    shortfall: int = Field(..., ge=0)
    model_version: str


def _clean_plan(plan: dict) -> dict:
    """바깥에서 온 QueryPlan 을 읽는 키만 남긴다.

    `filter_query_pool` 은 `filters`·`exclude` 만 본다. `_clap_embedding` 같은 밑줄 키는
    **내부 전용**이다 — 바깥에서 넣을 수 있게 두면 호출자가 의미 점수 벡터를 직접
    주입하게 된다.
    """
    if not isinstance(plan, dict):
        raise ValueError("query_plan 은 객체여야 합니다")
    out: dict = {}
    for key in ("filters", "exclude"):
        v = plan.get(key)
        if v is None:
            continue
        if not isinstance(v, dict):
            raise ValueError(f"query_plan.{key} 는 객체여야 합니다")
        out[key] = {k: val for k, val in v.items() if not str(k).startswith("_")}
    if not out:
        raise ValueError("query_plan 에 filters 또는 exclude 가 있어야 합니다")
    return out


class ErrorResponse(BaseModel):
    error_code: str
    message: str


ERRORS = {400: {"model": ErrorResponse}, 401: {"model": ErrorResponse},
          404: {"model": ErrorResponse}, 500: {"model": ErrorResponse},
          503: {"model": ErrorResponse}}


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


def _track_of(external_id: str) -> str:
    """구간 UID 의 `#` 앞이 곧 곡 문자열 ID 다 (Q3 — MySQL 에 곡 문자열 ID 를 두지 않는다)."""
    return external_id.split("#", 1)[0]


def to_response(rec_id: int, out: dict, limit: int,
                has_query: bool) -> RecommendationResponse:
    """`recommend()` 결과를 계약 DTO 로 옮긴다. 순서·점수는 건드리지 않는다.

    **숫자 ID 로 바꾸지 않는다.** Spring 이 `external_id` 로 MySQL 에서 찾는다 — 제목·
    아티스트·이미지를 붙이려고 어차피 하는 조회라 비용이 같다.
    """
    query_source = str(out.get("diagnostics", {}).get("query_source", ""))
    metrics.observe_query_source(query_source)
    items: list[RecommendationItem] = []
    for r in out.get("results", []):
        code = _reason_code(r, has_query, query_source)
        metrics.observe_reason(code)
        ref = r.get("matched_dna_rep")
        items.append(RecommendationItem(
            external_id=str(r["moment_id"]),
            rank_order=int(r["rank"]),
            score=float(r["final_score"]),
            reason_code=code,
            ref_external_id=(str(ref) if ref else None)))
    items, dropped = _dedupe_by_track(items)
    if dropped:
        # 추천기가 이미 곡 단위로 거르므로 여기서 걸리면 그쪽이 샌 것이다.
        log(f"같은 곡이 {dropped}건 겹쳐 정리했다 — 추천기 중복 제거 확인 필요")
    if not items:
        raise ApiError("NO_CANDIDATES", "해당되는 노래가 없습니다.")
    # shortfall 은 추천기가 이미 센 값을 쓰되, 경계에서 떨군 것도 부족분이다.
    # 추천기가 센 값만 믿으면 "10개 달라고 했는데 7개 왔고 shortfall 은 0" 이 된다.
    short = out.get("diagnostics", {}).get("shortfall")
    short = max(int(short) if short is not None else 0, limit - len(items), 0)
    return RecommendationResponse(recommendation_id=rec_id, items=items,
                                  shortfall=short, model_version=MODEL_VERSION)


def _dedupe_by_track(
        items: list["RecommendationItem"]) -> tuple[list["RecommendationItem"], int]:
    """같은 곡은 한 번만 내보낸다. 최종 점수가 가장 높은 구간 하나만 남긴다.

    남은 것들의 상대 순서는 그대로 두고 `rank_order` 만 1부터 다시 붙인다.
    """
    best: dict[str, "RecommendationItem"] = {}
    order: list[str] = []
    for it in items:
        t = _track_of(it.external_id)
        cur = best.get(t)
        if cur is None:
            best[t] = it
            order.append(t)
        elif it.score > cur.score:
            best[t] = it
    kept = [best[t] for t in order]
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
    # 벡터를 갈아 끼우는 동안에는 **분명한 오류**를 돌려준다. 그때 candidate 와
    # raw 가 서로 어긋나 있어서, 그 상태의 추천은 오류가 아니라 그냥 이상한 결과로
    # 나온다. 로컬 옛 벡터로 조용히 내려가지도 않는다.
    paused = pinecone_store.pause_state()
    if paused is not None:
        raise ApiError("RELOAD_IN_PROGRESS",
                       "추천 벡터를 갱신하는 중입니다. 잠시 뒤 다시 시도해 주세요.")
    svc = _svc_mod()
    if getattr(svc, "CAT", None) is None or len(svc.CAT.pool) == 0:
        raise ApiError("INDEX_NOT_READY", "추천 색인이 아직 준비되지 않았습니다.")
    return svc


# 행동을 얼마나 거슬러 읽을 것인가. Early Skip 감점의 반감기가 7일이라 90일이면
# 그 너머는 사실상 0 이다. 더 넓히면 조회만 무거워진다.
BEHAVIOR_WINDOW_SEC = 90 * 86400


def _behavior_events(user_id: int) -> Optional[list]:
    """ClickHouse 에서 행동을 읽어 온다. **요청마다 직접 조회한다.**

    실패해도 추천을 죽이지 않는다 — 대신 빈 목록으로 계산하고 그 사실을 지표에
    남긴다. 조용히 파일로 물러나면 "연결은 됐는데 개인화만 0" 인 상태를 아무도
    눈치채지 못한다.

    주소가 아예 없으면 `None` 을 돌려 종전의 파일 경로로 간다(개발·오프라인).

    조인 결과가 **0행일 때만** 원본 이벤트를 한 번 더 세어 `mapping_empty` 를 가른다.
    정상 경로(1행 이상)에는 쿼리를 늘리지 않는다 — 추천은 사용자가 기다리는 경로다.
    """
    since = time.time() - BEHAVIOR_WINDOW_SEC
    if not clickhouse.configured():
        metrics.observe_behavior_source("file")
        return None
    try:
        rows = clickhouse.behavior_events(user_id, since)
    except Exception as e:                       # 연결·쿼리 실패
        metrics.observe_behavior_source("unavailable")
        log(f"행동 조회 실패 — 개인화 없이 추천한다 ({type(e).__name__}: {e})")
        return []
    metrics.observe_behavior_source(
        "clickhouse" if rows else _empty_reason(user_id, since))
    return rows


def _empty_reason(user_id: int, since: float) -> str:
    """조인 결과가 0행이다. **안 들은 것과 못 이은 것을 가른다.**

    원본 이벤트가 있는데 조인이 0이면 `v_track_sections_current` 가 그 곡을 되돌리지
    못한 것이다 — 색인과 ClickHouse 딕셔너리가 어긋났다는 뜻이고, 한 사용자의 문제가
    아니라 전 사용자의 개인화가 빠지는 문제다. 그것을 "행동 없음" 으로 세면 콜드스타트
    비율만 조금 올라간 것처럼 보인다.

    세는 것 자체가 실패하면 **`mapping_empty` 라고 주장하지 않는다.** 조인은 성공했고
    (0행) 그 뒤 판정만 못 한 것이라 `clickhouse` 로 남긴다.
    """
    try:
        if clickhouse.raw_event_count(user_id, since) > 0:
            log(f"행동 {user_id}: 원본 이벤트는 있는데 곡 매핑이 0건이다 "
                f"— v_track_sections_current 를 본다")
            return "mapping_empty"
    except Exception as e:
        log(f"원본 이벤트 수를 세지 못했다 — 매핑 공백 여부는 미판정 "
            f"({type(e).__name__}: {e})")
    return "clickhouse"


def _genre_catalog(svc) -> list[str]:
    """AI 카탈로그의 장르 어휘. 없으면 빈 목록이고, 그때는 장르가 반영되지 않는다."""
    try:
        facets = svc.catalog_facets()
        genre = [str(x["value"]) for x in facets.get("genre", [])]
        style = [str(x["value"]) for x in facets.get("style", [])]
        return list(set(genre + style))
    except Exception as e:
        log(f"장르 어휘를 읽지 못했다 — 온보딩 장르가 반영되지 않는다 ({type(e).__name__})")
        return []


def _onboarding(req: "_Base", svc) -> Optional[dict]:
    """요청의 온보딩을 카탈로그 어휘로 옮긴다. 없으면 None (파일 경로로 간다)."""
    if req.onboarding is None:
        return None
    ans = onb.from_request(req.onboarding.model_dump(), _genre_catalog(svc))
    if ans is None:
        return ans
    # 축을 **따로** 본다. 둘 다 비었을 때만 보면, 무드는 되고 장르만 안 되는
    # 경우가 안 보인다 — 코드표 세분류 장르 45종(49~93)이 지금 그 상태다
    # (`alternative_rock` 대 `Rock---Alternative Rock`). 그 축만 조용히 빠진 채
    # 추천은 정상으로 나가므로 응답으로는 찾을 수 없다.
    failed = [axis for axis in ("mood", "genre")
              if ans[f"{axis}_raw"] and not ans[axis]]
    for axis in failed:
        metrics.observe_onboarding_unmapped(axis)
        log(f"온보딩 {axis} 를 하나도 옮기지 못했다 — {ans[f'{axis}_raw']}")
    if len(failed) == 2:
        # **두 축 모두** 실패했다. 축별 셈만 보면 "mood 1건, genre 1건" 이 서로 다른
        # 사용자인지 같은 사용자인지 알 수 없는데, 같은 사용자면 그 사람은 씨앗이
        # 통째로 없다(`recommender.onboarding_seeds` 가 빈 씨앗을 돌려준다).
        # 실측으로 기존 회원 8명 중 6명이 이쪽이었다.
        metrics.observe_onboarding_unmapped("both")
    return ans


def _user_ref(user_id: int) -> str:
    """데모 저장소는 문자열 사용자 키를 쓴다. 숫자 user_id 를 그 이름공간에 얹는다."""
    return f"internal:{int(user_id)}"


def _exclude(req: _Base) -> set[str]:
    """이미 보여 준 곡. 문자열로 오므로 번역이 필요 없다."""
    return {str(x) for x in req.exclude_tracks}


# ------------------------------------------------------------------ 라우터
router = APIRouter(prefix=PREFIX, tags=["internal"])


@router.post("/recommendations/next", response_model=RecommendationResponse,
             responses=ERRORS, summary="이어 듣기 추천")
def next_(req: NextRequest) -> RecommendationResponse:
    svc = _svc()
    # 문자열로 왔으므로 번역이 없다. 구간만 주고 곡을 안 줬으면 `#` 앞에서 뽑는다.
    cur_m = req.current_section
    cur_t = req.current_track or (_track_of(cur_m) if cur_m else None)
    out = svc.recommend(_user_ref(req.user_id), req.limit, _exclude(req),
                        True, None, cur_t, cur_m,
                        beh_events=_behavior_events(req.user_id),
                        onboarding=_onboarding(req, svc))
    return to_response(req.recommendation_id, out, req.limit, False)


@router.post("/recommendations/daily", response_model=RecommendationResponse,
             responses=ERRORS, summary=f"오늘의 모멘트 ({DAILY_LIMIT}개 고정)")
def daily(req: DailyRequest) -> RecommendationResponse:
    """오늘의 모멘트 4개. 개수는 요청이 아니라 **여기가** 정한다.

    `req.limit` 을 쓰지 않고 `DAILY_LIMIT` 을 그대로 넘긴다. 검증에서 이미 4만
    통과하지만, 개수의 출처를 한 곳으로 두면 나중에 값을 바꿀 때 상수만 고치면 된다.
    """
    svc = _svc()
    out = svc.recommend(_user_ref(req.user_id), DAILY_LIMIT, _exclude(req),
                        True, None, None, None,
                        beh_events=_behavior_events(req.user_id),
                        onboarding=_onboarding(req, svc))
    return to_response(req.recommendation_id, out, DAILY_LIMIT, False)


# 자연어 검색은 외부 해석기가 있어야 조건을 뽑는다. 해석기 없이 열어 두면 사용자가 적은
# 조건을 말없이 무시한 채 아무 곡이나 돌려주게 된다 — 조건을 몰래 푸는 것과 같다.
# 설정 여부 판단과 실제 호출은 **같은 환경변수**(MUPID_AI_NLP_URL)를 본다. 확장은
# S15P21A204-219.


@router.post("/recommendations/search", response_model=RecommendationResponse,
             responses=ERRORS, summary="자연어 검색 추천")
def search(req: SearchRequest) -> RecommendationResponse:
    svc = _svc_mod()
    if req.query_plan is not None:
        # 호출자가 이미 조건을 뽑아 보냈다. 해석기가 필요 없다.
        _svc()                              # 색인 준비 확인
        plan = req.query_plan
    else:
        if not getattr(svc, "NLP_PARSE_URL", ""):
            raise ApiError("SEARCH_NOT_CONFIGURED",
                           "자연어 해석기가 설정되지 않았습니다. "
                           "MUPID_AI_NLP_URL 을 지정하거나 query_plan 을 보내세요. "
                           "(S15P21A204-219)")
        _svc()                              # 색인 준비 확인
        plan, error = svc.parse_nlp_query(req.query.strip())
        if error is not None:
            # 조건을 뽑지 못했다. 일반 추천으로 조용히 넘어가지 않는다.
            raise ApiError(*error)
    out = svc.recommend(_user_ref(req.user_id), req.limit, _exclude(req),
                        True, plan, None, None,
                        beh_events=_behavior_events(req.user_id),
                        onboarding=_onboarding(req, _svc()))
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
    fields["vocal"]["values"] = vals("vocal")
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


@router.post("/similar", response_model=SimilarResponse, responses=ERRORS,
             summary="기준 구간과 닮은 다른 곡의 구간")
def similar(req: SimilarRequest) -> SimilarResponse:
    """`recommend` 와 달리 사용자 취향을 섞지 않는다. 같은 질문에 늘 같은 답이다."""
    svc = _svc()
    rows = svc.similar_moments(req.external_id, req.limit,
                               {str(x) for x in req.exclude_tracks})
    items = [SimilarItem(external_id=str(r["moment_id"]), rank_order=i + 1,
                         similarity=float(r["similarity"]))
             for i, r in enumerate(rows)]
    if not items:
        raise ApiError("NO_CANDIDATES", "닮은 구간을 찾지 못했습니다.")
    return SimilarResponse(external_id=req.external_id, items=items,
                           shortfall=max(0, req.limit - len(items)),
                           embedding_version=EMBEDDING_VERSION)


@router.get("/metrics", summary="운영 지표 (프로세스 내 카운터)")
def metrics_view() -> dict:
    """재시작하면 0 이다. 장기 추세는 수집기가 주기적으로 읽어 쌓는다.

    `/internal/ready` 상태도 함께 싣는다 — 지표만 보고 "왜 실패율이 1.0 인가" 를
    판단하려면 준비 여부가 같은 화면에 있어야 한다.
    """
    snap = metrics.snapshot()
    try:
        _svc()
        snap["ready"] = {"ok": True, "behavior_store": behavior_store_status()}
    except Exception as e:                       # 색인 미적재 등
        snap["ready"] = {"ok": False, "error": f"{type(e).__name__}: {e}"}
    return snap


def behavior_store_status() -> dict:
    """행동 저장소(ClickHouse)에 닿는가. `/internal/ready` 와 지표가 함께 쓴다."""
    if not clickhouse.configured():
        # 주소가 없으면 개인화 없이 도는 것이 정상이다(개발·오프라인).
        return {"ok": True, "configured": False}
    try:
        clickhouse.query("SELECT 1")
        return {"ok": True, "configured": True}
    except Exception as e:
        return {"ok": False, "configured": True, "error": f"{type(e).__name__}: {e}"}


@router.get("/ready", summary="실서비스 준비 완료 여부")
def ready() -> dict:
    """색인이 올라왔는가.

    **숫자 ID 매핑은 더 이상 보지 않는다.** 번역을 Spring 이 하므로 이 서버가 확인할
    매핑이 없다.

    행동 저장소 상태는 **싣기만 하고 막지는 않는다.** ClickHouse 가 죽으면 개인화가
    빠진 콜드스타트 추천이 나가지만 추천 자체는 된다. 그때 준비 완료를 거두면 모든
    파드가 한꺼번에 빠져 품질 저하가 장애로 번진다.
    """
    # 갱신 중이면 여기서 RELOAD_IN_PROGRESS(503) 로 나간다 — readinessProbe 가
    # 그것을 보고 파드를 엔드포인트에서 뺀다. 재개는 배치가 검증을 통과한 뒤에만
    # 표시를 지우면서 일어난다.
    svc = _svc()                       # 색인 미적재면 INDEX_NOT_READY (503)
    return {"ready": True,
            "tracks": int(svc.CAT.pool.track_id.nunique()),
            "moments": int(len(svc.CAT.pool)),
            # 곡·순간 메타데이터의 출처. 이것이 비면 장르·무드·보컬 조건이
            # 통째로 빈 채 추천이 나가므로, 준비 판정을 보는 자리에서 함께 읽는다.
            "metadata_source": catalog_api.status(),
            # 벡터 검색 갈래. Pinecone 장애는 여기서 드러나야 하고, 로컬 옛 벡터로
            # 조용히 내려가지 않는다.
            "vector_search": {"backend": svc.vector_backend(),
                              **pinecone_store.settings()},
            "behavior_store": behavior_store_status(),
            "paths": paths.check()}


def refresh_catalog() -> dict:
    """카탈로그를 다시 읽고, 성공하면 색인과 다시 묶는다. **구현은 여기 하나다.**

    부르는 자리가 둘이다 — 운영자가 치는 `/internal/catalog/refresh` 와 TTL 주기
    갱신. 두 곳이 각자 들고 있으면 한쪽만 고쳐져 "손으로 부르면 반영되는데 주기로는
    안 된다" 같은 상태가 생긴다.

    실패는 두 단계로 갈린다.

      · 받아오기 실패 — 옛 카탈로그를 그대로 쓴다(`catalog_api.refresh`).
      · 받아왔는데 색인과 못 묶음 — **옛 `Catalog` 객체를 그대로 둔다.** 반쯤 바뀐
        상태로 추천하지 않는다. 다만 `catalog_api` 쪽 스냅샷은 이미 새것이므로,
        다음 갱신에서 다시 묶기를 시도한다.
    """
    result = catalog_api.refresh()
    svc = _svc_mod()
    if result["ok"] and getattr(svc, "CAT", None) is not None:
        # 색인은 그대로 두고 **메타데이터만** 갈아 끼운다. 판을 다시 읽지 않으므로
        # 벡터·행 번호는 건드리지 않는다 — 어긋날 자리가 생기지 않는다.
        try:
            svc.CAT = svc.Catalog()
            result["reloaded"] = True
        except Exception as e:
            result["reloaded"] = False
            result["reload_error"] = f"{type(e).__name__}: {e}"
            log(f"카탈로그 갱신 후 색인 재구성 실패 — {result['reload_error']}")
    else:
        result["reloaded"] = False
    return result


@router.post("/catalog/refresh", summary="곡·순간 메타데이터를 다시 읽는다")
def catalog_refresh() -> dict:
    """MySQL 쪽이 바뀐 것을 **재기동 없이** 반영한다.

    곡을 지우거나 장르를 고친 뒤 부른다. 시간으로도 갱신되게 하려면
    `MUPID_AI_CATALOG_TTL_SEC` 를 켠다 — 기본은 꺼져 있다.

    **실패해도 200 이다.** 돌려주는 `ok` 가 거짓이면 갱신을 못 했다는 뜻이고,
    그때도 들고 있던 카탈로그로 추천은 계속된다. 여기서 500 을 내면 부르는 쪽이
    재시도하다 서비스를 멈추게 만들기 쉬운데, 멈춰야 할 상황이 아니다.

    토큰 검사 안에 있다(`PUBLIC_PATHS` 밖). 카탈로그를 다시 읽게 하는 것은 Spring 을
    향한 호출을 일으키는 일이라 아무나 부를 자리가 아니다.
    """
    return refresh_catalog()


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

    @app.middleware("http")
    async def _observe(request: Request, call_next):
        if not request.url.path.startswith(PREFIX):
            return await call_next(request)
        expected = _expected_token()
        if expected and request.url.path not in PUBLIC_PATHS:
            got = request.headers.get(AUTH_HEADER, "")
            # 상수 시간 비교. 길이·내용이 응답 시간으로 새지 않게 한다.
            if not hmac.compare_digest(got, expected):
                metrics.observe_request(request.url.path, 0.0, "HTTP_401")
                return _err("UNAUTHORIZED", "내부 호출 토큰이 올바르지 않습니다.", 401)
        t0 = time.perf_counter()
        response = await call_next(request)
        # 오류 코드는 예외 처리기가 본문에 실어 둔 것을 상태 코드로 갈음한다.
        # 본문을 여기서 읽으면 스트리밍 응답을 소비해 버린다.
        code = None if response.status_code < 400 else f"HTTP_{response.status_code}"
        metrics.observe_request(request.url.path, time.perf_counter() - t0, code)
        return response

    prev = app.exception_handlers.get(RequestValidationError)

    @app.exception_handler(ApiError)
    async def _api(request: Request, exc: ApiError):
        status = {"NO_CANDIDATES": 404, "INDEX_NOT_READY": 503,
                  "RELOAD_IN_PROGRESS": 503,
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
