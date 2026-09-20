package com.osundosun.mupid.api.music.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MusicErrorCode implements ErrorCode {
    
    /**
     * 요청한 음악을 찾을 수 없습니다.
     */
    MUSIC_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 음악을 찾을 수 없습니다."),

    /**
     * 요청한 음악이 <b>삭제</b>되었습니다. <b>410</b> 이다.
     *
     * <p>`404` 와 나누는 이유: 곡은 지워지지 않고 `is_deleted` 만 세워지므로
     * 행은 남아 있다. "그런 곡이 없다" 와 "있었는데 지금은 없다" 는 보낸 쪽이
     * 할 일이 다르다 — 앞은 번호를 고치거나 등록해야 하고, 뒤는 그 자원을
     * 포기해야 한다. 배치는 이 구분이 없어서 곡이 삭제된 것을 404 로만 알았고,
     * `catalog` 이 서버 곡 목록을 훑어 우회해야 했다(S15P21A204-399).
     *
     * <p><b>되살아날 수 있다.</b> 서버 행은 남고 원본도 배치 서버에 있으므로
     * 관리자가 되돌릴 수 있다. 그래서 응답에 캐시 지시자를 붙이지 않는다 —
     * `410` 은 원래 영구 캐시 대상이라 되살린 곡이 안 보일 수 있다.
     */
    MUSIC_GONE(HttpStatus.GONE, "요청한 음악은 삭제되었습니다.");

    private final HttpStatus status;
    private final String message;

}
