package com.osundosun.mupid.api.admin.service;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.admin.dto.AdminAttribute;
import com.osundosun.mupid.api.admin.dto.AdminField;
import com.osundosun.mupid.api.admin.dto.AdminResourceMeta;
import com.osundosun.mupid.api.admin.error.AdminErrorCode;
import com.osundosun.mupid.api.admin.response.AdminResourceSummary;
import com.osundosun.mupid.api.admin.response.AdminRowPageResponse;
import com.osundosun.mupid.api.admin.response.AdminSchemaResponse;
import com.osundosun.mupid.api.analytics.sync.service.TrackSectionSync;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;
import com.osundosun.mupid.api.utils.SecurityUtils;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.PersistenceUnitUtil;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminResourceServiceImpl implements AdminResourceService {

    /**
     * 한 페이지에 허용하는 최대 행 수. 화면에서 `size` 를 크게 불러 서버를 끌어내리지 못하게 막는다.
     */
    private static final int MAX_PAGE_SIZE = 200;

    /**
     * 필터 값이 이것이면 완전 일치가 아니라 `IS NULL` 로 본다.
     * 빈 문자열은 "필터 없음" 과 구분되지 않아서 따로 약속을 둔다.
     */
    private static final String NULL_FILTER = "__null__";

    /**
     * 필터 값이 이 접두사로 시작하면 쉼표로 이은 **목록 중 하나**(`IN`)로 본다 (S15P21A204-380-B).
     *
     * <p>타임라인의 구간-분위기 밴드가 이것을 쓴다. 분위기는 구간에 달려 있어서 곡 하나의
     * 분위기를 고르려면 **그 곡의 구간 번호 여러 개**를 한 번에 걸어야 한다. 구간마다 한 번씩
     * 부르면 구간 수만큼 질의가 붙어 그대로 N+1 이 된다.
     *
     * <p>`__null__` 과 같은 성격의 약속이다 — 값을 타입에 맞춰 바꾸기 전에 해석하므로,
     * 문자열 컬럼에 이 접두사를 값으로 넣으려 하면 걸린다. 그런 값은 관리자 화면에서 쓰지
     * 않는다는 판단이 `__null__` 과 같다.
     */
    private static final String IN_FILTER = "__in__:";

    /**
     * `count(*)` 에 테이블 이름을 끼워 넣기 전에 확인하는 모양.
     */
    private static final Pattern SAFE_TABLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]*");

    private final EntityManager entityManager;
    private final EntityManagerFactory entityManagerFactory;
    private final AdminResourceRegistry registry;
    private final PasswordEncoder passwordEncoder;

    /**
     * 구간을 이 화면에서 고치면 ClickHouse 사본이 어긋난다. 같은 트랜잭션에 미반영 기록을
     * 남겨 재시도 작업이 1분 안에 MySQL 의 지금 구간으로 덮게 한다 (S15P21A204-451).
     */
    private final TrackSectionSync trackSectionSync;

    /**
     * MySQL 쪽. `JdbcTemplate` 빈이 둘이라(ClickHouse 용이 따로 있다) 못 박아 둔다.
     */
    @Qualifier("jdbcTemplate")
    private final JdbcTemplate jdbcTemplate;

    // -------------------------------------------------------------------- 메타

    @Override
    public List<AdminResourceSummary> resources() {
        List<AdminResourceSummary> summaries = new ArrayList<>();
        for (AdminResourceMeta meta : registry.all()) {
            summaries.add(new AdminResourceSummary(
                meta.getName(),
                meta.getLabel(),
                meta.getTable(),
                meta.getCategory(),
                meta.isComposite(),
                count(meta)
            ));
        }
        return summaries;
    }

    @Override
    public AdminSchemaResponse schema(String resource) {
        AdminResourceMeta meta = require(resource);
        return new AdminSchemaResponse(
            meta.getName(),
            meta.getLabel(),
            meta.getTable(),
            meta.getCategory(),
            meta.isComposite(),
            meta.getIdFieldNames(),
            meta.fields(),
            meta.getChildLinks(),
            meta.getTimelineAxes(),
            meta.isEventTimeline()
        );
    }

    // -------------------------------------------------------------------- 읽기

    @Override
    @SuppressWarnings("unchecked")
    public AdminRowPageResponse list(
        String resource,
        int page,
        int size,
        String sort,
        String direction,
        String keyword,
        Map<String, String> filters
    ) {
        AdminResourceMeta meta = require(resource);
        Class<Object> javaType = (Class<Object>) meta.getJavaType();

        int safePage = Math.max(page, 0);
        int safeSize = Math.clamp(size, 1, MAX_PAGE_SIZE);

        CriteriaBuilder builder = entityManager.getCriteriaBuilder();

        CriteriaQuery<Object> query = builder.createQuery(javaType);
        Root<Object> root = query.from(javaType);
        Predicate[] where = predicates(builder, root, meta, keyword, filters);
        query.where(where);
        query.orderBy(order(builder, root, meta, sort, direction));

        /*
         * FK 자리에 함께 그릴 대표값과 썸네일을 **같은 질의로** 가져온다.
         *
         * 행마다 따로 물어보면 그대로 N+1 이라 목록이 수백 행일 때 죽는다. 값은 이미
         * `order`·필터가 쓰는 LEFT JOIN 을 그대로 타고 올라가므로 질의 수가 늘지 않는다 —
         * 목록 1 + COUNT 1 은 그대로다 (`AdminRowProjection`).
         *
         * 연관 필드가 없는 자원이거나 대표값·이미지가 없으면 투영이 비고, 그때는 예전과
         * 똑같이 엔티티만 가져온다.
         */
        AdminRowProjection projection = AdminRowProjection.of(root, meta, registry);
        boolean projected = !projection.selections().isEmpty();

        CriteriaQuery<Long> countQuery = builder.createQuery(Long.class);
        Root<Object> countRoot = countQuery.from(javaType);
        countQuery.select(builder.count(countRoot));
        countQuery.where(predicates(builder, countRoot, meta, keyword, filters));

        List<Map<String, Object>> content = new ArrayList<>(safeSize);
        long total;
        try {
            /*
             * 투영이 있으면 질의를 **Tuple 로 따로 만든다.**
             *
             * 결과 타입이 엔티티인 질의에 `multiselect` 를 걸면 하이버네이트는 그 결과를
             * 생성자로 만들려 든다 — `Cannot instantiate ... no constructor with signature`
             * 로 기동 후 첫 조회가 죽는다. 결과 타입을 Tuple 로 선언해야 행이 값 묶음으로
             * 온다. 그래서 조회가 두 갈래다: 있는 그대로 엔티티를 받거나, Tuple 로 받아
             * 평평한 Map 으로 만들거나.
             */
            if (projected) {
                CriteriaQuery<Tuple> tupleQuery = builder.createQuery(Tuple.class);
                Root<Object> tupleRoot = tupleQuery.from(javaType);
                tupleQuery.where(predicates(builder, tupleRoot, meta, keyword, filters));
                tupleQuery.orderBy(order(builder, tupleRoot, meta, sort, direction));

                // 투영은 **이 질의의 root** 로 세워야 한다. 위에서 세운 계획은 다른 root 의
                // 것이라 여기 못 쓴다.
                AdminRowProjection tupleProjection =
                    AdminRowProjection.of(tupleRoot, meta, registry);

                List<Selection<?>> selections = new ArrayList<>();
                selections.add(tupleRoot);
                selections.addAll(tupleProjection.selections());
                tupleQuery.multiselect(selections);

                List<Tuple> tuples = entityManager.createQuery(tupleQuery)
                    .setFirstResult(safePage * safeSize)
                    .setMaxResults(safeSize)
                    .getResultList();

                for (Tuple tuple : tuples) {
                    Map<String, Object> flat = toRow(meta, tuple.get(0));
                    tupleProjection.attach(tuple, flat);
                    content.add(flat);
                }
            } else {
                List<Object> rows = entityManager.createQuery(query)
                    .setFirstResult(safePage * safeSize)
                    .setMaxResults(safeSize)
                    .getResultList();
                for (Object row : rows) {
                    content.add(toRow(meta, row));
                }
            }
            total = entityManager.createQuery(countQuery).getSingleResult();
        } catch (PersistenceException e) {
            throw queryFailed(meta, e);
        }

        return AdminRowPageResponse.of(content, safePage, safeSize, total);
    }

    @Override
    public Map<String, Object> get(String resource, String id) {
        AdminResourceMeta meta = require(resource);
        return attachProjection(meta, findOrThrow(meta, id));
    }

    /**
     * 단건 조회에 FK 부가값을 붙인다.
     *
     * <p>목록과 **같은 규칙**을 쓴다. 목록에서만 대표값이 나오고 단건에서는 안 나오면
     * 화면이 갈라진다. 한 행짜리라 질의가 한 번 더 도는 값은 싸다 (N+1 이 아니다).
     *
     * <p>투영 계획은 `root.join(...)` 을 부르며 만들어지므로 **그 질의의 root 로** 세워야
     * 한다. 그래서 계획을 재사용하지 않고 여기서 다시 세운다.
     */
    private Map<String, Object> attachProjection(AdminResourceMeta meta, Object entity) {
        Map<String, Object> row = toRow(meta, entity);
        if (hasNoRelation(meta)) {
            return row;
        }

        @SuppressWarnings("unchecked")
        Class<Object> javaType = (Class<Object>) meta.getJavaType();

        Object id = read(meta.getIdMember(), entity);
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = builder.createQuery(Tuple.class);
        Root<Object> root = query.from(javaType);
        query.where(builder.equal(root.get(meta.getIdAttributeName()), id));

        AdminRowProjection projection = AdminRowProjection.of(root, meta, registry);
        if (projection.selections().isEmpty()) {
            return row;
        }

        List<Selection<?>> selections = new ArrayList<>();
        selections.add(root);
        selections.addAll(projection.selections());
        query.multiselect(selections);

        List<Tuple> found = entityManager.createQuery(query).setMaxResults(1).getResultList();
        if (!found.isEmpty()) {
            projection.attach(found.get(0), row);
        }
        return row;
    }

    /**
     * 연관 필드가 하나도 없는 자원인가. 그런 자원에는 붙일 부가값 자체가 없다.
     */
    private static boolean hasNoRelation(AdminResourceMeta meta) {
        return meta.getAttributes().values().stream()
            .noneMatch(attribute -> attribute.getRelationType() != null);
    }

    /**
     * 조회 자체가 깨졌을 때.
     *
     * 관리자 화면은 엔티티 서른 개를 전부 노출하므로, 어느 하나의 매핑이 실제 테이블과
     * 어긋나 있으면 **여기서 처음 드러난다.** 그냥 두면 `GlobalExceptionHandler` 의
     * 마지막 폴백에 먹혀 "서버 내부 오류" 한 줄이 되고, 화면을 보는 사람은 무엇이
     * 잘못됐는지 알 길이 없다. 어느 자원인지와 DB 가 한 말을 로그에 남긴다.
     */
    private BusinessException queryFailed(AdminResourceMeta meta, PersistenceException e) {
        log.error("관리자 화면에서 {}({}) 조회 실패: {}", meta.getName(), meta.getTable(), e.getMessage());
        return new BusinessException(AdminErrorCode.RESOURCE_QUERY_FAILED);
    }

    // -------------------------------------------------------------------- 쓰기

    @Override
    @Transactional
    public Map<String, Object> create(String resource, Map<String, Object> body) {
        AdminResourceMeta meta = require(resource);
        Object entity = instantiate(meta.getJavaType());

        if (meta.isComposite()) {
            write(meta.getIdMember(), entity, instantiate(meta.getIdJavaType()));
        }

        apply(meta, entity, body, true);

        if (meta.isComposite()) {
            fillCompositeIdFromRelations(meta, entity);
        }

        persist(entity);
        markSectionChanged(entity, null);
        return toRow(meta, entity);
    }

    @Override
    @Transactional
    public Map<String, Object> update(String resource, String id, Map<String, Object> body) {
        AdminResourceMeta meta = require(resource);
        Object entity = findOrThrow(meta, id);

        // 값을 덮어쓰기 **전에** 막는다. 덮어쓴 뒤에 보면 "바뀌었는가" 를 알 수 없다.
        guardUserUpdate(meta, entity, body);
        Integer musicBefore = sectionMusicId(entity);

        apply(meta, entity, body, false);
        flush();
        markSectionChanged(entity, musicBefore);
        return toRow(meta, entity);
    }

    @Override
    @Transactional
    public void delete(String resource, String id) {
        AdminResourceMeta meta = require(resource);
        Object entity = findOrThrow(meta, id);

        guardUserDelete(meta, entity);

        entityManager.remove(entity);
        flush();
        markSectionChanged(entity, null);
    }

    // ------------------------------------------------------------ 사본 동기화

    /**
     * 구간 행을 만들거나 고치거나 지웠으면 그 곡을 미반영으로 올린다.
     *
     * <p>ClickHouse 에 바로 쓰지 않는다. 쓰기는 {@code TrackSectionSync} 한 곳이 맡고, 곡 단위
     * 교체라 행 하나를 고쳐도 곡 전체를 다시 쓴다 — 재시도 작업이 1분 안에 그 일을 한다.
     * 곡을 옮겼으면(`music` 을 바꿨으면) 옛 곡도 올린다 — 옛 곡의 사본에 그 구간이 남는다.
     */
    private void markSectionChanged(Object entity, Integer musicBefore) {
        if (!(entity instanceof MusicSectionAnalysis section)) {
            return;
        }
        Integer musicNow = sectionMusicId(section);
        if (musicNow != null) {
            trackSectionSync.markPending(musicNow, section.getAnalysisVersion());
        }
        if (musicBefore != null && !musicBefore.equals(musicNow)) {
            trackSectionSync.markPending(musicBefore, section.getAnalysisVersion());
        }
    }

    private static Integer sectionMusicId(Object entity) {
        if (entity instanceof MusicSectionAnalysis section && section.getMusic() != null) {
            return section.getMusic().getMusicId();
        }
        return null;
    }

    // ------------------------------------------------------------------ 질의 조립

    private Predicate[] predicates(
        CriteriaBuilder builder,
        Root<Object> root,
        AdminResourceMeta meta,
        String keyword,
        Map<String, String> filters
    ) {
        Map<String, From<?, ?>> joins = new HashMap<>();
        List<Predicate> predicates = new ArrayList<>();

        if (filters != null) {
            for (Map.Entry<String, String> filter : filters.entrySet()) {
                AdminAttribute attribute = meta.attribute(filter.getKey());
                if (attribute == null) {
                    throw new BusinessException(AdminErrorCode.UNKNOWN_FIELD);
                }
                Path<?> path = path(root, joins, meta, attribute);
                if (NULL_FILTER.equals(filter.getValue())) {
                    predicates.add(builder.isNull(path));
                } else if (filter.getValue() != null && filter.getValue().startsWith(IN_FILTER)) {
                    predicates.add(inPredicate(builder, path, attribute, filter.getValue()));
                } else {
                    predicates.add(builder.equal(path, AdminValueCodec.coerce(filter.getValue(), attribute.getValueType())));
                }
            }
        }

        if (keyword != null && !keyword.isBlank()) {
            predicates.add(searchPredicate(builder, root, joins, meta, keyword.trim()));
        }

        return predicates.toArray(Predicate[]::new);
    }

    /**
     * `IN` 필터 하나. 값은 `__in__:5,7,9` 처럼 **쉼표로 이은 목록**이다.
     *
     * <p>값이 하나도 없으면 **거짓 조건**을 만든다. 빈 `IN ()` 은 SQL 문법 오류라 질의 자체가
     * 죽고, 조건을 빼면 "아무것도 안 걸린 곡의 전부" 가 나와 화면이 다른 곡의 데이터를 그린다.
     * 둘 다 거짓말이므로 `disjunction()`(항상 거짓) 이 정직하다.
     */
    private Predicate inPredicate(
        CriteriaBuilder builder,
        Path<?> path,
        AdminAttribute attribute,
        String raw
    ) {
        List<Object> values = new ArrayList<>();
        for (String part : raw.substring(IN_FILTER.length()).split(",")) {
            if (part.isBlank()) {
                continue;
            }
            values.add(AdminValueCodec.coerce(part, attribute.getValueType()));
        }
        if (values.isEmpty()) {
            return builder.disjunction();
        }
        CriteriaBuilder.In<Object> in = builder.in(path);
        values.forEach(in::value);
        return in;
    }

    /**
     * 검색어 하나로 훑는 범위.
     *
     * 문자열 컬럼은 부분 일치, 숫자로 읽히면 숫자 컬럼과 식별자는 완전 일치다.
     * enum 은 `LIKE` 를 걸 수 없어 완전 일치일 때만 걸린다 — 목록에서 값을 골라
     * 필터로 거르는 편이 정확하므로 화면은 그쪽을 권한다.
     */
    private Predicate searchPredicate(
        CriteriaBuilder builder,
        Root<Object> root,
        Map<String, From<?, ?>> joins,
        AdminResourceMeta meta,
        String keyword
    ) {
        String like = "%" + keyword.toLowerCase() + "%";
        Long number = parseLongOrNull(keyword);

        List<Predicate> candidates = new ArrayList<>();
        for (AdminAttribute attribute : meta.getAttributes().values()) {
            AdminField field = attribute.getField();
            if (field.sensitive()) {
                continue;
            }

            String type = field.type();
            if ("string".equals(type) || "text".equals(type)) {
                Expression<String> path = path(root, joins, meta, attribute).as(String.class);
                candidates.add(builder.like(builder.lower(path), like));
            } else if ("enum".equals(type)) {
                Object match = matchingEnum(attribute.getValueType(), keyword);
                if (match != null) {
                    candidates.add(builder.equal(path(root, joins, meta, attribute), match));
                }
            } else if (number != null && ("number".equals(type) || "relation".equals(type))) {
                candidates.add(builder.equal(
                    path(root, joins, meta, attribute),
                    AdminValueCodec.coerce(keyword, attribute.getValueType())));
            }
        }

        if (candidates.isEmpty()) {
            // 훑을 컬럼이 하나도 없으면 "아무것도 없음" 이 정직하다. 조건을 빼면
            // 검색어를 무시하고 전체를 돌려주게 되는데, 그건 거짓말이다.
            return builder.disjunction();
        }
        return builder.or(candidates.toArray(Predicate[]::new));
    }

    private List<jakarta.persistence.criteria.Order> order(
        CriteriaBuilder builder,
        Root<Object> root,
        AdminResourceMeta meta,
        String sort,
        String direction
    ) {
        String fieldName = sort == null || sort.isBlank() ? meta.getIdFieldNames().get(0) : sort;
        AdminAttribute attribute = meta.attribute(fieldName);
        if (attribute == null) {
            throw new BusinessException(AdminErrorCode.UNKNOWN_FIELD);
        }

        Path<?> path = path(root, new HashMap<>(), meta, attribute);
        boolean ascending = "asc".equalsIgnoreCase(direction);
        return List.of(ascending ? builder.asc(path) : builder.desc(path));
    }

    /**
     * 필드 이름 하나를 Criteria 경로로 바꾼다.
     *
     * 세 갈래다. 복합키 구성 요소는 식별자 속성을 한 번 더 타고 들어가고, 연관 필드는
     * 대상의 식별자까지 가야 하며(LEFT JOIN — INNER 로 붙이면 FK 가 null 인 행이 조용히
     * 사라진다), 나머지는 그대로다.
     */
    private Path<?> path(
        Root<Object> root,
        Map<String, From<?, ?>> joins,
        AdminResourceMeta meta,
        AdminAttribute attribute
    ) {
        String name = attribute.getField().name();

        if (attribute.isInsideId()) {
            return root.get(meta.getIdAttributeName()).get(name);
        }
        if (attribute.getRelationType() != null) {
            From<?, ?> join = joins.computeIfAbsent(name, key -> root.join(key, JoinType.LEFT));
            AdminResourceMeta target = registry.findByType(attribute.getRelationType());
            return join.get(target.getIdAttributeName());
        }
        return root.get(name);
    }

    // -------------------------------------------------------------------- 변환

    /**
     * 엔티티 하나를 화면이 쓰는 `Map` 으로.
     *
     * 게터가 아니라 필드를 직접 읽는다. 게터를 거치면 `@ManyToOne(LAZY)` 프록시가
     * 깨어나 목록 한 화면에 수십 번의 추가 쿼리가 붙는다. 프록시 객체 그대로
     * `PersistenceUnitUtil` 에 넘기면 쿼리 없이 식별자만 꺼낼 수 있다.
     */
    private Map<String, Object> toRow(AdminResourceMeta meta, Object entity) {
        PersistenceUnitUtil persistenceUnitUtil = entityManagerFactory.getPersistenceUnitUtil();
        Object idValue = read(meta.getIdMember(), entity);

        Map<String, Object> row = new LinkedHashMap<>();
        for (AdminAttribute attribute : meta.getAttributes().values()) {
            AdminField field = attribute.getField();

            if (field.sensitive()) {
                // 해시라도 내보내지 않는다. 쓰기만 가능한 필드다.
                row.put(field.name(), null);
                continue;
            }

            Object raw = attribute.isInsideId()
                ? idValue == null ? null : read(attribute.getMember(), idValue)
                : read(attribute.getMember(), entity);

            if (raw == null) {
                row.put(field.name(), null);
            } else if (attribute.getRelationType() != null) {
                row.put(field.name(), persistenceUnitUtil.getIdentifier(raw));
            } else if (raw instanceof Enum<?> constant) {
                row.put(field.name(), constant.name());
            } else {
                row.put(field.name(), raw);
            }
        }

        row.put("_id", encodeId(meta, entity));
        return row;
    }

    private void apply(AdminResourceMeta meta, Object entity, Map<String, Object> body, boolean creating) {
        if (body == null) {
            return;
        }

        for (Map.Entry<String, Object> entry : body.entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("_")) {
                // toRow 가 붙여 준 `_id`. 화면이 행을 통째로 되돌려 보내도 걸리지 않게 흘린다.
                continue;
            }

            AdminAttribute attribute = meta.attribute(name);
            if (attribute == null) {
                throw new BusinessException(AdminErrorCode.UNKNOWN_FIELD);
            }

            AdminField field = attribute.getField();
            boolean writable = creating ? field.insertable() : field.updatable();
            if (!writable) {
                // 화면이 행 전체를 되돌려 보내는 흐름을 허용하려면 조용히 넘겨야 한다.
                // 못 고치는 필드라는 사실은 스키마에 이미 나가 있다.
                continue;
            }

            Object value = entry.getValue();

            if (field.sensitive()) {
                // 관리자가 적어 넣은 평문을 그대로 넣으면 로그인이 되지 않는다.
                // 비워 두었다면 "안 바꿈" 이다 — 빈 해시로 덮어쓰지 않는다.
                String plain = value == null ? null : String.valueOf(value);
                if (plain == null || plain.isBlank()) {
                    continue;
                }
                writeValue(meta, entity, attribute, passwordEncoder.encode(plain));
                continue;
            }

            if (attribute.getRelationType() != null) {
                writeValue(meta, entity, attribute, resolveRelation(attribute, value));
                continue;
            }

            writeValue(meta, entity, attribute, AdminValueCodec.coerce(value, attribute.getValueType()));
        }
    }

    /**
     * 연관 필드에 넣을 엔티티를 찾는다.
     *
     * `getReference` 를 쓰면 없는 식별자를 넣어도 이 자리에서는 통과하고 flush 때
     * `EntityNotFoundException` 으로 터진다 — 그건 500 이 된다. 한 번 더 조회해서
     * 404 로 정직하게 알려 준다. 관리자 쓰기는 드문 일이라 이 비용은 싸다.
     */
    private Object resolveRelation(AdminAttribute attribute, Object value) {
        Object id = AdminValueCodec.coerce(value, attribute.getValueType());
        if (id == null) {
            return null;
        }
        Object target = entityManager.find(attribute.getRelationType(), id);
        if (target == null) {
            throw new BusinessException(AdminErrorCode.RECORD_NOT_FOUND);
        }
        return target;
    }

    private void writeValue(AdminResourceMeta meta, Object entity, AdminAttribute attribute, Object value) {
        if (!attribute.isInsideId()) {
            write(attribute.getMember(), entity, value);
            return;
        }

        Object idValue = read(meta.getIdMember(), entity);
        if (idValue == null) {
            idValue = instantiate(meta.getIdJavaType());
            write(meta.getIdMember(), entity, idValue);
        }
        write(attribute.getMember(), idValue, value);
    }

    /**
     * 복합키 구성 요소가 `insertable = false` 인 엔티티를 위한 보정.
     *
     * `MusicGenre` 처럼 식별자 컬럼을 연관 쪽(`@JoinColumn`)이 쓰도록 매핑된 엔티티는,
     * 식별자 객체가 비어 있으면 persist 단계에서 터진다. 같은 컬럼을 가리키는 연관
     * 필드가 이미 채워져 있으면 거기서 값을 끌어온다.
     */
    private void fillCompositeIdFromRelations(AdminResourceMeta meta, Object entity) {
        Object idValue = read(meta.getIdMember(), entity);
        if (idValue == null) {
            return;
        }

        PersistenceUnitUtil persistenceUnitUtil = entityManagerFactory.getPersistenceUnitUtil();

        for (String idFieldName : meta.getIdFieldNames()) {
            AdminAttribute idPart = meta.attribute(idFieldName);
            if (read(idPart.getMember(), idValue) != null || idPart.getColumnName() == null) {
                continue;
            }

            for (AdminAttribute candidate : meta.getAttributes().values()) {
                if (candidate.getRelationType() == null
                    || !idPart.getColumnName().equals(candidate.getColumnName())) {
                    continue;
                }
                Object target = read(candidate.getMember(), entity);
                if (target != null) {
                    write(idPart.getMember(), idValue, persistenceUnitUtil.getIdentifier(target));
                }
                break;
            }
        }
    }

    // ------------------------------------------------------------------- 식별자

    private String encodeId(AdminResourceMeta meta, Object entity) {
        Object idValue = read(meta.getIdMember(), entity);
        if (idValue == null) {
            return null;
        }
        if (!meta.isComposite()) {
            return String.valueOf(idValue);
        }

        List<String> parts = new ArrayList<>();
        for (String idFieldName : meta.getIdFieldNames()) {
            Object part = read(meta.attribute(idFieldName).getMember(), idValue);
            parts.add(part == null ? "" : String.valueOf(part));
        }
        return String.join(",", parts);
    }

    private Object decodeId(AdminResourceMeta meta, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BusinessException(AdminErrorCode.INVALID_RECORD_ID);
        }
        if (!meta.isComposite()) {
            return AdminValueCodec.coerce(raw, meta.getIdJavaType());
        }

        String[] parts = raw.split(",", -1);
        if (parts.length != meta.getIdFieldNames().size()) {
            throw new BusinessException(AdminErrorCode.INVALID_RECORD_ID);
        }

        Object idValue = instantiate(meta.getIdJavaType());
        for (int index = 0; index < parts.length; index++) {
            AdminAttribute idPart = meta.attribute(meta.getIdFieldNames().get(index));
            write(idPart.getMember(), idValue, AdminValueCodec.coerce(parts[index], idPart.getValueType()));
        }
        return idValue;
    }

    private Object findOrThrow(AdminResourceMeta meta, String id) {
        Object entity = entityManager.find(meta.getJavaType(), decodeId(meta, id));
        if (entity == null) {
            throw new BusinessException(AdminErrorCode.RECORD_NOT_FOUND);
        }
        return entity;
    }

    // --------------------------------------------------------------- 되돌릴 수 없는 것

    /**
     * 자기 계정의 권한·상태는 이 화면에서 못 바꾼다.
     *
     * 관리자가 실수로 자기 권한을 내리면 그 순간 이 화면에 다시 들어올 수 없다. DB 를
     * 직접 고치는 것 말고는 되돌릴 방법이 없어서 아예 막는다. 다른 관리자 계정으로는
     * 바꿀 수 있다.
     */
    private void guardUserUpdate(AdminResourceMeta meta, Object entity, Map<String, Object> body) {
        if (meta.getJavaType() != User.class || body == null) {
            return;
        }

        User target = (User) entity;
        boolean self = isSelf(target);

        String nextRole = text(body.get("role"));
        String nextStatus = text(body.get("status"));

        boolean roleChanged = body.containsKey("role") && nextRole != null
            && !nextRole.equals(target.getRole().name());
        boolean leavingService = body.containsKey("status") && nextStatus != null
            && !nextStatus.equals(target.getStatus().name())
            && UserStatus.WITHDRAW.name().equals(nextStatus);

        if (self && (roleChanged || leavingService)) {
            throw new BusinessException(AdminErrorCode.SELF_MODIFICATION_NOT_ALLOWED);
        }

        boolean losingAdmin = roleChanged && !UserRole.ROLE_ADMIN.name().equals(nextRole);
        if ((losingAdmin || leavingService) && isLastAdmin(target)) {
            throw new BusinessException(AdminErrorCode.LAST_ADMIN_PROTECTED);
        }
    }

    private void guardUserDelete(AdminResourceMeta meta, Object entity) {
        if (meta.getJavaType() != User.class) {
            return;
        }

        User target = (User) entity;
        if (isSelf(target)) {
            throw new BusinessException(AdminErrorCode.SELF_MODIFICATION_NOT_ALLOWED);
        }
        if (isLastAdmin(target)) {
            throw new BusinessException(AdminErrorCode.LAST_ADMIN_PROTECTED);
        }
    }

    private boolean isSelf(User target) {
        User current = SecurityUtils.getAuthUser();
        return current != null && current.getUserId().equals(target.getUserId());
    }

    /**
     * 마지막 남은 관리자인가. 탈퇴 처리된 계정은 로그인할 수 없으므로 세지 않는다.
     */
    private boolean isLastAdmin(User target) {
        if (target.getRole() != UserRole.ROLE_ADMIN || target.getStatus() == UserStatus.WITHDRAW) {
            return false;
        }
        Long admins = entityManager
            .createQuery(
                "select count(u) from User u where u.role = :role and u.status <> :withdraw", Long.class)
            .setParameter("role", UserRole.ROLE_ADMIN)
            .setParameter("withdraw", UserStatus.WITHDRAW)
            .getSingleResult();
        return admins <= 1;
    }

    // -------------------------------------------------------------------- 잡동사니

    private AdminResourceMeta require(String resource) {
        AdminResourceMeta meta = registry.find(resource);
        if (meta == null) {
            throw new BusinessException(AdminErrorCode.RESOURCE_NOT_FOUND);
        }
        return meta;
    }

    /**
     * 행 수. 세지 못하면 **-1** 이다 (화면은 물음표로 그린다).
     *
     * JPA 가 아니라 테이블 이름으로 바로 센다. 두 가지 이유다.
     *
     * 1. **자원 하나가 깨졌다고 목록 전체가 죽으면 안 된다.** 여기는 서른 번 도는
     *    자리이고 화면 왼쪽 전부와 대시보드가 이 결과에 달려 있다. JPA 로 세다가
     *    매핑이 어긋난 엔티티에서 예외가 나면 하이버네이트가 세션을 롤백 표시해
     *    **뒤따르는 스물아홉 개까지 함께 실패한다.** 잡아 봐야 소용이 없다.
     * 2. 세는 데 매핑이 필요 없다. 컬럼 하나가 잘못 매핑돼 있어도 행 수는 셀 수 있다.
     *
     * 테이블 이름은 `@Table(name=...)` 에서 온 우리 코드의 값이지만, SQL 에 문자열로
     * 끼워 넣는 자리이므로 식별자 모양인지 한 번 확인하고 쓴다.
     */
    private long count(AdminResourceMeta meta) {
        String table = meta.getTable();
        if (!SAFE_TABLE_NAME.matcher(table).matches()) {
            log.error("관리자 화면: 테이블 이름이 식별자 모양이 아니다 ({})", table);
            return -1;
        }
        try {
            Long count = jdbcTemplate.queryForObject("select count(*) from `%s`".formatted(table), Long.class);
            return count == null ? -1 : count;
        } catch (DataAccessException e) {
            log.error("관리자 화면에서 {}({}) 세기 실패: {}", meta.getName(), table, e.getMessage());
            return -1;
        }
    }

    private void persist(Object entity) {
        try {
            entityManager.persist(entity);
            entityManager.flush();
        } catch (PersistenceException e) {
            log.warn("관리자 화면에서 저장 실패: {}", e.getMessage());
            throw new BusinessException(AdminErrorCode.RECORD_WRITE_FAILED);
        }
    }

    private void flush() {
        try {
            entityManager.flush();
        } catch (PersistenceException e) {
            log.warn("관리자 화면에서 저장 실패: {}", e.getMessage());
            throw new BusinessException(AdminErrorCode.RECORD_WRITE_FAILED);
        }
    }

    private static Object instantiate(Class<?> type) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            // 엔티티·임베더블에 기본 생성자가 없으면 JPA 자체가 뜨지 않는다. 여기까지 올 일이 없다.
            throw new IllegalStateException("%s 의 기본 생성자를 찾지 못했습니다.".formatted(type.getName()), e);
        }
    }

    private static Object read(Field member, Object owner) {
        try {
            return member.get(owner);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("%s 를 읽지 못했습니다.".formatted(member), e);
        }
    }

    private static void write(Field member, Object owner, Object value) {
        try {
            member.set(owner, value);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            throw new BusinessException(AdminErrorCode.INVALID_FIELD_VALUE);
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long parseLongOrNull(String text) {
        try {
            return Long.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Object matchingEnum(Class<?> enumType, String keyword) {
        for (Object constant : enumType.getEnumConstants()) {
            if (((Enum<?>) constant).name().equalsIgnoreCase(keyword)) {
                return constant;
            }
        }
        return null;
    }

}
