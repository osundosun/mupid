package com.osundosun.mupid.api.ai.service;

import com.osundosun.mupid.api.ai.response.AiCatalogPageResponse;

public interface AiCatalogService {

    /**
     * 추천 서버가 메모리에 담을 카탈로그 한 페이지. 배치(`ROLE_SYSTEM`)만 부른다.
     *
     * <p>삭제된 곡과 구간이 없는 곡은 들어 있지 않다. 페이지를 벗어난 번호를 주면
     * 빈 목록이 오지만 {@code totalPages} 는 그대로이므로, 받는 쪽은 개수가 아니라
     * 그 값으로 끝을 판정한다.
     */
    AiCatalogPageResponse getCatalogPage(int page, int size);

}
