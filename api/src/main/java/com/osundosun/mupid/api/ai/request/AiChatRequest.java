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
    Integer musicId,

    /**
     * 이어 갈 대화 세션 번호. 없으면 **새 대화다.**
     *
     * <p>서버가 대화를 저장하므로 두 번째 턴부터는 직전 응답의 `sessionId` 를 그대로
     * 실어 보낸다. 화면이 세션을 바꾸지 않고 계속 말을 잇는 것이 기본 동작이다.
     *
     * <p>없으면 서버가 세션을 새로 열고 그 번호를 응답에 실어 준다. 클라이언트가 그 값을
     * 들고 있지 않아도 대화는 되지만, 그때마다 기록이 새 세션으로 갈라진다.
     */
    Integer sessionId

) {

}
