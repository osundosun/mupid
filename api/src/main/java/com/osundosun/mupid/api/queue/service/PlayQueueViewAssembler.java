package com.osundosun.mupid.api.queue.service;

import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.music.service.MusicSummaryAssembler;
import com.osundosun.mupid.api.queue.dto.PlayQueueDocument;
import com.osundosun.mupid.api.queue.response.PlayQueueResponse;

import lombok.RequiredArgsConstructor;

/**
 * 저장된 큐 문서를 화면용 응답으로 바꾼다.
 *
 * Redis 에는 곡 번호만 있으므로 제목·아티스트·커버는 여기서 붙인다.
 * `MusicSummaryAssembler` 가 **넘긴 순서를 지키고 삭제된 곡은 빼고** 돌려주는데,
 * 큐 조회가 정확히 그 시그니처다 — 지워진 곡을 걸러 내는 일이 공짜로 따라온다.
 *
 * 서비스가 아니라 조립기로 둔 것은 `MusicSummaryAssembler` 와 같은 이유다.
 * 표현을 만드는 일이라 서비스끼리 서로를 부르게 만들 필요가 없다.
 */
@Component
@RequiredArgsConstructor
public class PlayQueueViewAssembler {

    private final MusicSummaryAssembler musicSummaryAssembler;

    public PlayQueueResponse toResponse(PlayQueueDocument document) {
        return new PlayQueueResponse(
            document.revision(), /* 판본 */
            document.currentMusicId(), /* 재생 중인 곡 */
            PlayQueueDocument.LIMIT, /* 담을 수 있는 최대 곡 수 */
            musicSummaryAssembler.byIds(document.musicIds()) /* 담긴 곡 */
        );
    }

}
