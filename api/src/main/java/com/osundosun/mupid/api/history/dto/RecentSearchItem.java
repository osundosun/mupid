package com.osundosun.mupid.api.history.dto;

import java.time.LocalDateTime;

/**
 * 최근 검색어 한 줄.
 *
 * 기록은 검색할 때마다 쌓이지만 화면에는 **같은 말이 한 번만** 나온다. 그래서
 * 이 항목의 열쇠는 기록 번호가 아니라 `keyword` 다. 뒤에 붙을 삭제 API 도
 * 같은 말의 기록을 통째로 지우는 쪽이라 번호를 내려 줄 이유가 없다.
 */
public record RecentSearchItem(

    String keyword,

    /** 이 말을 **마지막으로** 검색한 시각. */
    LocalDateTime searchedAt

) {

}
