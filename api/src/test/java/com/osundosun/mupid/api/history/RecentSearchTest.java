package com.osundosun.mupid.api.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4 에서 패키지가 옮겨졌다. 3.x 의 `boot.test.autoconfigure.web.servlet` 이 아니다.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.history.dto.RecentSearchItem;
import com.osundosun.mupid.api.history.entity.SearchHistory;
import com.osundosun.mupid.api.history.service.HistoryService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 최근 검색어 조회 검증. 트랜잭션은 테스트마다 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RecentSearchTest {

    @Autowired
    private HistoryService historyService;

    @Autowired
    private MockMvc mockMvc;

    @PersistenceContext
    private EntityManager em;

    private User me;
    private User stranger;

    @BeforeEach
    void setUp() {
        me = persistUser();
        stranger = persistUser();
        em.flush();
    }

    @Test
    @DisplayName("검색한 적이 없으면 빈 목록이다")
    void returnsEmptyList() {
        assertTrue(historyService.getRecentSearches(me, 10).keywords().isEmpty());
    }

    @Test
    @DisplayName("내 검색어만 최신순으로 나온다")
    void returnsMyKeywordsNewestFirst() {
        persistSearch(me, "먼저 찾은 것", LocalDateTime.now().minusDays(1));
        persistSearch(me, "나중에 찾은 것", LocalDateTime.now());
        persistSearch(stranger, "남이 찾은 것", LocalDateTime.now());
        em.flush();

        List<RecentSearchItem> keywords = historyService.getRecentSearches(me, 10).keywords();

        assertEquals(2, keywords.size());
        assertEquals("나중에 찾은 것", keywords.get(0).keyword());
        assertEquals("먼저 찾은 것", keywords.get(1).keyword());
    }

    @Test
    @DisplayName("같은 말은 한 번만 나오고 마지막으로 찾은 시각을 쓴다")
    void collapsesRepeatedKeyword() {
        LocalDateTime last = LocalDateTime.now().withNano(0);
        persistSearch(me, "무피드", last.minusDays(2));
        persistSearch(me, "무피드", last);
        persistSearch(me, "다른 말", last.minusDays(1));
        em.flush();

        List<RecentSearchItem> keywords = historyService.getRecentSearches(me, 10).keywords();

        assertEquals(2, keywords.size());
        assertEquals("무피드", keywords.get(0).keyword());
        assertEquals(last, keywords.get(0).searchedAt());
    }

    @Test
    @DisplayName("size 만큼만 잘라 준다. 0 이하나 과한 값은 범위 안으로 당긴다")
    void clampsSize() {
        for (int index = 0; index < 5; index++) {
            persistSearch(me, "검색어 %d".formatted(index), LocalDateTime.now().minusMinutes(index));
        }
        em.flush();

        assertEquals(2, historyService.getRecentSearches(me, 2).keywords().size());
        assertEquals(1, historyService.getRecentSearches(me, 0).keywords().size());
        assertEquals(5, historyService.getRecentSearches(me, 9999).keywords().size());
    }

    @Test
    @DisplayName("GET /api/v1/user/history/search 가 snake_case 로 목록을 내린다")
    void servesRecentSearchOverHttp() throws Exception {
        persistSearch(me, "무피드", LocalDateTime.now());
        em.flush();

        mockMvc.perform(get("/api/v1/user/history/search")
                .param("size", "10")
                .with(authentication(
                    new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.keywords[0].keyword").value("무피드"))
            .andExpect(jsonPath("$.data.keywords[0].searched_at").exists());
    }

    // ─────────────────────────────────────────────────────────────

    private void persistSearch(User user, String keyword, LocalDateTime createdAt) {
        SearchHistory history = SearchHistory.builder().user(user).keyword(keyword).build();
        em.persist(history);
        em.flush();
        /*
         * createdAt 은 엔티티에서 updatable = false 라 setter 로 바꿔도 DB 에 닿지 않는다.
         * 정렬은 DB 가 하므로 검색 시각은 네이티브 UPDATE 로 직접 넣는다.
         */
        em.createNativeQuery(
                "UPDATE search_histories SET created_at = :createdAt WHERE search_history_id = :id")
            .setParameter("createdAt", createdAt)
            .setParameter("id", history.getSearchHistoryId())
            .executeUpdate();
    }

    private User persistUser() {
        User user = User.builder()
            .email("test-%s@mupid.test".formatted(UUID.randomUUID()))
            .password("$2a$10$abcdefghijklmnopqrstuv")
            .nickname("테스터")
            .birthYear(1999)
            .gender(UserGender.ETC)
            .role(UserRole.ROLE_USER)
            .status(UserStatus.ACTIVE)
            .build();
        em.persist(user);
        return user;
    }

}
