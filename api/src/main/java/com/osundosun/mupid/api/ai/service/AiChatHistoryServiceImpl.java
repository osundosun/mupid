package com.osundosun.mupid.api.ai.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.ai.entity.AiChatMessage;
import com.osundosun.mupid.api.ai.entity.AiChatSession;
import com.osundosun.mupid.api.ai.enumerate.AiMessageType;
import com.osundosun.mupid.api.ai.repository.AiChatMessageRepository;
import com.osundosun.mupid.api.ai.repository.AiChatSessionRepository;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link AiChatHistoryService} 구현.
 *
 * <h2>왜 서비스가 따로 필요한가</h2>
 *
 * <p>{@code AiChatServiceImpl} 에는 `@Transactional` 이 없다. 의도한 것이다 — LLM 응답을
 * 기다리는 수십 초 동안 DB 커넥션을 쥐면 안 되기 때문이다. 그런데 저장은 트랜잭션 밖에서
 * 하면 위험하다: 세션 INSERT 는 커밋됐는데 메시지 INSERT 가 실패하면 <b>빈 세션</b>이
 * 남는다. 그래서 저장만 떼어 짧은 트랜잭션으로 감쌌다. LLM 왕복은 이 트랜잭션 밖이다.
 *
 * <h2>순서(index)는 서버가 정한다</h2>
 *
 * <p>클라이언트가 보낸 이전 대화로 순서를 만들면 두 창에서 동시에 말할 때 순서가 뒤집힌다.
 * 세션에 이미 쌓인 최댓값을 읽어 +1 한다. 그 조회와 INSERT 가 같은 트랜잭션 안이라
 * 한 세션 안에서 순서가 겹치지 않는다.
 *
 * <h2>`REQUIRES_NEW` 를 쓰지 않는다</h2>
 *
 * <p>한때 `append` 에 `REQUIRES_NEW` 를 걸었다. "호출자의 트랜잭션에 휩쓸리지 않게 한다"
 * 는 뜻이었는데, 그 선택이 <b>`open` 직후 `append` 를 불가능하게</b> 만들었다 — 안쪽
 * 트랜잭션이 바깥 트랜잭션을 중단시키므로 아직 커밋되지 않은 세션 행이 보이지 않아
 * "대화 세션이 없다" 로 죽는다. 실제로 `AiChatHistoryPersistTest` 에서 그렇게 걸렸다.
 *
 * <p>평범한 `REQUIRED` 면 충분하다. `AiChatServiceImpl` 에 트랜잭션이 없으므로 각 호출이
 * 각자 짧은 트랜잭션을 열어 <b>원래 의도(LLM 왕복 동안 커넥션을 쥐지 않는다)가 그대로
 * 지켜지고</b>, 바깥 트랜잭션이 있는 호출자에게는 그 안에서 이어져 두 호출이 서로를 본다.
 *
 * <p>`ai_chat_sessions.title` 은 `VARCHAR(50)` 이다. 넘치면 MySQL 이 조용히 자르거나
 * (strict mode 에서는) 거부한다. 첫 사용자 메시지를 잘라 쓴다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatHistoryServiceImpl implements AiChatHistoryService {

    /** `ai_chat_sessions.title` 이 `VARCHAR(50)` 이다. ERD 를 따른다. */
    private static final int TITLE_MAX = 50;

    /** 제목이 비어 보이지 않게 하는 자리표시자. */
    private static final String EMPTY_TITLE = "새 대화";

    private final AiChatSessionRepository sessionRepository;
    private final AiChatMessageRepository messageRepository;
    private final MusicRepository musicRepository;

    @Override
    @Transactional
    public AiChatSession open(User user, String firstMessage) {
        AiChatSession session = sessionRepository.save(AiChatSession.builder()
            .title(titleOf(firstMessage))
            .user(user)
            .isDeleted(false)
            .build());
        // IDENTITY 라 save 시점에 INSERT 가 나가고 번호가 채워진다. 번호가 없으면
        // 메시지가 붙을 자리가 없으므로 여기서 멈춘다 — 조용히 진행하면 고아 메시지가 된다.
        if (session.getAiChatSessionId() == null) {
            throw new IllegalStateException("대화 세션 번호를 발급받지 못했다");
        }
        return session;
    }

    @Override
    @Transactional
    public AiChatMessage append(Integer sessionId, AiMessageType messageType, String text,
        Integer musicId) {
        if (sessionId == null) {
            throw new IllegalArgumentException("세션 번호 없이 대화를 저장할 수 없다");
        }
        // 프록시 참조를 쓴다. sessionRepository.getReferenceById 를 쓰면 없는 세션에서
        // INSERT 시점에 터지는데, 그때는 어느 세션인지 로그에 남지 않는다.
        AiChatSession session = sessionRepository.findById(sessionId).orElseThrow(
            () -> new IllegalStateException("대화 세션이 없다 session_id=" + sessionId));

        return messageRepository.save(AiChatMessage.builder()
            .session(session)
            .index(messageRepository.findMaxIndexBySessionId(sessionId) + 1)
            .messageType(messageType)
            .text(text)
            .music(musicOf(musicId))
            .build());
    }

    /**
     * 그때 듣고 있던 곡. **없는 번호면 null 로 둔다.**
     *
     * <p>`ai_chat_messages.music_id` 에 FK 가 걸려 있어 없는 번호를 넣으면 INSERT 가
     * 실패한다. 곡 번호는 클라이언트가 보낸 값이라 믿을 수 없으므로, 못 찾으면 곡 문맥
     * 없이 저장한다 — 그 한 마디 때문에 대화 기록 전체가 날아가면 안 된다.
     */
    private Music musicOf(Integer musicId) {
        if (musicId == null) {
            return null;
        }
        return musicRepository.findById(musicId).orElse(null);
    }

    /** 첫 메시지에서 제목을 만든다. 줄바꿈은 공백으로 눌러 한 줄로 만든다. */
    private String titleOf(String firstMessage) {
        if (firstMessage == null || firstMessage.isBlank()) {
            return EMPTY_TITLE;
        }
        String oneLine = firstMessage.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= TITLE_MAX ? oneLine : oneLine.substring(0, TITLE_MAX);
    }
}
