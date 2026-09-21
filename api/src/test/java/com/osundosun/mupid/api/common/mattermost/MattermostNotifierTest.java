package com.osundosun.mupid.api.common.mattermost;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * Mattermost 알림이 <b>던지지 않는 것</b>과 <b>URL 을 로그에 남기지 않는 것</b>을 못박는다.
 *
 * <h2>왜 목이 아니라 진짜 RestClient 인가</h2>
 *
 * <p>처음에는 {@code RestClient} 를 목으로 세웠는데 <b>그 목이 거짓 통과를 만들었다.</b>
 * {@code RequestBodySpec.body(Object)} 는 자기 자신을 돌려주는 <b>default 메서드</b>라
 * Mockito 의 기본 답(답이 없으면 {@code null})이 끼어들어 {@code retrieve()} 가
 * {@code null} 이 됐다. 그런데 {@code notify} 가 예외를 삼키므로 NPE 가 조용히 먹혔고,
 * "보낸다" 테스트가 실패해야 할 자리에서 실패한 것이다 — 삼키는 코드를 시험하는 목은
 * 특히 위험하다.
 *
 * <p>그래서 여기서는 {@link ClientHttpRequestFactory} 만 갈아끼운 <b>실물 {@code RestClient}
 * 체인</b>을 쓴다. 확인하는 것도 훨씬 구체적이 된다 — 메서드·URI·Content-Type·본문 JSON
 * 이 실제로 그렇게 나가는지, 그리고 연결이 죽었을 때 정말 아무것도 던지지 않는지.
 *
 * <h2>이 티켓의 두 계약</h2>
 *
 * <ol>
 *   <li><b>알림이 죽어도 문의 접수는 산다.</b> `infra/deploy/.env.example:16` 이 명시한다 —
 *       "비어 있으면 알림만 조용히 꺼지고 피드백 접수는 정상 동작한다". 그래서 웹훅이
 *       비었을 때·연결이 죽었을 때·4xx/5xx 일 때 모두 {@code notify} 가 던지지 않아야 한다.</li>
 *   <li><b>웹훅 URL 을 로그에 남기지 않는다.</b> URL 자체가 자격증명이다. 이 저장소는 배포용
 *       SSH 키를 로그에 찍은 적이 있고 그때 키를 교체해야 했다. 실패 로그에 예외를 그대로
 *       넘기면 {@code RestClientResponseException} 메시지에 요청 URL 이 들어 있어 같은 실수가
 *       반복된다 — 그래서 {@link MattermostNotifier#mask(String)} 를 직접 확인한다.</li>
 * </ol>
 */
class MattermostNotifierTest {

    /**
     * 웹훅 URL 모양의 **가짜** 값이다. 실제 발급분이 아니다 — 절대 넣지 마라.
     *
     * <p>이 테스트는 {@link CountingRequestFactory} 로 요청을 가로채므로 **네트워크를 타지 않는다.**
     * URL 은 문자열로만 쓰인다. 그런데도 실제 발급분을 두면 두 가지가 망가진다:
     * <ul>
     *   <li><b>자격증명이 저장소에 들어간다.</b> 웹훅 URL 은 아는 쪽이 그 채널에 아무 메시지나
     *       보낼 수 있는 값이다 — 여기 박으면 커밋 이력에 영구히 남는다.</li>
     *   <li><b>테스트가 우연히 통과할 수 있다.</b> 자격증명이 남아 있으면 나중에 누가 이 테스트를
     *       실제 호출로 바꿨을 때 진짜 채널로 메시지가 갈 수 있다.</li>
     * </ul>
     *
     * <p>`another-token-here` 처럼 **모양만 같은** 값을 쓴다. 마스킹 검증이 토큰 부분을 가리는지
     * 보려면 문자열이 충분히 길기만 하면 된다.
     */
    private static final String WEBHOOK =
        "https://meeting.ssafy.com/hooks/fake-token-for-tests-only";

    /** 웹훅이 설정되지 않은 상태 — 기본값이 빈 문자열이고 이게 정상 동작이다. */
    private static final MattermostProperties NOT_CONFIGURED = new MattermostProperties("", null, null);

    private static final MattermostProperties CONFIGURED =
        new MattermostProperties(WEBHOOK, null, null);

    /** 요청이 만들어졌는지 세는 팩토리. "건너뛰었다" 를 HTTP 를 안 부른 것으로 확인한다. */
    private static final class CountingRequestFactory implements ClientHttpRequestFactory {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<MockClientHttpRequest> lastRequest = new AtomicReference<>();
        private final HttpStatus status;
        private final IOException failure;

        private CountingRequestFactory(HttpStatus status, IOException failure) {
            this.status = status;
            this.failure = failure;
        }

        static CountingRequestFactory ok() {
            return new CountingRequestFactory(HttpStatus.OK, null);
        }

        static CountingRequestFactory refusing() {
            return new CountingRequestFactory(null, new IOException(
                "Connection refused: " + WEBHOOK));
        }

        static CountingRequestFactory answering(HttpStatus status) {
            return new CountingRequestFactory(status, null);
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, org.springframework.http.HttpMethod method)
                throws IOException {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            request.setResponse(new MockClientHttpResponse(new byte[0], status));
            lastRequest.set(request);
            return request;
        }

        String bodyAsString() throws IOException {
            MockClientHttpRequest request = lastRequest.get();
            if (request == null) {
                return null;
            }
            return new String(((ByteArrayOutputStream)request.getBody()).toByteArray(),
                StandardCharsets.UTF_8);
        }
    }

    private static RestClient clientOver(CountingRequestFactory factory) {
        return RestClient.builder().baseUrl(WEBHOOK).requestFactory(factory).build();
    }

    // ─────────────────────────────────────────────────────────────
    // 1. 보낸다

    @Test
    @DisplayName("웹훅이 설정되면 POST 로 본문 {\"text\": ...} 를 보낸다")
    void sendsTextPayload() throws IOException {
        CountingRequestFactory factory = CountingRequestFactory.ok();
        MattermostNotifier notifier = new MattermostNotifier(clientOver(factory), CONFIGURED);

        assertTrue(notifier.isConfigured());
        assertDoesNotThrow(() -> notifier.notify("새 문의가 도착했습니다"));

        assertEquals(1, factory.calls.get(), "웹훅을 한 번도 부르지 않았다");
        MockClientHttpRequest sent = factory.lastRequest.get();
        assertEquals("POST", sent.getMethod().name(), "Incoming Webhook 은 POST 다");
        assertEquals(URI.create(WEBHOOK), sent.getURI(), "웹훅 주소가 그대로여야 한다");
        assertEquals(MediaType.APPLICATION_JSON,
            sent.getHeaders().getContentType(), "Content-Type 이 application/json 이어야 한다");
        /*
         * Mattermost Incoming Webhook 의 본문 규약은 {"text": "..."} 하나다.
         * 필드 이름이 바뀌면 채널에 아무것도 그려지지 않는다.
         */
        assertEquals("{\"text\":\"새 문의가 도착했습니다\"}", factory.bodyAsString());

        /*
         * 요청이 **실행까지** 갔는지 본다. `MockClientHttpRequest.isExecuted()` 는
         * `execute()` 가 실제로 불렸을 때만 true 다 — 요청 객체만 만들고 안 보낸 경우와
         * 구분된다. 성공 로그의 존재는 실호출 검증(`_shared/verify-384.sh`)이 확인한다.
         */
        assertTrue(sent.isExecuted(), "요청을 만들기만 하고 보내지 않았다");
    }

    @Test
    @DisplayName("웹훅이 비어 있으면 HTTP 를 아예 부르지 않고 조용히 건너뛴다")
    void skipsWhenNotConfigured() {
        CountingRequestFactory factory = CountingRequestFactory.ok();
        MattermostNotifier notifier = new MattermostNotifier(clientOver(factory), NOT_CONFIGURED);

        assertFalse(notifier.isConfigured());
        assertDoesNotThrow(() -> notifier.notify("새 문의"));

        /*
         * "건너뛰었다" 의 증거는 로그 문구가 아니라 **요청을 만들지 않았다**는 사실이다.
         * 빈 설정으로 나가면 `http://mattermost.invalid` 로 매 요청마다 타임아웃을 기다린다.
         */
        assertEquals(0, factory.calls.get(), "웹훅이 비었는데 HTTP 를 불렀다");
        assertNull(factory.lastRequest.get());
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 실패해도 던지지 않는다 — 접수를 막지 않는다

    @Test
    @DisplayName("연결이 거부돼도(가짜 주소) 예외를 던지지 않는다")
    void swallowsConnectionFailure() {
        /*
         * 실호출 검증의 `http://127.0.0.1:9/hooks/none` 과 같은 상황이다 — 닫힌 포트라
         * 연결이 거부된다. 여기서 예외가 올라가면 문의 접수가 실패한다.
         */
        CountingRequestFactory factory = CountingRequestFactory.refusing();
        MattermostNotifier notifier = new MattermostNotifier(clientOver(factory), CONFIGURED);

        assertDoesNotThrow(() -> notifier.notify("새 문의"));

        assertEquals(1, factory.calls.get(), "연결은 시도했어야 한다 — 시도조차 안 하면 이 테스트가 무의미하다");
    }

    @Test
    @DisplayName("웹훅이 404 로 답해도 예외를 던지지 않는다")
    void swallows404() {
        CountingRequestFactory factory = CountingRequestFactory.answering(HttpStatus.NOT_FOUND);
        MattermostNotifier notifier = new MattermostNotifier(clientOver(factory), CONFIGURED);

        assertDoesNotThrow(() -> notifier.notify("새 문의"));
    }

    @Test
    @DisplayName("웹훅이 500 으로 답해도 예외를 던지지 않는다")
    void swallows500() {
        CountingRequestFactory factory = CountingRequestFactory.answering(HttpStatus.INTERNAL_SERVER_ERROR);
        MattermostNotifier notifier = new MattermostNotifier(clientOver(factory), CONFIGURED);

        assertDoesNotThrow(() -> notifier.notify("새 문의"));
    }

    // ─────────────────────────────────────────────────────────────
    // 3. URL 을 로그에 남기지 않는다

    @Test
    @DisplayName("설정된 웹훅 URL 은 로그 문구에서 가려진다")
    void masksConfiguredUrl() {
        MattermostNotifier notifier = new MattermostNotifier(clientOver(CountingRequestFactory.ok()),
            CONFIGURED);

        String masked = notifier.mask(
            "I/O error on POST request for \"" + WEBHOOK + "\": Connection refused");

        assertFalse(masked.contains(WEBHOOK), "웹훅 URL 이 로그로 새어 나간다");
        assertTrue(masked.contains(MattermostNotifier.MASKED_URL));
    }

    /**
     * 설정값과 문자열이 정확히 같지 않아도 가려야 한다 — 예외가 리다이렉트나 다른 표기로
     * URL 을 다시 실어 올 수 있다.
     */
    @Test
    @DisplayName("설정값과 달라도 hooks/ 가 보이는 주소는 가려진다")
    void masksAnyWebhookShapedUrl() {
        MattermostNotifier notifier = new MattermostNotifier(clientOver(CountingRequestFactory.ok()),
            CONFIGURED);

        String masked = notifier.mask(
            "redirected to https://meeting.ssafy.com/hooks/another-token-here (301)");

        assertFalse(masked.contains("another-token-here"), "다른 웹훅 주소가 그대로 남았다");
    }

    /**
     * <b>마스킹이 정말 필요한지</b>를 같은 테스트에서 보인다 — 위험이 실재한다는 것과
     * 방어가 동작한다는 것을 붙여 둔다.
     *
     * <h2>어느 예외가 URL 을 품는가 (실측)</h2>
     *
     * <p>여기서 확인한 사실 둘.
     *
     * <ul>
     *   <li><b>연결 실패는 URL 을 품는다.</b> {@code ResourceAccessException} 의 메시지가
     *       {@code I/O error on POST request for "https://…/hooks/…": Connection refused}
     *       다. 가짜 웹훅 주소를 넣었을 때 실제로 나는 예외가 이것이고, 그대로 로그에
     *       넘기면 자격증명이 새어 나간다.</li>
     *   <li><b>상태 코드 오류는 URL 을 품지 않는다.</b>
     *       {@code HttpClientErrorException$BadRequest} 의 문자열은
     *       {@code 400 Bad Request: [no body]} 뿐이다. 그래서 마스킹을 상태 오류 쪽
     *       근거로 삼으면 안 된다 — 근거는 연결 실패다.</li>
     * </ul>
     *
     * <p>둘 다 같은 {@code mask} 를 지나므로 방어는 하나로 충분하다.
     */
    @Test
    @DisplayName("연결 실패 예외 문자열에는 웹훅 URL 이 들어 있다 — 그래서 마스킹이 필요하다")
    void realExceptionCarriesTheUrlAndMaskRemovesIt() {
        CountingRequestFactory factory = CountingRequestFactory.refusing();
        MattermostNotifier notifier = new MattermostNotifier(clientOver(factory), CONFIGURED);

        /* notify 안에서 실제로 나는 그 예외를 여기서도 똑같이 만들어 본다. */
        String thrown = null;
        try {
            clientOver(factory).post()
                .contentType(MediaType.APPLICATION_JSON)
                .body(new Object())
                .retrieve()
                .toBodilessEntity();
        } catch (RuntimeException e) {
            thrown = e.toString();
        }

        assertTrue(thrown != null && thrown.contains(WEBHOOK),
            "연결 실패 예외에 URL 이 없다면 이 테스트는 마스킹의 필요성을 못 보인다: " + thrown);
        assertFalse(notifier.mask(thrown).contains(WEBHOOK),
            "예외 문자열을 그대로 로그에 넘기면 웹훅 URL 이 새어 나간다");
    }

    /**
     * 웹훅이 설정돼 있지 않아도 {@code hooks/} 모양 주소는 가린다. <b>의도된 동작이다.</b>
     *
     * <p>"설정값이 없으면 가릴 것도 없다" 로 두면 안 된다. 어떤 경로로든 웹훅 모양 주소가
     * 로그 문자열에 들어왔다면 그건 이미 자격증명이고, 우리 쪽 설정 여부와 무관하게
     * 가려야 한다. 그래서 {@code mask} 는 설정값 치환과 무관하게 정규식 규칙을 항상 건다.
     */
    @Test
    @DisplayName("웹훅이 설정돼 있지 않아도 hooks/ 모양 주소는 가린다")
    void maskHidesWebhookShapedUrlsEvenWhenUnconfigured() {
        MattermostNotifier notifier = new MattermostNotifier(clientOver(CountingRequestFactory.ok()),
            NOT_CONFIGURED);

        assertFalse(notifier.mask(WEBHOOK).contains(WEBHOOK),
            "설정 여부와 무관하게 웹훅 모양 주소는 가려야 한다");
        assertNull(notifier.mask(null), "null 은 그대로 null 이다");
    }

    /** 웹훅과 무관한 로그 문구는 건드리지 않는다 — 마스킹이 과하게 세면 디버깅이 불가능해진다. */
    @Test
    @DisplayName("웹훅과 무관한 문구는 그대로 둔다")
    void maskLeavesUnrelatedMessagesAlone() {
        MattermostNotifier notifier = new MattermostNotifier(clientOver(CountingRequestFactory.ok()),
            CONFIGURED);

        String unrelated = "I/O error on POST request for \"https://api.example.com/v1/chat\": timeout";
        assertEquals(unrelated, notifier.mask(unrelated));
    }

}
