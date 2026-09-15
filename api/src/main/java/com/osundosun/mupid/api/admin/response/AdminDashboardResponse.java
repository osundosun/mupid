package com.osundosun.mupid.api.admin.response;

import java.util.List;
import java.util.Map;

/**
 * 첫 화면. 서버가 지금 어떤 상태인지 한눈에 본다.
 */
public record AdminDashboardResponse(

    /**
     * 자원별 행 수. 분류 → 이름 순.
     */
    List<AdminResourceSummary> resources,

    /**
     * JVM / 프로세스 상태.
     */
    Map<String, Object> runtime,

    /**
     * 붙어 있는 외부 저장소(MySQL·Redis) 상태.
     */
    List<AdminDependencyStatus> dependencies,

    /**
     * 회원 통계 — 권한별·상태별 인원.
     */
    Map<String, Object> users

) {
}
