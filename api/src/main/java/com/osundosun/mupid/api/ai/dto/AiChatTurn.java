package com.osundosun.mupid.api.ai.dto;

import com.osundosun.mupid.api.ai.enumerate.AiMessageType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 클라이언트가 들고 있는 이전 대화 한 마디.
 *
 * 서버가 대화를 저장하지 않으므로 화면이 지금까지의 대화를 매번 함께 보낸다.
 * `AiChatSession`·`AiChatMessage` 엔티티는 이미 있지만 아직 쓰지 않는다 — 아래 참고.
 *
 * 역할 이름은 화면의 `assistant`/`user` 가 아니라 **ERD 의 `AiMessageType`** 을 쓴다.
 * 나중에 대화를 저장하게 되면 그대로 `ai_chat_messages.message_type` 이 될 값이라,
 * 같은 것을 두 이름으로 부르지 않으려는 것이다.
 */
public record AiChatTurn(

    /** `USER` 또는 `AI`. `SYSTEM`·`ETC` 는 무시한다. */
    @NotNull(message = "대화 역할이 입력되지 않았습니다.")
    AiMessageType role,

    @NotBlank(message = "대화 내용이 입력되지 않았습니다.")
    @Size(max = 2000, message = "대화 내용은 2000자를 넘을 수 없습니다.")
    String content

) {

}
