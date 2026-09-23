package com.osundosun.mupid.api.admin.response;

/**
 * AI 선호도 수치 한 묶음 — 한 시간 칸이거나 창 전체의 합 (S15P21A204-427).
 *
 * <p>건수와 비율을 함께 보낸다. 비율만 보내면 "재생 1회 중 1회" 의 100% 와 "천 회 중
 * 천 회" 의 100% 가 같아 보인다. 화면은 선을 비율로 그리고, 마우스를 올리면 건수를 보인다.
 *
 * <p><b>비율은 분모가 0 이면 {@code null}</b> 이다. 0 으로 보내면 "재생은 있었는데 아무도
 * 끝까지 안 들었다" 로 읽힌다. 화면은 {@code null} 에서 선을 끊는다.
 *
 * @param plays          재생 수
 * @param users          재생한 서로 다른 사람 수. <b>한 구분의 한 시간 칸에만 있다</b> —
 *                       칸끼리·구분끼리 더하면 같은 사람을 두 번 세므로 합계에서는 {@code null}
 * @param engagedPlays   선호 재생 (30초 이상 들었거나 끝까지 들었다)
 * @param completedPlays 끝까지 들은 재생
 * @param skippedPlays   넘긴 재생
 * @param earlySkipPlays 30초 전에 넘긴 재생
 * @param likedPlays     재생 중 좋아요를 누른 재생
 * @param listenedMs     실제로 들은 시간 합
 * @param engagedRate    <b>선호율</b> = 선호 재생 / 재생. 이 화면의 머리 지표다
 * @param completionRate 완주율 = 끝까지 들은 재생 / 재생
 * @param skipRate       스킵률 = 넘긴 재생 / 재생
 * @param earlySkipRate  조기 스킵률 = 30초 전에 넘긴 재생 / 재생
 * @param likeRate       좋아요율 = 좋아요 누른 재생 / 재생
 * @param share          비중 = 이 구분의 재생 / 같은 시간 전체 재생. 사람들이 듣는 것
 *                       가운데 AI 가 고른 곡이 얼마나 되는가
 */
public record AdminAiPreferenceStats(
    long plays,
    Long users,
    long engagedPlays,
    long completedPlays,
    long skippedPlays,
    long earlySkipPlays,
    long likedPlays,
    long listenedMs,
    Double engagedRate,
    Double completionRate,
    Double skipRate,
    Double earlySkipRate,
    Double likeRate,
    Double share
) {

}
