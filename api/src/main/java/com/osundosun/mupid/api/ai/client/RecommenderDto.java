package com.osundosun.mupid.api.ai.client;

import java.util.List;
import java.util.Map;

import com.osundosun.mupid.api.music.enumerate.SectionLabel;

/**
 * 추천 서버 `/internal` 계약의 요청·응답.
 *
 * <p>필드 이름은 저쪽(`ai_server/app/internal_api.py`)이 정한다. 본문은 snake_case 이고
 * {@link AiJson} 이 변환한다 — 애플리케이션 Jackson 설정에 묶어 두면 누가 그 설정을 바꾸는
 * 순간 계약이 조용히 깨진다. LLM 게이트웨이와 같은 이유다.
 *
 * <p>요청 타입이 엄격하다. {@code "5001"} 같은 문자열이나 실수 {@code limit} 은 400 이다.
 */
public final class RecommenderDto {

    private RecommenderDto() {
    }

    /**
     * 이어 듣기·오늘의 추천 요청.
     *
     * @param recommendationId Spring 이 발급하는 BIGINT. 추천 한 묶음을 가리킨다
     * @param excludeMusicIds  이미 보여 준 곡. 지금 듣는 곡은 여기 넣는다
     */
    public record NextRequest(
        long recommendationId,
        int userId,
        int limit,
        List<Integer> excludeMusicIds,
        Integer currentMusicId,
        Integer currentMusicSectionId
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
        List<Integer> excludeMusicIds,
        String query,
        Map<String, Object> queryPlan
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
     * 추천된 구간 하나.
     *
     * <p>{@code sectionLabel} 은 {@link SectionLabel} 과 같은 대문자로 온다. 추천 서버가
     * ENUM 밖 값(예: {@code OTHER})을 내보내지 않도록 경계에서 막고 있다 — 막지 않으면
     * 여기 역직렬화가 터진다.
     */
    public record Item(
        int musicId,
        int musicSectionId,
        int startTimeMs,
        int endTimeMs,
        SectionLabel sectionLabel,
        int rankOrder,
        double score,
        String reasonCode,
        /** 추천 서버가 만든 안전한 기본 문장. 내부 ID·모델 수치는 들어 있지 않다. */
        String reason
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
