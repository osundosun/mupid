package com.osundosun.mupid.api.queue.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Redis 에 통째로 들어가는 재생 큐 한 벌. 배경은 decision/0022.
 *
 * 필드명이 한 글자인 것은 사용자 수만큼 이 문서가 쌓이기 때문이다. 100곡짜리
 * 큐에서 `"musicIds"` 대신 `"t"` 를 쓰면 문서 하나가 눈에 띄게 줄고, 이 값은
 * 화면이 보지 않는다 — API 응답은 별도의 record 로 나간다.
 */
public record PlayQueueDocument(

    /**
     * 갱신할 때마다 1씩 오른다. 화면이 읽은 값과 다르면 그 사이 다른 기기가
     * 고쳤다는 뜻이라 순서 교체를 거절한다.
     */
    @JsonProperty("r") int revision,

    /**
     * 지금 재생 중인 곡. 새 곡을 **이 곡 바로 아래**에 넣고, 퇴출에서는 이 곡을
     * 제외한다. 아직 아무것도 틀지 않았으면 null 이다.
     */
    @JsonProperty("c") Integer currentMusicId,

    /** 화면에 보이는 순서 그대로. 위가 앞이다. */
    @JsonProperty("t") List<Integer> musicIds

) {

    /**
     * 큐에 담기는 곡 수의 상한.
     *
     * **플레이리스트 수록곡 상한이기도 하다.** 플레이리스트 전체 재생이 큐를 통째로
     * 갈아끼우는데 두 곳의 상한이 다르면 뒷부분이 조용히 잘린다 — decision/0022 규칙 7.
     *
     * 상수가 서비스가 아니라 이 record 에 있는 것은 그래서다. 플레이리스트 쪽이
     * `PlayQueueService` 를 보게 하면 두 패키지가 서로를 참조한다(큐 서비스는 이미
     * `PlaylistService` 를 쓴다). 이 record 는 아무것도 의존하지 않아 양쪽이 안전하게 본다.
     */
    public static final int LIMIT = 100;

    public static PlayQueueDocument empty() {
        return new PlayQueueDocument(0, null, List.of());
    }

    public PlayQueueDocument next(Integer currentMusicId, List<Integer> musicIds) {
        return new PlayQueueDocument(revision + 1, currentMusicId, List.copyOf(musicIds));
    }

}
