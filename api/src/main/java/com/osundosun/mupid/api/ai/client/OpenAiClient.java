package com.osundosun.mupid.api.ai.client;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.osundosun.mupid.api.ai.error.AiErrorCode;
import com.osundosun.mupid.api.ai.properties.AiProperties;
import com.osundosun.mupid.api.common.exception.BusinessException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI 호환 `chat/completions` 한 방 호출.
 *
 * 대화의 상태(메시지 누적·툴 실행)는 이 클래스가 모른다. 그건 `AiChatServiceImpl` 의 몫이고
 * 여기는 "메시지 목록을 넣으면 메시지 하나가 나온다" 만 책임진다. Gmail 을 `GmailProvider`
 * 한 클래스로 감싼 것과 같은 자리다.
 *
 * 재시도·백오프는 없다. S3·Gmail 경로와 마찬가지로 실패는 예외로 올린다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiClient {

    private static final String CHAT_COMPLETIONS = "/chat/completions";

    private final RestClient aiRestClient;
    private final AiProperties props;

    /**
     * @param messages 지금까지의 대화 전체. 이 API 는 상태를 갖지 않아 매번 다 보낸다
     * @param tools    모델에게 알려 줄 툴. null 이면 대화만 한다
     * @return 모델이 돌려준 메시지 (본문이거나 툴 호출이거나)
     */
    public OpenAiMessage complete(List<OpenAiMessage> messages, List<OpenAiTool> tools) {
        if (!props.isConfigured()) {
            log.warn("AI 키가 없어 대화를 시작할 수 없다 (MUPID_AI_GMS_KEY)");
            throw new BusinessException(AiErrorCode.AI_NOT_CONFIGURED);
        }

        // 툴 결과 뒤에는 툴을 닫는다 — 규칙은 OpenAiChatRequest.of 주석 (S15P21A204-437).
        OpenAiChatRequest request = OpenAiChatRequest.of(props.model(), messages, tools);

        byte[] body;
        try {
            body = aiRestClient.post()
                .uri(CHAT_COMPLETIONS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer %s".formatted(props.apiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(AiJson.writeBytes(request))
                .retrieve()
                .onStatus(HttpStatusCode::isError, (req, res) -> {
                    // 게이트웨이 오류 본문에는 우리가 보낸 프롬프트가 되비치지 않는다.
                    // 상태와 사유만 남긴다 — 대화 내용은 로그에 쓰지 않는다.
                    log.error("AI 게이트웨이 오류 status={} model={}", res.getStatusCode(), props.model());
                    throw new BusinessException(AiErrorCode.AI_REQUEST_FAILED);
                })
                .body(byte[].class);
        } catch (BusinessException e) {
            throw e;
        } catch (RestClientException e) {
            // 연결 실패·읽기 타임아웃. 여기서 잡지 않으면 500 이 된다.
            log.error("AI 게이트웨이 호출 실패 base-url={}", props.baseUrl(), e);
            throw new BusinessException(AiErrorCode.AI_REQUEST_FAILED);
        }

        if (body == null || body.length == 0) {
            throw new BusinessException(AiErrorCode.AI_EMPTY_RESPONSE);
        }

        OpenAiMessage message = AiJson.read(body, OpenAiChatResponse.class).firstMessage();
        if (message == null) {
            throw new BusinessException(AiErrorCode.AI_EMPTY_RESPONSE);
        }
        return message;
    }

}
