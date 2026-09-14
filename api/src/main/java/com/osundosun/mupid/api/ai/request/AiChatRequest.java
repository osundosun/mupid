package com.osundosun.mupid.api.ai.request;

import java.util.List;

import com.osundosun.mupid.api.ai.dto.AiChatTurn;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiChatRequest(

    @NotBlank(message = "메시지가 입력되지 않았습니다.")
    @Size(max = 500, message = "메시지는 500자를 넘을 수 없습니다.")
    String message,

    /**
     * 이전 대화. 없으면 새 대화다. `ai.max-history` 를 넘으면 **최근 것부터** 남기고 자른다.
     */
    @Size(max = 100, message = "이전 대화가 너무 많습니다.")
    List<@Valid AiChatTurn> history,

    /**
     * 지금 듣고 있는 곡. 화면의 "지금 함께 듣는 곡" 이다.
     *
     * 곡명·아티스트는 받지 않는다. 이 번호로 서버가 찾는다 — 클라이언트가 보낸 이름을
     * 프롬프트에 그대로 넣으면 없는 곡을 있는 것처럼 이야기하게 만들 수 있다.
     * 없는 번호면 조용히 무시하고 곡 문맥 없이 답한다.
     */
    Integer musicId

) {

}
