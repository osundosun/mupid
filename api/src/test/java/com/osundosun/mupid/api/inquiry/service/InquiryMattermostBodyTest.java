package com.osundosun.mupid.api.inquiry.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.osundosun.mupid.api.common.gmail.GmailProperties;
import com.osundosun.mupid.api.common.gmail.GmailProvider;
import com.osundosun.mupid.api.common.mattermost.MattermostNotifier;
import com.osundosun.mupid.api.email.service.EmailTemplateService;

/**
 * Mattermost 로 나가는 본문의 <b>모양</b>을 못박는다.
 *
 * <h2>본문이 가져야 할 모양</h2>
 *
 * <pre>
 *   # 📬 새 문의가 도착했습니다          ← H1 제목. 본문에 **정확히 한 줄**
 *   - **보낸 사람**: 문의자 (user@…)
 *
 *   ```
 *   사용자가 쓴 내용                     ← 코드블록 안. 마크다운이 무력화된다
 *   ```
 * </pre>
 *
 * <h2>왜 이 다섯 가지를 보는가</h2>
 *
 * <ol>
 *   <li><b>제목이 H1 이고 이모지가 있다.</b> `**...**` 는 본문 굵기라 채널에서 제목으로
 *       읽히지 않는다. 관리자 채널에는 곧·배치 알림도 섞여 들어오므로 이모지로 구분한다.</li>
 *   <li><b>H1 이 정확히 하나다.</b> 사용자 내용에 `# ` 가 들어와도 제목이 늘면 안 된다 —
 *       제목이 여럿이면 어느 것이 제목인지 사라지고, 사용자가 채널을 덮을 수 있다.</li>
 *   <li><b>내용이 코드블록 안에 있다.</b> 그래야 사용자가 쓴 `# `·`&gt; `·`---` 가 서식이
 *       아니라 글자로 남는다.</li>
 *   <li><b>내용에 백틱이 있어도 블록이 안 깨진다.</b> 여는/닫는 펜스를 내용에서 가장 긴
 *       백틱 연속보다 길게 잡지 않으면 블록이 거기서 닫히고 <b>그 뒤로 마크다운이 다시
 *       살아나 H1 주입이 가능해진다.</b> 이 테스트의 핵심이다.</li>
 *   <li><b>원문이 보존된다.</b> 코드블록을 고른 이유가 이것이다 — 들여쓰기·빈 줄·앞뒤
 *       공백이 그대로여야 하고, 보이지 않는 문자를 끼워 넣으면 안 된다.</li>
 * </ol>
 *
 * <h2>왜 `InquiryNotifier` 를 직접 보는가</h2>
 *
 * <p>본문 문자열을 <b>만드는 곳이 여기</b> 이기 때문이다. 서비스는 넘기기만 하므로
 * 서비스 테스트로는 문자열을 볼 수 없다. {@code @Async} 도 문제가 되지 않는다 — 목으로 둔
 * 협력자를 직접 부르는 단위 테스트라 실행기를 타지 않는다.
 */
class InquiryMattermostBodyTest {

    /** 제목 줄이 만들어 내는 H1 의 표시. `## `·`### ` 는 세지 않는다. */
    private static final String H1 = "# ";

    /** 본문에 절대 남으면 안 되는 문자. 한때 마크다운 무력화에 썼다. */
    private static final String ZERO_WIDTH_SPACE = "\u200B";

    /** 코드블록을 만드는 최소 백틱 개수. `InquiryNotifier` 와 같은 값이다. */
    private static final int MIN_FENCE = InquiryNotifier.MIN_FENCE_LENGTH;

    private MattermostNotifier mattermost;
    private InquiryNotifier notifier;

    @BeforeEach
    void setUp() {
        mattermost = mock(MattermostNotifier.class);
        /*
         * 메일 쪽도 목으로 둔다. 여기서 보려는 것은 Mattermost 본문 하나뿐이고, Gmail 을
         * 실물로 두면 자격증명이 없어 실패한다(그 실패가 접수를 막지 않는다는 것은
         * `InquiryServiceImplTest` 와 실호출 검증이 본다).
         */
        notifier = new InquiryNotifier(
            mock(GmailProvider.class),
            new GmailProperties("id", "secret", "token", "admin@mupid.test"),
            mock(EmailTemplateService.class),
            mattermost);
    }

