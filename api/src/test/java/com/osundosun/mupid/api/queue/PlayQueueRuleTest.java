package com.osundosun.mupid.api.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.playlist.service.PlaylistService;
import com.osundosun.mupid.api.queue.dto.PlayQueueChange;
import com.osundosun.mupid.api.queue.dto.PlayQueueDocument;
import com.osundosun.mupid.api.queue.error.PlayQueueErrorCode;
import com.osundosun.mupid.api.queue.repository.PlayQueueRepository;
import com.osundosun.mupid.api.queue.service.PlayQueueServiceImpl;

/**
 * 재생 큐 규칙 검증 (decision/0022 의 지키는 규칙).
 *
 * Spring 을 띄우지 않는다. 검증 대상이 **삽입 자리와 퇴출 대상을 고르는 계산**
 * 이라 Redis 도 MySQL 도 필요 없고, 저장소는 메모리 가짜로 바꿔 끼운다.
 */
class PlayQueueRuleTest {

    private static final int USER = 1;

    private FakeRepository repository;
    private PlayQueueServiceImpl service;

    /**
     * 메모리에 문서 한 벌을 들고 있는 가짜 저장소. 판본 비교까지 흉내낸다 —
     * 재시도 경로를 검증하려면 어긋남을 만들 수 있어야 한다.
     */
    private static class FakeRepository extends PlayQueueRepository {

        private PlayQueueDocument stored = PlayQueueDocument.empty();

        /** 다음 쓰기 시도를 몇 번 어긋나게 할 것인가. 경합을 흉내낸다. */
        private int conflictsToInject;

        FakeRepository() {
            super(null);
        }

        @Override
        public PlayQueueDocument load(int userId) {
            return stored;
        }

        @Override
        public boolean compareAndSet(int userId, PlayQueueDocument document, int expectedRevision) {
            if (conflictsToInject > 0) {
                conflictsToInject--;
                // 다른 기기가 먼저 쓴 것처럼 판본만 올려 둔다.
                stored = new PlayQueueDocument(
                    stored.revision() + 1, stored.currentMusicId(), stored.musicIds());
                return false;
            }
            if (stored.revision() != expectedRevision) return false;
            stored = document;
            return true;
        }
    }

    @BeforeEach
    void setUp() {
        repository = new FakeRepository();

        MusicService musicService = mock(MusicService.class);
        // 곡의 내용은 보지 않는다. 있는 곡인지만 확인한다.
        when(musicService.getMusic(anyInt())).thenReturn(Optional.of(mock(Music.class)));

        service = new PlayQueueServiceImpl(repository, musicService, mock(PlaylistService.class));
    }

    private void given(Integer currentMusicId, Integer... musicIds) {
        repository.stored = new PlayQueueDocument(1, currentMusicId, List.of(musicIds));
    }

    @Test
    @DisplayName("담은 곡은 재생 중인 곡 바로 아래로 들어간다")
    void insertsBelowCurrent() {
        given(20, 10, 20, 30, 40);

        PlayQueueChange change = service.add(USER, 99);

        // 20 이 울리는 중이므로 99 는 그 다음에 들려야 한다. 맨 위(10 앞)에 넣으면
        // 재생 커서 뒤라 영영 재생되지 않는다.
        assertEquals(List.of(10, 20, 99, 30, 40), change.document().musicIds());
        assertFalse(change.duplicated());
        assertNull(change.evictedMusicId());
    }

    @Test
    @DisplayName("재생 중인 곡이 없으면 맨 위로 들어간다")
    void insertsAtHeadWithoutCurrent() {
        given(null, 10, 20);

        assertEquals(List.of(99, 10, 20), service.add(USER, 99).document().musicIds());
    }

    @Test
    @DisplayName("재생 중인 곡이 큐에서 빠진 상태여도 맨 위로 들어간다")
    void insertsAtHeadWhenCurrentIsGone() {
        // 재생 중인 곡을 목록에서 뺀 직후의 상태다. 재생은 계속되므로 번호는 남아 있다.
        given(77, 10, 20);

        assertEquals(List.of(99, 10, 20), service.add(USER, 99).document().musicIds());
    }

    @Test
    @DisplayName("이미 담긴 곡은 자리를 옮기지 않고 중복으로 알린다")
    void duplicatedKeepsOrder() {
        given(10, 10, 20, 30);

        PlayQueueChange change = service.add(USER, 30);

        assertTrue(change.duplicated());
        assertEquals(List.of(10, 20, 30), change.document().musicIds());
        // 판본이 오르면 화면이 서버가 바뀐 줄 알고 다시 읽는다. 아무것도 안 했으면 그대로여야 한다.
        assertEquals(1, change.document().revision());
    }

