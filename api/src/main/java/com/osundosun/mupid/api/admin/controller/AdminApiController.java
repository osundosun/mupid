package com.osundosun.mupid.api.admin.controller;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.admin.dto.AdminAiPreferenceRange;
import com.osundosun.mupid.api.admin.dto.AdminConsoleTicket;
import com.osundosun.mupid.api.admin.dto.AdminUserTimelineRange;
import com.osundosun.mupid.api.admin.response.AdminAiPreferenceAggregateResponse;
import com.osundosun.mupid.api.admin.response.AdminAiPreferenceResponse;
import com.osundosun.mupid.api.admin.response.AdminClickHouseConsoleResponse;
import com.osundosun.mupid.api.admin.response.AdminDashboardResponse;
import com.osundosun.mupid.api.admin.response.AdminMusicTimelineResponse;
import com.osundosun.mupid.api.admin.response.AdminResourceSummary;
import com.osundosun.mupid.api.admin.response.AdminRowPageResponse;
import com.osundosun.mupid.api.admin.response.AdminSchemaResponse;
import com.osundosun.mupid.api.admin.response.AdminUserTimelineResponse;
import com.osundosun.mupid.api.admin.service.AdminAiPreferenceService;
import com.osundosun.mupid.api.admin.service.AdminClickHouseConsoleService;
import com.osundosun.mupid.api.admin.service.AdminClickHouseConsoleServiceImpl;
import com.osundosun.mupid.api.admin.service.AdminDashboardService;
import com.osundosun.mupid.api.admin.service.AdminResourceService;
import com.osundosun.mupid.api.admin.service.AdminTimelineService;
import com.osundosun.mupid.api.admin.service.AdminUserTimelineService;
import com.osundosun.mupid.api.admin.service.AdminUserTimelineServiceImpl;
import com.osundosun.mupid.api.common.annotation.AdminRole;
import com.osundosun.mupid.api.common.response.BaseResponse;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 관리자 화면이 쓰는 데이터 API.
 *
 * 화면(`/api/admin`)은 골격만 있는 HTML 이고, 실제 데이터는 전부 여기서 받는다.
 * 그래서 **권한 검사는 이쪽에만 있으면 된다** — `@AdminRole` 이 클래스 전체에 걸려
 * ROLE_ADMIN 이 아니면 403 이다.
 *
 * 자원 이름(`{resource}`)은 엔티티 이름의 첫 글자를 내린 것이다 (`MusicComment` →
 * `musicComment`). 목록은 `GET /api/v1/admin/resource` 에서 받는다 — 손으로 적은 목록이
 * 아니라 JPA 메타모델에서 나온 것이라 엔티티가 늘면 저절로 따라온다.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@AdminRole
public class AdminApiController {

    /**
     * 목록 조회에서 `filter.` 로 시작하는 쿼리 파라미터는 컬럼 필터로 본다.
     * 예) `?filter.role=ROLE_ADMIN&filter.status=ACTIVE`
     */
    private static final String FILTER_PREFIX = "filter.";

    private final AdminResourceService adminResourceService;
    private final AdminDashboardService adminDashboardService;
    private final AdminClickHouseConsoleService consoleService;
    private final AdminTimelineService adminTimelineService;
    private final AdminUserTimelineService adminUserTimelineService;
    private final AdminAiPreferenceService adminAiPreferenceService;

    @GetMapping("/dashboard")
    public ResponseEntity<BaseResponse<AdminDashboardResponse>> dashboard() {
        return ResponseEntity.ok(
            BaseResponse.of(adminDashboardService.dashboard())
        );
    }

