package com.osundosun.mupid.api.music.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ArtistErrorCode implements ErrorCode {
    
    /**
     * 요청한 음악을 찾을 수 없습니다.
     */
    ARTIST_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 아티스트를 찾을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

}
