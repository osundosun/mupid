package com.osundosun.mupid.api.music.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum WaveformErrorCode implements ErrorCode {

    /**
     * 이 곡에는 아직 파형이 없습니다. <b>404</b> 다.
     *
     * <p>곡이 없는 것(`MUSIC_NOT_FOUND`)과 다르다. 곡은 멀쩡하고 파형만 아직
     * 올라오지 않은 상태이며, <b>정상적인 상태다</b> — 배치가 아직 닿지 않은
     * 곡이 그렇다. 화면은 이걸 받으면 기기에서 청크를 디코딩해 직접 그린다.
     */
    WAVEFORM_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 곡의 파형이 없습니다."),

    /**
     * 올라온 파형을 쓸 수 없습니다. <b>400</b> 이다.
     *
     * <p>길이나 값 범위는 요청 검증이 먼저 거른다. 여기까지 오는 것은 값이
     * <b>전부 0</b> 인 경우다 — 형식은 맞지만 그려 봐야 평평한 선이라, 계산이
     * 실패한 배치가 빈 배열을 올린 것으로 본다. 받아 두면 화면이 폴백조차 하지
     * 않고 빈 파형을 그린다.
     */
    INVALID_WAVEFORM(HttpStatus.BAD_REQUEST, "파형 값이 모두 0 입니다."),

    /**
     * 저장된 파형을 읽지 못했습니다. <b>500</b> 이다.
     *
     * <p>행은 있는데 오브젝트가 없거나 JSON 이 깨진 경우다. 파일과 행을 함께 쓰는
     * 경로가 하나뿐이라 정상적으로는 생기지 않는다 — 스토리지를 손으로 건드렸거나
     * 업로드가 반쯤 끊긴 흔적이다. 404 로 접지 않는 것은 "아직 없다" 와 "있어야
     * 하는데 깨졌다" 가 할 일이 다르기 때문이다.
     */
    WAVEFORM_UNREADABLE(HttpStatus.INTERNAL_SERVER_ERROR, "저장된 파형을 읽을 수 없습니다.");

    private final HttpStatus status;
    private final String message;

}
