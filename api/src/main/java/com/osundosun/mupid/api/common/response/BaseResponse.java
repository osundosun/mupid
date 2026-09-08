package com.osundosun.mupid.api.common.response;

public record BaseResponse<T>(

    /**
     * 응답 관련 메시지
     */
    String message,

    /**
     * 실제 응답 데이터
     */
    T data

) {

    /**
     * 메시지만 전송하는 경우
     */
    public static BaseResponse<Void> message(String message) {
        return new BaseResponse<Void>(message, null);
    }

    /**
     * 데이터만 전송하는 경우
     */
    public static <T> BaseResponse<T> of(T data) {
        return new BaseResponse<T>(null, data);
    }

    /**
     * 데이터 + 메시지를 전송하는 경우
     */
    public static <T> BaseResponse<T> of(String message, T data) {
        return new BaseResponse<T>(message, data);
    }

}
