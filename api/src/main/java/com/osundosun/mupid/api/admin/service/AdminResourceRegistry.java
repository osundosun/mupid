package com.osundosun.mupid.api.admin.service;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.admin.dto.AdminAttribute;
import com.osundosun.mupid.api.admin.dto.AdminChildLink;
import com.osundosun.mupid.api.admin.dto.AdminField;
import com.osundosun.mupid.api.admin.dto.AdminResourceMeta;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;

import jakarta.persistence.Column;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.metamodel.Attribute.PersistentAttributeType;
import jakarta.persistence.metamodel.EmbeddableType;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;

import lombok.extern.slf4j.Slf4j;

/**
 * 관리자 화면이 다룰 자원 목록을 **JPA 메타모델에서** 만들어 둔다.
 *
 * 자원 목록을 손으로 적지 않는 것이 이 클래스의 존재 이유다. 엔티티가 스물아홉 개이고
 * 앞으로도 늘 텐데, 도메인마다 관리 컨트롤러를 따로 쓰면 새 엔티티가 생길 때마다 조용히
 * 빠진다. 여기서는 {@code @Entity} 면 자동으로 관리 대상이 된다.
 *
 * 한글 이름과 분류만 손으로 유지한다 — 그건 코드에서 유도할 수 없다. 표에 없는 엔티티도
 * 동작에는 지장이 없고, 클래스 이름과 "기타" 로 나온다.
 */
@Slf4j
@Component
public class AdminResourceRegistry {

    /**
     * 조회 응답에 절대 실리면 안 되는 필드. 쓰기는 된다 (비밀번호 재설정).
     */
    private static final Set<String> SENSITIVE_FIELDS = Set.of("password");

    /**
     * 화면 왼쪽 목록의 분류 순서. 여기 없는 분류는 뒤에 붙는다.
     */
    public static final List<String> CATEGORY_ORDER = List.of(
        "회원", "음악", "소셜", "재생", "스트리밍", "추천·분석", "AI", "시스템", "기타"
    );

    /**
     * FK 를 **대표 컬럼 값과 함께** 보여 주기 위해, 자원마다 "그 개체를 한 마디로 알아볼 값"
     * 하나를 손으로 정한 표다.
     *
     * <p><b>메타모델에서 유도할 수 없어서 표로 둔다.</b> `title`·`name`·`displayName`·`email`
     * 중 무엇이 그 자원을 알아보게 하는 값인지는 도메인 판단이지 구조가 아니다. "이름이
     * String 인 첫 컬럼" 같은 규칙으로 유도하면 조용히 틀린다 — `User` 는 `email` 앞에
     * `password` 가 있지 않지만 `Code` 는 `codeName` 이 `displayName` 보다 먼저고,
     * `ObjectStorageFile` 은 `originalFileName` 이 맞지만 그건 우연이다. 틀려도 화면은
     * 멀쩡히 그려지고 값만 엉뚱해지므로, 눈에 보이게 적어 두고 리뷰에서 걸리는 편이 낫다.
     *
     * <p>표에 없는 자원은 <b>지금처럼 번호만</b> 보인다. 동작에 지장이 없고, 그 자원의 FK
     * 자리는 대표값 없이 번호만 남는다([{@link #LABELS}]·[{@link #CATEGORIES}] 와 같은 성격).
     */
    private static final Map<String, String> REPRESENTATIVE_FIELDS = Map.ofEntries(
        Map.entry("User", "email"),
        Map.entry("Artist", "name"),
        Map.entry("Album", "title"),
        Map.entry("Music", "title"),
        Map.entry("Playlist", "title"),
        Map.entry("Mubti", "title"),
        Map.entry("DailyRecommendation", "title"),
        Map.entry("AiChatSession", "title"),
        Map.entry("Code", "displayName"),
        Map.entry("ObjectStorageFile", "originalFileName")
    );

