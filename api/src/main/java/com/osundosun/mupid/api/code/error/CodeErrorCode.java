package com.osundosun.mupid.api.code.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CodeErrorCode implements ErrorCode {

    /**
     * 그 코드가 없습니다.
     *
     * 곡에 분류를 붙일 때 존재하지 않는 번호를 주면 여기서 막습니다. 그냥 넘기면
     * 곡은 등록됐는데 분류만 비는 상태가 되고, 오류가 아니라 <b>빈 장르</b>라
     * 화면만 보고는 무엇이 빠졌는지 알 수 없습니다.
     */
    CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 코드를 찾을 수 없습니다."),

    /**
     * 코드 종류가 자리와 맞지 않습니다.
     *
     * 장르 자리에 분위기 코드를 넣어도 표에는 들어갑니다 — 둘 다 `codes` 를
     * 가리키는 숫자라서. 그러면 온보딩 설문과 곡 분류가 서로 다른 것을 보게 되고,
     * 그 어긋남은 조회에서 드러나지 않습니다.
     */
    CODE_TYPE_MISMATCH(HttpStatus.BAD_REQUEST, "코드 종류가 요청한 자리와 다릅니다.");

    private final HttpStatus status;
    private final String message;

}
