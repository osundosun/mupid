package com.osundosun.mupid.api.admin.response;

import java.util.List;
import java.util.Map;

/**
 * 표 한 페이지.
 *
 * 행은 엔티티가 아니라 `Map` 이다. 자원마다 타입이 다르고, 무엇보다 엔티티를 그대로
 * 직렬화하면 `@ManyToOne(LAZY)` 프록시가 깨어나 행마다 추가 쿼리가 붙는다.
 * `AdminResourceService` 가 필드를 직접 읽어 담는다.
 *
 * 각 행에는 `_id` 키가 하나 더 들어 있다 — 이 행을 가리키는 URL 조각이다.
 */
public record AdminRowPageResponse(

    List<Map<String, Object>> content,

    /**
     * 0부터 센다.
     */
    int page,

    int size,

    long total,

    int pages

) {

    public static AdminRowPageResponse of(List<Map<String, Object>> content, int page, int size, long total) {
        int pages = size <= 0 ? 0 : (int) Math.ceil((double) total / size);
        return new AdminRowPageResponse(content, page, size, total, pages);
    }

}
