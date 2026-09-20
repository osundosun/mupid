package com.osundosun.mupid.api.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.ai.client.AiJson;
import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.client.RecommenderDto;
import com.osundosun.mupid.api.recommendation.service.RecommendationReasonCache;

/**
 * {@code userId} 가 null 인 추천 요청이 **NPE 로 죽지 않는지** 본다.
 *
 * <h2>무엇이 문제였나</h2>
 *
 * <p>인터페이스는 {@code recommend(Integer userId, …)} 로 <b>null 을 허용하는 타입</b>을
 * 선언하는데, 구현은 그 값을 {@link RecommenderDto} 의 primitive {@code int userId} 에
 * 그대로 넣었다. 컴파일러는 {@code Integer → int} 를 **정상 변환**으로 보므로 경고도
 * 없고, null 이 들어오는 순간 언박싱 지점에서 {@link NullPointerException} 이 난다.
 * 대화 한 번이 통째로 500 이 되는 자리다.
 *
 * <h2>도달 가능성</h2>
 *
 * <p>지금은 <b>도달 불가</b>다. {@code recommend} 를 부르는 곳은
 * {@code AiChatServiceImpl.runTool} 한 곳뿐이고, 거기서 넘기는 {@code user.getUserId()} 는
 * {@code Authentication} 의 주체라 항상 값이 있다. 그래도 <b>타입이 null 을 허용하는데
 * 코드가 그것을 감당하지 못하는 상태</b>는 그대로 두지 않는다 — 이 테스트가 그 계약을
 * 못박는다.
 *
 * <p>스프링 컨텍스트도 DB 도 쓰지 않는다. 추천 서버만 대역으로 바꾸고 협력자를 직접
 * 조립한다 — {@code RecommendToolSilentDropTest} 와 같은 스타일이다.
 */
class AiRecommendToolNullUserTest {

    /** 조건이 없으면 {@code next} 경로다. 지금 곡도 없으면 협력자를 하나도 건드리지 않는다. */
    private AiRecommendToolImpl tool(RecommenderClient recommender) {
        return new AiRecommendToolImpl(recommender, null, null, null,
            mock(RecommendationReasonCache.class));
    }

    private AiRecommendTool.Arguments arguments(Map<String, Object> filters) {
        return new AiRecommendTool.Arguments("지금 곡보다 잔잔한 곡", 3, filters, Map.of());
    }

    @Test
    @DisplayName("userId 가 null 이어도 이어 듣기(next) 경로가 죽지 않는다")
    void nextPathSurvivesNullUser() {
        RecommenderClient recommender = mock(RecommenderClient.class);
        when(recommender.next(any())).thenReturn(List.of());

        AiRecommendToolImpl tool = tool(recommender);

        // 조건이 없으므로 next 경로다. 여기서 primitive int 로 언박싱되면 NPE 가 났다.
        assertThatCode(() -> tool.recommend(null, null, arguments(Map.of())))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("userId 가 null 이어도 조건 검색(search) 경로가 죽지 않는다")
    void searchPathSurvivesNullUser() {
        RecommenderClient recommender = mock(RecommenderClient.class);
        when(recommender.search(any())).thenReturn(List.of());

        AiRecommendToolImpl tool = tool(recommender);

        // 조건이 하나라도 있으면 search 경로다. 두 경로 모두 같은 자리에서 터졌다.
        assertThatCode(() -> tool.recommend(
            null, null, arguments(Map.of("genre", List.of("pop")))))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("추천 요청 DTO 는 null userId 를 억지로 언박싱하지 않는다 — 계약이 primitive 를 강요하지 않는다")
    void requestDtoDoesNotForceUnboxing() {
        // null 리터럴은 primitive 파라미터에 못 넘긴다(컴파일 오류). 실제 코드처럼
        // Integer 변수로 넘겨야 **런타임 언박싱 지점**이 드러난다.
        Integer absent = null;

        assertThatCode(() -> new RecommenderDto.NextRequest(
            7L, absent, 3, List.of(), "T000901", null, null))
            .doesNotThrowAnyException();
        assertThatCode(() -> new RecommenderDto.SearchRequest(
            7L, absent, 3, List.of(), null,
            Map.of("filters", Map.of("genre", List.of("pop"))), null))
            .doesNotThrowAnyException();
        assertThatCode(() -> new RecommenderDto.DailyRequest(
            7L, absent, 3, List.of(), null))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("userId 가 null 이면 본문에서 빠진다 — 0 으로 나가면 추천 서버가 다른 사용자로 읽는다")
    void omitsNullUserFromWire() {
        Integer absent = null;

        String json = new String(AiJson.writeBytes(new RecommenderDto.NextRequest(
            7L, absent, 3, List.of(), "T000901", null, null)), StandardCharsets.UTF_8);

        // primitive 였다면 언박싱 자체가 터졌다. Integer 라 터지지 않는데, 그렇다고
        // `"user_id":0` 이 나가면 계약(`ge=1`) 위반이 아니라 **다른 사용자 조회**가 된다.
        // NON_NULL 이라 칸이 통째로 빠지고, 추천 서버가 missing 으로 판정한다.
        assertThat(json).doesNotContain("user_id");
        assertThat(json).contains("\"recommendation_id\":7");
    }
}
