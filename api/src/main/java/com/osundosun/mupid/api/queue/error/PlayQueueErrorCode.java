package com.osundosun.mupid.api.queue.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlayQueueErrorCode implements ErrorCode {

    /**
     * 읽은 뒤에 다른 기기가 큐를 고쳤다.
     *
     * 순서 교체(PUT)에만 난다. 화면은 이 응답을 받으면 서버 큐를 다시 읽어
     * 자기 상태를 덮는다 — 큐는 병합할 가치가 있는 데이터가 아니다.
     */
    QUEUE_REVISION_CONFLICT(HttpStatus.CONFLICT, "다른 기기에서 재생 목록이 바뀌었어요. 다시 불러올게요."),

    /**
     * 재생 목록에 담겨 있지 않은 곡을 빼려 했다.
     *
     * 담기와 달리 **없으면 404** 다. 플레이리스트와 같은 규칙이다 — 중복 담기를
     * 성공으로 보는 것은 "이미 원하는 상태" 라서지만, 없는 곡을 빼는 것은
     * 화면과 서버가 어긋났다는 뜻이라 조용히 넘기지 않는다.
     */
    QUEUE_TRACK_NOT_FOUND(HttpStatus.NOT_FOUND, "재생 목록에 담겨 있지 않은 곡입니다."),

    /**
     * 순서 교체 요청에 같은 곡이 두 번 들어 있다.
     *
     * 큐는 곡 하나당 한 자리다. 중복을 허용하면 "이 곡을 뺀다" 가 어느 자리를
     * 가리키는지 정할 수 없다.
     */
    QUEUE_DUPLICATED_TRACK(HttpStatus.BAD_REQUEST, "재생 목록에 같은 곡을 두 번 담을 수 없습니다."),

    /**
     * 재생 큐 갱신이 거듭 어긋났다.
     *
     * 곡 담기·빼기는 서버가 읽고 고쳐 쓰므로 판본이 어긋나면 스스로 다시 시도한다.
     * 그것마저 연달아 실패하는 것은 정상 경합이 아니다.
     */
    QUEUE_UPDATE_FAILED(HttpStatus.CONFLICT, "재생 목록을 저장하지 못했어요. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;

}
