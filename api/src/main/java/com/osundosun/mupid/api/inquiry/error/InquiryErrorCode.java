package com.osundosun.mupid.api.inquiry.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;
import com.osundosun.mupid.api.inquiry.request.InquirySendRequest;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 문의 보내기의 오류.
 *
 * <p>여기 문구는 <b>그대로 사용자 화면에 뜬다.</b> 대화상자가 실패 시 {@code e.message}
 * 를 그리기 때문이다. 그래서 내부 코드·스택·SQL 을 담지 않고, 무엇을 고쳐야 하는지만
 * 한국어로 적는다.
 *
 * <p>`MUPID_INVALID_REQUEST_BODY`(400, "데이터가 잘못 입력되었습니다.")를 쓰지 않는
 * 이유도 같다 — 정확한 원인은 {@code error_details} 에 들어가는데 화면은 그것을 보지
 * 않는다. 사용자는 "데이터가 잘못 입력되었습니다" 만 보고 무엇이 잘못됐는지 알 수 없다.
 */
@Getter
@RequiredArgsConstructor
public enum InquiryErrorCode implements ErrorCode {

    /**
     * 내용이 없거나 공백뿐입니다.
     *
     * <p>`trim` 기준이다. 프런트가 {@code body.trim().length > 0} 일 때만 보내므로
     * 서버도 같은 기준으로 보지 않으면 공백만 보낸 요청이 통과한다.
     */
    INQUIRY_CONTENT_REQUIRED(HttpStatus.BAD_REQUEST, "문의 내용을 입력해 주세요."),

    /**
     * 내용이 너무 깁니다.
     *
     * <p>상한은 {@link InquirySendRequest#CONTENT_MAX}(1000)이고 화면의 `maxLength` 와
     * 같은 값이다. UI 제약을 우회한 요청을 서버가 여기서 막는다.
     */
    INQUIRY_CONTENT_TOO_LONG(HttpStatus.BAD_REQUEST,
        "문의 내용은 %d자를 넘을 수 없습니다.".formatted(InquirySendRequest.CONTENT_MAX));

    private final HttpStatus status;
    private final String message;

}
