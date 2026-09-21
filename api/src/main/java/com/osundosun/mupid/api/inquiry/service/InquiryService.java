package com.osundosun.mupid.api.inquiry.service;

import com.osundosun.mupid.api.inquiry.request.InquirySendRequest;
import com.osundosun.mupid.api.user.entity.User;

/**
 * 문의 보내기 (S15P21A204-384).
 */
public interface InquiryService {

    /**
     * 문의를 접수하고 알림을 넘긴다.
     *
     * <p><b>이 메서드는 메일·알림 발송을 기다리지 않는다.</b> 호출이 끝났다는 것은
     * "접수했다" 는 뜻이고, 발송은 비동기 스레드가 맡는다 — 발송이 실패해도 접수는
     * 성공이다.
     *
     * @param user    인증 주체. 이 사람의 이메일로 접수 통보가 나간다
     * @param request 요청 본문. 내용만 있다
     */
    void sendInquiry(User user, InquirySendRequest request);

}
