package com.osundosun.mupid.api.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 오늘의 모먼트 개수는 추천 서버와 **같은 값**이어야 한다.
 *
 * <p>추천 서버는 개수를 고정하고 다른 값은 400 으로 거절한다. 2026-09-24 에 그쪽(4)이 먼저
 * 배포되고 여기는 10 인 채로 남아 정오 배치가 전원 실패했다 — 각자의 시험은 모두 통과했다.
 * 같은 저장소에 있으므로 그쪽 상수를 직접 읽어 비교한다.
 */
class DailyLimitContractTest {

    private static final Path AI_API = Path.of("..", "ai_server", "app", "internal_api.py");

    @Test
    @DisplayName("Spring 이 요청하는 오늘의 모먼트 개수는 추천 서버의 DAILY_LIMIT 과 같다")
    void dailyLimitMatchesTheRecommender() throws IOException {
        String source = Files.readString(AI_API, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("(?m)^DAILY_LIMIT\\s*=\\s*(\\d+)").matcher(source);

        assertThat(m.find()).as("%s 에서 DAILY_LIMIT 을 찾지 못했다", AI_API).isTrue();
        assertThat(DailyRecommendationServiceImpl.DAILY_LIMIT)
            .isEqualTo(Integer.parseInt(m.group(1)));
    }

}
