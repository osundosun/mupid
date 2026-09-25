package com.osundosun.mupid.api.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.osundosun.mupid.api.auth.provider.JwtProvider;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.servlet.http.Cookie;

/**
 * ROLE_SYSTEM 의 장기 리프레시 토큰 (S15P21A204-454, 설계 §11 미결 10).
 *
 * <p>사용자 결정 세 가지를 HTTP 로 확인한다 — ① ROLE_SYSTEM 만 만료를 180일까지 지정한다,
 * ② ROLE_SYSTEM 은 회전하지 않는다, ③ 관리자가 ROLE_SYSTEM 계정의 토큰을 일괄 폐기한다.
 *
 * <p>"회전했다" 를 보려면 옛 토큰과 새 토큰이 달라야 하는데, 같은 초에 같은 주체로 발급한
 * JWT 는 글자까지 같다. 그래서 옛 토큰은 발급 시각을 1분 전으로 돌려 직접 서명한다.
 * 같은 이유로 폐기 경계보다 확실히 앞선 토큰이 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SystemRefreshTokenTest {

    private static final String AUTH = "/api/v1/auth";
    private static final String RAW_PASSWORD = "Password1234!";
    private static final long DAY_SECONDS = Duration.ofDays(1).toSeconds();

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheService cacheService;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @PersistenceContext
    private EntityManager em;

    private User ci;
    private User otherSystem;
    private User member;
    private User admin;

    private final List<Integer> touchedUsers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        ci = persistUser(UserRole.ROLE_SYSTEM);
        otherSystem = persistUser(UserRole.ROLE_SYSTEM);
        member = persistUser(UserRole.ROLE_USER);
        admin = persistUser(UserRole.ROLE_ADMIN);
    }

    /** DB 는 롤백되지만 Redis 의 폐기 표시는 남는다 */
    @AfterEach
    void cleanUp() {
        touchedUsers.forEach(id -> cacheService.delete(String.valueOf(id), CacheKind.TokenRevokedBefore));
    }

    // ─────────────────────────────────────────────────────────────
    // ② 회전

    @Test
    @DisplayName("ROLE_SYSTEM 은 재발급해도 같은 리프레시 토큰이 돌아오고, 쿠키 수명은 남은 수명이다")
    void systemRefreshDoesNotRotate() throws Exception {
        String refresh = signRefresh(ci, Duration.ofMinutes(1), Duration.ofDays(180));

        MvcResult result = mockMvc.perform(get(AUTH).cookie(new Cookie("refresh_token", refresh)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.access_token").isNotEmpty())
            .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertEquals(refresh, cookieValue(setCookie));
        long maxAge = maxAge(setCookie);
        assertTrue(maxAge > 179 * DAY_SECONDS && maxAge <= 180 * DAY_SECONDS, setCookie);
    }

    @Test
    @DisplayName("다른 역할은 지금처럼 재발급마다 새 리프레시 토큰으로 회전한다")
    void userRefreshStillRotates() throws Exception {
        String refresh = signRefresh(member, Duration.ofMinutes(1), Duration.ofDays(7));

        MvcResult result = mockMvc.perform(get(AUTH).cookie(new Cookie("refresh_token", refresh)))
            .andExpect(status().isOk())
            .andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotEquals(refresh, cookieValue(setCookie));
        assertEquals(7 * DAY_SECONDS, maxAge(setCookie), 5);
    }

    // ─────────────────────────────────────────────────────────────
    // ① 만료 지정

    @Test
    @DisplayName("ROLE_SYSTEM 은 로그인 때 만료를 180일까지 지정할 수 있다")
    void systemLoginWithLongValidity() throws Exception {
        MvcResult result = login(ci, 180).andExpect(status().isOk()).andReturn();

        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertEquals(180 * DAY_SECONDS, maxAge(setCookie));
        Claims claims = parse(cookieValue(setCookie));
        long lifetime = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertEquals(Duration.ofDays(180).toMillis(), lifetime, 1000);
    }

    @Test
    @DisplayName("180일을 넘기거나 1일보다 짧으면 거절한다")
    void rejectsOutOfRange() throws Exception {
        login(ci, 181)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_REFRESH_TOKEN_VALIDITY_OUT_OF_RANGE"));
        login(ci, 0)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_REFRESH_TOKEN_VALIDITY_OUT_OF_RANGE"));
    }

    @Test
    @DisplayName("다른 역할은 만료를 지정할 수 없고, 지정하지 않으면 지금처럼 7일이다")
    void otherRolesCannotChooseValidity() throws Exception {
        login(member, 30)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_REFRESH_TOKEN_VALIDITY_NOT_ALLOWED"));
        login(admin, 30)
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_REFRESH_TOKEN_VALIDITY_NOT_ALLOWED"));

        MvcResult result = login(member, null).andExpect(status().isOk()).andReturn();
        assertEquals(7 * DAY_SECONDS, maxAge(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)));
    }

    @Test
    @DisplayName("시스템 계정 생성 때 만료를 지정할 수 있고, 범위 밖이면 계정을 만들지 않는다")
    void createSystemUserWithValidity() throws Exception {
        String email = "system-ci-%s@mupid.test".formatted(UUID.randomUUID().toString().substring(0, 8));

        mockMvc.perform(as(admin, post("/api/v1/user/system"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"refresh_token_validity_days\":181}".formatted(email)))
            .andExpect(status().isBadRequest());
        assertEquals(0L, countUsers(email));

        MvcResult result = mockMvc.perform(as(admin, post("/api/v1/user/system"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"%s\",\"refresh_token_validity_days\":180}".formatted(email)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.refresh_token_expires_at").isNotEmpty())
            .andReturn();

        Claims claims = parse(body(result).path("refresh_token").asText());
        long lifetime = claims.getExpiration().getTime() - claims.getIssuedAt().getTime();
        assertEquals(Duration.ofDays(180).toMillis(), lifetime, 1000);
    }

    @Test
    @DisplayName("관리자는 시스템 계정의 토큰을 새로 발급할 수 있고, 사람 계정은 대상이 아니다")
    void adminIssuesSystemToken() throws Exception {
        MvcResult result = mockMvc.perform(as(admin, post("/api/v1/user/system/{id}/token", ci.getUserId()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token_validity_days\":90}"))
            .andExpect(status().isOk())
            .andReturn();
        Claims claims = parse(body(result).path("refresh_token").asText());
        assertEquals(String.valueOf(ci.getUserId()), claims.getSubject());
        assertEquals(Duration.ofDays(90).toMillis(),
            claims.getExpiration().getTime() - claims.getIssuedAt().getTime(), 1000);

        mockMvc.perform(as(admin, post("/api/v1/user/system/{id}/token", member.getUserId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_NOT_SYSTEM_USER"));
        mockMvc.perform(as(ci, post("/api/v1/user/system/{id}/token", ci.getUserId())))
            .andExpect(status().isForbidden());
    }

    // ─────────────────────────────────────────────────────────────
    // ③ 폐기

    @Test
    @DisplayName("폐기하면 그 계정의 리프레시·액세스 토큰이 401 이 되고, 다른 시스템 계정은 그대로다")
    void revokeLogsOutAllTokens() throws Exception {
        String refresh = signRefresh(ci, Duration.ofMinutes(1), Duration.ofDays(180));
        String access = signAccess(ci, Duration.ofMinutes(1));
        String otherRefresh = signRefresh(otherSystem, Duration.ofMinutes(1), Duration.ofDays(180));

        // 폐기 전에는 통한다 (없는 모델이라 404 — 인증은 지났다)
        mockMvc.perform(get(AUTH).cookie(new Cookie("refresh_token", refresh)))
            .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/system/models/{name}", "no-such-model")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
            .andExpect(status().isNotFound());

        touchedUsers.add(ci.getUserId());
        mockMvc.perform(as(admin, delete("/api/v1/user/system/{id}/token", ci.getUserId())))
            .andExpect(status().isOk());

        mockMvc.perform(get(AUTH).cookie(new Cookie("refresh_token", refresh)))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/system/models/{name}", "no-such-model")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + access))
            .andExpect(status().isUnauthorized());
        mockMvc.perform(get(AUTH).cookie(new Cookie("refresh_token", otherRefresh)))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("폐기 뒤에 새로 발급받은 토큰은 통한다")
    void tokenIssuedAfterRevokeWorks() throws Exception {
        touchedUsers.add(ci.getUserId());
        mockMvc.perform(as(admin, delete("/api/v1/user/system/{id}/token", ci.getUserId())))
            .andExpect(status().isOk());

        // 폐기 경계는 발급 시각(초 단위)과 비교해 같은 초에 발급된 것을 막는다 (TokenRevocationService)
        Thread.sleep(1100);
        MvcResult issued = mockMvc.perform(as(admin, post("/api/v1/user/system/{id}/token", ci.getUserId())))
            .andExpect(status().isOk())
            .andReturn();
        String refresh = body(issued).path("refresh_token").asText();

        mockMvc.perform(get(AUTH).cookie(new Cookie("refresh_token", refresh)))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("폐기는 관리자만, 시스템 계정만 대상이다")
    void revokeIsAdminOnlyAndSystemOnly() throws Exception {
        mockMvc.perform(as(ci, delete("/api/v1/user/system/{id}/token", otherSystem.getUserId())))
            .andExpect(status().isForbidden());
        mockMvc.perform(as(admin, delete("/api/v1/user/system/{id}/token", member.getUserId())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_NOT_SYSTEM_USER"));
        assertFalse(cacheService.exists(String.valueOf(otherSystem.getUserId()), CacheKind.TokenRevokedBefore));
    }

    // ─────────────────────────────────────────────────────────────

    private ResultActions login(User user, Integer days) throws Exception {
        String extra = days == null ? "" : ",\"refresh_token_validity_days\":" + days;
        return mockMvc.perform(post(AUTH)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"%s\",\"password\":\"%s\"%s}".formatted(user.getEmail(), RAW_PASSWORD, extra)));
    }

    private SecretKey key() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(jwtSecret));
    }

    private String signRefresh(User user, Duration issuedAgo, Duration validity) {
        Date issued = new Date(System.currentTimeMillis() - issuedAgo.toMillis());
        return Jwts.builder()
            .subject(user.getUserId().toString())
            .claim("token_type", "REFRESH")
            .issuedAt(issued)
            .expiration(new Date(issued.getTime() + validity.toMillis()))
            .signWith(key())
            .compact();
    }

    private String signAccess(User user, Duration issuedAgo) {
        Date issued = new Date(System.currentTimeMillis() - issuedAgo.toMillis());
        return Jwts.builder()
            .subject(user.getUserId().toString())
            .claim("role", user.getRole().name())
            .claim("token_type", "ACCESS")
            .issuedAt(issued)
            .expiration(new Date(issued.getTime() + JwtProvider.ACCESS_TOKEN_VALIDITY_MS))
            .signWith(key())
            .compact();
    }

    private Claims parse(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }

    private static String cookieValue(String setCookie) {
        assertNotNull(setCookie);
        String first = setCookie.split(";")[0];
        return first.substring(first.indexOf('=') + 1);
    }

    private static long maxAge(String setCookie) {
        for (String part : setCookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("Max-Age=")) {
                return Long.parseLong(p.substring("Max-Age=".length()));
            }
        }
        throw new AssertionError("Max-Age 가 없다: " + setCookie);
    }

    private static JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private long countUsers(String email) {
        return em.createQuery("SELECT COUNT(u) FROM User u WHERE u.email = :email", Long.class)
            .setParameter("email", email)
            .getSingleResult();
    }

    private static MockHttpServletRequestBuilder as(User actor, MockHttpServletRequestBuilder builder) {
        return builder
            .with(csrf())
            .with(authentication(new UsernamePasswordAuthenticationToken(
                actor, null, List.of(new SimpleGrantedAuthority(actor.getRole().name())))));
    }

    private User persistUser(UserRole role) {
        User user = User.builder()
            .email("system-token-%s@mupid.test".formatted(UUID.randomUUID()))
            .password(passwordEncoder.encode(RAW_PASSWORD))
            .nickname("검증용")
            .birthYear(1999)
            .gender(UserGender.ETC)
            .role(role)
            .status(UserStatus.ACTIVE)
            .build();
        em.persist(user);
        em.flush();
        return user;
    }

}
