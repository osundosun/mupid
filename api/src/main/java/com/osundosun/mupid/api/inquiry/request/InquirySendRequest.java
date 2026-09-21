package com.osundosun.mupid.api.inquiry.request;

/**
 * 문의 보내기 요청.
 *
 * <h2>필드가 `content` 하나인 이유</h2>
 *
 * 프런트 계약이 그렇다 — `frontend/lib/api/endpoints.ts` 의 `inquiry.send(content)` 는
 * <b>내용만</b> 받아 `POST /api/v1/inquiries` 로 보낸다. 이메일 입력란이 아예 없고,
 * 대화상자 문구도 "답변은 <b>가입한</b> 메일로 발송돼요" 다
 * (`frontend/components/mupid/inquiry-dialog.tsx`). 즉 <b>받는 사람 주소는 서버가 인증
 * 주체에서 구한다.</b> 여기에 이메일 필드를 두면 아무 주소나 넣을 수 있게 되므로
 * 만들지 않는다.
 *
 * <h2>검증 애노테이션이 없는 이유</h2>
 *
 * {@code @NotBlank}·{@code @Size} 를 달면 거부는 되지만 사용자가 보는 문구가
 * {@code MUPID_INVALID_REQUEST_BODY} 의 "데이터가 잘못 입력되었습니다." 로 뭉개진다.
 * 대화상자는 <b>{@code e.message} 하나만</b> 화면에 그린다(필드별 {@code error_details}
 * 를 쓰지 않는다).
 *
 * <pre>
 *   catch (e) {
 *     setError(e instanceof Error &amp;&amp; e.message ? e.message : '문의를 보내지 못했어요. …');
 *   }
 * </pre>
 *
 * 그래서 여기서는 막지 않고 {@code InquiryServiceImpl} 이 trim 한 값으로 검증해
 * {@code InquiryErrorCode} 의 문구를 내려보낸다 — 빈 값·공백만·길이 초과가 각각 다른
 * 한국어 메시지로 나가야 사용자가 무엇을 고쳐야 할지 안다.
 */
public record InquirySendRequest(

    /**
     * 문의 내용. **서버가 trim 한 뒤** 빈 값과 길이를 판단한다.
     *
     * <p>프런트는 이미 {@code body.trim()} 해서 보내지만 그것을 믿지 않는다 —
     * {@code maxLength} 는 UI 제약이지 계약이 아니고, curl·앱 어디서든 직접 부를 수 있다.
     */
    String content

) {

    /**
     * 내용 상한. `frontend/components/mupid/inquiry-dialog.tsx:8` 의 {@code BODY_MAX} 와
     * 같은 값이다. UI 는 {@code maxLength} 로, 서버는 {@link com.osundosun.mupid.api.inquiry.error.InquiryErrorCode}
     * 로 각각 강제한다 — 둘 중 하나만 있으면 계약이 아니다.
     */
    public static final int CONTENT_MAX = 1000;

}