    /**
     * 자식 자원으로 건너가는 링크. "이 행에 속한 것들 보기" 다.
     *
     * <p>왜 자동으로 붙이지 않는가. 자식 관계는 메타모델에서 유도할 수 있지만
     * <b>유도한 것을 다 그리면 표가 못 읽게 된다</b> — `user` 를 가리키는 자식이 열 개가
     * 넘어서 회원 행마다 링크가 열 줄씩 붙는다. 그래서 유도하지 않고 <b>보여 줄 것만</b>
     * 적는다. 표에 없는 자원은 링크가 안 나올 뿐 화면 동작에는 지장이 없다.
     *
     * <p>선언한 관계가 실제로 없으면 기동할 때 로그를 남기고 그 링크만 버린다
     * ({@link #validateChildLinks}).
     */
    private static final Map<String, List<ChildDeclaration>> CHILD_LINKS = Map.of(
        // 앨범 행에서 "이 앨범의 음악들" 을 볼 수 있게 한다. `music` 자원을 `album` 필드로
        // 걸러 여는 링크다.
        "Album", List.of(new ChildDeclaration("음악", "music", "album"))
    );

    /**
     * 자식 링크 하나의 선언. {@link AdminChildLink} 로 나가기 전의 모양이다.
     *
     * @param label         링크에 쓸 한글 이름
     * @param childResource 자식 자원 이름 (URL 조각)
     * @param filterField   자식 자원에서 부모를 가리키는 필드 이름 (필터 파라미터로 나간다)
     */
    private record ChildDeclaration(String label, String childResource, String filterField) {
    }

    private static final Map<String, String> LABELS = Map.ofEntries(
        Map.entry("User", "회원"),
        Map.entry("EmailVerification", "이메일 인증"),
        Map.entry("OnboardingSurvey", "온보딩 설문"),
        Map.entry("Music", "음악"),
        Map.entry("Album", "앨범"),
        Map.entry("Artist", "아티스트"),
        Map.entry("Lyric", "가사"),
        Map.entry("MusicAnalysis", "음악 분석"),
        Map.entry("MusicSectionAnalysis", "구간 분석"),
        Map.entry("MusicGenre", "음악-장르"),
        Map.entry("MusicMood", "음악-분위기"),
        Map.entry("MusicAnalysisInstrument", "분석-악기"),
        Map.entry("MusicSectionAnalysisMood", "구간-분위기"),
        Map.entry("MusicComment", "음악 댓글"),
        Map.entry("MusicReaction", "음악 반응"),
        Map.entry("Playlist", "플레이리스트"),
        Map.entry("PlaylistTrack", "플레이리스트 수록곡"),
        Map.entry("PlayHistory", "재생 기록"),
        Map.entry("SearchHistory", "검색 기록"),
        Map.entry("Stream", "스트림"),
        Map.entry("Chunk", "청크"),
        Map.entry("DailyRecommendation", "일일 추천"),
        Map.entry("DailyRecommendationTrack", "일일 추천 곡"),
        Map.entry("Report", "리포트"),
        Map.entry("Listening", "청취 통계"),
        Map.entry("Mubti", "MUBTI"),
        Map.entry("AiChatSession", "AI 대화 세션"),
        Map.entry("AiChatMessage", "AI 대화 메시지"),
        Map.entry("Code", "공통 코드"),
        Map.entry("ObjectStorageFile", "스토리지 파일")
    );

