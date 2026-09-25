package com.osundosun.mupid.api.analytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;

import com.osundosun.mupid.api.analytics.dto.TrackSectionRow;
import com.osundosun.mupid.api.analytics.repository.TrackSectionRepository;
import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;
import com.osundosun.mupid.api.analytics.sync.service.TrackSectionSync.RetryOutcome;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.repository.MusicSectionAnalysisRepository;

/**
 * 미반영 기록·커밋 뒤 쓰기·재시도의 판단 (S15P21A204-451).
 *
 * <p>커밋 뒤 쓰기는 {@code @Transactional} 시험에서 돌지 않는다(매번 롤백하므로 afterCommit
 * 이 안 불린다). 그래서 그 뒤에서 하는 일을 여기서 직접 부른다. DB·ClickHouse 는 띄우지 않고,
 * 트랜잭션 관리자는 아무것도 하지 않는 가짜다 — 확인하려는 것은 <b>어느 행을 지우고 어느 행을
 * 남기는가</b>이고, 그 판단이 틀리면 곡이 조용히 사본에서 빠진다.
 */
class TrackSectionSyncTest {

    private static final int MUSIC_ID = 1001;
    private static final String KIND = "CLICKHOUSE";

    private AnalyticsSyncPendingRepository pending;
    private MusicSectionAnalysisRepository sections;
    private TrackSectionRepository clickHouse;
    private TrackSectionSync sync;

    @BeforeEach
    void setUp() {
        pending = mock(AnalyticsSyncPendingRepository.class);
        sections = mock(MusicSectionAnalysisRepository.class);
        clickHouse = mock(TrackSectionRepository.class);
        sync = new TrackSectionSync(pending, sections, clickHouse, mock(PlatformTransactionManager.class));
    }

    private static MusicSectionAnalysis section(int id, int index, String version, int start, int end) {
        return MusicSectionAnalysis.builder()
            .musicSectionAnalysisId(id)
            .index(index)
            .sectionLabel(SectionLabel.CHORUS)
            .startTimeMs(start)
            .endTimeMs(end)
            .externalId("T001001#" + index)
            .analysisVersion(version)
            .build();
    }

    private static List<TrackSectionRow> rows() {
        return List.of(new TrackSectionRow(MUSIC_ID, 5001, "v1", 0, "T001001#0", "CHORUS", 0, 30_000, null));
    }

    @Test
    @DisplayName("미반영으로 올리고 방금 남긴 revision 을 돌려준다")
    void marksPendingAndReturnsRevision() {
        when(pending.findRevision(MUSIC_ID, KIND)).thenReturn(Optional.of(7L));

        assertThat(sync.markPending(MUSIC_ID, "v1")).isEqualTo(7L);
        verify(pending).upsert(MUSIC_ID, KIND, "v1");
    }

    @Test
    @DisplayName("커밋 뒤 ClickHouse 쓰기가 실패하면 미반영 행이 남고 사유가 적힌다")
    void failedPushLeavesPendingRow() {
        doThrow(new DataAccessResourceFailureException("Connection refused"))
            .when(clickHouse).replaceSections(eq(MUSIC_ID), eq("v1"), anyList());

        boolean pushed = sync.pushAfterCommit(MUSIC_ID, "v1", rows(), 3L);

        assertThat(pushed).isFalse();
        verify(pending, never()).deleteIfRevision(anyInt(), anyString(), anyLong());
        verify(pending, never()).deletePending(anyInt(), anyString());
        verify(pending).markFailed(eq(MUSIC_ID), eq(KIND), org.mockito.ArgumentMatchers.contains("Connection refused"));
    }

    @Test
    @DisplayName("커밋 뒤 쓰기가 성공하면 자기 revision 의 미반영 행만 지운다")
    void successfulPushDeletesOnlyOwnRevision() {
        boolean pushed = sync.pushAfterCommit(MUSIC_ID, "v1", rows(), 3L);

        assertThat(pushed).isTrue();
        // 그 사이 같은 곡이 다시 기록됐으면 revision 이 올라가 있어 이 DELETE 는 0행이다.
        // 곡 단위로 지우면 뒤 요청의 미반영이 사라진다.
        verify(pending).deleteIfRevision(MUSIC_ID, KIND, 3L);
        verify(pending, never()).deletePending(anyInt(), anyString());
    }

