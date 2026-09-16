package com.osundosun.mupid.api.queue.response;

import java.util.List;

import com.osundosun.mupid.api.music.dto.MusicSummary;

/**
 * 큐 한 벌. 곡 정보까지 채워서 내려간다.
 *
 * **`tracks` 가 저장된 곡 수보다 짧을 수 있다.** Redis 에는 제약조건이 없어
 * 지워진 곡 번호가 남는데, 조립기가 그것을 걸러 내기 때문이다. 화면이 다음에
 * 순서를 저장할 때 걸러진 목록이 그대로 올라와 스스로 정리된다.
 */
public record PlayQueueResponse(

    int revision,

    Integer currentMusicId,

    /** 담을 수 있는 최대 곡 수. 화면이 숫자를 따로 들고 있지 않게 함께 보낸다. */
    int limit,

    List<MusicSummary> tracks

) {

}
