package com.osundosun.mupid.api.admin.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.admin.dto.AdminTimelineAxis;
import com.osundosun.mupid.api.admin.dto.AdminTimelineKind;
import com.osundosun.mupid.api.admin.dto.AdminAttribute;
import com.osundosun.mupid.api.admin.dto.AdminResourceMeta;
import com.osundosun.mupid.api.admin.error.AdminErrorCode;
import com.osundosun.mupid.api.admin.response.AdminMusicTimelineResponse;
import com.osundosun.mupid.api.admin.response.AdminRowPageResponse;
import com.osundosun.mupid.api.admin.response.AdminTimelineBand;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.repository.LyricRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.repository.MusicSectionAnalysisRepository;

import lombok.RequiredArgsConstructor;

/**
 * {@link AdminTimelineService} 구현.
 *
 * <p>조회는 {@link AdminResourceService#list} 를 그대로 쓴다. 요약하면 행의 모양이 목록
 * 조회와 같아야 화면이 **같은 편집기**를 쓸 수 있기 때문이다 — 저장 경로를 새로 만들지
 * 않는다는 판단의 근거가 여기 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminTimelineServiceImpl implements AdminTimelineService {

    /**
     * 밴드 하나에 담을 최대 항목 수.
     *
     * <p>가사는 실측 최대가 한 곡 75줄이라 여유가 크다. 상한을 두는 것은 이상한 데이터
     * 하나가 응답을 수 MB 로 만들지 못하게 하려는 것이다. 잘리면 응답의 `truncated` 가
     * 참이 되어 화면이 말한다 — **조용히 자르지 않는다.**
     *
     * <p>`AdminResourceServiceImpl.MAX_PAGE_SIZE`(200) 와 같거나 작아야 한다. 목록 조회가
     * 그 위를 잘라 내므로 더 크게 잡으면 온다고 믿는 만큼 오지 않는다.
     */
    private static final int MAX_BAND_ROWS = 200;

    /**
     * 화면이 쓰는 **정규화된 시간 키**. 자원마다 컬럼 이름이 다르므로(`timeMs` vs
     * `startTimeMs`) 서버가 같은 이름으로 맞춰 보낸다.
     *
     * <p>380-C 의 드래그가 이 값을 그대로 쓴다. 화면은 이 키를 `data-time-ms`·
     * `data-start-ms` 로 옮겨 적고, 드래그가 끝나면 같은 이름의 필드로 저장을 보낸다.
     */
    private static final String TIME_POINT = "_time_ms";
    private static final String TIME_START = "_start_ms";
    private static final String TIME_END = "_end_ms";

    /** 부모 행의 식별자. 화면과 380-C 가 "어느 구간의 항목인가" 를 아는 근거다. */
    private static final String PARENT_ID = "_parent_id";

    /** 자식이 자기 시간을 안 갖고 부모 시간을 빌릴 때, 부모를 곡으로 거는 기준 자원. */
    private static final String MUSIC_RESOURCE = "music";

    private final AdminResourceService adminResourceService;
    private final AdminResourceRegistry registry;
    private final MusicRepository musicRepository;
    private final LyricRepository lyricRepository;
    private final MusicSectionAnalysisRepository sectionRepository;

    @Override
    public AdminMusicTimelineResponse timeline(int musicId) {
        Music music = musicRepository.findByMusicIdAndIsDeletedFalse(musicId)
            .orElseThrow(() -> new BusinessException(AdminErrorCode.RECORD_NOT_FOUND));

        AdminResourceMeta meta = registry.find(MUSIC_RESOURCE);
        if (meta == null) {
            // 관리 대상이 아니면 축 선언도 없다. 빈 화면보다 정직하게 알린다.
            throw new BusinessException(AdminErrorCode.RESOURCE_NOT_FOUND);
        }

        Map<String, List<Map<String, Object>>> cache = new HashMap<>();
        Map<String, Long> totals = new HashMap<>();
        List<AdminTimelineBand> bands = new ArrayList<>();
        for (AdminTimelineAxis axis : meta.getTimelineAxes()) {
            List<Map<String, Object>> rows = bandRows(axis, musicId, cache, totals);
            long total = totals.getOrDefault(axis.resource(), (long) rows.size());
            // `truncated` 를 **서버가 계산해 보낸다.** 화면이 `rows.length < total` 로 직접
            // 판단하게 두면, 그 비교가 어긋나는 순간 조용히 잘린 것을 감춘다.
            bands.add(new AdminTimelineBand(axis, total, rows, rows.size() < total));
        }

        int axisMaxMs = axisMaxMs(musicId);
        return new AdminMusicTimelineResponse(
            musicId, music.getTitle(), axisMaxMs, axisMaxMs > 0, bands);
    }

    // ------------------------------------------------------------------- 밴드

    /**
     * 밴드 하나의 항목들. 시간 오름차순으로 온다.
     *
     * <p>두 갈래다.
     *
     * <ol>
     *   <li><b>자기 시간이 있는 밴드</b>(가사·구간 분석): 목록 조회를 곡으로 걸고 **시간으로
     *       정렬해** 한 번 부른다.</li>
     *   <li><b>부모의 시간을 빌리는 밴드</b>(구간-분위기): 그 표에는 시간 컬럼이 없다. 구간
     *       번호로만 곡에 닿으므로, 부모(구간)를 먼저 받아 식별자를 모으고 자식을
     *       **한 번의 `IN` 질의로** 고른다.</li>
     * </ol>
     *
     * <p>밴드마다 질의가 한 번씩이고 그 수는 행 수와 무관하다. 부모를 이미 받아 두었으면
     * 캐시에서 쓴다 — 같은 데이터를 두 번 읽지 않는다.
     */
    private List<Map<String, Object>> bandRows(
        AdminTimelineAxis axis,
        int musicId,
        Map<String, List<Map<String, Object>>> cache,
        Map<String, Long> totals
    ) {
        if (axis.ownsTime()) {
            AdminRowPageResponse page = fetchPage(
                axis.resource(), Map.of(axis.filterField(), String.valueOf(musicId)),
                axis.sortField(), "asc");
            cache.put(axis.resource(), page.content());
            totals.put(axis.resource(), page.total());
            return withOwnTime(page.content(), axis);
        }
        return withBorrowedTime(axis, musicId, cache, totals);
    }

    /**
     * 자기 시간 컬럼이 있는 밴드. 목록 조회가 이미 시간 순으로 줬으므로 **키 이름만 맞춘다** —
     * 화면이 자원마다 다른 컬럼 이름을 알 필요가 없게.
     */
    private static List<Map<String, Object>> withOwnTime(
        List<Map<String, Object>> rows,
        AdminTimelineAxis axis
    ) {
        List<Map<String, Object>> timed = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> copy = new LinkedHashMap<>(row);
            if (axis.kind() == AdminTimelineKind.POINT) {
                put(copy, TIME_POINT, row.get(axis.timeField()));
            } else {
                put(copy, TIME_START, row.get(axis.startField()));
                put(copy, TIME_END, row.get(axis.endField()));
            }
            timed.add(copy);
        }
        return timed;
    }

    /**
     * 부모의 시간을 빌리는 밴드 (구간-분위기).
     *
     * <p>부모(구간)를 곡으로 걸러 받고, 그 식별자들로 자식을 **한 번에** 고른다. 구간마다
     * 부르면 구간 수만큼 질의가 붙어 그대로 N+1 이다.
     *
     * <p>정렬은 부모의 시작 시각으로 다시 한다. 자식 자기 필드로는 시간 순이 될 수 없기
     * 때문이다(시간 컬럼이 없다). 화면이 대신 정렬하면 "서버가 준 순서" 와 "보이는 순서" 가
     * 갈라진다.
     */
    private List<Map<String, Object>> withBorrowedTime(
        AdminTimelineAxis axis,
        int musicId,
        Map<String, List<Map<String, Object>>> cache,
        Map<String, Long> totals
    ) {
        AdminResourceMeta parent = registry.find(axis.viaField());
        AdminResourceMeta music = registry.find(MUSIC_RESOURCE);
        if (parent == null || music == null) {
            return List.of();
        }

        // 부모를 곡으로 거는 필드 (구간의 `music`). 없으면 거르지 않고 전 곡을 훑게 되므로
        // 빈 밴드로 끝낸다 — 선언이 어긋난 것이고 기동 시 검증이 이미 로그를 남겼다.
        String parentMusicField = relationTo(parent, music);
        if (parentMusicField == null) {
            return List.of();
        }

        List<Map<String, Object>> parents = cache.get(parent.getName());
        if (parents == null) {
            AdminRowPageResponse page = fetchPage(parent.getName(),
                Map.of(parentMusicField, String.valueOf(musicId)),
                axis.startField(), "asc");
            parents = page.content();
            cache.put(parent.getName(), parents);
            totals.put(parent.getName(), page.total());
        }

        // 부모의 식별자 → 그 부모 행. 자식이 어느 부모에 달렸는지 되짚는 데 쓴다.
        String parentIdField = parent.getIdFieldNames().getFirst();
        Map<Object, Map<String, Object>> parentById = new HashMap<>();
        for (Map<String, Object> row : parents) {
            Object id = row.get(parentIdField);
            if (id != null) {
                parentById.put(id, row);
            }
        }
        if (parentById.isEmpty()) {
            return List.of();
        }

        AdminRowPageResponse page = fetchPage(axis.resource(),
            Map.of(axis.filterField(), inValues(parentById.keySet())),
            axis.filterField(), "asc");
        List<Map<String, Object>> children = page.content();
        cache.put(axis.resource(), children);
        totals.put(axis.resource(), page.total());

        List<Map<String, Object>> timed = new ArrayList<>(children.size());
        for (Map<String, Object> child : children) {
            Object parentId = child.get(axis.filterField());
            Map<String, Object> row = new LinkedHashMap<>(child);
            Map<String, Object> owner = parentById.get(parentId);
            if (owner != null) {
                put(row, TIME_START, owner.get(axis.startField()));
                put(row, TIME_END, owner.get(axis.endField()));
            }
            // 어느 구간의 항목인가를 남긴다. 화면이 구간 막대와 분위기를 잇는 근거다.
            if (parentId != null) {
                row.put(PARENT_ID, parentId);
            }
            timed.add(row);
        }
        timed.sort((left, right) -> compareTime(left.get(TIME_START), right.get(TIME_START)));
        return timed;
    }

    // ------------------------------------------------------------------- 축

    /**
     * 가로축의 최대값(ms). **DB 에서 집계한다.**
     *
     * <p>행에서 유도하지 않는 이유는 잘림이다. 밴드 상한에 걸려 뒤쪽 행이 빠지면 그 잘린
     * 행들로 축을 정하게 되어 **같은 곡인데 밴드마다 눈금이 달라진다** — 가사 밴드의 후렴
     * 위치와 구간 밴드의 후렴 위치가 화면에서 어긋나 보인다.
     *
     * <p>가사와 구간 중 **큰 쪽**을 쓴다. 둘 다 없으면 0 이고, 화면은 `hasTime` 이 거짓인
     * 것을 보고 "아직 분석 전" 을 말한다 — 0 으로 나누면 모든 항목이 0% 로 뭉친다.
     */
    private int axisMaxMs(int musicId) {
        Integer lyricMax = lyricRepository.maxTimeMsOfMusic(musicId);
        Integer sectionMax = sectionRepository.maxEndTimeMsOfMusic(musicId);
        return Math.max(0, Math.max(
            lyricMax == null ? 0 : lyricMax,
            sectionMax == null ? 0 : sectionMax));
    }

    // ------------------------------------------------------------------ 도구

    /**
     * 목록 조회를 그대로 부른다. **행의 모양이 같아야** 화면이 같은 편집기(`PATCH
     * /api/v1/admin/resource/{resource}/{id}`)를 쓸 수 있다.
     *
     * <p>페이지를 통째로 받는다. `total` 이 있어야 밴드가 잘렸는지 알 수 있다 — 행 수만
     * 받으면 "200개 다 왔는지 200개에서 잘렸는지" 를 구분할 수 없다.
     */
    private AdminRowPageResponse fetchPage(
        String resource,
        Map<String, String> filters,
        String sort,
        String direction
    ) {
        return adminResourceService.list(
            resource, 0, MAX_BAND_ROWS, sort, direction, null, filters);
    }

    /**
     * 시간 키를 넣는다. **값이 없으면 키를 만들지 않는다** — 380-A 의 `_labels` 와 같은
     * 규칙이다. 화면이 "0ms" 와 "시간 없음" 을 구분할 수 있어야 한다.
     */
    private static void put(Map<String, Object> row, String key, Object value) {
        Integer number = asNumber(value);
        if (number != null) {
            row.put(key, number);
        }
    }

    /**
     * 자식 자원에서 부모 자원을 가리키는 **연관 필드 이름**. 없으면 null.
     *
     * <p>여럿이면 이름이 부모 자원 이름과 같은 것을 고른다 — `MusicSectionAnalysisMood` 는
     * `musicSectionAnalysis` 와 `moodCode` 둘 다 연관이라 이름으로 가려야 한다.
     */
    private static String relationTo(AdminResourceMeta child, AdminResourceMeta parent) {
        String fallback = null;
        for (AdminAttribute attribute : child.getAttributes().values()) {
            if (!parent.getJavaType().equals(attribute.getRelationType())) {
                continue;
            }
            if (attribute.name().equals(parent.getName())) {
                return attribute.name();
            }
            if (fallback == null) {
                fallback = attribute.name();
            }
        }
        return fallback;
    }

    /** 목록 조회의 `IN` 필터 표기. `AdminResourceServiceImpl.IN_FILTER` 와 짝이다. */
    private static String inValues(java.util.Collection<Object> ids) {
        StringBuilder out = new StringBuilder("__in__:");
        boolean first = true;
        for (Object id : ids) {
            if (!first) {
                out.append(',');
            }
            out.append(id);
            first = false;
        }
        return out.toString();
    }

    private static Integer asNumber(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static int compareTime(Object left, Object right) {
        Integer a = asNumber(left);
        Integer b = asNumber(right);
        if (a == null) {
            return b == null ? 0 : -1;
        }
        return b == null ? 1 : Integer.compare(a, b);
    }

}
