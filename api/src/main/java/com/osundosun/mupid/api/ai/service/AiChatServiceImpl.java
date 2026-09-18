package com.osundosun.mupid.api.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.ai.client.AiJson;
import com.osundosun.mupid.api.ai.client.OpenAiClient;
import com.osundosun.mupid.api.ai.client.OpenAiMessage;
import com.osundosun.mupid.api.ai.client.OpenAiTool;
import com.osundosun.mupid.api.ai.client.OpenAiToolCall;
import com.osundosun.mupid.api.ai.dto.AiChatTurn;
import com.osundosun.mupid.api.ai.dto.AiRecommendedTrack;
import com.osundosun.mupid.api.ai.error.AiErrorCode;
import com.osundosun.mupid.api.ai.properties.AiProperties;
import com.osundosun.mupid.api.ai.request.AiChatRequest;
import com.osundosun.mupid.api.ai.response.AiChatResponse;
import com.osundosun.mupid.api.ai.tool.AiRecommendTool;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 대화 한 번의 오케스트레이션.
 *
 * <pre>
 * 1. 시스템 프롬프트 + 이전 대화 + 이번 메시지로 messages 를 만든다
 * 2. 게이트웨이에 보낸다 (툴 선언을 함께)
 * 3. 툴 호출이 돌아오면 실행하고 결과를 messages 에 붙여 다시 보낸다 ← 최대 ai.max-tool-rounds 회
 * 4. 본문이 돌아오면 그것이 답변이다. 그동안 모은 곡을 함께 돌려준다
 * </pre>
 *
 * <b>대화를 저장하지 않는다.</b> `ai_chat_sessions`·`ai_chat_messages` 엔티티가 이미 있지만
 * 아직 쓰지 않는다. 화면이 대화를 들고 있어 지금 당장 필요하지 않고, ERD 의
 * `ai_chat_sessions.ai_chat_session_id` 가 `AUTO_INCREMENT` 없이 `INT NOT NULL` 이라
 * (엔티티는 `GenerationType.IDENTITY`) 저장을 켜려면 DDL 부터 손봐야 하기 때문이다 —
 * `api/knowledge/decision/0011-jpa-manages-schema.md`.
 *
 * `@Transactional` 을 붙이지 않는다. 이 서비스가 하는 DB 작업은 곡 한 건 조회뿐인데,
 * 트랜잭션으로 감싸면 LLM 응답을 기다리는 수십 초 동안 커넥션을 쥐고 있게 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatServiceImpl implements AiChatService {

    /**
     * gpt-5 계열의 시스템 프롬프트 역할은 `system` 이 아니라 `developer` 다.
     */
    private static final String DEVELOPER_PROMPT = """
        당신은 음악 감상 서비스 Mupid 의 AI 큐레이터입니다.
        사용자가 "저장한 순간"(곡에서 마음에 남은 순간)에서 다음 음악으로 건너가도록 돕습니다.

        규칙:
        - 한국어로, 다정하지만 담백하게 두세 문장 안에서 답합니다.
        - 곡/분위기/장르 추천을 원하면 반드시 recommend_music 툴을 먼저 부릅니다.
        - 툴 결과에 있는 곡만 이야기합니다. 제목이나 아티스트를 지어내지 않습니다.
        - 곡 목록은 화면이 따로 그려 줍니다. 답변에 목록을 나열하지 말고, 어떤 결로 골랐는지만 말합니다.
        - 음악과 무관한 질문에는 짧게 답하고 음악 이야기로 돌아옵니다.
        """;

    private final OpenAiClient openAiClient;
    private final AiRecommendTool recommendTool;
    private final MusicRepository musicRepository;
    private final AiProperties props;

    @Override
    public AiChatResponse chat(User user, AiChatRequest request) {
        List<OpenAiTool> tools = declareTools();

        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.developer(developerPrompt(request.musicId())));
        appendHistory(messages, request.history());
        messages.add(OpenAiMessage.user(request.message()));

        // 툴이 여러 번 불려도 화면에는 한 번만 보여야 한다. 곡 번호로 접는다.
        Map<Integer, AiRecommendedTrack> tracks = new LinkedHashMap<>();
        // 마지막으로 곡을 돌려준 추천 묶음의 번호. 화면이 재생 이벤트에 되돌려 싣는다.
        long[] recommendationId = {0L};

        for (int round = 0; round <= props.maxToolRounds(); round++) {
            OpenAiMessage answer = openAiClient.complete(messages, tools);

            if (!answer.hasToolCalls()) {
                return new AiChatResponse(
                    replyText(answer),
                    recommendationId[0] == 0L ? null : recommendationId[0],
                    List.copyOf(tracks.values()));
            }

            // 툴 결과 앞에는 그것을 요청한 assistant 메시지가 반드시 있어야 한다.
            messages.add(answer);
            for (OpenAiToolCall call : answer.toolCalls()) {
                messages.add(runTool(user, request, call, tracks, recommendationId));
            }
        }

        log.warn("툴 호출이 {}회를 넘겨 대화를 끝내지 못했다 user_id={}",
            props.maxToolRounds(), user.getUserId());
        throw new BusinessException(AiErrorCode.AI_TOOL_ROUND_EXCEEDED);
    }

    /**
     * 모델에게 알려 줄 툴 목록.
     *
     * <p><b>툴 선언은 대화의 첫 문장이자 LLM 호출의 전제다.</b> 여기서 예외가 나면 LLM 을
     * 부르지도 못한 채 대화가 통째로 실패하는데, 사용자 눈에는 정확히 "도구 호출이 안 되는"
     * 상태로 보인다. 추천 서버 장애는 추천이 안 되는 것으로 충분하고 대화까지 끊을 이유가
     * 없으므로, 선언을 못 만들면 툴 없이 대화를 이어 간다 — 그때는 모델이 곡을 지어내지
     * 않도록 프롬프트가 이미 막고 있다.
     *
     * <p>정상 경로에서 여기가 비는 일은 없다. {@code declaration()} 이 추천 서버 실패를
     * 스스로 흡수하기 때문이다(어휘 없이 enum 만 빠진다). 이 catch 는 그 보장이 나중에
     * 깨져도 대화가 죽지 않게 하는 마지막 그물이다.
     */
    private List<OpenAiTool> declareTools() {
        try {
            return List.of(recommendTool.declaration());
        } catch (RuntimeException e) {
            log.warn("툴 선언을 만들지 못했다 — 툴 없이 대화를 이어 간다", e);
            return List.of();
        }
    }

    /**
     * 툴 하나를 실행하고 결과 메시지를 만든다.
     *
     * 실패해도 예외로 올리지 않는다. 모델에게 안 됐다고 알려 주면 그 사실을 담아 답할 수
     * 있는데, 여기서 502 를 내면 대화 자체가 끊긴다.
     */
    private OpenAiMessage runTool(
        User user,
        AiChatRequest request,
        OpenAiToolCall call,
        Map<Integer, AiRecommendedTrack> tracks,
        long[] recommendationId
    ) {
        String name = call.function() != null ? call.function().name() : null;
        if (!AiRecommendTool.NAME.equals(name)) {
            log.warn("선언하지 않은 툴을 불렀다 name={}", name);
            return OpenAiMessage.tool(call.id(), AiJson.writeString(
                Map.of("error", "%s 라는 툴은 없습니다.".formatted(String.valueOf(name)))
            ));
        }

        AiRecommendTool.Arguments arguments =
            AiJson.readArguments(call.function().arguments(), AiRecommendTool.Arguments.class);

        List<AiRecommendedTrack> found;
        try {
            AiRecommendTool.Result result =
                recommendTool.recommend(user.getUserId(), request.musicId(), arguments);
            found = result.tracks();
            if (!result.isEmpty()) {
                recommendationId[0] = result.recommendationId();
            }
        } catch (BusinessException e) {
            // 추천 서버가 죽었다고 대화까지 끊지 않는다. 모델에게 사실을 알려 주면
            // "지금은 곡을 찾아드릴 수 없다" 고 말할 수 있다. 여기서 502 를 올리면
            // 사용자는 빈 화면만 본다.
            log.warn("추천 서버 호출 실패 — 대화는 이어 간다 code={}", e.getErrorCode());
            return OpenAiMessage.tool(call.id(), AiJson.writeString(Map.of(
                "error", "지금은 곡을 찾을 수 없습니다. 잠시 후 다시 시도해 주세요.")));
        }
        found.forEach(track -> tracks.putIfAbsent(track.musicId(), track));

        if (found.isEmpty()) {
            // 빈 배열만 주면 모델이 "오류" 로 읽고 곡을 지어내는 일이 있다. 조건에 맞는
            // 곡이 없다는 것은 정상 결과이므로 그렇게 말해 준다.
            return OpenAiMessage.tool(call.id(), AiJson.writeString(Map.of(
                "tracks", found,
                "message", "조건에 맞는 곡이 없습니다. 조건을 줄이거나 다른 분위기를 제안하세요.")));
        }
        return OpenAiMessage.tool(call.id(), AiJson.writeString(Map.of("tracks", found)));
    }

    /**
     * 시스템 프롬프트에 지금 듣는 곡을 덧붙인다.
     *
     * 곡명과 아티스트는 요청 본문이 아니라 DB 에서 읽는다. 없는 번호면 붙이지 않는다.
     */
    private String developerPrompt(Integer musicId) {
        if (musicId == null) {
            return DEVELOPER_PROMPT;
        }
        return musicRepository.findDetail(musicId)
            .map(this::nowPlayingLine)
            .map(line -> DEVELOPER_PROMPT + "\n" + line)
            .orElse(DEVELOPER_PROMPT);
    }

    private String nowPlayingLine(Music music) {
        return "지금 함께 듣고 있는 곡: %s - %s (앨범 %s)".formatted(
            music.getTitle(),
            music.getArtist().getName(),
            music.getAlbum().getTitle()
        );
    }

    /**
     * 이전 대화를 붙인다. `ai.max-history` 를 넘으면 최근 것부터 남긴다.
     * 오래된 쪽을 버리는 것은 프롬프트가 무한정 길어지는 것을 막기 위해서다.
     */
    private void appendHistory(List<OpenAiMessage> messages, List<AiChatTurn> history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        int from = Math.max(0, history.size() - props.maxHistory());
        for (AiChatTurn turn : history.subList(from, history.size())) {
            switch (turn.role()) {
                case USER -> messages.add(OpenAiMessage.user(turn.content()));
                case AI -> messages.add(OpenAiMessage.assistant(turn.content()));
                // SYSTEM·ETC 는 클라이언트가 정할 값이 아니다. 프롬프트 주입 통로가 된다.
                default -> log.debug("이전 대화에서 무시한 역할 role={}", turn.role());
            }
        }
    }

    private String replyText(OpenAiMessage answer) {
        String content = answer.content();
        if (content == null || content.isBlank()) {
            throw new BusinessException(AiErrorCode.AI_EMPTY_RESPONSE);
        }
        return content.strip();
    }

}
