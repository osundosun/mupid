package com.osundosun.mupid.api.ai.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.ai.request.AiChatRequest;
import com.osundosun.mupid.api.ai.response.AiChatResponse;
import com.osundosun.mupid.api.ai.service.AiChatService;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * Mupid AI 대화. 화면의 `chat-panel`(`frontend/components/mupid/chat.tsx`)이 쓴다.
 *
 * `SecurityConfig` 에 permitAll 을 넣지 않는다. 대화는 사용자의 취향과 청취 이력을 근거로
 * 곡을 고르는 일이고 게이트웨이 호출에는 돈이 든다. 기본값 `anyRequest().authenticated()`
 * 가 그대로 맞다.
 */
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiChatService aiChatService;

    /**
     * 한 번의 문답.
     *
     * 서버가 대화를 저장하지 않으므로 이전 대화는 요청의 `history` 로 함께 보낸다.
     * 곡 추천이 필요하다고 모델이 판단하면 응답 `tracks` 가 함께 채워진다.
     */
    @PostMapping("/chat")
    public ResponseEntity<BaseResponse<AiChatResponse>> chat(
        Authentication auth,
        @Valid @RequestBody AiChatRequest request
    ) {
        User user = (User)auth.getPrincipal();
        return ResponseEntity.ok(BaseResponse.of(aiChatService.chat(user, request)));
    }

}