    private static final Map<String, String> CATEGORIES = Map.ofEntries(
        Map.entry("User", "회원"),
        Map.entry("EmailVerification", "회원"),
        Map.entry("OnboardingSurvey", "회원"),
        Map.entry("Music", "음악"),
        Map.entry("Album", "음악"),
        Map.entry("Artist", "음악"),
        Map.entry("Lyric", "음악"),
        Map.entry("MusicAnalysis", "음악"),
        Map.entry("MusicSectionAnalysis", "음악"),
        Map.entry("MusicGenre", "음악"),
        Map.entry("MusicMood", "음악"),
        Map.entry("MusicAnalysisInstrument", "음악"),
        Map.entry("MusicSectionAnalysisMood", "음악"),
        Map.entry("MusicComment", "소셜"),
        Map.entry("MusicReaction", "소셜"),
        Map.entry("Playlist", "재생"),
        Map.entry("PlaylistTrack", "재생"),
        Map.entry("PlayHistory", "재생"),
        Map.entry("SearchHistory", "재생"),
        Map.entry("Stream", "스트리밍"),
        Map.entry("Chunk", "스트리밍"),
        Map.entry("DailyRecommendation", "추천·분석"),
        Map.entry("DailyRecommendationTrack", "추천·분석"),
        Map.entry("Report", "추천·분석"),
        Map.entry("Listening", "추천·분석"),
        Map.entry("Mubti", "추천·분석"),
        Map.entry("AiChatSession", "AI"),
        Map.entry("AiChatMessage", "AI"),
        Map.entry("Code", "시스템"),
        Map.entry("ObjectStorageFile", "시스템")
    );

    private final Map<String, AdminResourceMeta> byName;
    private final Map<Class<?>, AdminResourceMeta> byType;

    public AdminResourceRegistry(EntityManagerFactory entityManagerFactory) {
        Metamodel metamodel = entityManagerFactory.getMetamodel();

        // 1차: 이름부터 전부 정해 둔다. 연관 필드가 가리키는 자원 이름을 2차에서 바로
        //      쓰려면 아직 만들지 않은 엔티티의 이름도 알아야 한다.
        Map<Class<?>, String> names = new HashMap<>();
        for (EntityType<?> entityType : metamodel.getEntities()) {
            names.put(entityType.getJavaType(), resourceName(entityType.getName()));
        }

        // 2차: 분류 → 한글 이름 순으로 정렬해 담는다. 화면 왼쪽 목록 순서가 곧 이 순서다.
        Map<String, AdminResourceMeta> sorted = new TreeMap<>();
        Map<Class<?>, AdminResourceMeta> types = new HashMap<>();
        for (EntityType<?> entityType : metamodel.getEntities()) {
            AdminResourceMeta meta = build(metamodel, entityType, names);
            sorted.put("%02d|%s".formatted(categoryOrder(meta.getCategory()), meta.getLabel()), meta);
            types.put(meta.getJavaType(), meta);
        }

        Map<String, AdminResourceMeta> ordered = new LinkedHashMap<>();
        sorted.values().forEach(meta -> ordered.put(meta.getName(), meta));

        this.byName = Collections.unmodifiableMap(ordered);
        this.byType = Collections.unmodifiableMap(types);

        // 선언한 자식 링크가 실제 관계와 맞는지 여기서 본다. 화면이 링크를 그렸는데 필터가
        // 400 으로 떨어지는 것보다, 기동할 때 로그를 남기고 링크를 안 그리는 편이 정직하다.
        validateChildLinks();
    }

    /**
     * 분류 → 한글 이름 순으로 정렬된 전체 자원.
     */
    public List<AdminResourceMeta> all() {
        return List.copyOf(byName.values());
    }

    public AdminResourceMeta find(String name) {
        return byName.get(name);
    }

    public AdminResourceMeta findByType(Class<?> javaType) {
        return byType.get(javaType);
    }

    // ------------------------------------------------------------------ 만들기

