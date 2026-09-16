package com.osundosun.mupid.api.queue.request;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 큐 전체 교체. 순서 바꾸기와 재생 위치 이동이 같은 요청을 쓴다.
 *
 * 부분 수정(자리 하나 옮기기)을 만들지 않은 것은 의도다. 100개짜리 목록이라
 * 통째로 보내도 작고, 부분 연산을 만들면 그 연산마다 판본 규칙을 다시 정의해야 한다.
 */
public record PlayQueueReplaceRequest(

    /** 화면에 보이는 순서 그대로. 위가 앞이다. */
    @NotNull List<@NotNull Integer> musicIds,

    /** 지금 재생 중인 곡. 아무것도 틀지 않았으면 null 이다. */
    Integer currentMusicId,

    /**
     * 화면이 마지막으로 읽은 판본.
     *
     * 서버 값과 다르면 409 다. 그 사이 다른 기기가 큐를 고쳤다는 뜻이라
     * 이 요청을 그대로 쓰면 남의 변경을 지운다.
     */
    @NotNull @PositiveOrZero Integer revision

) {

}
