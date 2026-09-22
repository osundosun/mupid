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
import com.osundosun.mupid.api.ai.dto.AiToolTrackView;
import com.osundosun.mupid.api.ai.dto.AiToolCallRecord;
import com.osundosun.mupid.api.ai.entity.AiChatSession;
import com.osundosun.mupid.api.ai.enumerate.AiMessageType;
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
 * <b>대화를 저장한다.</b> `ai_chat_sessions`·`ai_chat_messages` 에 남긴다 —
 * {@link AiChatHistoryService}. 한때는 저장하지 않았다. 화면이 대화를 들고 있어 지금
 * 당장 필요하지 않았고, ERD 의 `ai_chat_sessions.ai_chat_session_id` 가
 * `AUTO_INCREMENT` 없이 `INT NOT NULL` 이라(엔티티는 `GenerationType.IDENTITY`) 저장을
 * 켜려면 DDL 부터 손봐야 했기 때문이다 — `api/knowledge/decision/0011-jpa-manages-schema.md`.
 * 그 수기 보정이 `erd.sql` 에 반영돼 이제 남긴다 (S15P21A204-375).
 *
 * <h2>저장이 대화를 끊지 않는다</h2>
 *
 * <p>화면은 여전히 `history` 를 함께 보낸다. 저장은 그 위에 얹는 <b>기록</b>이지 대화의
 * 전제가 아니다. 그래서 저장 실패는 로그로 남기고 대화는 이어 간다 — 기록을 못 남긴 것과
 * 답변을 못 한 것은 사용자에게 전혀 다른 일이고, 후자를 전자의 대가로 치를 이유가 없다.
 *
 * <p><b>응답은 저장이 끝난 뒤에 만든다.</b> 그래야 클라이언트가 받은 세션 번호로 다음 턴을
 * 이어 갈 때 그 세션의 첫 마디가 이미 DB 에 있다.
 *
 * `@Transactional` 을 붙이지 않는다. 이 서비스가 하는 DB 작업은 곡 한 건 조회와 대화 저장인데,
 * 트랜잭션으로 감싸면 LLM 응답을 기다리는 수십 초 동안 커넥션을 쥐고 있게 된다. 저장만
 * {@link AiChatHistoryService} 안에서 짧은 트랜잭션으로 돈다.
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
    private final AiChatHistoryService history;

    @Override
    public AiChatResponse chat(User user, AiChatRequest request) {
        List<OpenAiTool> tools = declareTools();

        List<OpenAiMessage> messages = new ArrayList<>();
        messages.add(OpenAiMessage.developer(developerPrompt(request.musicId())));
        appendHistory(messages, request.history());
        messages.add(OpenAiMessage.user(request.message()));

        // 툴이 여러 번 불려도 화면에는 한 번만 보여야 한다. 곡 번호로 접는다.
        Map<Integer, AiRecommendedTrack> tracks = new LinkedHashMap<>();
        // **접지 않는다.** 호출 순서대로 전부 남긴다 — 몇 번 불렀는지가 정보다.
        List<AiToolCallRecord> toolCalls = new ArrayList<>();
        // 마지막으로 곡을 돌려준 추천 묶음의 번호. 화면이 재생 이벤트에 되돌려 싣는다.
        long[] recommendationId = {0L};

        for (int round = 0; round <= props.maxToolRounds(); round++) {
            OpenAiMessage answer = openAiClient.complete(messages, tools);

            if (!answer.hasToolCalls()) {
                // 답이 나왔다. 여기서 저장하고 돌려준다 — 응답이 나간 뒤에 저장하면
                // 저장을 못 한 턴의 답변은 화면에만 남고 기록에는 없다.
                return save(user, request, replyText(answer), tracks, toolCalls, recommendationId[0]);
            }

            // 툴 결과 앞에는 그것을 요청한 assistant 메시지가 반드시 있어야 한다.
            messages.add(answer);
            for (OpenAiToolCall call : answer.toolCalls()) {
                messages.add(runTool(user, request, call, tracks, toolCalls, recommendationId));
            }
        }

        log.warn("툴 호출이 {}회를 넘겨 대화를 끝내지 못했다 user_id={}",
            props.maxToolRounds(), user.getUserId());
        throw new BusinessException(AiErrorCode.AI_TOOL_ROUND_EXCEEDED);
    }

    /**
     * 대화 한 턴을 DB 에 남기고 응답을 만든다.
     *
     * <p><b>저장 실패가 대화를 끊지 않는다.</b> 화면은 `history` 를 함께 보내므로 대화는
     * 저장과 무관하게 이어진다. 기록을 못 남긴 것과 답변을 못 한 것은 사용자에게 전혀 다른
     * 일인데, 후자를 전자의 대가로 치를 이유가 없다. 대신 조용히 넘기지 않는다 — 여기서
     * 아무것도 남기지 않으면 "기록이 안 쌓인다" 는 사실을 아무도 모른다.
     *
     * <p>세션은 요청에 `sessionId` 가 있으면 그 세션을, 없으면 새로 연다. 새로 열면 그
     * 번호가 응답에 실려 화면이 다음 턴부터 이어 쓸 수 있다.
     */
    private AiChatResponse save(
        User user,
        AiChatRequest request,
        String reply,
        Map<Integer, AiRecommendedTrack> tracks,
        List<AiToolCallRecord> toolCalls,
        long recommendationId
    ) {
        Integer sessionId = request.sessionId();
        try {
            if (sessionId == null) {
                AiChatSession session = history.open(user, request.message());
                sessionId = session.getAiChatSessionId();
            }
            // 사용자 말 → AI 답 순서로 붙인다. 순서는 서비스가 세션별로 이어 붙인다.
            history.append(sessionId, AiMessageType.USER, request.message(), request.musicId());
            history.append(sessionId, AiMessageType.AI, reply, request.musicId());
        } catch (RuntimeException e) {
            log.warn("대화 기록을 저장하지 못했다 user_id={} session_id={} — 대화는 그대로 답한다",
                user.getUserId(), sessionId, e);
        }
        return new AiChatResponse(
            reply,
            sessionId,
            recommendationId == 0L ? null : recommendationId,
            List.copyOf(tracks.values()),
            List.copyOf(toolCalls));
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
     *
     * <p><b>모델에게 돌려주는 메시지 모양은 그대로다.</b> 게이트웨이가 요구하는 계약이라
     * 여기서 손대면 툴 왕복 자체가 깨진다. 기록은 {@code records} 에 따로 모은다
     * (S15P21A204-409) — 실패도 담는다. 예전에는 여기서 삼킨 실패가 로그로만 남아
     * 사용자에게는 "추천이 안 온다" 로 보였다.
     */
    private OpenAiMessage runTool(
        User user,
        AiChatRequest request,
        OpenAiToolCall call,
        Map<Integer, AiRecommendedTrack> tracks,
        List<AiToolCallRecord> records,
        long[] recommendationId
    ) {
        String name = call.function() != null ? call.function().name() : null;
        if (!AiRecommendTool.NAME.equals(name)) {
            log.warn("선언하지 않은 툴을 불렀다 name={}", name);
            // 사용자에게는 우리가 아는 이름으로 보여준다. 모르는 툴이면 이름 그대로가 정직하다.
            records.add(AiToolCallRecord.failed(
                name, labelOf(name), Map.of(), "이 대화에서 쓸 수 없는 도구입니다."));
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
            //
            // **대화를 이어 가는 것과 조용히 넘기는 것은 다르다.** 실패를 기록에 남겨
            // 화면까지 보낸다 — 그래야 사용자가 "도구 호출 → 실패" 를 볼 수 있다.
            log.warn("추천 서버 호출 실패 — 대화는 이어 간다 code={}", e.getErrorCode());
            records.add(AiToolCallRecord.failed(
                AiRecommendTool.NAME, labelOf(AiRecommendTool.NAME), argumentsOf(arguments),
                e.getMessage()));
            return OpenAiMessage.tool(call.id(), AiJson.writeString(Map.of(
                "error", "지금은 곡을 찾을 수 없습니다. 잠시 후 다시 시도해 주세요.")));
        }
        found.forEach(track -> tracks.putIfAbsent(track.musicId(), track));
        records.add(AiToolCallRecord.succeeded(
            AiRecommendTool.NAME, labelOf(AiRecommendTool.NAME), argumentsOf(arguments), found.size()));

        if (found.isEmpty()) {
            // 빈 배열만 주면 모델이 "오류" 로 읽고 곡을 지어내는 일이 있다. 조건에 맞는
            // 곡이 없다는 것은 정상 결과이므로 그렇게 말해 준다.
            return OpenAiMessage.tool(call.id(), AiJson.writeString(Map.of(
                "tracks", modelView(found),
                "message", "조건에 맞는 곡이 없습니다. 조건을 줄이거나 다른 분위기를 제안하세요.")));
        }
        return OpenAiMessage.tool(call.id(), AiJson.writeString(Map.of("tracks", modelView(found))));
    }

    /**
     * 모델에게 돌려줄 모양으로 줄인다 (S15P21A204-426).
     *
     * <p><b>화면에 나가는 것을 그대로 보내지 않는다.</b> 곡 표현({@code MusicSummary})에는
     * {@code LocalDateTime} 이 있는데 게이트웨이 전용 매퍼에는 JavaTimeModule 이 없어,
     * 그대로 보내면 직렬화가 터지고 **추천이 성공한 모든 턴이 502** 가 된다. 무엇을
     * 보내고 무엇을 안 보내는지는 {@link AiToolTrackView} 주석에 있다.
     */
    private List<AiToolTrackView> modelView(List<AiRecommendedTrack> tracks) {
        return tracks.stream().map(AiToolTrackView::of).toList();
    }

    /**
     * 툴 이름을 사용자에게 읽히는 말로 옮긴다 (S15P21A204-409).
     *
     * <p>화면은 **개발자용 로그가 아니라 사용자 화면**이다. {@code recommend_music} 을
     * 그대로 노출하면 사용자는 그것이 무슨 일을 한 것인지 알 수 없다. 옮기는 판단을
     * 서버가 하는 이유는, 화면마다 다른 문구가 생기는 것을 막고 툴이 늘어도 한 곳만
     * 고치면 되게 하려는 것이다.
     *
     * <p>모르는 이름은 그대로 돌려준다. 우리가 모르는 툴을 화면이 임의로 번역하면
     * 없는 기능을 있다고 말하게 된다.
     */
    private String labelOf(String name) {
        if (AiRecommendTool.NAME.equals(name)) {
            return "음악 추천";
        }
        return name;
    }

    /**
     * 모델이 넘긴 인자를 화면에 보여줄 모양으로 다듬는다.
     *
     * <p>사용자가 **"왜 이 곡이 나왔나"** 를 이해하는 근거라 버리지 않는다. 다만 빈 칸은
     * 뺀다 — {@code {"query": null, "filters": {}}} 처럼 빈 껍데기가 화면에 줄줄이
     * 그려지면 읽을 수 없다. 모델은 조건이 없을 때도 빈 껍데기를 곧잘 만든다.
     *
     * <p>순서를 고정한다({@code LinkedHashMap}). 같은 대화가 매번 다른 차례로 보이면
     * 기록으로 읽히지 않는다.
     */
    private Map<String, Object> argumentsOf(AiRecommendTool.Arguments arguments) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (arguments == null) {
            return out;
        }
        if (arguments.query() != null && !arguments.query().isBlank()) {
            out.put("query", arguments.query());
        }
        if (arguments.limit() != null) {
            out.put("limit", arguments.limit());
        }
        // 조건은 **알맹이가 있는 것만** 넣는다. 모델은 조건이 없을 때도
        // `{"genre": []}` 같은 빈 껍데기를 곧잘 만드는데, 그것을 그대로 실으면 화면에
        // 값 없는 줄이 붙어 "무언가를 걸었다" 로 잘못 읽힌다.
        putIfMeaningful(out, "filters", arguments.filters());
        putIfMeaningful(out, "exclude", arguments.exclude());
        return out;
    }

    /**
     * 조건 맵에서 알맹이가 있는 칸만 남긴다.
     *
     * <p>빈 리스트(`[]`)·빈 맵(`{}`)·null 은 걷어낸다. 값이 남은 칸만 기록에 싣는다 —
     * 전부 빈 껍데기면 키 자체가 기록에 나오지 않는다.
     */
    private void putIfMeaningful(Map<String, Object> out, String key, Map<String, Object> conditions) {
        Map<String, Object> meaningful = new LinkedHashMap<>();
        if (conditions != null) {
            conditions.forEach((field, value) -> {
                if (value == null) {
                    return;
                }
                if (value instanceof List<?> list && list.isEmpty()) {
                    return;
                }
                if (value instanceof Map<?, ?> map && map.isEmpty()) {
                    return;
                }
                meaningful.put(field, value);
            });
        }
        if (!meaningful.isEmpty()) {
            out.put(key, meaningful);
        }
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
