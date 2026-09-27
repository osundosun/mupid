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
     * 행동 타임라인을 가진 자원인가 (회원). 타임라인은 왼쪽 목록에 따로 두지 않고 이 자원의
     * 행에서 들어가므로, 타임라인 화면이 "돌아갈 목록" 과 "켜 둘 왼쪽 줄" 을 여기서 찾는다.
     */
    boolean eventTimeline,

    /**
     * 전체 행 수. 테이블마다 `COUNT` 한 번씩 돈다.
     */
    long count

) {
}
