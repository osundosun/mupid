package com.osundosun.mupid.api.queue.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.playlist.dto.PlaylistDetail;
import com.osundosun.mupid.api.playlist.service.PlaylistService;
import com.osundosun.mupid.api.queue.dto.PlayQueueChange;
import com.osundosun.mupid.api.queue.dto.PlayQueueDocument;
import com.osundosun.mupid.api.queue.error.PlayQueueErrorCode;
import com.osundosun.mupid.api.queue.repository.PlayQueueRepository;

import lombok.RequiredArgsConstructor;

/**
 * 재생 큐 규칙의 본체. decision/0022 의 "지키는 규칙" 을 그대로 옮긴 것이다.
 *
 * `@Transactional` 이 없다. 본업이 Redis 라 MySQL 트랜잭션이 걸릴 것이 없고,
 * 플레이리스트 조회는 그쪽 서비스가 자기 트랜잭션 안에서 처리한다.
 */
@Service
@RequiredArgsConstructor
public class PlayQueueServiceImpl implements PlayQueueService {

    /**
     * 판본이 어긋났을 때 다시 읽고 시도하는 횟수.
     *
     * 경합하는 것은 **같은 사람의 기기 둘**뿐이라 한 번 다시 읽으면 대개 끝난다.
     * 세 번까지 실패하면 정상 경합이 아니므로 화면에 알린다.
     */
    private static final int MAX_ATTEMPTS = 3;

    private final PlayQueueRepository repository;
    private final MusicService musicService;
    private final PlaylistService playlistService;

    @Override
    public PlayQueueDocument get(int userId) {
        return repository.load(userId);
    }

    @Override
    public PlayQueueDocument replace(
        int userId,
        List<Integer> musicIds,
        Integer currentMusicId,
        int revision
    ) {
        List<Integer> ids = musicIds == null ? List.of() : musicIds;

        /*
         * 같은 곡이 두 자리를 차지하면 "이 곡을 뺀다" 가 어느 자리인지 정할 수 없다.
         * 화면도 중복을 만들지 않지만, 서버가 믿고 저장하면 한 번 들어간 중복을
         * 아무도 되돌리지 못한다.
         */
        Set<Integer> seen = new HashSet<>(ids);
        if (seen.size() != ids.size()) {
            throw new BusinessException(PlayQueueErrorCode.QUEUE_DUPLICATED_TRACK);
        }

        /*
         * 상한을 넘겨 오면 거절하지 않고 앞에서부터 상한까지만 남긴다. 순서 교체는
         * 사람이 끌어다 놓은 결과라 거절하면 화면이 방금 한 동작을 되돌려야 한다.
         * 화면이 규칙대로 보냈다면 여기 걸릴 일이 없다.
         */
        List<Integer> capped = ids.size() > PlayQueueDocument.LIMIT ? List.copyOf(ids.subList(0, PlayQueueDocument.LIMIT)) : List.copyOf(ids);

        PlayQueueDocument current = repository.load(userId);
        PlayQueueDocument updated = current.next(currentMusicId, capped);
        if (!repository.compareAndSet(userId, updated, revision)) {
            throw new BusinessException(PlayQueueErrorCode.QUEUE_REVISION_CONFLICT);
        }
        return updated;
    }

    @Override
    public PlayQueueChange add(int userId, int musicId) {
        // 없는 곡은 담기 전에 막는다. Redis 에는 제약조건이 없어 한번 들어가면
        // 조회할 때마다 조용히 걸러질 뿐 계속 남는다.
        musicService.requireAlive(musicId);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            PlayQueueDocument document = repository.load(userId);
            if (document.musicIds().contains(musicId)) {
                return PlayQueueChange.duplicated(document);
            }

            List<Integer> next = new ArrayList<>(document.musicIds());
            next.add(insertIndex(next, document.currentMusicId()), musicId);
            Integer evicted = evictIfFull(next, document.currentMusicId(), musicId);

            PlayQueueDocument updated = document.next(document.currentMusicId(), next);
            if (repository.compareAndSet(userId, updated, document.revision())) {
                return PlayQueueChange.of(updated, evicted);
            }
        }