    /**
     * 자식 링크 선언이 실제 메타모델과 맞는지 확인한다.
     *
     * <p>맞지 않으면 그 자원의 링크를 통째로 버리지 않고 **어긋난 것만** 뺀다. 관계 이름을
     * 잘못 적었을 때 화면에는 링크가 사라지고 서버 로그에만 이유가 남는다 — 조용히 400 을
     * 돌려주는 것보다 낫다.
     *
     * <p>표의 키는 **엔티티 이름**(`Album`)이다. 자원 이름(`album`)은 첫 글자를 내린
     * 것이라 여기서 만들어 찾는다. 이 둘을 헷갈리면 링크가 조용히 안 붙는다 — 실제로
     * 자원 이름으로 찾았다가 아무 링크도 안 나왔다.
     */
    private void validateChildLinks() {
        for (Map.Entry<String, List<ChildDeclaration>> entry : CHILD_LINKS.entrySet()) {
            AdminResourceMeta parent = byName.get(resourceName(entry.getKey()));
            if (parent == null) {
                log.error("관리자 화면: 자식 링크의 부모 자원이 없습니다 — {}", entry.getKey());
                continue;
            }

            List<AdminChildLink> valid = new ArrayList<>();
            for (ChildDeclaration declaration : entry.getValue()) {
                AdminResourceMeta child = byName.get(declaration.childResource());
                if (child == null) {
                    log.error("관리자 화면: {} 의 자식 링크가 가리키는 자원이 없습니다 — {}",
                        entry.getKey(), declaration.childResource());
                    continue;
                }
                AdminAttribute filter = child.attribute(declaration.filterField());
                // 자식 쪽 필터는 부모의 **식별자**와 비교한다. 연관 필드(프록시)면 목록 조회가
                // LEFT JOIN 으로 대상의 식별자까지 가므로 FK 번호로 거를 수 있다.
                if (filter == null
                    || (filter.getRelationType() == null && !filter.getField().id())) {
                    log.error("관리자 화면: {} 의 자식 링크 필터가 {}.{} 에 없습니다",
                        entry.getKey(), declaration.childResource(), declaration.filterField());
                    continue;
                }
                valid.add(new AdminChildLink(
                    declaration.label(), declaration.childResource(), declaration.filterField()));
            }

            parent.setChildLinks(valid);
        }
    }

    private AdminResourceMeta build(
        Metamodel metamodel,
        EntityType<?> entityType,
        Map<Class<?>, String> names
    ) {
        Class<?> javaType = entityType.getJavaType();
        String simpleName = entityType.getName();

        SingularAttribute<?, ?> idAttribute = idAttributeOf(entityType);
        Type<?> idType = idAttribute.getType();
        boolean composite = idType.getPersistenceType() == Type.PersistenceType.EMBEDDABLE;

        Map<String, SingularAttribute<?, ?>> singulars = new HashMap<>();
        for (SingularAttribute<?, ?> attribute : entityType.getSingularAttributes()) {
            singulars.put(attribute.getName(), attribute);
        }

        Map<String, AdminAttribute> attributes = new LinkedHashMap<>();
        List<String> idFieldNames = new ArrayList<>();

        for (Field field : declaredFieldsInOrder(javaType)) {
            SingularAttribute<?, ?> attribute = singulars.get(field.getName());
            if (attribute == null) {
                // @Transient 이거나 매핑되지 않은 필드. 관리 대상이 아니다.
                continue;
            }

            boolean isId = attribute.getName().equals(idAttribute.getName());

            if (isId && composite) {
                // 복합키는 덩어리째 보여 주면 손댈 수 없다. 구성 요소를 바깥으로 펼친다.
                EmbeddableType<?> embeddable = (EmbeddableType<?>) idType;
                for (Field part : declaredFieldsInOrder(idType.getJavaType())) {
                    SingularAttribute<?, ?> partAttribute = embeddableAttribute(embeddable, part.getName());
                    if (partAttribute == null) {
                        continue;
                    }
                    AdminAttribute built = buildAttribute(metamodel, names, part, partAttribute, true, true);
                    rejectNameCollision(javaType, attributes, built.name());
                    attributes.put(built.name(), built);
                    idFieldNames.add(built.name());
                }
                continue;
            }

            AdminAttribute built = buildAttribute(metamodel, names, field, attribute, isId, false);
            rejectNameCollision(javaType, attributes, built.name());
            attributes.put(built.name(), built);
            if (isId) {
                idFieldNames.add(built.name());
            }
        }

        return AdminResourceMeta.builder()
            .name(names.get(javaType))
            .label(LABELS.getOrDefault(simpleName, simpleName))
            .table(tableNameOf(javaType, simpleName))
            .category(CATEGORIES.getOrDefault(simpleName, "기타"))
            .javaType(javaType)
            .idAttributeName(idAttribute.getName())
            .idJavaType(idType.getJavaType())
            .idMember(accessible(javaType, idAttribute.getName()))
            .composite(composite)
            .idFieldNames(List.copyOf(idFieldNames))
            .attributes(Collections.unmodifiableMap(attributes))
            .build();
    }

