package com.osundosun.mupid.api.ai.service;

import com.osundosun.mupid.api.ai.entity.AiChatMessage;
import com.osundosun.mupid.api.ai.entity.AiChatSession;
import com.osundosun.mupid.api.ai.enumerate.AiMessageType;
import com.osundosun.mupid.api.user.entity.User;

/**
 * 챗봇 대화를 MySQL 에 남긴다.
 *
 * <p>화면이 대화를 들고 다니는 동안에는 저장이 필요 없었다. 세션·메시지 엔티티가 있는데도
 * 쓰지 않은 이유는 ERD 의 `ai_chat_sessions.ai_chat_session_id` 에 `AUTO_INCREMENT` 가
 * 없었기 때문이다(엔티티는 `IDENTITY`). 그 수기 보정이 `erd.sql` 에 반영돼 이제 저장할 수
 * 있다 — S15P21A204-375.
 *
 * <p>이 서비스는 <b>저장만</b> 한다. 대화를 어떻게 이어 갈지는 {@code AiChatServiceImpl} 이
 * 정하고, 여기는 "세션을 열고 한 마디를 붙인다" 만 책임진다.
 *
 * <p>실패는 감추지 않는다 — 예외를 그대로 올린다. 저장이 곁가지라 대화를 끊으면 안 된다는
 * 판단은 <b>호출부</b>의 몫이고(거기서 로그를 남기고 이어 간다), 여기서 삼키면 테스트가
 * 저장 실패를 볼 수 없다.
 */
public interface AiChatHistoryService {

    /**
     * 새 대화 세션을 열고 저장한다.
     *
     * @param user         대화의 주체. `user_id` FK 로 들어간다
     * @param firstMessage 세션 제목을 만들 첫 사용자 메시지
     * @return 저장된 세션. `aiChatSessionId` 가 채워져 있다
     */
    AiChatSession open(User user, String firstMessage);

    /**
     * 세션에 한 마디를 덧붙인다. 순서(`index`)는 <b>서버가</b> 정한다 — 클라이언트가
     * 보낸 순서를 믿으면 두 창에서 동시에 말할 때 순서가 뒤집힌다.
     *
     * @param musicId 그때 듣고 있던 곡. 없으면 null
     */
    AiChatMessage append(Integer sessionId, AiMessageType messageType, String text,
        Integer musicId);
}
