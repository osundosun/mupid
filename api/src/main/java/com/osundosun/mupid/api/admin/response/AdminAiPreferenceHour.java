package com.osundosun.mupid.api.admin.response;

/**
 * 가로축의 한 칸 (S15P21A204-427).
 *
 * @param hour     칸의 시작. {@code yyyy-MM-dd HH:00} ({@code Asia/Seoul}). 화면이 시각을
 *                 다시 계산하지 않도록 서버가 글자로 준다 — 브라우저 시간대를 타면 칸이 밀린다
 * @param computed 배치가 이 칸을 계산해 두었는가. 거짓이면 그 칸의 수치는 전부 0 이지만
 *                 "재생이 없었다" 가 아니라 <b>"모른다"</b> 다. 표를 만든 직후나 배치가 멈췄을 때 생긴다
 */
public record AdminAiPreferenceHour(
    String hour,
    boolean computed
) {

}
