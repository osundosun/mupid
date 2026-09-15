package com.osundosun.mupid.api.admin.service;

import java.util.List;
import java.util.Map;

import com.osundosun.mupid.api.admin.response.AdminResourceSummary;
import com.osundosun.mupid.api.admin.response.AdminRowPageResponse;
import com.osundosun.mupid.api.admin.response.AdminSchemaResponse;

/**
 * 자원(=엔티티) 하나를 읽고 쓰는 일 전부.
 *
 * 도메인 서비스를 거치지 않고 `EntityManager` 를 직접 쓴다. 도메인 서비스는 사용자가
 * 지켜야 할 규칙(이메일 인증, 본인 확인 …)을 담고 있어서 관리자가 데이터를 바로잡는
 * 통로로는 오히려 걸림돌이 된다. 대신 되돌릴 수 없는 것만 여기서 따로 막는다
 * (자기 계정, 마지막 관리자).
 */
public interface AdminResourceService {

    /**
     * 관리 대상 자원 전부와 각 행 수.
     */
    List<AdminResourceSummary> resources();

    AdminSchemaResponse schema(String resource);

    /**
     * @param sort      정렬할 필드 이름. null 이면 첫 식별자 필드.
     * @param direction `asc` 또는 `desc`.
     * @param keyword   문자열 컬럼 전체를 훑는 검색어. null 이면 전체.
     * @param filters   `필드 = 값` 완전 일치. 값이 `__null__` 이면 `IS NULL`.
     */
    AdminRowPageResponse list(
        String resource,
        int page,
        int size,
        String sort,
        String direction,
        String keyword,
        Map<String, String> filters
    );

    Map<String, Object> get(String resource, String id);

    Map<String, Object> create(String resource, Map<String, Object> body);

    Map<String, Object> update(String resource, String id, Map<String, Object> body);

    void delete(String resource, String id);

}