    /**
     * 속성 이름이 겹치면 **기동할 때 터진다.**
     *
     * 화면에 나가는 컬럼은 `attributes` 라는 `Map` 인데 키가 **필드 이름**이다. 그래서
     * 엔티티의 연관 필드 이름이 임베디드 식별자의 구성 요소 이름과 같으면, 나중에 담는
     * 쪽이 먼저 담은 것을 조용히 덮어쓴다. 덮어써진 쪽의 리플렉션 통로(`member`)가
     * 사라지는데, 식별자 구성 요소는 URL 의 `{id}` 를 조립하는 `encodeId()` 가 그 통로로
     * 읽으므로 **그 자원의 조회가 통째로 500** 이 된다.
     *
     * 실측이 이랬다 — `MusicSectionAnalysisMood` 의 연관 필드가 `moodCodeId` 였고 복합키
     * 구성 요소도 `moodCodeId` 라, `관리자 → 구간-분위기` 가
     * `IllegalArgumentException: Can not get ...Code field ...moodCodeId on ...MoodId`
     * 로 죽어 있었다 (S15P21A204-387). 목록 조회뿐 아니라 단건·수정·삭제까지 전부다.
     *
     * 조용히 잘못 그리는 것보다 **기동할 때 이름을 대고 터지는 편이 낫다.** 같은 부류가
     * 다시 들어오면 개발자가 알아채지 못한 채 배포되어 500 으로만 드러난다.
     *
     * <p>패키지 사설인 것은 테스트가 직접 부를 수 있게 하기 위해서다 — 실제로 터뜨리려면
     * 이름이 겹치는 엔티티를 새로 만들어야 하는데, 그건 이 검사가 막으려는 바로 그 짓이다.
     */
    static void rejectNameCollision(Class<?> javaType, Map<String, AdminAttribute> attributes, String name) {
        if (attributes.containsKey(name)) {
            // 괄호가 필요하다 — 없으면 `.formatted` 가 앞 문자열이 아니라 **뒤 문자열에만**
            // 붙어 `%s` 가 그대로 남는다.
            throw new IllegalStateException((
                "%s 의 '%s' 가 두 번 매핑되었습니다(임베디드 식별자 구성 요소와 필드 이름 충돌). "
                    + "관리자 화면은 필드 이름을 키로 쓰므로 한쪽이 조용히 사라지고 조회가 500 이 됩니다."
            ).formatted(javaType.getName(), name));
        }
    }

