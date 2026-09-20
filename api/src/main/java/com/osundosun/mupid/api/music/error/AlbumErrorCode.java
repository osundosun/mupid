package com.osundosun.mupid.api.music.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AlbumErrorCode implements ErrorCode {
    
    /**
     * 요청한 앨범을 찾을 수 없습니다.
     */
    ALBUM_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 앨범을 찾을 수 없습니다."),

    /**
     * 요청한 앨범이 <b>삭제</b>되었습니다. <b>410</b> 이다.
     *
     * <p>`is_deleted` 만 세워지고 행은 남는다(`deleteAlbum`). "없다" 와
     * "지워졌다" 를 나누는 이유는 `MusicErrorCode.MUSIC_GONE` 참고.
     */
    ALBUM_GONE(HttpStatus.GONE, "요청한 앨범은 삭제되었습니다.");

    private final HttpStatus status;
    private final String message;

}
