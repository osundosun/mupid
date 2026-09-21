package com.osundosun.mupid.api.admin.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.admin.dto.AdminAttribute;
import com.osundosun.mupid.api.admin.dto.AdminResourceMeta;
import com.osundosun.mupid.api.admin.response.AdminRowPageResponse;
import com.osundosun.mupid.api.admin.service.AdminResourceRegistry;
import com.osundosun.mupid.api.admin.service.AdminResourceService;

/**
 * S15P21A204-387 재현·회귀 테스트 — 관리자 화면의 **복합키 자원 조회**.
 *
 * <p>고치기 전에는 `관리자 → 구간-분위기` 가 500 이었다.
 *
 * <pre>
 * java.lang.IllegalArgumentException: Can not get com.osundosun.mupid.api.code.entity.Code
 *   field com.osundosun.mupid.api.music.mapping.entity.MusicSectionAnalysisMood.moodCodeId
 *   on ...MusicSectionAnalysisMood$MusicSectionAnalysisMoodId
 *   at ...AdminResourceServiceImpl.read(AdminResourceServiceImpl.java:702)
 *   at ...AdminResourceServiceImpl.encodeId(AdminResourceServiceImpl.java:525)
 * </pre>
 *
 * <p>원인은 리플렉션 필드 접근 방식이 아니라 **속성 이름 충돌**이었다. 관리자 레지스트리는
 * 화면에 나갈 컬럼을 <b>필드 이름을 키로 하는 `Map`</b> 에 담는데, 연관 필드 이름이
 * 임베디드 식별자 구성 요소 이름과 같으면 나중에 담는 쪽이 앞의 것을 덮어쓴다. 그러면
 * `encodeId()` 가 그 이름으로 식별자 구성 요소를 읽으려다 **남은 쪽(연관 필드, `Code`
 * 타입)의 `Field` 를 임베디더블 인스턴스에 대고** `get` 을 부른다 — 타입이 맞지 않으니
 * JDK 가 `IllegalArgumentException` 을 던진다. 예외 메시지의 "Can not get ... on ..." 이
 * 정확히 그 뜻이다.
 *
 * <p>그래서 여기서는 세 가지를 본다.
 *
 * <ol>
 *   <li>레지스트리가 세운 메타데이터에 `moodCodeId` **하나만** 있고, 그 `member` 가
 *       임베디더블(`...Id`)의 필드이며 선언 타입이 `Integer` 다 — 이 자원이 죽은 직접
 *       원인이다.
 *   <li>그 자원의 목록 조회가 실제로 값을 돌려준다 (고치기 전에는 예외였다).
 *   <li>단건 조회가 왕복한다 — `_id` 를 조립하는 `encodeId()` 가 죽던 자리다.
 * </ol>
 *
 * <p>로컬 MySQL 의 실제 스키마를 쓴다. `music_section_analysis_mood` 는 0행이 아니라
 * 실데이터가 있지만, 테스트가 그 데이터의 존재에 기대면 다른 환경에서 거짓 실패한다.
 * 그래서 "행이 있으면 그 행으로 단건도 왕복한다" 로 조건을 걸었다.
 */
@SpringBootTest
@Transactional
class AdminCompositeKeyResourceTest {

    private static final String RESOURCE = "musicSectionAnalysisMood";

    @Autowired
    private AdminResourceRegistry registry;

    @Autowired
    private AdminResourceService resourceService;

    @Test
    @DisplayName("387: 임베디드 식별자 구성 요소와 이름이 겹치는 필드가 없다 (전수)")
    void noEntityReusesAnEmbeddedIdComponentName() {
        for (AdminResourceMeta meta : registry.all()) {
            if (!meta.isComposite()) {
                continue;
            }
            for (String idFieldName : meta.getIdFieldNames()) {
                AdminAttribute attribute = meta.attribute(idFieldName);
                assertNotNull(attribute, "%s 의 식별자 구성 요소 %s 가 메타데이터에 없다"
                    .formatted(meta.getName(), idFieldName));
                assertEquals(
                    meta.getIdJavaType(),
                    attribute.getMember().getDeclaringClass(),
                    "%s 의 '%s' 가 임베디더블의 필드가 아니라 %s 의 필드다 — 연관 필드가 같은 이름으로 "
                        .formatted(meta.getName(), idFieldName, attribute.getMember().getDeclaringClass().getName())
                        + "식별자 구성 요소를 덮어썼다는 뜻이고, 이 자원의 조회는 500 이 된다 (S15P21A204-387)");
                assertTrue(
                    !attribute.getMember().getType().isAssignableFrom(meta.getJavaType()),
                    "%s 의 '%s' 가 엔티티 타입을 담고 있다 — 속성 이름이 겹쳐 연관 필드가 남았다"
                        .formatted(meta.getName(), idFieldName));
            }
        }
    }

