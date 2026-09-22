package com.osundosun.mupid.api.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.ai.dto.AiRecommendedTrack;
import com.osundosun.mupid.api.ai.dto.AiToolTrackView;
import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;

/**
 * 툴 결과를 모델에게 돌려줄 수 있는지 못박는다 (S15P21A204-426).
 *
 * <p>여기서 터지면 {@code AiJson.writeString} 이 {@code AI_REQUEST_FAILED} 를 던지고
 * <b>추천이 성공한 모든 대화 턴이 502</b> 가 된다. 실제로 그렇게 났다 — 화면에 나가는
 * 곡 표현({@code MusicSummary})을 모델에게도 그대로 보냈는데, 거기 있는
 * {@code LocalDateTime} 을 게이트웨이 전용 매퍼가 쓰지 못했다(JavaTimeModule 없음).
 */
class AiJsonToolResultTest {

    private AiRecommendedTrack track() {
        return new AiRecommendedTrack(
            1,
            new MusicSummary(
                7, "곡", LocalDateTime.of(2026, 1, 1, 0, 0), 180_000, true, 12, 3, null,
                new MusicSummary.ArtistSummary(1, "아티스트", null),
                new MusicSummary.AlbumSummary(1, "앨범", null, LocalDateTime.of(2026, 1, 1, 0, 0)),
                List.of(), List.of()),
            10, SectionLabel.CHORUS, 45_000, 60_000, 0.9, "QUERY_FILTER_MATCH", "이유");
    }

    /** 실제로 모델에게 나가는 모양. `AiChatServiceImpl.runTool` 이 만드는 것과 같다. */
    private String toolResult() {
        return AiJson.writeString(
            Map.of("tracks", List.of(AiToolTrackView.of(track()))));
    }

    @Test
    @DisplayName("툴 결과를 모델에게 돌려줄 JSON 으로 쓸 수 있다 — 여기서 터지면 대화가 502 다")
    void writesToolResult() {
        assertThatCode(this::toolResult).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("모델이 곡 이야기를 하는 데 필요한 값은 그대로 간다")
    void keepsWhatTheModelNeeds() {
        String json = toolResult();
        assertThat(json)
            .contains("\"title\":\"곡\"")
            .contains("\"artist\":\"아티스트\"")
            .contains("\"album\":\"앨범\"")
            .contains("\"music_id\":7")
            .contains("\"rank_order\":1")
            .contains("\"section_label\":\"CHORUS\"")
            .contains("\"start_time_ms\":45000")
            .contains("\"reason\":\"이유\"");
    }

    @Test
    @DisplayName("모델이 쓸 수 없는 값은 보내지 않는다 — 매 턴 프롬프트로 나가는 값이다")
    void dropsWhatTheModelCannotUse() {
        String json = toolResult();
        // 발매일이 이 경로로 나가는 순간 직렬화가 터진다. 나머지는 토큰 낭비다.
        assertThat(json)
            .doesNotContain("release_date")
            .doesNotContain("play_count")
            .doesNotContain("like_count")
            .doesNotContain("image_url")
            .doesNotContain("duration_ms")
            .doesNotContain("playable");
    }
}