    @Test
    @DisplayName("MySQL 쪽 미반영 삭제가 실패해도 예외를 던지지 않는다 — 이미 커밋된 요청이다")
    void deleteFailureIsSwallowed() {
        when(pending.deleteIfRevision(MUSIC_ID, KIND, 3L))
            .thenThrow(new DataAccessResourceFailureException("mysql gone"));

        assertThat(sync.pushAfterCommit(MUSIC_ID, "v1", rows(), 3L)).isTrue();
    }

    @Test
    @DisplayName("재시도는 MySQL 의 지금 구간으로 교체하고, 성공하면 미반영 행을 지운다")
    void retryReplacesWithCurrentMysqlSections() {
        when(pending.claim(MUSIC_ID, KIND)).thenReturn(Optional.of(4L));
        when(sections.findByMusic_MusicIdOrderByIndexAsc(MUSIC_ID)).thenReturn(List.of(
            section(9001, 0, "v1", 0, 12_000),
            section(9002, 1, "v1", 12_000, 40_000)));

        RetryOutcome outcome = sync.retry(MUSIC_ID);

        assertThat(outcome).isEqualTo(RetryOutcome.SYNCED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TrackSectionRow>> written = ArgumentCaptor.forClass(List.class);
        verify(clickHouse).replaceSections(eq(MUSIC_ID), eq("v1"), written.capture());
        // 실패했던 요청의 스냅숏이 아니라 **지금 MySQL 에 있는 행**이다.
        assertThat(written.getValue()).containsExactly(
            new TrackSectionRow(MUSIC_ID, 9001, "v1", 0, "T001001#0", "CHORUS", 0, 12_000, null),
            new TrackSectionRow(MUSIC_ID, 9002, "v1", 1, "T001001#1", "CHORUS", 12_000, 40_000, null));
        verify(pending).deletePending(MUSIC_ID, KIND);
    }

    @Test
    @DisplayName("재시도가 실패하면 실패 횟수를 올리고 행을 남긴다")
    void failedRetryKeepsRow() {
        when(pending.claim(MUSIC_ID, KIND)).thenReturn(Optional.of(4L));
        when(sections.findByMusic_MusicIdOrderByIndexAsc(MUSIC_ID))
            .thenReturn(List.of(section(9001, 0, "v1", 0, 12_000)));
        doThrow(new DataAccessResourceFailureException("timeout"))
            .when(clickHouse).replaceSections(eq(MUSIC_ID), eq("v1"), anyList());

        assertThat(sync.retry(MUSIC_ID)).isEqualTo(RetryOutcome.FAILED);
        verify(pending).markFailed(eq(MUSIC_ID), eq(KIND), anyString());
        verify(pending, never()).deletePending(anyInt(), anyString());
    }

    @Test
    @DisplayName("다른 인스턴스가 잡은 곡은 건드리지 않는다")
    void skipsLockedRow() {
        when(pending.claim(MUSIC_ID, KIND)).thenReturn(Optional.empty());

        assertThat(sync.retry(MUSIC_ID)).isEqualTo(RetryOutcome.SKIPPED);
        verify(clickHouse, never()).replaceSections(anyInt(), anyString(), anyList());
        verify(sections, never()).findByMusic_MusicIdOrderByIndexAsc(anyInt());
    }

    @Test
    @DisplayName("MySQL 에 구간이 없으면 쓰지 않고 기록만 치운다")
    void dropsRowWhenMysqlHasNoSections() {
        when(pending.claim(MUSIC_ID, KIND)).thenReturn(Optional.of(4L));
        when(sections.findByMusic_MusicIdOrderByIndexAsc(MUSIC_ID)).thenReturn(List.of());

        assertThat(sync.retry(MUSIC_ID)).isEqualTo(RetryOutcome.NOTHING_TO_WRITE);
        verify(clickHouse, never()).replaceSections(anyInt(), anyString(), anyList());
        verify(pending).deletePending(MUSIC_ID, KIND);
    }

    @Test
    @DisplayName("실패 사유는 컬럼 길이(500)를 넘지 않는다")
    void describeIsBounded() {
        String message = "x".repeat(2_000);
        assertThat(TrackSectionSync.describe(new RuntimeException(message))).hasSize(500);
    }

}