    private AdminAttribute buildAttribute(
        Metamodel metamodel,
        Map<Class<?>, String> names,
        Field field,
        SingularAttribute<?, ?> attribute,
        boolean isId,
        boolean insideId
    ) {
        Column column = field.getAnnotation(Column.class);
        JoinColumn joinColumn = field.getAnnotation(JoinColumn.class);

        PersistentAttributeType attributeType = attribute.getPersistentAttributeType();
        boolean association = attributeType == PersistentAttributeType.MANY_TO_ONE
            || attributeType == PersistentAttributeType.ONE_TO_ONE;

        Class<?> relationType = association ? attribute.getJavaType() : null;
        Class<?> valueType = association
            ? metamodel.entity(relationType).getIdType().getJavaType()
            : attribute.getJavaType();

        // 감사 필드는 AuditingEntityListener 가 저장 직전에 덮어쓴다. 입력칸을 내주면
        // 적어 넣은 값이 조용히 사라져 화면이 거짓말을 하게 된다. 아예 읽기 전용으로 둔다.
        boolean audited = field.isAnnotationPresent(CreatedDate.class)
            || field.isAnnotationPresent(LastModifiedDate.class);

        boolean generated = field.isAnnotationPresent(GeneratedValue.class);
        boolean insertable = audited ? false
            : column != null ? column.insertable()
            : joinColumn != null ? joinColumn.insertable()
            : true;
        boolean updatable = audited ? false
            : column != null ? column.updatable()
            : joinColumn != null ? joinColumn.updatable()
            : true;
        boolean nullable = isId ? false
            : column != null ? column.nullable()
            : joinColumn != null ? joinColumn.nullable()
            : attribute.isOptional();

        AdminField adminField = new AdminField(
            field.getName(),
            field.getName(),
            typeOf(valueType, association, column),
            isId,
            nullable,
            insertable && !generated,
            updatable && !isId,
            generated,
            SENSITIVE_FIELDS.contains(field.getName()),
            valueType.isEnum() ? enumOptions(valueType) : null,
            association ? names.get(relationType) : null,
            representativeFieldOf(relationType),
            thumbnailableOf(relationType)
        );

        return AdminAttribute.builder()
            .field(adminField)
            .member(accessible(field))
            .valueType(valueType)
            .relationType(relationType)
            .insideId(insideId)
            .columnName(columnNameOf(column, joinColumn))
            .build();
    }

    private static String typeOf(Class<?> valueType, boolean association, Column column) {
        if (association) {
            return "relation";
        }
        if (valueType.isEnum()) {
            return "enum";
        }
        if (valueType == Boolean.class || valueType == boolean.class) {
            return "boolean";
        }
        if (Number.class.isAssignableFrom(valueType)
            || valueType == int.class || valueType == long.class
            || valueType == short.class || valueType == byte.class
            || valueType == double.class || valueType == float.class) {
            return "number";
        }
        if (valueType == LocalDateTime.class
            || valueType == Instant.class
            || valueType == OffsetDateTime.class
            || valueType == ZonedDateTime.class
            || java.util.Date.class.isAssignableFrom(valueType)) {
            return "datetime";
        }
        if (valueType == LocalDate.class) {
            return "date";
        }
        if (valueType == LocalTime.class) {
            return "time";
        }
        if (valueType == String.class) {
            // 긴 컬럼을 한 줄짜리 입력칸에 넣으면 못 쓴다. 여러 줄 입력칸으로 보낸다.
            String definition = column == null ? "" : column.columnDefinition().toLowerCase();
            boolean longText = column != null && column.length() > 255
                || definition.contains("text")
                || definition.contains("json");
            return longText ? "text" : "string";
        }
        return "other";
    }

    private static String columnNameOf(Column column, JoinColumn joinColumn) {
        if (column != null && !column.name().isBlank()) {
            return column.name();
        }
        if (joinColumn != null && !joinColumn.name().isBlank()) {
            return joinColumn.name();
        }
        return null;
    }

