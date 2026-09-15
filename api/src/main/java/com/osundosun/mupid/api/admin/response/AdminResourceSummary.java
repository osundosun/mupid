package com.osundosun.mupid.api.admin.response;

/**
 * 왼쪽 자원 목록의 한 줄.
 */
public record AdminResourceSummary(

    String name,

    String label,

    String table,

    String category,

    /**
     * 복합키인가. 화면이 식별자 입력칸을 몇 개 그릴지 정한다.
     */
    boolean composite,

    /**
     * 전체 행 수. 테이블마다 `COUNT` 한 번씩 돈다.
     */
    long count

) {
}
