package com.osundosun.mupid.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import com.osundosun.mupid.api.analytics.dto.TrackSectionRow;
import com.osundosun.mupid.api.analytics.repository.TrackSectionRepository;

/**
 * 구간을 ClickHouse {@code track_sections} 에 갈아 끼우는 규칙 (S15P21A204-435).
 *
 * <p>ClickHouse 를 띄우지 않는다. 확인하려는 것은 <b>어떤 문장을 어떤 값으로 던지는가</b>
 * 뿐이고, 그 판단이 틀리면 표는 조용히 옛 구간을 들고 있게 된다 — 서버를 띄워도 눈으로는
 * 안 보이는 종류의 오류다. {@code verify.sh} 가 실제 클러스터 쪽을 맡는다.
 */
class TrackSectionReplaceTest {

    private static final String VERSION = "v1";

    private static TrackSectionRow section(int index, int sectionId, int startMs, int endMs) {
        return new TrackSectionRow(1001, sectionId, VERSION, index,
            "T001001#" + index, "CHORUS", startMs, endMs, 0.25, List.of("piano", "other"));
    }

    @Test
    @DisplayName("INSERT 는 스키마 선언 순서대로 값을 싣는다")
    void insertsColumnsInSchemaOrder() {
        JdbcTemplate clickHouse = mock(JdbcTemplate.class);
        when(clickHouse.queryForList(anyString(), eq(Integer.class), eq(VERSION), eq(1001)))
            .thenReturn(List.of());

        new TrackSectionRepository(clickHouse)
            .replaceSections(1001, VERSION, List.of(section(0, 5001, 0, 30_000)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Object[]>> batch = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(clickHouse).batchUpdate(sql.capture(), batch.capture());

        // 자리표시자 순서가 선언 순서와 어긋나면 곡 번호와 구간 번호가 서로의 자리에
        // 들어간다. 둘 다 UInt32 라 ClickHouse 는 아무 말도 하지 않는다.
        assertThat(sql.getValue()).contains(
            "(track_id, music_section_analysis_id, analysis_version",
            "section_index, section_uid, fn, start_ms, end_ms, energy, instruments)");

        // `track_sections`(Distributed) 가 아니라 로컬 표다. mupid_all 은 샤딩이 없어
        // 복제로 전 노드에 퍼지고, Distributed 로 넣을 때 필요한
        // `SETTINGS distributed_foreground_insert = 1` 은 **JDBC 로는 쓸 수 없다** —
        // v2 드라이버 파서가 VALUES 앞의 SETTINGS 를 모르고, 자리표시자를 하나도 못
        // 찾은 채 넘어가 ArrayIndexOutOfBoundsException 으로 터진다.
        assertThat(sql.getValue())
            .contains("INSERT INTO track_sections_local")
            .doesNotContain("SETTINGS");
        // 악기는 String[] 로 싣는다 — JDBC 가 Array(String) 으로 옮기는 모양이다(`TrackFeatureRepository` 와 같다).
        assertThat(batch.getValue()).singleElement().isEqualTo(
            new Object[] {1001, 5001, VERSION, 0, "T001001#0", "CHORUS", 0, 30_000, 0.25,
                new String[] {"piano", "other"}});
    }

    @Test
    @DisplayName("구간 수가 그대로거나 늘면 mutation 을 던지지 않는다")
    void doesNotMutateWhenEveryOldIndexIsOverwritten() {
        JdbcTemplate clickHouse = mock(JdbcTemplate.class);
        when(clickHouse.queryForList(anyString(), eq(Integer.class), eq(VERSION), eq(1001)))
            .thenReturn(List.of(0, 1));

        new TrackSectionRepository(clickHouse).replaceSections(1001, VERSION, List.of(
            section(0, 5001, 0, 30_000),
            section(1, 5002, 30_000, 60_000),
            section(2, 5003, 60_000, 90_000)));

        // 덮어쓰기로 사라지는 것에까지 mutation 을 던지면 1,000곡 배치가 파트를
        // 1,000번 재기록한다. 평소 경로에서는 아예 부르지 않아야 한다.
        verify(clickHouse, never()).execute(anyString());
    }

    @Test
    @DisplayName("재분석으로 사라진 순번만 골라 지운다")
    void deletesOnlyTheIndexesTheNewAnalysisDropped() {
        JdbcTemplate clickHouse = mock(JdbcTemplate.class);
        when(clickHouse.queryForList(anyString(), eq(Integer.class), eq(VERSION), eq(1001)))
            .thenReturn(List.of(0, 1, 2, 3));

        new TrackSectionRepository(clickHouse).replaceSections(1001, VERSION, List.of(
            section(0, 5001, 0, 30_000),
            section(2, 5003, 30_000, 60_000)));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(clickHouse).execute(sql.capture());

        // 남겨 두면 딕셔너리가 그 범위의 재생 위치를 **없어진 구간**으로 판정하고,
        // 추천이 곡에 없는 구간을 내놓는다.
        assertThat(sql.getValue())
            .contains("ALTER TABLE track_sections_local ON CLUSTER mupid_all")
            .contains("analysis_version = 'v1'")
            .contains("track_id = 1001")
            .contains("section_index IN (1,3)")
            .contains("mutations_sync = 1");
    }

    @Test
    @DisplayName("빈 목록으로는 곡의 구간을 날리지 않는다")
    void emptyRequestTouchesNothing() {
        JdbcTemplate clickHouse = mock(JdbcTemplate.class);

        assertThat(new TrackSectionRepository(clickHouse)
            .replaceSections(1001, VERSION, List.of())).isZero();

        verify(clickHouse, never()).execute(anyString());
    }

}