    /**
     * ClickHouse 콘솔을 연다.
     *
     * 응답 본문에는 주소만 있고 **열람권은 쿠키로 나간다.** iframe 안에서 도는 것은
     * ClickHouse 가 만든 화면이라 우리가 헤더를 붙일 수 없어서다
     * (`AdminClickHouseController` 참고).
     *
     * `Path` 를 프록시 경로로 좁혀 두어 다른 API 요청에는 실리지 않는다. `Secure` 는
     * 요청이 https 로 들어왔을 때만 붙인다 — 리프레시 토큰처럼 늘 붙이면 http 로 띄우는
     * 로컬에서 브라우저가 쿠키를 버려 콘솔이 열리지 않는다.
     */
    @PostMapping("/clickhouse/console")
    public ResponseEntity<BaseResponse<AdminClickHouseConsoleResponse>> openConsole(
        HttpServletRequest request
    ) {
        AdminConsoleTicket ticket = consoleService.open();

        ResponseCookie cookie = consoleCookie(request, ticket.value(), ticket.ttl());
        AdminClickHouseConsoleResponse response = new AdminClickHouseConsoleResponse(
            AdminClickHouseConsoleServiceImpl.PLAY_PATH,
            AdminClickHouseConsoleServiceImpl.DASHBOARD_PATH,
            ticket.ttl().toSeconds()
        );

        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(BaseResponse.of(response));
    }

