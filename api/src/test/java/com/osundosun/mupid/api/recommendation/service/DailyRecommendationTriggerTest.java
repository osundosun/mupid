package com.osundosun.mupid.api.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import com.osundosun.mupid.api.recommendation.controller.DailyRecommendationController;

/**
 * 데이터 배치가 오늘의 모먼트를 시작시키는 연결 (S15P21A204-448).
 *
 * <p>배치(`music_preprocessing`)와 API 가 같은 저장소에 있으므로 서로의 소스를 직접 읽어
 * 경로가 어긋나지 않았는지 본다. 한쪽만 바꾸면 각자의 시험은 통과하고 배치만 404 로 실패한다.
 */
class DailyRecommendationTriggerTest {

    private static final Path RUN_BATCH = Path.of("..", "music_preprocessing", "run_batch.sh");
    private static final Path CLIENT = Path.of("..", "music_preprocessing", "src",
        "music_preprocessing", "api_client.py");

    @Test
    @DisplayName("배치가 부르는 경로가 API 의 시작 엔드포인트와 같다")
    void batchCallsTheStartEndpoint() throws Exception {
        String base = DailyRecommendationController.class.getAnnotation(RequestMapping.class).value()[0];
        String sub = DailyRecommendationController.class.getMethod("startDaily")
            .getAnnotation(PostMapping.class).value()[0];

        assertThat(Files.readString(CLIENT, StandardCharsets.UTF_8))
            .contains("\"POST\", \"" + base + sub + "\"");
    }

    @Test
    @DisplayName("배치는 분석이 성공한 날에만 오늘의 모먼트를 시작시킨다")
    void batchStartsOnlyAfterSuccessfulAnalysis() throws IOException {
        String script = Files.readString(RUN_BATCH, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(script).contains(
            "if [ \"$analysis_ok\" -eq 1 ]; then\n  run daily-recommendation || failed=1\n");
    }

}