    /**
     * 연관 대상 자원에서 "그 개체를 알아보게 하는 컬럼" 을 고른다.
     *
     * <p>표에 없으면 null 이고 화면은 번호만 그린다. 표에 이름을 적어 두었는데 그 자원에
     * 그런 필드가 없으면 그것도 null 이다 — 기동할 때 로그만 남긴다. 화면이 링크를 못
     * 그리는 것보다 터지는 편이 나쁘다.
     *
     * @param relationType 연관 필드가 가리키는 엔티티 클래스. 연관이 아니면 null.
     */
    private String representativeFieldOf(Class<?> relationType) {
        if (relationType == null) {
            return null;
        }
        String entityName = relationType.getSimpleName();
        String fieldName = REPRESENTATIVE_FIELDS.get(entityName);
        if (fieldName == null) {
            return null;
        }
        for (Field field : declaredFieldsInOrder(relationType)) {
            if (field.getName().equals(fieldName)) {
                return fieldName;
            }
        }
        log.error("관리자 화면: {} 의 대표 컬럼으로 적은 {} 이 없습니다 — 번호만 표시합니다",
            entityName, fieldName);
        return null;
    }

    /**
     * 연관 대상이 작은 이미지를 가질 수 있는 자원인가.
     *
     * <p>둘 중 하나면 참이다 — 대상이 이미지 파일 자체(`User.imageFile` 처럼)이거나,
     * 대상이 이미지 필드를 들고 있다(`Album` 의 `Artist` — 앨범 행에 아티스트 사진을
     * 그리는 자리). 판단 기준은 {@link AdminRowProjection} 과 같아야 한다. 여기서 참이라
     * 보낸 필드에 값이 안 오면 화면은 "이미지 없음" 을 그리고, 거짓이면 아예 안 그린다.
     */
    private static boolean thumbnailableOf(Class<?> relationType) {
        if (relationType == null) {
            return false;
        }
        if (ObjectStorageFile.class.equals(relationType)) {
            return true;
        }
        for (Field field : declaredFieldsInOrder(relationType)) {
            if (ObjectStorageFile.class.equals(field.getType())) {
                return true;
            }
        }
        return false;
    }

    private static List<String> enumOptions(Class<?> enumType) {
        List<String> options = new ArrayList<>();
        for (Object constant : enumType.getEnumConstants()) {
            options.add(((Enum<?>) constant).name());
        }
        return options;
    }

    private static SingularAttribute<?, ?> idAttributeOf(EntityType<?> entityType) {
        // hasSingleIdAttribute() 가 false 인 `@IdClass` 는 이 저장소에 없다. 생기면 여기서
        // 걸린다 — 조용히 잘못 그리는 것보다 기동할 때 터지는 편이 낫다.
        return entityType.getId(entityType.getIdType().getJavaType());
    }

    private static SingularAttribute<?, ?> embeddableAttribute(EmbeddableType<?> embeddable, String name) {
        for (SingularAttribute<?, ?> attribute : embeddable.getSingularAttributes()) {
            if (attribute.getName().equals(name)) {
                return attribute;
            }
        }
        return null;
    }

    /**
     * 선언 순서대로 필드를 모은다.
     *
     * 메타모델의 속성 집합은 순서를 보장하지 않아서 그대로 쓰면 컬럼이 매번 뒤바뀐다.
     * 엔티티 소스에 적힌 순서가 사람이 읽기에 가장 자연스럽다.
     */
    private static List<Field> declaredFieldsInOrder(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            chain.add(current);
        }
        Collections.reverse(chain);

        List<Field> fields = new ArrayList<>();
        for (Class<?> current : chain) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    private static Field accessible(Class<?> type, String name) {
        for (Field field : declaredFieldsInOrder(type)) {
            if (field.getName().equals(name)) {
                return accessible(field);
            }
        }
        throw new IllegalStateException("%s 에 %s 필드가 없습니다.".formatted(type.getName(), name));
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }

    private static String tableNameOf(Class<?> javaType, String fallback) {
        Table table = javaType.getAnnotation(Table.class);
        return table != null && !table.name().isBlank() ? table.name() : fallback;
    }

    private static String resourceName(String entityName) {
        return Character.toLowerCase(entityName.charAt(0)) + entityName.substring(1);
    }

    private static int categoryOrder(String category) {
        int index = CATEGORY_ORDER.indexOf(category);
        return index < 0 ? CATEGORY_ORDER.size() : index;
    }

}
