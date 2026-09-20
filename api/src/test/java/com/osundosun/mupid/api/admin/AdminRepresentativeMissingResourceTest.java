package com.osundosun.mupid.api.admin;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.admin.dto.AdminAttribute;
import com.osundosun.mupid.api.admin.dto.AdminResourceMeta;
import com.osundosun.mupid.api.admin.service.AdminResourceRegistry;
import com.osundosun.mupid.api.admin.service.AdminResourceService;

/**
 * 대표 컬럼이 **정해지지 않은 연관 필드** 를 가진 자원의 목록 조회 회귀 테스트.
 *
 * <h2>왜 이 테스트가 필요한가 (380-A 회귀)</h2>
 *
 * 380-A 는 FK 자리에 대표값을 함께 그리려고, 연관 필드마다 "대표 컬럼 값 자리(offset)" 를
 * 투영에 넣었다. 그런데 대표 컬럼이 정해지지 않은 자원
 * ({@code AdminResourceRegistry.REPRESENTATIVE_FIELDS} 에 없음 — 구간-분위기 같은 복합키
 * 자원이 그렇다)은 그 자리가 <b>없어서 `-1`</b> 로 남는다. 그때 결과를 읽는 쪽이 검사 없이
 * {@code tuple.get(-1)} 을 부르면 Hibernate 의 {@code TupleImpl.get} 이 배열을 그대로
 * 인덱싱해
 *
 * <pre>
 * java.lang.ArrayIndexOutOfBoundsException: Index -1 out of bounds for length 4
 *   at org.hibernate.sql.results.internal.TupleImpl.get(TupleImpl.java:96)
 *   at AdminRowProjection.attach(AdminRowProjection.java:150)
 * </pre>
 *
 * 로 **목록 조회 전체가 500** 이 된다. {@code -1} 은 오류가 아니라 "그 필드에는 부가값이
 * 없다" 는 정상 상태이고, 그때 화면은 번호만 그려야 한다(S15P21A204-387 에서 합의된 동작).
 *
 * <h2>기존 {@code AdminRowProjectionTest} 가 왜 못 잡았나</h2>
 *
 * 그 테스트는 {@code user}·{@code album}·{@code artist} 만 본다. 셋 다 대표 컬럼이 있어서
 * `labelOffset` 이 <b>한 번도 `-1` 이 되지 않았다</b> — 경로 자체를 지나가지 않았다.
 * 그래서 여기서는 특정 자원 이름에 기대지 않고 <b>레지스트리를 훑어</b> 그런 자원을 찾아
 * 전부 조회한다. 자원 목록이 바뀌어도 같은 부류가 다시 나면 여기서 걸린다.
 *
 * <p>행이 하나도 없어도 조회는 성립해야 한다(예외가 나는 것이 문제였다). 그래서 행의 내용이
 * 아니라 "예외 없이 응답하고, 대표값이 없으면 번호만 나간다" 를 본다.
 */
@SpringBootTest
@Transactional
class AdminRepresentativeMissingResourceTest {

    @Autowired
    private AdminResourceRegistry registry;

    @Autowired
    private AdminResourceService adminResourceService;

    /**
     * 대표 컬럼이 정해지지 않은 연관 필드를 하나라도 가진 자원들.
     *
     * <p>이 목록을 손으로 적지 않는다 — 손으로 적으면 자원이 늘 때 조용히 빠진다. 대신
     * 레지스트리가 만든 메타데이터에서 <b>대표 컬럼이 비어 있는 relation 필드를 찾아</b>
     * 그 자원을 모은다.
     */
    private List<String> resourcesWithRepresentativeMissingRelation() {
        List<String> names = new ArrayList<>();
        for (AdminResourceMeta meta : registry.all()) {
            boolean hasSuchRelation = false;
            for (AdminAttribute attribute : meta.getAttributes().values()) {
                if (attribute.getRelationType() == null) {
                    continue;
                }
                if (attribute.getField().representativeField() == null) {
                    hasSuchRelation = true;
                    break;
                }
            }
            if (hasSuchRelation) {
                names.add(meta.getName());
            }
        }
        return names;
    }

    @Test
    @DisplayName("380-A 회귀: 대표 컬럼이 없는 연관 자원의 목록 조회가 예외 없이 응답한다 (전수)")
    void listDoesNotThrowWhenRepresentativeIsMissing() {
        List<String> names = resourcesWithRepresentativeMissingRelation();

        // 이 경로를 지나는 자원이 정말 있는지 먼저 확인한다. 없으면 이 테스트는 아무것도
        // 안 보는 채로 통과하고, 회귀는 조용히 되돌아온다.
        assertTrue(!names.isEmpty(),
            "대표 컬럼이 없는 연관을 가진 자원을 하나도 못 찾았다 — 자원 목록이나 대표 컬럼 "
                + "규칙이 바뀌어 이 테스트가 의미를 잃었다. 380-A 회귀가 다시 통과할 수 있는 상태다.");

        for (String name : names) {
            Map<String, Object> firstRow = adminResourceService
                .list(name, 0, 1, null, null, null, Map.of())
                .content()
                .stream()
                .findFirst()
                .orElse(null);

            if (firstRow == null) {
                // 행이 없어도 조회가 예외 없이 끝난 것이면 된다. 이것이 원래 500 이던 자리다.
                continue;
            }
            // 값이 없으면 `_labels` 키 자체를 만들지 않는 것이 규칙이다(화면이 "없음" 과
            // "빈 표" 를 구분하지 않아도 되게). 있더라도 비어 있으면 안 된다.
            Object labels = firstRow.get("_labels");
            if (labels instanceof Map<?, ?> map) {
                assertTrue(!map.isEmpty(),
                    name + ": 대표값이 없는데 `_labels` 가 빈 표로 나갔다 — 화면이 빈칸을 그린다");
            }
        }
    }

    @Test
    @DisplayName("380-A 회귀: 단건 조회도 같은 자원에서 예외 없이 왕복한다")
    void getDoesNotThrowWhenRepresentativeIsMissing() {
        for (String name : resourcesWithRepresentativeMissingRelation()) {
            String id = adminResourceService
                .list(name, 0, 1, null, null, null, Map.of())
                .content()
                .stream()
                .map(row -> String.valueOf(row.get("_id")))
                .findFirst()
                .orElse(null);
            if (id == null) {
                continue;
            }
            Map<String, Object> row = adminResourceService.get(name, id);
            assertNotNull(row, name + ": 단건 조회가 빈 값을 돌려줬다");
        }
    }
}
