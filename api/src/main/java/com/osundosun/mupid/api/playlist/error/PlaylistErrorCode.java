package com.osundosun.mupid.api.playlist.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

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
     * 플레이리스트에 담겨 있지 않은 곡입니다.
     *
     * 빼기는 멱등이 아니다 — 화면이 이미 사라진 곡을 또 빼려 하면 그건 화면과
     * 서버가 어긋났다는 뜻이라 조용히 성공시키지 않는다.
     */
    PLAYLIST_TRACK_NOT_FOUND(HttpStatus.NOT_FOUND, "플레이리스트에 담겨 있지 않은 곡입니다.");

    private final HttpStatus status;
    private final String message;

}
