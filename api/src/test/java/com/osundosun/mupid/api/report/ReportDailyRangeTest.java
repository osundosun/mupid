package com.osundosun.mupid.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.report.entity.Listening;
import com.osundosun.mupid.api.report.entity.Mubti;
import com.osundosun.mupid.api.report.entity.Report;
import com.osundosun.mupid.api.report.enumerate.MubtiClass;
import com.osundosun.mupid.api.report.response.ReportDailyRangeResponse;
import com.osundosun.mupid.api.report.response.ReportResponse;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * `GET /api/v1/report/daily` 의 HTTP 계약 검증.
 *
 * <h2>왜 스냅샷을 심어 놓고 부르는가</h2>
 *
 * 오늘치가 아닌 날짜에 {@code reports} 행이 있으면 서비스가 ClickHouse 를 건드리지
 * 않고 그 행을 읽는다 ({@code ReportServiceImpl.getDailyReport} 의
 * {@code stored.isPresent()} 갈래). 그래서 <b>분석 클러스터 없이도</b> 응답 조립을
 * 끝까지 지나갈 수 있다 — 이 테스트가 확인하려는 것은 집계가 아니라 <b>응답의 모양과
 * 경계 처리</b>이고, 집계 규칙은 이미 {@code MubtiScorerTest} 가 본다.
 *
 * <h2>무엇을 지키는 테스트인가</h2>
 *
 * <ol>
 *   <li>기록 없는 날이 <b>빠지지 않고</b> 0/빈 값으로 채워지는 것 — 화면이 카드 일곱 장을
 *       항상 그리고 {@code state} 로 "기록 없음"과 "로딩 중"을 구분하기 때문이다.</li>
 *   <li>요청한 <b>모든</b> 날짜가 순서대로 오는 것.</li>
 *   <li>기간 규칙 위반이 400 으로, 그리고 <b>조용히 잘리지 않고</b> 거절되는 것.</li>
 *   <li><b>기존 하루치 조회가 그대로인 것</b> — 이 티켓이 새 경로만 더하고 옛 계약을
 *       건드리지 않았다는 증거다.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ReportDailyRangeTest {

    /** `Asia/Seoul` 기준 오늘. 컨트롤러가 보는 값과 같은 규칙으로 만든다. */
    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));

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

    // ─────────────────────────────────────────────────────────────
    // 정상 경로

    @Test
    @DisplayName("요청한 날짜가 하루도 빠지지 않고 순서대로 온다 — 기록 없는 날은 0 으로 채운다")
    void everyRequestedDayComesBackInOrder() throws Exception {
        LocalDate withRecord = TODAY.minusDays(2);
        persistSnapshot(me, withRecord, MubtiClass.A.V, MubtiClass.B.M, MubtiClass.C.F, MubtiClass.D.C);

        LocalDate from = TODAY.minusDays(3);
        LocalDate to = TODAY.minusDays(1);
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", from.toString())
                .param("to", to.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            // 기록 있는 날 / 없는 날 둘 다 포함해 요청한 3일이 전부 온다.
            .andExpect(jsonPath("$.data.days.length()").value(3))
            .andExpect(jsonPath("$.data.days[0].date").value(from.toString()))
            .andExpect(jsonPath("$.data.days[1].date").value(withRecord.toString()))
            .andExpect(jsonPath("$.data.days[2].date").value(to.toString()))
            // 기록 없는 날은 빼지 않고 0 으로 채운다.
            .andExpect(jsonPath("$.data.days[0].total_listening_ms").value(0))
            .andExpect(jsonPath("$.data.days[0].mubti").doesNotExist())
            .andExpect(jsonPath("$.data.days[0].sample.sufficient").value(false))
            // 기록 있는 날은 유형이 실린다.
            .andExpect(jsonPath("$.data.days[1].total_listening_ms").value(180_000))
            .andExpect(jsonPath("$.data.days[1].mubti.code").value("VMFC"))
            .andExpect(jsonPath("$.data.days[1].sample.sufficient").value(true));
    }

    @Test
    @DisplayName("응답이 자기 날짜 기준 시간대를 말한다")
    void responseStatesItsTimeZone() throws Exception {
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", TODAY.toString())
                .param("to", TODAY.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.time_zone").value("Asia/Seoul"))
            .andExpect(jsonPath("$.data.from").value(TODAY.toString()))
            .andExpect(jsonPath("$.data.to").value(TODAY.toString()));
    }

    @Test
    @DisplayName("하루짜리 기간도 한 칸으로 온다")
    void singleDayRange() throws Exception {
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", TODAY.toString())
                .param("to", TODAY.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days.length()").value(1));
    }

    @Test
    @DisplayName("일곱 날을 한 번에 받는다 — 화면이 쓰는 창이다")
    void weeklyWindow() throws Exception {
        LocalDate from = TODAY.minusDays(6);
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", from.toString())
                .param("to", TODAY.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days.length()").value(7))
            .andExpect(jsonPath("$.data.days[0].date").value(from.toString()))
            .andExpect(jsonPath("$.data.days[6].date").value(TODAY.toString()));
    }

    @Test
    @DisplayName("snake_case 로 나간다")
    void servesSnakeCase() throws Exception {
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", TODAY.toString())
                .param("to", TODAY.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days[0].total_listening_ms").exists())
            .andExpect(jsonPath("$.data.days[0].sample.sufficient").exists())
            .andExpect(jsonPath("$.data.time_zone").exists())
            // snake_case 가 아니면 나가지 않는다.
            .andExpect(jsonPath("$.data.days[0].totalListeningMs").doesNotExist());
    }

    // ─────────────────────────────────────────────────────────────
    // 경계값 · 오류

    @Test
    @DisplayName("미래가 낀 기간은 400 이다")
    void rejectsFuture() throws Exception {
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", TODAY.minusDays(1).toString())
                .param("to", TODAY.plusDays(1).toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_REPORT_INVALID_DATE"));
    }

    @Test
    @DisplayName("뒤집힌 기간은 400 이다")
    void rejectsReversed() throws Exception {
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", TODAY.toString())
                .param("to", TODAY.minusDays(1).toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_REPORT_INVALID_DATE"));
    }

    @Test
    @DisplayName("파라미터가 없으면 400 이다 — 빈 기간을 '오늘'로 읽지 않는다")
    void rejectsMissingParams() throws Exception {
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_REPORT_INVALID_DATE"));

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", TODAY.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_REPORT_INVALID_DATE"));
    }

    @Test
    @DisplayName("31일은 통과하고 32일은 거절한다 — 조용히 자르지 않는다")
    void maxDaysBoundary() throws Exception {
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", TODAY.minusDays(30).toString())
                .param("to", TODAY.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days.length()").value(31));

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", TODAY.minusDays(31).toString())
                .param("to", TODAY.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_REPORT_RANGE_TOO_LONG"));
    }

    @Test
    @DisplayName("과도한 기간(1년)도 잘리지 않고 400 이다")
    void rejectsOverlyLongRange() throws Exception {
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", TODAY.minusYears(1).toString())
                .param("to", TODAY.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_REPORT_RANGE_TOO_LONG"));
    }

    @Test
    @DisplayName("날짜 형식이 틀리면 400 이다 (500 이 아니라)")
    void rejectsMalformedDate() throws Exception {
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", "2026-13-45")
                .param("to", TODAY.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────────────────────────────────────
    // 남의 리포트 · 인증

    @Test
    @DisplayName("남의 리포트는 보이지 않는다 — 내 날짜만 내려간다")
    void doesNotLeakOtherUsersReports() throws Exception {
        LocalDate date = TODAY.minusDays(1);
        persistSnapshot(stranger, date, MubtiClass.A.V, MubtiClass.B.M, MubtiClass.C.F, MubtiClass.D.C);
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", date.toString())
                .param("to", date.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days[0].total_listening_ms").value(0))
            .andExpect(jsonPath("$.data.days[0].mubti").doesNotExist());
    }

    @Test
    @DisplayName("토큰이 없으면 401 이다")
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", TODAY.toString())
                .param("to", TODAY.toString()))
            .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────────────────────────────────────
    // 기존 하루치 계약이 그대로인가 — 하위 호환 증거

    @Test
    @DisplayName("기존 GET /api/v1/report 는 그대로 동작한다 — 응답 필드가 하나도 줄지 않았다")
    void legacySingleDayEndpointUnchanged() throws Exception {
        LocalDate date = TODAY.minusDays(1);
        persistSnapshot(me, date, MubtiClass.A.I, MubtiClass.B.T, MubtiClass.C.S, MubtiClass.D.E);
        em.flush();

        mockMvc.perform(get("/api/v1/report")
                .param("date", date.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            // 새 경로가 생기면서 옛 계약의 필드가 사라지지 않았음을 못 박는다.
            .andExpect(jsonPath("$.data.date").value(date.toString()))
            .andExpect(jsonPath("$.data.generated_at").exists())
            .andExpect(jsonPath("$.data.source").value("STORED"))
            .andExpect(jsonPath("$.data.total_listening_ms").value(180_000))
            .andExpect(jsonPath("$.data.unmatched_ms").exists())
            .andExpect(jsonPath("$.data.tracks").isArray())
            .andExpect(jsonPath("$.data.keywords").isArray())
            .andExpect(jsonPath("$.data.mubti.code").value("ITSE"))
            .andExpect(jsonPath("$.data.sample.sufficient").value(true))
            .andExpect(jsonPath("$.data.sample.distinct_music_count").exists());
    }

    @Test
    @DisplayName("기존 하루치 조회는 date 를 생략하면 오늘을 준다 — 그 동작도 그대로다")
    void legacyDefaultRemainsToday() throws Exception {
        em.flush();

        mockMvc.perform(get("/api/v1/report")
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.date").value(TODAY.toString()));
    }

    @Test
    @DisplayName("두 경로가 같은 날 같은 유형을 말한다 — 계산이 한 곳이라는 증거")
    void bothPathsAgreeOnTheSameDay() throws Exception {
        LocalDate date = TODAY.minusDays(2);
        persistSnapshot(me, date, MubtiClass.A.V, MubtiClass.B.T, MubtiClass.C.F, MubtiClass.D.E);
        em.flush();

        String single = mockMvc.perform(get("/api/v1/report")
                .param("date", date.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String range = mockMvc.perform(get("/api/v1/report/daily")
                .param("from", date.toString())
                .param("to", date.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        String dayCode = extract(range, "\"mubti\":{\"code\":\"", "\"");
        assertEquals("VTFE", dayCode);
        assertTrue(single.contains("\"code\":\"VTFE\""),
            "하루치와 기간 조회가 같은 유형을 말해야 한다. 단일 응답: " + single);
    }

    // ─────────────────────────────────────────────────────────────
    // 응답 DTO 의 축약 규칙

    @Test
    @DisplayName("달력 줄은 본문의 무거운 필드를 담지 않는다 — 40곡 트리맵이 일곱 벌 중복되지 않는다")
    void dayRowOmitsHeavyFields() throws Exception {
        LocalDate date = TODAY.minusDays(1);
        persistSnapshot(me, date, MubtiClass.A.V, MubtiClass.B.M, MubtiClass.C.F, MubtiClass.D.C);
        em.flush();

        mockMvc.perform(get("/api/v1/report/daily")
                .param("from", date.toString())
                .param("to", date.toString())
                .with(authentication(new UsernamePasswordAuthenticationToken(me, null, List.of()))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.days[0].mubti.title").exists())
            .andExpect(jsonPath("$.data.days[0].mubti.image_url").exists())
            // 트리맵 타일·키워드·축 점수는 달력 줄에 없다.
            .andExpect(jsonPath("$.data.days[0].tracks").doesNotExist())
            .andExpect(jsonPath("$.data.days[0].keywords").doesNotExist())
            .andExpect(jsonPath("$.data.days[0].mubti.axes").doesNotExist());
    }

    @Test
    @DisplayName("축약은 하루치 하나만 받는다 — 새로 계산하지 않는다")
    void dayFromReusesSingleDayValue() {
        ReportResponse response = new ReportResponse(
            LocalDate.of(2026, 9, 15),
            java.time.LocalDateTime.of(2026, 9, 15, 21, 4, 11),
            com.osundosun.mupid.api.report.enumerate.ReportSource.STORED,
            3_540_000, 0, List.of(), List.of(),
            new ReportResponse.Mubti("VTFC", "보컬 인싸형", "부제", "설명",
                "/api/v1/storage/image/12", List.of()),
            new ReportResponse.Sample(true, null, 5, 3_540_000));

        ReportDailyRangeResponse.Day day = ReportDailyRangeResponse.Day.from(response);

        assertEquals(LocalDate.of(2026, 9, 15), day.date());
        assertEquals(3_540_000, day.totalListeningMs());
        assertEquals("VTFC", day.mubti().code());
        assertEquals("보컬 인싸형", day.mubti().title());
        assertEquals("/api/v1/storage/image/12", day.mubti().imageUrl());
        assertTrue(day.sample().sufficient());
    }

    @Test
    @DisplayName("유형이 없으면 축약도 null 이다 — 0 이나 빈 문자열로 채우지 않는다")
    void dayFromKeepsNullMubti() {
        ReportResponse response = new ReportResponse(
            LocalDate.of(2026, 9, 15),
            java.time.LocalDateTime.of(2026, 9, 15, 21, 4, 11),
            com.osundosun.mupid.api.report.enumerate.ReportSource.LIVE,
            0, 0, List.of(), List.of(), null,
            new ReportResponse.Sample(false, 0L, 0, 0));

        ReportDailyRangeResponse.Day day = ReportDailyRangeResponse.Day.from(response);

        assertEquals(0, day.totalListeningMs());
        assertEquals(null, day.mubti());
        assertEquals(false, day.sample().sufficient());
    }

    // ─────────────────────────────────────────────────────────────

    /** 문자열에서 두 표식 사이를 잘라낸다. 테스트 안에서만 쓰는 최소 파서. */
    private static String extract(String body, String after, String before) {
        int start = body.indexOf(after);
        assertTrue(start >= 0, "표식을 찾지 못했다: " + after + " / 본문: " + body);
        start += after.length();
        return body.substring(start, body.indexOf(before, start));
    }

    /**
     * 그날의 확정 스냅샷을 심는다.
     *
     * `Report` · `Listening` 은 {@code @CreatedDate} 감사 리스너를 쓰므로 `em.flush()`
     * 전에 {@code createdAt} 이 채워진다. `reports.start_date` 는 DATE 컬럼이라
     * 시각 부분은 버려지지만, 조회가 {@code date.atStartOfDay()} 로 맞추므로 그대로 둔다.
     */
    private void persistSnapshot(
        User user, LocalDate date,
        MubtiClass.A a, MubtiClass.B b, MubtiClass.C c, MubtiClass.D d
    ) {
        Mubti mubti = persistMubti(a, b, c, d);
        // 곡 FK 가 NOT NULL 이라 실제 곡이 필요하다. 없으면 컬렉션 FK 로 조용히 깨진다.
        Integer musicId = anyMusicId();

        Report report = Report.builder()
            .user(user)
            .mubti(mubti)
            .startDate(date.atStartOfDay())
            .endDate(date.plusDays(1).atStartOfDay())
            .build();
        em.persist(report);
        em.flush();

        if (musicId != null) {
            em.persist(Listening.of(report, em.getReference(com.osundosun.mupid.api.music.entity.Music.class, musicId), 180_000));
            em.flush();
        }
    }

    /** 카탈로그에 곡이 하나라도 있으면 그 번호. 개발 DB 는 비어 있지 않다. */
    private Integer anyMusicId() {
        List<?> found = em.createNativeQuery("SELECT music_id FROM music ORDER BY music_id LIMIT 1")
            .getResultList();
        return found.isEmpty() ? null : ((Number)found.getFirst()).intValue();
    }

    /**
     * `mubti` 행을 새로 넣는다.
     *
     * 16행은 부트스트랩이 채우지만(`BootstrapRunner`) `title` 에 유니크 제약이 있어
     * 기존 행을 재사용하면 다른 테스트와 부딪힌다. 제목을 무작위로 만들어 새 행을
     * 심는다 — 트랜잭션이 롤백되므로 쌓이지 않는다.
     */
    private Mubti persistMubti(MubtiClass.A a, MubtiClass.B b, MubtiClass.C c, MubtiClass.D d) {
        ObjectStorageFile image = ObjectStorageFile.builder()
            .fileType(FileType.IMG)
            .category(FileCategory.MUBTI)
            .originalFileName("test-mubti.png")
            .storageObjectKey("image/mubti/test-%s".formatted(UUID.randomUUID()))
            .fileSizeBytes(1024)
            .mime("image/png")
            .isDeleted(false)
            .build();
        em.persist(image);
        em.flush();

        Mubti mubti = Mubti.builder()
            .title("테스트유형-%s".formatted(UUID.randomUUID().toString().substring(0, 8)))
            .subtitle("부제")
            .description("설명")
            .classA(a)
            .classB(b)
            .classC(c)
            .classD(d)
            .imageFile(image)
            .build();
        em.persist(mubti);
        em.flush();
        return mubti;
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
