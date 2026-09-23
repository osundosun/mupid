package com.osundosun.mupid.api.ai.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 카탈로그 한 페이지. <b>{@code ROLE_SYSTEM} 전용이다.</b>
 *
 * <p>추천 서버는 기동할 때 이 계약을 처음부터 끝까지 돌아 메모리에 담는다. 그래서
 * <b>{@code totalPages} 를 반드시 싣는다</b> — 받는 쪽이 "빈 페이지가 나올 때까지"
 * 로 끝을 판정하면 중간에 한 페이지가 비었을 때 카탈로그가 조용히 잘린다.
 *
 * <p>{@code totalElements} 는 곡 수다(구간 수가 아니다). 페이지 단위와 같은 것을
 * 세야 받는 쪽이 "몇 개를 받아야 하는가" 를 계산할 수 있다.
 *
 * <p>{@code generatedAt} 은 이 페이지를 만든 시각이다. 전체를 도는 동안 곡이
 * 지워지거나 들어올 수 있으므로, 받는 쪽은 첫 페이지와 마지막 페이지의 이 값이
 * 크게 벌어졌는지로 <b>한 벌로 볼 수 있는 스냅샷인지</b>를 판단한다.
 */
public record AiCatalogPageResponse(
    int page,
    int size,
    int totalPages,
    long totalElements,
    LocalDateTime generatedAt,
    List<AiCatalogTrackResponse> tracks
) {
}
