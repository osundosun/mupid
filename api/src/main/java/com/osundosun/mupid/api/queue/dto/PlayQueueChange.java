package com.osundosun.mupid.api.queue.dto;

/**
 * 곡을 담은 결과. 문서만으로는 화면이 무슨 일이 일어났는지 알 수 없어 함께 나른다.
 */
public record PlayQueueChange(

    PlayQueueDocument document,

    /** 이미 담겨 있던 곡이라 아무것도 하지 않았다. */
    boolean duplicated,

    /**
     * 100곡이 차서 밀려난 곡. 없으면 null.
     *
     * 화면은 이 값이 있을 때만 퇴출 안내를 띄운다. 서버가 판단한 것을 화면이
     * 다시 세어 맞히게 하면 두 곳의 규칙이 어긋난다.
     */
    Integer evictedMusicId

) {

    public static PlayQueueChange of(PlayQueueDocument document, Integer evictedMusicId) {
        return new PlayQueueChange(document, false, evictedMusicId);
    }

    public static PlayQueueChange duplicated(PlayQueueDocument document) {
        return new PlayQueueChange(document, true, null);
    }

}