        throw new BusinessException(PlayQueueErrorCode.QUEUE_UPDATE_FAILED);
    }

    @Override
    public PlayQueueDocument remove(int userId, int musicId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            PlayQueueDocument document = repository.load(userId);
            if (!document.musicIds().contains(musicId)) {
                throw new BusinessException(PlayQueueErrorCode.QUEUE_TRACK_NOT_FOUND);
            }

            List<Integer> next = document.musicIds().stream()
                .filter(id -> id.intValue() != musicId)
                .toList();

            /*
             * 재생 중인 곡을 빼도 `currentMusicId` 는 그대로 둔다. 그 곡은 실제로
             * 울리고 있고, 여기서 지우면 화면이 "아무것도 재생 중이 아니다" 로 읽는다.
             * 큐에 없는 번호가 되지만 담기 규칙(`insertIndex`)이 그 경우를 맨 앞으로
             * 떨어뜨리므로 문제되지 않는다.
             */
            PlayQueueDocument updated = document.next(document.currentMusicId(), next);
            if (repository.compareAndSet(userId, updated, document.revision())) {
                return updated;
            }
        }

        throw new BusinessException(PlayQueueErrorCode.QUEUE_UPDATE_FAILED);
    }

    @Override
    public PlayQueueDocument replaceFromPlaylist(int userId, int playlistId) {
        // 남의 플레이리스트이거나 없는 번호면 여기서 걸린다.
        PlaylistDetail detail = playlistService.getPlaylist(userId, playlistId);

        /*
         * 상한을 넘는 플레이리스트는 이제 만들 수 없지만(`PlaylistServiceImpl`),
         * 규칙이 생기기 전에 만들어진 것은 그대로 남아 있다. 그것을 전체 재생하면
         * 앞 100곡만 담긴다.
         */
        List<Integer> ids = detail.tracks().stream()
            .map(it -> it.track().getMusic().getMusicId())
            .distinct()
            .limit(PlayQueueDocument.LIMIT)
            .toList();

        Integer first = ids.isEmpty() ? null : ids.get(0);

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            PlayQueueDocument document = repository.load(userId);
            PlayQueueDocument updated = document.next(first, ids);
            if (repository.compareAndSet(userId, updated, document.revision())) {
                return updated;
            }
        }

        throw new BusinessException(PlayQueueErrorCode.QUEUE_UPDATE_FAILED);
    }

    /**
     * 새 곡이 들어갈 자리 — **재생 중인 곡 바로 아래**다.
     *
     * "최근에 담은 것이 위" 를 글자 그대로 맨 위 삽입으로 구현하면, 3번째 곡을
     * 듣는 중에 담은 곡이 1번 자리(재생 커서 **뒤**)로 가서 영영 재생되지 않는다.
     * 바로 아래에 넣으면 위쪽에 쌓이는 인상은 그대로면서 반드시 다음에 들린다.
     *
     * 재생 중인 곡이 없거나 큐에서 빠졌으면 맨 앞이다.
     */
    private int insertIndex(List<Integer> musicIds, Integer currentMusicId) {
        if (currentMusicId == null) return 0;
        int index = musicIds.indexOf(currentMusicId);
        return index < 0 ? 0 : index + 1;
    }

    /**
     * 상한을 넘겼으면 가장 오래 담긴 곡을 뺀다. 뺀 곡을 돌려준다.
     *
     * 새 곡이 계속 위쪽에 끼어들므로 **꼬리로 갈수록 오래 담긴 곡**이다. 그래서
     * "맨 아래 = 가장 오래된 곡" 이 그냥 성립하고, 담은 시각을 따로 저장하지 않는다.
     * 사람이 순서를 바꾼 뒤에는 둘이 어긋나지만 그때는 **화면에 보이는 맨 아래**가
     * 기준이다 — 목록 중간에서 곡이 사라지면 사라진 이유를 알 수 없다.
     *
     * 꼬리부터 올라오면서 **뺄 수 없는 두 곡을 건너뛴다.**
     *
     * 1. 지금 울리는 곡. 사용자가 그 곡을 맨 아래로 끌어 놓았을 때 걸린다.
     * 2. 방금 담은 곡. 재생 중인 곡이 맨 아래일 때 그 "바로 아래" 는 목록의 끝이라
     *    새 곡이 꼬리에 놓인다. 이때 꼬리를 그냥 빼면 **담자마자 도로 빠져서**
     *    담기 버튼이 아무 일도 하지 않는 것처럼 보인다.
     */
    private Integer evictIfFull(List<Integer> musicIds, Integer currentMusicId, int addedMusicId) {
        if (musicIds.size() <= PlayQueueDocument.LIMIT) return null;

        for (int index = musicIds.size() - 1; index >= 0; index--) {
            int candidate = musicIds.get(index);
            if (candidate == addedMusicId) continue;
            if (currentMusicId != null && candidate == currentMusicId.intValue()) continue;
            return musicIds.remove(index);
        }

        // 상한만큼이 전부 뺄 수 없는 곡일 수는 없다(그런 곡은 둘뿐이다). 방어용이다.
        return null;
    }

}