    @Test
    @DisplayName("100곡이 차면 맨 아래 곡이 밀려난다")
    void evictsFromTail() {
        // 1..100 을 담아 두고 1 번을 재생 중. 맨 아래는 100 이다.
        Integer[] full = IntStream.rangeClosed(1, PlayQueueDocument.LIMIT)
            .boxed().toArray(Integer[]::new);
        given(1, full);

        PlayQueueChange change = service.add(USER, 999);

        assertEquals(Integer.valueOf(100), change.evictedMusicId());
        assertEquals(PlayQueueDocument.LIMIT, change.document().musicIds().size());
        assertFalse(change.document().musicIds().contains(100));
        // 새 곡은 재생 중인 1 바로 아래다.
        assertEquals(List.of(1, 999, 2), change.document().musicIds().subList(0, 3));
    }

    @Test
    @DisplayName("맨 아래가 재생 중인 곡이면 그 위 곡을 대신 뺀다")
    void neverEvictsCurrent() {
        // 사용자가 재생 중인 곡을 맨 아래로 끌어다 놓은 경우다.
        List<Integer> ids = new ArrayList<>(
            IntStream.rangeClosed(1, PlayQueueDocument.LIMIT).boxed().toList());
        given(PlayQueueDocument.LIMIT, ids.toArray(Integer[]::new));

        PlayQueueChange change = service.add(USER, 999);

        // 100 이 울리는 중이므로 99 가 대신 빠지고 100 은 남는다.
        assertEquals(Integer.valueOf(99), change.evictedMusicId());
        assertTrue(change.document().musicIds().contains(PlayQueueDocument.LIMIT));
    }

    @Test
    @DisplayName("담겨 있지 않은 곡을 빼면 404 다")
    void removeMissingTrack() {
        given(10, 10, 20);

        BusinessException error = assertThrows(BusinessException.class, () -> service.remove(USER, 30));
        assertEquals(PlayQueueErrorCode.QUEUE_TRACK_NOT_FOUND, error.getErrorCode());
    }

    @Test
    @DisplayName("재생 중인 곡을 빼도 재생 대상은 그대로 남는다")
    void removingCurrentKeepsPlayback() {
        given(20, 10, 20, 30);

        PlayQueueDocument document = service.remove(USER, 20);

        assertEquals(List.of(10, 30), document.musicIds());
        // 그 곡은 실제로 울리고 있다. 여기서 지우면 화면이 재생 중이 아닌 것으로 읽는다.
        assertEquals(Integer.valueOf(20), document.currentMusicId());
    }

    @Test
    @DisplayName("판본이 어긋나면 순서 교체를 거절한다")
    void replaceRejectsStaleRevision() {
        given(10, 10, 20, 30);

        BusinessException error = assertThrows(BusinessException.class,
            () -> service.replace(USER, List.of(30, 20, 10), 10, 0));
        assertEquals(PlayQueueErrorCode.QUEUE_REVISION_CONFLICT, error.getErrorCode());
        // 거절했으면 아무것도 바뀌지 않아야 한다.
        assertEquals(List.of(10, 20, 30), repository.stored.musicIds());
    }

    @Test
    @DisplayName("같은 곡이 두 번 든 순서 교체는 거절한다")
    void replaceRejectsDuplicates() {
        given(10, 10, 20);

        BusinessException error = assertThrows(BusinessException.class,
            () -> service.replace(USER, List.of(10, 20, 10), 10, 1));
        assertEquals(PlayQueueErrorCode.QUEUE_DUPLICATED_TRACK, error.getErrorCode());
    }

    @Test
    @DisplayName("담기는 판본이 어긋나면 다시 읽고 시도한다")
    void addRetriesOnConflict() {
        given(10, 10, 20);
        repository.conflictsToInject = 1;

        PlayQueueChange change = service.add(USER, 99);

        assertEquals(List.of(10, 99, 20), change.document().musicIds());
    }

    @Test
    @DisplayName("거듭 어긋나면 저장 실패로 알린다")
    void addGivesUpAfterRetries() {
        given(10, 10, 20);
        repository.conflictsToInject = 99;

        BusinessException error = assertThrows(BusinessException.class, () -> service.add(USER, 99));
        assertEquals(PlayQueueErrorCode.QUEUE_UPDATE_FAILED, error.getErrorCode());
    }

}
