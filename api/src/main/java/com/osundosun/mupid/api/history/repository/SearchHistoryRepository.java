package com.osundosun.mupid.api.history.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * 한 말의 기록을 **전부** 지운다.
     *
     * 목록의 한 줄은 기록 하나가 아니라 같은 말의 기록 묶음이다. 그래서 줄
     * 하나를 지우면 그 말로 남은 기록이 다 없어져야 한다. 하나만 지우면 화면은
     * 그대로인데 서버만 줄어서, 누른 사람은 삭제가 안 먹었다고 본다.
     *
     * `keyword` 는 `utf8mb4_unicode_ci` 라 대소문자를 가리지 않는다. 목록을
     * 묶는 `GROUP BY` 도 같은 규칙을 쓰므로 "kcik" 한 줄을 지우면 "KCIK" 도
     * 함께 사라진다 — 애초에 한 줄로 보이던 것들이다.
     *
     * @return 지운 기록 수. 0 이면 이미 없던 말이다
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SearchHistory h WHERE h.user.userId = :userId AND h.keyword = :keyword")
    int deleteByUserAndKeyword(@Param("userId") Integer userId, @Param("keyword") String keyword);

    /**
     * 그 사람의 검색 기록을 모두 지운다.
     *
     * @return 지운 기록 수
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM SearchHistory h WHERE h.user.userId = :userId")
    int deleteAllByUser(@Param("userId") Integer userId);

}