    /**
     * 콘솔을 닫는다. 쿠키를 비우는 것만으로는 부족하다 — 열람권이 Redis 에 남아 있으면
     * 그 값을 들고 있는 누구든 남은 시간 동안 분석 DB 를 계속 본다. 로그아웃할 때 부른다.
     */
    @DeleteMapping("/clickhouse/console")
    public ResponseEntity<BaseResponse<Void>> closeConsole(
        HttpServletRequest request,
        @CookieValue(name = AdminClickHouseController.TICKET_COOKIE, required = false) String ticket
    ) {
        consoleService.close(ticket);

        ResponseCookie cookie = consoleCookie(request, "", Duration.ZERO);
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookie.toString())
            .body(BaseResponse.message("콘솔을 닫았습니다."));
    }

    private ResponseCookie consoleCookie(HttpServletRequest request, String value, Duration maxAge) {
        return ResponseCookie
            .from(AdminClickHouseController.TICKET_COOKIE, value)
            .path(AdminClickHouseConsoleServiceImpl.PREFIX.substring(
                0, AdminClickHouseConsoleServiceImpl.PREFIX.length() - 1))
            .httpOnly(true)
            .secure(isSecure(request))
            .sameSite("Strict")
            .maxAge(maxAge)
            .build();
    }

    /**
     * 운영에서는 nginx 가 TLS 를 끊고 스프링에는 http 로 넘긴다. `request.isSecure()` 만
     * 보면 https 뒤에 있는데도 `Secure` 가 빠진다. nginx 가 붙여 주는
     * `X-Forwarded-Proto` 를 먼저 본다 (`infra/deploy/nginx.conf`).
     */
    private boolean isSecure(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-Proto");
        if (forwarded != null && !forwarded.isBlank()) {
            return "https".equalsIgnoreCase(forwarded.split(",")[0].trim());
        }
        return request.isSecure();
    }

    @GetMapping("/resource")
    public ResponseEntity<BaseResponse<List<AdminResourceSummary>>> resources() {
        return ResponseEntity.ok(
            BaseResponse.of(adminResourceService.resources())
        );
    }

    @GetMapping("/resource/{resource}/schema")
    public ResponseEntity<BaseResponse<AdminSchemaResponse>> schema(
        @PathVariable String resource
    ) {
        return ResponseEntity.ok(
            BaseResponse.of(adminResourceService.schema(resource))
        );
    }

    /**
     * 곡 하나의 **가사·구간 타임라인** (S15P21A204-380-B).
     *
     * <p>왜 자원 목록 조회로는 안 되는가. 요구는 "가사·구간·구간-분위기를 한 화면에서
     * 함께 보고 가로로 시간순 배치" 인데 셋이 **각각 별도 자원**이라 목록 조회를 세 번
     * 부르면 화면이 세 번 갈라지고, 가로축의 기준(최대 시간)도 밴드마다 달라진다.
     *
     * <p>그래도 조회 자체는 목록 조회를 그대로 쓴다 — 행의 모양(`_id`·`_labels`·
     * `_thumbnails`)이 같아야 화면이 **같은 편집기**로 수정할 수 있다. 그래서 이
     * 엔드포인트는 조회 전용이고, 저장은 기존 `PATCH /resource/{resource}/{id}` 다.
     */
    @GetMapping("/resource/music/{id}/timeline")
    public ResponseEntity<BaseResponse<AdminMusicTimelineResponse>> timeline(
        @PathVariable int id
    ) {
        return ResponseEntity.ok(
            BaseResponse.of(adminTimelineService.timeline(id))
        );
    }

    /**
     * 회원 하나의 **행동 타임라인** (S15P21A204-430).
     *
     * <p>왜 자원 목록 조회로는 안 되는가. 요구가 말하는 "행동 데이터" 는 MySQL 에 없다 —
     * 재생·스킵·되감기·좋아요는 전부 ClickHouse 의 {@code listen_events} 한 스트림이고,
     * 그 표는 엔티티가 아니라 관리 자원으로 올라오지도 않는다.
     *
     * <p>곡 타임라인과 <b>경로 모양만</b> 같고 성격은 다르다. 그쪽은 축이 곡 안의 ms 이고
     * 행을 그 자리에서 고칠 수 있지만, 이쪽은 축이 하루이고 <b>조회 전용</b>이다 —
     * 이벤트 원본은 고치지 않고 쌓기만 한다는 수집 설계 그대로다.
     *
     * @param from 창의 시작일(`YYYY-MM-DD`). 포함. {@code to} 와 <b>둘 다 주거나 둘 다
     *             생략한다</b> — 한쪽만 주면 나머지를 서버가 지어내게 되고, 화면은 자기가
     *             요청한 창을 받았다고 믿는다. 둘 다 없으면 오늘로 끝나는 30일이다
     * @param to   창의 종료일. 포함
     * @param date 시간축에 펼칠 하루. 생략하면 <b>창 안에서 활동이 있었던 가장 최근 날</b>
     *             이다 — 마지막 날을 그냥 쓰면 어제까지만 쓴 사용자에게 빈 화면이 열린다
     * @return 404 {@code MUPID_RECORD_NOT_FOUND} — 그 회원이 없을 때. 탈퇴 계정은
     *         <b>404 가 아니다</b>. 400 {@code MUPID_INVALID_DATE_RANGE} — 창이 뒤집혔거나
     *         미래를 포함하거나 상한(92일)을 넘거나 {@code date} 가 창 밖일 때.
     *         503 {@code MUPID_ANALYTICS_UNAVAILABLE} — ClickHouse 를 못 읽을 때
     */
    @GetMapping("/resource/user/{id}/timeline")
    public ResponseEntity<BaseResponse<AdminUserTimelineResponse>> userTimeline(
        @PathVariable int id,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        AdminUserTimelineRange range = AdminUserTimelineRange.of(
            from, to, date, LocalDate.now(AdminUserTimelineServiceImpl.ZONE));
        return ResponseEntity.ok(
            BaseResponse.of(adminUserTimelineService.timeline(id, range))
        );
    }

    /**
     * AI 추천곡을 사람들이 실제로 듣는가 — <b>AI 선호도</b>의 시간별 추이 (S15P21A204-427).
     *
     * <p>배치(`AiPreferenceBatch`)가 한 시간에 한 번 쌓아 둔 ClickHouse
     * {@code ai_preference_hourly} 를 읽기만 한다. 원본을 훑지 않으므로 창이 한 달이어도
     * 빠르다. 지금 진행 중인 시간은 아직 집계 전이라 들어 있지 않다.
     *
     * @param from 창의 시작일(`YYYY-MM-DD`). 포함. {@code to} 와 둘 다 주거나 둘 다 생략한다.
     *             둘 다 없으면 오늘로 끝나는 7일이다
     * @param to   창의 종료일. 포함
     * @return 400 {@code MUPID_INVALID_DATE_RANGE} — 창이 뒤집혔거나 미래를 포함하거나 31일을
     *         넘을 때. 503 {@code MUPID_ANALYTICS_UNAVAILABLE} — ClickHouse 를 못 읽을 때
     */
    @GetMapping("/ai-preference")
    public ResponseEntity<BaseResponse<AdminAiPreferenceResponse>> aiPreference(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        AdminAiPreferenceRange range = AdminAiPreferenceRange.of(
            from, to, LocalDate.now(AdminUserTimelineServiceImpl.ZONE));
        return ResponseEntity.ok(
            BaseResponse.of(adminAiPreferenceService.preference(range))
        );
    }

    /**
     * AI 선호도를 원본에서 <b>다시 집계</b>한다 (S15P21A204-427).
     *
     * <p>표를 처음 만든 뒤 지난 기간을 채우거나, 배치가 여섯 시간 넘게 멈췄던 구간을 메울 때
     * 부른다. 몇 번을 불러도 같은 값이 된다 — 덧셈이 아니라 대입이다. 창 규칙은 조회와 같다.
     */
    @PostMapping("/ai-preference/aggregate")
    public ResponseEntity<BaseResponse<AdminAiPreferenceAggregateResponse>> aggregateAiPreference(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        AdminAiPreferenceRange range = AdminAiPreferenceRange.of(
            from, to, LocalDate.now(AdminUserTimelineServiceImpl.ZONE));
        return ResponseEntity.ok(
            BaseResponse.of("다시 집계했습니다.", adminAiPreferenceService.aggregate(range))
        );
    }

    @GetMapping("/resource/{resource}")
    public ResponseEntity<BaseResponse<AdminRowPageResponse>> list(
        @PathVariable String resource,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size,
        @RequestParam(required = false) String sort,
        @RequestParam(defaultValue = "desc") String direction,
        @RequestParam(name = "q", required = false) String keyword,
        @RequestParam Map<String, String> params
    ) {
        return ResponseEntity.ok(
            BaseResponse.of(
                adminResourceService.list(resource, page, size, sort, direction, keyword, filters(params))
            )
        );
    }

    @GetMapping("/resource/{resource}/{id}")
    public ResponseEntity<BaseResponse<Map<String, Object>>> get(
        @PathVariable String resource,
        @PathVariable String id
    ) {
        return ResponseEntity.ok(
            BaseResponse.of(adminResourceService.get(resource, id))
        );
    }

    @PostMapping("/resource/{resource}")
    public ResponseEntity<BaseResponse<Map<String, Object>>> create(
        @PathVariable String resource,
        @RequestBody Map<String, Object> body
    ) {
        return ResponseEntity.ok(
            BaseResponse.of("추가되었습니다.", adminResourceService.create(resource, body))
        );
    }

    @PatchMapping("/resource/{resource}/{id}")
    public ResponseEntity<BaseResponse<Map<String, Object>>> update(
        @PathVariable String resource,
        @PathVariable String id,
        @RequestBody Map<String, Object> body
    ) {
        return ResponseEntity.ok(
            BaseResponse.of("수정되었습니다.", adminResourceService.update(resource, id, body))
        );
    }

    @DeleteMapping("/resource/{resource}/{id}")
    public ResponseEntity<BaseResponse<Void>> delete(
        @PathVariable String resource,
        @PathVariable String id
    ) {
        adminResourceService.delete(resource, id);
        return ResponseEntity.ok(
            BaseResponse.message("삭제되었습니다.")
        );
    }

    private static Map<String, String> filters(Map<String, String> params) {
        Map<String, String> filters = new LinkedHashMap<>();
        for (Map.Entry<String, String> param : params.entrySet()) {
            if (param.getKey().startsWith(FILTER_PREFIX)) {
                filters.put(param.getKey().substring(FILTER_PREFIX.length()), param.getValue());
            }
        }
        return filters;
    }

}
