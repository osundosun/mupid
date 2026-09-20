package com.osundosun.mupid.api.playlist.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;
import com.osundosun.mupid.api.queue.dto.PlayQueueDocument;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum PlaylistErrorCode implements ErrorCode {

    /**
     * 요청한 플레이리스트를 찾을 수 없습니다.
     */
    PLAYLIST_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 플레이리스트를 찾을 수 없습니다."),

    /**
     * 플레이리스트에 접근할 권한이 없습니다.
     */
    PLAYLIST_ACCESS_DENIED(HttpStatus.FORBIDDEN, "플레이리스트에 접근할 권한이 없습니다."),

    /**
     * 곡 추가가 동시에 처리되었습니다.
     *
     * 같은 곡을 동시에 두 번 추가할 때만 난다. 중복 자체는 오류가 아니지만
     * (중복 응답을 준다), 경쟁에서 진 요청은 트랜잭션이 깨져 되돌릴 수 없다.
     */
    PLAYLIST_TRACK_CONFLICT(HttpStatus.CONFLICT, "곡 추가가 동시에 처리되었습니다. 다시 시도해 주세요."),

    /**
     * 플레이리스트가 수록곡 상한에 닿았다.
     *
     * 상한 값은 재생 큐의 것을 그대로 쓴다(`PlayQueueService.QUEUE_LIMIT`).
     * 플레이리스트 전체 재생이 큐를 통째로 갈아끼우는데 두 상한이 다르면
     * 뒷부분이 조용히 잘린다 — decision/0022 규칙 7.
     *
     * 큐와 달리 **거절한다.** 큐는 흘러가는 목록이라 오래된 것을 밀어내도 되지만
     * 플레이리스트는 사람이 모아 둔 것이라 말없이 빼면 안 된다.
     */
    PLAYLIST_TRACK_LIMIT_EXCEEDED(HttpStatus.CONFLICT,
        "플레이리스트에는 " + PlayQueueDocument.LIMIT + "곡까지 담을 수 있습니다."),

    /**
     * 플레이리스트에 담겨 있지 않은 곡입니다.
     *
     * 빼기는 멱등이 아니다 — 화면이 이미 사라진 곡을 또 빼려 하면 그건 화면과
     * 서버가 어긋났다는 뜻이라 조용히 성공시키지 않는다.
     */
    PLAYLIST_TRACK_NOT_FOUND(HttpStatus.NOT_FOUND, "플레이리스트에 담겨 있지 않은 곡입니다."),

    /**
     * 요청한 플레이리스트가 <b>삭제</b>되었습니다. <b>410</b> 이다.
     *
     * <p>삭제는 `deleted_at` 을 세우는 소프트 삭제다(`deletePlaylist`). 조회가
     * `DeletedAtIsNull` 로 거르므로 둘 다 `Optional.empty()` 가 되어 그대로는
     * 구분되지 않는다 — `DeletedAtIsNotNull` 로 한 번 더 본다.
     *
     * <p><b>`PLAYLIST_ACCESS_DENIED`(403) 와는 다른 뜻이다.</b> 남의 플레이리스트는
     * 403 이고, 삭제된 것은 410 이다. 403 을 410 으로 바꾸면 "없는 남의 목록" 을
     * 캐내는 통로가 된다.
     */
    PLAYLIST_GONE(HttpStatus.GONE, "요청한 플레이리스트는 삭제되었습니다.");

    private final HttpStatus status;
    private final String message;

}
