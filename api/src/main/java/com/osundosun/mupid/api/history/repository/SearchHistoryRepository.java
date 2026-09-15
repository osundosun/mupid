package com.osundosun.mupid.api.history.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.history.dto.RecentSearchItem;
import com.osundosun.mupid.api.history.entity.SearchHistory;

public interface SearchHistoryRepository extends JpaRepository<SearchHistory, Integer> {

    /**
     * 최근 검색어. **같은 말은 한 번만** 나온다.
     *
     * 기록은 검색할 때마다 쌓인다. 같은 말을 다시 찾는 일이 흔해서 그대로
     * 내리면 목록이 한 말로 뒤덮인다. 말별 마지막 검색 시각으로 묶어
     * 최신순으로 준다.
     *
     * 전체 개수는 세지 않는다. 검색창 아래 목록은 앞의 몇 개만 쓴다.
     */
    @Query("""
        SELECT new com.osundosun.mupid.api.history.dto.RecentSearchItem(
            h.keyword, MAX(h.createdAt))
        FROM SearchHistory h
        WHERE h.user.userId = :userId
        GROUP BY h.keyword
        ORDER BY MAX(h.createdAt) DESC
        """)
    List<RecentSearchItem> findRecentKeywords(@Param("userId") Integer userId, Pageable pageable);

}
