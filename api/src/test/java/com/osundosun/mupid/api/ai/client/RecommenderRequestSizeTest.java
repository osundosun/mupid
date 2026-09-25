package com.osundosun.mupid.api.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.recommendation.request.NextTrackRequest;

/**
 * 계약 v3 의 요청 본문 크기 (S15P21A204-458, 설계 {@code infra/VECTOR-STORE-DESIGN.md} §9.1).
 *
 * <p>예전에는 요청마다 후보 전부를 {@code catalog} 로 실어 약 500KB 였다. v3 에서 그것을 걷어냈고,
 * 남은 본문을 좌우하는 것은 {@code exclude_tracks}(곡당 약 10B)뿐이다. 누가 무거운 칸을 다시
 * 얹으면(후보·특징·긴 목록) 여기서 걸린다 — 크기는 오류로 드러나지 않고 지연으로만 보인다.
 *
 * <p>실제로 나가는 바이트({@link AiJson#writeBytes})를 잰다. 잰 값은 표준 출력에 남긴다(시험 보고서의
 * system-out) — 설계 문서 표의 근거다.
 */
class RecommenderRequestSizeTest {

    /** 평소 요청의 상한. */
    private static final int LIMIT = 5 * 1024;

    /** 뺄 곡을 상한(500곡)까지 채운 요청의 상한. */
    private static final int MAX_CASE_LIMIT = 6 * 1024;

    /** 배치가 실제로 쓰는 상관 번호 모양({@code DailyRecommendationServiceImpl.generate}). */
    private static final long DAILY_ID = LocalDate.of(2026, 9, 25).toEpochDay() * 1_000_000L + 1015;

    /** 온보딩은 우리 코드표 이름 그대로 간다. 한국어라 글자당 3B 다. */
    private static final RecommenderDto.Onboarding ONBOARDING = new RecommenderDto.Onboarding(
        List.of("잔잔한", "몽환적인", "설레는"), List.of("Ballad", "Jazz", "R&B"));

    private static List<String> tracks(int count) {
        return IntStream.range(0, count).mapToObj(i -> "T%06d".formatted(901 + i)).toList();
    }

    private static int measure(String name, Object request) {
        byte[] body = AiJson.writeBytes(request);
        String json = new String(body, StandardCharsets.UTF_8);
        System.out.printf("[S15P21A204-458] %s 요청 본문 %d B%n", name, body.length);
        assertThat(json).as(name).doesNotContain("catalog");
        return body.length;
    }

    @Test
    @DisplayName("오늘의 모먼트 요청은 5KB 아래이고 catalog 가 없다")
    void daily() {
        int bytes = measure("daily", new RecommenderDto.DailyRequest(
            DAILY_ID, 1015, 4, List.of(), ONBOARDING));

        assertThat(bytes).isLessThan(LIMIT);
    }

    @Test
    @DisplayName("이어 듣기 요청은 뺄 곡 200곡을 실어도 5KB 아래다")
    void nextWithTwoHundredExcludes() {
        int bytes = measure("next(제외 200곡)", new RecommenderDto.NextRequest(
            System.currentTimeMillis(), 1015, 5, tracks(200), "T000900", "T000900#3", ONBOARDING));

        assertThat(bytes).isLessThan(LIMIT);
    }

    @Test
    @DisplayName("이어 듣기 요청은 뺄 곡을 상한(500곡)까지 채워도 6KB 아래다")
    void nextAtTheExcludeCap() {
        // 상한은 받는 입구(`NextTrackRequest`)에 있다. 지금 듣는 곡이 하나 더 붙는다.
        List<String> exclude = new ArrayList<>(tracks(NextTrackRequest.MAX_EXCLUDE));
        exclude.add("T000900");
        int bytes = measure("next(제외 501곡, 상한)", new RecommenderDto.NextRequest(
            System.currentTimeMillis(), 1015, 50, exclude, "T000900", "T000900#3", ONBOARDING));

        assertThat(bytes).isLessThan(MAX_CASE_LIMIT);
    }

    @Test
    @DisplayName("조건 검색 요청은 query_plan 을 그대로 실어도 5KB 아래다")
    void search() {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("genre", List.of("pop", "ko_ballad"));
        filters.put("language", List.of("korean"));
        filters.put("vocal", List.of("female"));
        filters.put("instrument", List.of("piano"));
        filters.put("mood", List.of("serene", "dreamy"));
        filters.put("flow", List.of("chorus"));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("filters", filters);
        plan.put("exclude", Map.of("mood", List.of("aggressive"), "instrument", List.of("drums")));

        int bytes = measure("search", new RecommenderDto.SearchRequest(
            System.currentTimeMillis(), 1015, 5, List.of("T000900"), null, plan, ONBOARDING));

        assertThat(bytes).isLessThan(LIMIT);
    }

    @Test
    @DisplayName("닮은 구간 요청은 5KB 아래다")
    void similar() {
        int bytes = measure("similar", new RecommenderDto.SimilarRequest(
            "T000901#2", 5, List.of("T000901")));

        assertThat(bytes).isLessThan(LIMIT);
    }

}
