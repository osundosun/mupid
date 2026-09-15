package com.osundosun.mupid.api.history;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import com.osundosun.mupid.api.common.error.GlobalErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;
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
 * 최근 검색어 삭제 검증. 트랜잭션은 테스트마다 롤백된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RecentSearchDeleteTest {

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
    @DisplayName("한 줄을 지우면 그 말로 남은 기록이 전부 사라진다")
    void deletesEveryRecordOfOneKeyword() {
        persistSearch(me, "무피드");
        persistSearch(me, "무피드");
        persistSearch(me, "남는 말");
        em.flush();

        assertEquals(2, historyService.deleteRecentSearch(me, "무피드"));

        List<RecentSearchItem> left = historyService.getRecentSearches(me, 10).keywords();
        assertEquals(1, left.size());
        assertEquals("남는 말", left.get(0).keyword());
    }

    @Test
    @DisplayName("남의 기록은 같은 말이어도 건드리지 않는다")
    void neverTouchesAnotherUser() {
        persistSearch(me, "무피드");
        persistSearch(stranger, "무피드");
        em.flush();

        assertEquals(1, historyService.deleteRecentSearch(me, "무피드"));

        assertTrue(historyService.getRecentSearches(me, 10).keywords().isEmpty());
        assertEquals(1, historyService.getRecentSearches(stranger, 10).keywords().size());
    }

    @Test
    @DisplayName("이미 없던 말을 지워도 오류가 아니다")
    void deletingMissingKeywordIsNotAnError() {
        assertEquals(0, historyService.deleteRecentSearch(me, "찾은 적 없는 말"));
    }

    @Test
    @DisplayName("지울 말이 비어 있으면 400 이다. 빈 값은 전부가 아니다")
    void rejectsBlankKeyword() {
        persistSearch(me, "무피드");
        em.flush();

        for (String blank : new String[] { "", "   ", null }) {
            BusinessException e = assertThrows(BusinessException.class,
                () -> historyService.deleteRecentSearch(me, blank));
            assertEquals(GlobalErrorCode.INVALID_REQUEST_BODY, e.getErrorCode());
        }
        // 하나도 지워지지 않았다.
        assertEquals(1, historyService.getRecentSearches(me, 10).keywords().size());
    }

    @Test
    @DisplayName("전체 삭제는 내 기록만 모두 지운다")
    void clearsOnlyMyRecords() {
        persistSearch(me, "무피드");
        persistSearch(me, "푸른 오후");
        persistSearch(stranger, "남의 말");
        em.flush();

        assertEquals(2, historyService.clearRecentSearches(me));

        assertTrue(historyService.getRecentSearches(me, 10).keywords().isEmpty());
        assertEquals(1, historyService.getRecentSearches(stranger, 10).keywords().size());
    }

    @Test
    @DisplayName("검색한 적이 없어도 전체 삭제는 성공이다")
    void clearingEmptyHistoryIsNotAnError() {
        assertEquals(0, historyService.clearRecentSearches(me));
    }

    @Test
    @DisplayName("DELETE /api/v1/user/history/search 는 keyword 가 없으면 400 이다")
    void rejectsDeleteWithoutKeywordOverHttp() throws Exception {
        mockMvc.perform(delete("/api/v1/user/history/search")
                .with(csrf())
                .with(authentication(
                    new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("DELETE 두 갈래가 HTTP 로도 동작한다")
    void servesDeleteOverHttp() throws Exception {
        persistSearch(me, "무피드");
        persistSearch(me, "푸른 오후");
        em.flush();

        mockMvc.perform(delete("/api/v1/user/history/search")
                .param("keyword", "무피드")
                .with(csrf())
                .with(authentication(
                    new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk());
        assertEquals(1, historyService.getRecentSearches(me, 10).keywords().size());

        mockMvc.perform(delete("/api/v1/user/history/search/all")
                .with(csrf())
                .with(authentication(
                    new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk());
        assertTrue(historyService.getRecentSearches(me, 10).keywords().isEmpty());
    }

    // ─────────────────────────────────────────────────────────────

    private void persistSearch(User user, String keyword) {
        em.persist(SearchHistory.builder().user(user).keyword(keyword).build());
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
