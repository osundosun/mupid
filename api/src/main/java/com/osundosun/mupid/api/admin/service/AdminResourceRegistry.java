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
import com.osundosun.mupid.api.admin.dto.AdminField;
import com.osundosun.mupid.api.admin.dto.AdminResourceMeta;

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
                    attributes.put(built.name(), built);
                    idFieldNames.add(built.name());
                }
                continue;
            }

            AdminAttribute built = buildAttribute(metamodel, names, field, attribute, isId, false);
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
            association ? names.get(relationType) : null
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
