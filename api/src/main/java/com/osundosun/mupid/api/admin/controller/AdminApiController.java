package com.osundosun.mupid.api.admin.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.admin.response.AdminDashboardResponse;
import com.osundosun.mupid.api.admin.response.AdminResourceSummary;
import com.osundosun.mupid.api.admin.response.AdminRowPageResponse;
import com.osundosun.mupid.api.admin.response.AdminSchemaResponse;
import com.osundosun.mupid.api.admin.service.AdminDashboardService;
import com.osundosun.mupid.api.admin.service.AdminResourceService;
import com.osundosun.mupid.api.common.annotation.AdminRole;
import com.osundosun.mupid.api.common.response.BaseResponse;

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

    @GetMapping("/dashboard")
    public ResponseEntity<BaseResponse<AdminDashboardResponse>> dashboard() {
        return ResponseEntity.ok(
            BaseResponse.of(adminDashboardService.dashboard())
        );
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
