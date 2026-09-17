package com.osundosun.mupid.api.queue.service;

import java.util.List;

import com.osundosun.mupid.api.queue.dto.PlayQueueChange;
import com.osundosun.mupid.api.queue.dto.PlayQueueDocument;

/**
 * 사용자별 재생 큐. 저장소는 Redis 다 — decision/0022.
 */
public interface PlayQueueService {

    PlayQueueDocument get(int userId);

    /**
     * 큐를 통째로 바꾼다. 화면이 순서를 고쳤을 때 쓴다.
     *
     * `revision` 이 지금 저장된 판본과 다르면 거절한다 — 그 사이 다른 기기가
     * 고쳤다는 뜻이다.
     */
    PlayQueueDocument replace(int userId, List<Integer> musicIds, Integer currentMusicId, int revision);

    /**
     * 한 곡을 담는다. 자리와 퇴출은 서버가 정한다.
     *
     * 화면도 같은 규칙으로 미리 반영하지만(즉시 반응해야 한다), 기준은 여기다.
     */
    PlayQueueChange add(int userId, int musicId);

    PlayQueueDocument remove(int userId, int musicId);

    /**
     * 플레이리스트 수록곡으로 큐를 갈아끼우고 첫 곡을 재생 대상으로 둔다.
     */
    PlayQueueDocument replaceFromPlaylist(int userId, int playlistId);

}