    @Test
    @DisplayName("387: 구간-분위기의 식별자 구성 요소는 임베디더블의 Integer 필드다")
    void sectionMoodIdComponentIsTheEmbeddableField() {
        AdminResourceMeta meta = registry.find(RESOURCE);
        assertNotNull(meta, "자원이 등록되지 않았다: " + RESOURCE);
        assertTrue(meta.isComposite(), "복합키 자원이어야 한다");
        assertEquals(
            List.of("musicSectionAnalysisId", "moodCodeId"),
            meta.getIdFieldNames(),
            "URL 의 {id} 순서가 바뀌면 기존 링크가 전부 어긋난다");

        AdminAttribute moodCodeId = meta.attribute("moodCodeId");
        assertNotNull(moodCodeId, "moodCodeId 가 메타데이터에서 사라졌다");
        assertEquals(
            "com.osundosun.mupid.api.music.mapping.entity.MusicSectionAnalysisMood$MusicSectionAnalysisMoodId",
            moodCodeId.getMember().getDeclaringClass().getName(),
            "moodCodeId 는 임베디더블의 필드여야 한다 — 아니면 encodeId 가 500 을 낸다");
        assertEquals(Integer.class, moodCodeId.getMember().getType());
        assertTrue(moodCodeId.isInsideId(), "임베디드 식별자 구성 요소로 표시되어야 한다");
    }

    @Test
    @DisplayName("387: 구간-분위기 목록 조회가 500 이 아니라 행을 돌려준다")
    void listsSectionMoodRows() {
        AdminRowPageResponse page = resourceService.list(RESOURCE, 0, 2, "musicSectionAnalysisId", "desc", null, Map.of());

        assertNotNull(page);
        assertTrue(page.total() >= 0, "행 수가 음수일 수 없다");
        assertEquals(Math.min(2, page.total()), page.content().size());
        for (Map<String, Object> row : page.content()) {
            assertNotNull(row.get("_id"), "encodeId() 가 죽던 자리다 — _id 가 나와야 한다");
            assertNotNull(row.get("moodCodeId"), "분위기 번호가 나와야 한다");
            // 연관 필드는 대상 엔티티가 아니라 식별자로 나온다. 관계 이름이 곧 필드 이름이다.
            assertEquals(row.get("moodCodeId"), row.get("moodCode"),
                "관계 필드(moodCode)와 식별자 구성 요소(moodCodeId)가 같은 값을 가리켜야 한다");
        }
    }

    @Test
    @DisplayName("387: 구간-분위기 단건 조회가 _id 로 왕복한다")
    void readsOneSectionMoodRowById() {
        AdminRowPageResponse page = resourceService.list(RESOURCE, 0, 1, "musicSectionAnalysisId", "desc", null, Map.of());
        if (page.content().isEmpty()) {
            return;                       // 이 환경에 데이터가 없다 — 목록 테스트가 그 사실을 이미 본다
        }

        String id = String.valueOf(page.content().getFirst().get("_id"));
        Map<String, Object> row = resourceService.get(RESOURCE, id);

        assertEquals(id, row.get("_id"), "encodeId(decodeId(x)) 가 왕복해야 한다");
        assertEquals(page.content().getFirst().get("moodCodeId"), row.get("moodCodeId"));
    }

    @Test
    @DisplayName("387: 복합키 자원 전부가 목록 조회에서 예외 없이 응답한다 (전수)")
    void everyCompositeResourceLists() {
        Map<String, String> failures = new LinkedHashMap<>();

        for (AdminResourceMeta meta : registry.all()) {
            if (!meta.isComposite()) {
                continue;
            }
            try {
                AdminRowPageResponse page = resourceService.list(meta.getName(), 0, 1, null, "desc", null, Map.of());
                assertNotNull(page);
                for (Map<String, Object> row : page.content()) {
                    assertNotNull(row.get("_id"), meta.getName() + " 의 _id 가 null 이다");
                }
            } catch (RuntimeException e) {
                // 하나가 깨져도 나머지 조사는 계속한다 — 몇 개가 같은 부류인지 세는 게 목적이다.
                failures.put(meta.getName(), e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        assertTrue(failures.isEmpty(), "복합키 자원 조회가 깨졌다: " + failures);
    }

    @Test
    @DisplayName("387: 식별자 구성 요소 이름이 겹치면 레지스트리가 기동 시 거절한다")
    void registryRejectsANameCollision() {
        AdminResourceMeta meta = registry.find(RESOURCE);
        Map<String, AdminAttribute> taken = new LinkedHashMap<>();
        taken.put("moodCodeId", meta.attribute("moodCodeId"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> AdminResourceRegistry.rejectNameCollision(meta.getJavaType(), taken, "moodCodeId"));

        assertTrue(thrown.getMessage().contains("moodCodeId"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("500"), "왜 문제인지 메시지가 말해야 한다: " + thrown.getMessage());
    }

}