    /**
     * `notifyInquiry` 가 Mattermost 로 넘긴 본문을 꺼낸다.
     *
     * <p>호출 기록을 먼저 지운다 — 한 테스트에서 여러 번 불러 서로 다른 입력을 비교하기
     * 때문이다. 대신 <b>문의 한 건이 알림을 정확히 한 번</b> 부른다는 사실은 확인한다.
     */
    private String bodyFor(String content) {
        clearInvocations(mattermost);
        notifier.notifyInquiry(content, "user@mupid.test", "문의자");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mattermost, times(1)).notify(text.capture());
        return text.getValue();
    }

    /**
     * 본문에서 <b>실제로 H1 이 되는</b> 줄만 센다.
     *
     * <p>코드블록 <b>안</b>의 `# ` 는 세지 않는다 — 그 안은 글자 그대로 렌더링되므로 H1 이
     * 아니다. 이 구분이 없으면 이 테스트는 아무것도 지키지 못한다: 내용에 `# ` 를 넣었을 때
     * "H1 이 늘었다" 고 잘못 판정해서, 정작 막아야 할 회귀(내용이 블록 밖으로 새는 것)과
     * 정상 동작을 구별하지 못한다.
     */
    private long countH1Lines(String body) {
        long count = 0;
        for (String line : linesOutsideCodeBlocks(body)) {
            if (line.startsWith(H1)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 코드블록 밖의 줄들. CommonMark 규칙대로 판정한다.
     *
     * <p><b>닫는 펜스는 여는 펜스보다 짧을 수 없다.</b> 이 규칙을 빼먹으면 안쪽의 짧은 백틱
     * 줄을 닫는 펜스로 잘못 읽는다 — 예컨대 4개 펜스 안에 3개 백틱 줄이 있으면 거기서
     * 블록이 닫힌 것으로 오해해, 그 뒤 내용을 "H1 이 살아 있는 구간" 으로 잘못 판정한다.
     * 그러면 테스트가 정상 동작을 회귀로 신고한다.
     */
    private java.util.List<String> linesOutsideCodeBlocks(String body) {
        java.util.List<String> outside = new java.util.ArrayList<>();
        int openingFenceLength = 0;   /* 0 이면 블록 밖 */
        for (String line : body.split("\n", -1)) {
            boolean isFence = line.length() >= MIN_FENCE && line.chars().allMatch(c -> c == '`');
            if (openingFenceLength == 0) {
                if (isFence) {
                    openingFenceLength = line.length();
                } else {
                    outside.add(line);
                }
            } else if (isFence && line.length() >= openingFenceLength) {
                openingFenceLength = 0;
            }
        }
        return outside;
    }

    /** 본문에서 내용 부분(보낸 사람 줄 다음의 빈 줄 뒤 전부)을 꺼낸다. */
    private String contentPart(String body) {
        int at = body.indexOf("- **보낸 사람**: ");
        int blank = body.indexOf("\n\n", at);
        return blank < 0 ? "" : body.substring(blank + 2);
    }

    /** 코드블록을 벗긴 내용. 여는 펜스 줄과 닫는 펜스 줄을 뺀 나머지다. */
    private String insideFence(String body) {
        String part = contentPart(body);
        String[] lines = part.split("\n", -1);
        assertTrue(lines.length >= 3, "코드블록이 아니다:\n" + body);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, 1, lines.length - 1));
    }

    /** 여는 펜스의 백틱 개수. */
    private int openingFenceLength(String body) {
        String first = contentPart(body).split("\n", -1)[0];
        assertEquals(first.replace("`", ""), "", "여는 펜스 뒤에 언어 태그가 붙었다: " + first);
        return first.length();
    }

    /** 닫는 펜스의 백틱 개수. */
    private int closingFenceLength(String body) {
        String[] lines = contentPart(body).split("\n", -1);
        String last = lines[lines.length - 1];
        assertEquals(last.replace("`", ""), "", "닫는 펜스가 아니다: " + last);
        return last.length();
    }

    // ─────────────────────────────────────────────────────────────
    // 1. 제목 — H1 + 이모지

    @Test
    @DisplayName("제목이 줄 맨 앞 `# ` H1 이고 이모지를 포함한다")
    void headingIsH1WithEmoji() {
        String body = bodyFor("내용");

        assertTrue(body.startsWith("# "), "제목이 `# ` H1 로 시작하지 않는다:\n" + body);
        assertFalse(body.contains("**새 문의가 도착했습니다**"),
            "`**...**` 는 본문 굵기다 — 제목이 아니다");
        assertTrue(body.lines().findFirst().orElse("").matches("^# \\S+ .+"),
            "제목에 이모지가 없다: " + body.lines().findFirst().orElse(""));
    }

    @Test
    @DisplayName("보낸 사람 줄이 남는다 — 관리자가 채널에서 바로 답할 수 있어야 한다")
    void senderLineIsKept() {
        String body = bodyFor("내용");

        assertTrue(body.contains("- **보낸 사람**: 문의자 (user@mupid.test)"),
            "닉네임과 이메일이 함께 실려야 동명이인을 가릴 수 있다:\n" + body);
    }

    // ─────────────────────────────────────────────────────────────
    // 2. H1 은 정확히 하나

    @Test
    @DisplayName("H1 줄이 본문에 정확히 1개다")
    void exactlyOneH1() {
        assertEquals(1, countH1Lines(bodyFor("평범한 문의입니다")), "제목이 하나가 아니다");
    }

    @Test
    @DisplayName("내용이 `# ` 로 시작해도 H1 이 늘지 않는다")
    void headingAtStartDoesNotAddH1() {
        /*
         * 이것이 코드블록으로 감싸는 이유다. 감싸지 않으면 사용자가 쓴 `# ` 가 채널에서
         * H1 이 되어 알림 한 건이 채널 전체를 덮는다.
         */
        String body = bodyFor("# 긴급 공지입니다");

        assertEquals(1, countH1Lines(body), "내용의 `# ` 가 H1 이 됐다:\n" + body);
        assertTrue(insideFence(body).contains("# 긴급 공지입니다"), "원문이 블록 안에 없다");
    }

    @Test
    @DisplayName("여러 줄에 걸쳐 `# ` 를 넣어도 H1 은 여전히 1개다")
    void multipleHeadingsInContentKeepSingleH1() {
        String body = bodyFor("첫 줄\n# 두 번째 줄이 제목\n# 세 번째도 제목\n## 네 번째");

        assertEquals(1, countH1Lines(body), "내용의 `# ` 들이 H1 이 됐다:\n" + body);
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 내용이 코드블록 안에 있다

    @Test
    @DisplayName("내용이 여는 펜스와 닫는 펜스 사이에 있다")
    void contentIsInsideCodeFence() {
        String content = "홈에서 목록이 위로 튀어요";
        String body = bodyFor(content);

        assertEquals(content, insideFence(body), "내용이 코드블록 안에 그대로 있지 않다:\n" + body);
        assertEquals(openingFenceLength(body), closingFenceLength(body),
            "여는 펜스와 닫는 펜스 길이가 다르다:\n" + body);
    }

    @Test
    @DisplayName("백틱이 없는 내용은 3개 펜스로 감싼다")
    void plainContentUsesThreeBackticks() {
        String body = bodyFor("평범한 문의");

        assertEquals(3, openingFenceLength(body), "기본 펜스는 3개다:\n" + body);
        assertEquals(3, closingFenceLength(body));
    }

    @Test
    @DisplayName("여는 펜스 뒤에 언어 태그를 붙이지 않는다")
    void openingFenceHasNoLanguageTag() {
        /*
         * 붙이면 내용이 그 언어로 강조돼 원문이 달라 보이고, 언어에 따라 `#` 가 주석으로
         * 흐려진다.
         */
        assertEquals("```", contentPart(bodyFor("내용")).split("\n", -1)[0]);
    }

    // ─────────────────────────────────────────────────────────────
    // 4. 내용에 백틱이 있어도 블록이 깨지지 않는다 — 이 테스트의 핵심

    @Test
    @DisplayName("내용에 ``` 가 있으면 4개 펜스로 감싼다")
    void tripleBacktickInContentWidensFence() {
        /*
         * CommonMark: 닫는 펜스는 여는 펜스보다 짧을 수 없다. 3개로 감쌌다면 내용의 ``` 가
         * 블록을 닫아 버리고, 그 뒤로 마크다운이 다시 살아난다.
         */
        String content = "이렇게 쓰면 되나요?\n```\n# 제목 시도\n```";
        String body = bodyFor(content);

        assertTrue(openingFenceLength(body) >= 4,
            "내용에 ``` 가 있는데 펜스가 3개다 — 블록이 거기서 닫힌다:\n" + body);
        assertEquals(openingFenceLength(body), closingFenceLength(body));
        assertEquals(content, insideFence(body), "내용이 블록 안에서 변형됐다:\n" + body);
    }

    @Test
    @DisplayName("백틱과 `# ` H1 주입을 함께 시도해도 H1 은 1개다")
    void backtickFenceBreakDoesNotReopenMarkdown() {
        /*
         * 공격 시나리오 그대로다 — 블록을 닫아 마크다운을 되살린 뒤 H1 을 넣으려는 시도.
         * 펜스 길이를 내용보다 길게 잡으면 블록이 닫히지 않아 `# ` 가 글자로 남는다.
         */
        String content = "```\n# 채널을 덮으려는 제목\n```\n# 또 하나";
        String body = bodyFor(content);

        assertEquals(1, countH1Lines(body),
            "펜스가 내용의 ``` 에 닫혀 H1 주입이 성공했다:\n" + body);
        assertEquals(content, insideFence(body), "내용이 변형됐다:\n" + body);
    }

    @Test
    @DisplayName("긴 백틱 연속도 그보다 긴 펜스로 감싼다")
    void longestRunDeterminesFenceLength() {
        /* 가장 긴 연속을 세야 한다 — 하나라도 짧게 잡으면 그 자리에서 블록이 닫힌다. */
        String body = bodyFor("`` 아님 `````` 이게 가장 길다 ` 짧음");

        assertEquals(7, openingFenceLength(body), "가장 긴 연속(6)보다 1개 길어야 한다:\n" + body);
        assertEquals(7, closingFenceLength(body));
    }

    // ─────────────────────────────────────────────────────────────
    // 5. 원문 보존 — 코드블록을 고른 이유

    @Test
    @DisplayName("줄바꿈과 빈 줄이 그대로 보존된다")
    void lineBreaksAndBlankLinesArePreserved() {
        String content = "첫 문단입니다\n\n둘째 문단입니다\n\n\n넷째 줄";

        assertEquals(content, insideFence(bodyFor(content)), "빈 줄이 사라지거나 늘었다");
    }

    @Test
    @DisplayName("들여쓰기와 앞뒤 공백이 그대로 남는다")
    void indentationAndWhitespaceArePreserved() {
        /*
         * ZWSP 방식은 여기서 원문을 변형했다(보이지 않는 문자를 끼워 넣었다). 코드블록은
         * 감싸기만 하므로 안쪽 문자열이 한 글자도 바뀌지 않는다.
         */
        String content = "  들여쓴 줄\n\t탭 줄\n   ";

        assertEquals(content, insideFence(bodyFor(content)), "공백이 변형됐다");
    }

    @Test
    @DisplayName("보이지 않는 문자를 끼워 넣지 않는다 — ZWSP 를 쓰지 않는다")
    void noInvisibleCharactersAreInjected() {
        /*
         * 관리자가 채널에서 문의를 복사해 답장할 때 ZWSP 가 클립보드에 따라간다. 붙여넣은
         * 쪽에는 안 보이는데 문자는 있으니 검색이 안 되거나 이상하게 취급된다.
         */
        String content = "# 제목처럼 쓴 줄\n> 인용처럼 쓴 줄\n---";
        String body = bodyFor(content);

        assertFalse(body.contains(ZERO_WIDTH_SPACE), "본문에 제로폭 문자가 들어갔다:\n" + body);
        assertEquals(content, insideFence(body), "원문이 변형됐다:\n" + body);
    }

    @Test
    @DisplayName("마크다운 기호가 내용에 있어도 글자로만 남는다")
    void markdownSymbolsStayLiteral() {
        /* 블록 안이므로 서식이 아니라 글자다. 원문 확인이 목적이므로 그대로 보여야 한다. */
        String content = "> 인용\n- 목록\n---\n**굵게**\n# 해시";

        assertEquals(content, insideFence(bodyFor(content)));
    }

    // ─────────────────────────────────────────────────────────────
    // 6. 경계값

    @Test
    @DisplayName("내용이 비어도 예외 없이 빈 코드블록이 된다")
    void emptyContentIsTolerated() {
        /* 서비스가 이미 막지만 방어선을 둔다. */
        assertEquals("```\n\n```", InquiryNotifier.toMattermostBody(""));
        assertEquals("```\n\n```", InquiryNotifier.toMattermostBody(null));
    }

    @Test
    @DisplayName("백틱만 있는 내용은 그보다 긴 펜스로 감싼다")
    void contentOfOnlyBackticksIsWrapped() {
        String body = bodyFor("```");

        assertEquals(4, openingFenceLength(body), "내용이 ``` 뿐이면 4개로 감싼다:\n" + body);
        assertEquals("```", insideFence(body), "원문이 블록 안에 없다");
        assertEquals(1, countH1Lines(body));
    }

}
