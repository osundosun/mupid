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
    PLAYLIST_TRACK_CONFLICT(HttpStatus.CONFLICT, "곡 추가가 동시에 처리되었습니다. 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;

}
