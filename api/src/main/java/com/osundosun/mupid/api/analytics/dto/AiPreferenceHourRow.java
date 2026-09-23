package com.osundosun.mupid.api.analytics.dto;

import java.time.LocalDateTime;

import com.osundosun.mupid.api.analytics.enumerate.AiPreferenceSegment;

/**
 * AI 선호도 집계의 한 칸 — 한 시간 × 한 구분 (S15P21A204-427).
 *
 * <p>ClickHouse {@code ai_preference_hourly} 한 행과 1:1 이다. 세는 단위는 전부
 * <b>재생</b>({@code play_id})이고, 재생은 <b>시작한 시각</b>의 시간에 속한다. 10시 59분에
 * 틀어 11시 3분에 끝난 곡은 10시 칸이다.
 *
 * <p>비율은 담지 않는다. 비율은 합칠 수 없어서 — 시간 칸을 하루나 이레로 묶으려면
 * 분자와 분모를 따로 더한 뒤 나눠야 한다. 나누는 것은 화면에 내보내기 직전
 * ({@code AdminAiPreferenceStats})이다.
 *
 * @param hour           시간 칸의 시작 (`Asia/Seoul`, 분·초 0)
 * @param segment        구분
 * @param plays          재생 수
 * @param users          재생한 서로 다른 사람 수. <b>시간 칸끼리 더할 수 없다</b> — 같은
 *                       사람이 두 시간에 들으면 두 번 센다
 * @param engagedPlays   <b>선호 재생</b> — 30초 이상 들었거나 끝까지 들은 재생
 * @param completedPlays 끝까지 들은 재생 ({@code PLAY_END} 가 있다)
 * @param skippedPlays   넘긴 재생 ({@code TRACK_SKIP} 이 있다)
 * @param earlySkipPlays 30초를 못 채우고 넘긴 재생
 * @param likedPlays     재생 중에 좋아요를 누른 재생
 * @param listenedMs     실제로 들은 시간 합. {@code PLAY_PROGRESS} 의 {@code [from_ms, position_ms)} 합이다
 */
public record AiPreferenceHourRow(
    LocalDateTime hour,
    AiPreferenceSegment segment,
    long plays,
    long users,
    long engagedPlays,
    long completedPlays,
    long skippedPlays,
    long earlySkipPlays,
    long likedPlays,
    long listenedMs
) {

    /** 재생이 한 건도 없었던 칸. 배치가 빈 칸을 0 으로 채울 때 쓴다. */
    public static AiPreferenceHourRow empty(LocalDateTime hour, AiPreferenceSegment segment) {
        return new AiPreferenceHourRow(hour, segment, 0, 0, 0, 0, 0, 0, 0, 0);
    }

}
