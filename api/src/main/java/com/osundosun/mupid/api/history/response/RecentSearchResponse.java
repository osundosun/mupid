package com.osundosun.mupid.api.history.response;

import java.util.List;

import com.osundosun.mupid.api.history.dto.RecentSearchItem;

/**
 * 최근 검색어 목록.
 *
 * 페이지네이션이 없다. 검색창 아래에 펼쳐지는 목록이라 한 화면을 넘길 일이
 * 없고, 넘긴다면 그건 `size` 를 잘못 부른 것이다.
 */
public record RecentSearchResponse(

    List<RecentSearchItem> keywords

) {

}
