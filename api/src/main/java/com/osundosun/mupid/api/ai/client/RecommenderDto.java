package com.osundosun.mupid.api.ai.client;

import java.util.List;
import java.util.Map;

/**
 * 추천 서버 `/internal` 계약의 요청·응답.
 *
 * <p>필드 이름은 저쪽(`ai_server/app/internal_api.py`)이 정한다. 본문은 snake_case 이고
 * {@link AiJson} 이 변환한다 — 애플리케이션 Jackson 설정에 묶어 두면 누가 그 설정을 바꾸는
 * 순간 계약이 조용히 깨진다. LLM 게이트웨이와 같은 이유다.
 *
 * <p>요청 타입이 엄격하다. {@code "5001"} 같은 문자열이나 실수 {@code limit} 은 400 이다.
 *
 * <h2>곡·구간은 문자열 ID 로 주고받는다</h2>
 *
 * <p>추천 서버는 숫자 ID 를 모른다. 그쪽 색인의 키는 {@code "T000901#2"} 같은 문자열이고,
 * MySQL 의 {@code music_section_analysis.external_id} 가 그 값을 그대로 들고 있다.
 * 번역은 **우리가** 한다 → {@code MusicSectionService}.
 *
 * <p>응답에 제목·아티스트·이미지를 붙이려면 어차피 MySQL 을 조회해야 한다. 조회 조건을
 * {@code music_id} 대신 {@code external_id} 로 두는 것뿐이라 쿼리 수는 같다.
 *
 * <p>예외는 {@code userId} 다. 추천 서버가 ClickHouse 를 그 번호로 조회한다.
 */
public final class RecommenderDto {

    private RecommenderDto() {
    }

    /**
     * 이어 듣기·오늘의 추천 요청.
     *
     * @param recommendationId Spring 이 발급하는 BIGINT. 추천 한 묶음을 가리킨다
     * @param excludeTracks    이미 보여 준 곡의 문자열 ID. 지금 듣는 곡도 여기 넣는다
     * @param onboarding       이력이 없는 사용자의 첫 추천 근거. 없으면 콜드스타트다
     */
    public record NextRequest(
        long recommendationId,
        int userId,
        int limit,
        List<String> excludeTracks,
        String currentTrack,
        String currentSection,
        Onboarding onboarding
    ) {
    }

    /**
     * 온보딩 답변. **우리 코드표의 이름 그대로** 보낸다.
     *
     * <p>AI 카탈로그 태그로 옮기는 것은 추천 서버 몫이다. 두 어휘는 한 건도 겹치지
     * 않아서, 옮기지 않으면 조건이 조용히 사라진다.
     */
    public record Onboarding(
        List<String> moods,
        List<String> genres
    ) {
    }

    /**
     * 자연어 검색 요청.
     *
     * <p>{@code queryPlan} 과 {@code query} 중 **하나는 있어야 한다.** 둘 다 없으면 400 이다.
     *
     * <ul>
     *   <li>{@code queryPlan} — 조건을 우리가 이미 뽑아 보내는 경우. 해석기가 필요 없다
     *   <li>{@code query} — 원문만 보내는 경우. 추천 서버에 해석기가 없으면 501 이다
     * </ul>
     *
     * <p>우리는 {@code queryPlan} 만 쓴다. 대화 모델이 조건을 뽑아 주기 때문이다.
     */
    public record SearchRequest(
        long recommendationId,
        int userId,
        int limit,
        List<String> excludeTracks,
        String query,
        Map<String, Object> queryPlan,
        Onboarding onboarding
    ) {
    }

    public record Response(
        long recommendationId,
        List<Item> items,
        /** 요청 수를 못 채운 만큼. 임의로 채우지 않는다는 뜻이다. */
        int shortfall,
        String modelVersion
    ) {
        public List<Item> safeItems() {
            return items == null ? List.of() : items;
        }
    }

    /**
     * 추천된 구간 하나. **문자열 ID 와 순위·사유뿐이다.**
     *
     * <p>곡 번호·시간·구간 종류는 오지 않는다. 우리가 {@code externalId} 로 MySQL 에서
     * 가져오는 값이라 두 벌이 되면 어긋날 자리만 생긴다.
     *
     * <p>사람이 읽는 문구도 오지 않는다. {@code reasonCode} 로 우리가 만든다.
     */
    public record Item(
        String externalId,
        int rankOrder,
        double score,
        String reasonCode,
        /** 이 추천의 근거가 된, 사용자 자신이 좋아한 구간. 없으면 null 이다. */
        String refExternalId
    ) {
    }

    /** `error_code` 와 메시지. 상태 코드만으로는 재시도 여부를 정할 수 없다. */
    public record Error(
        String errorCode,
        String message
    ) {
    }

    /**
     * `/internal/facets` — QueryPlan 에 쓸 수 있는 태그 어휘.
     *
     * <p>{@code fields} 의 값 모양은 필드마다 다르다(값 목록이 있는 것, 범위만 있는 것).
     * 여기서 굳히지 않고 {@code Map} 으로 받아 쓰는 쪽이 필요한 것만 꺼낸다 — 저쪽이 필드를
     * 늘려도 깨지지 않아야 한다.
     */
    public record Facets(
        Map<String, Object> catalog,
        Map<String, Object> fields,
        Map<String, Object> example
    ) {
    }
}
