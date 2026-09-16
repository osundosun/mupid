package com.osundosun.mupid.api.queue.response;

/**
 * 곡을 담은 뒤의 큐와, 담는 과정에서 일어난 일.
 */
public record PlayQueueTrackAddResponse(

    PlayQueueResponse queue,

    /** 이미 담겨 있어 아무것도 하지 않았다. */
    boolean duplicated,

    /**
     * 상한이 차서 밀려난 곡. 없으면 null.
     *
     * 화면은 이 값이 있을 때만 퇴출 안내를 띄운다.
     */
    Integer evictedMusicId

) {

}
