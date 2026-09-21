package com.osundosun.mupid.api.inquiry.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.osundosun.mupid.api.common.gmail.GmailProperties;
import com.osundosun.mupid.api.common.gmail.GmailProvider;
import com.osundosun.mupid.api.common.mattermost.MattermostNotifier;
import com.osundosun.mupid.api.email.service.EmailTemplateService;
import com.osundosun.mupid.api.inquiry.request.InquirySendRequest;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

/**
 * Mattermost 로 나가는 본문에 <b>사용자 입력이 마크다운 블록 서식으로 새지 않는지</b> 못박는다.
 *
 * <h2>왜 이 테스트가 필요한가</h2>
 *
 * <p>Mattermost 의 Incoming Webhook 은 {@code {"text": "..."}} 를 <b>마크다운으로
 * 렌더링</b>한다. 문의 내용을 그대로 이어 붙이면 사용자가 쓴 서식이 채널에서 살아난다 —
 * 특히 <b>H1(`# `)</b> 이 문제다. 채널에서 H1 은 본문보다 훨씬 크게, 다른 메시지들보다
 * 강하게 그려져서 <b>알림 한 건이 채널 전체를 덮는다.</b> 관리자에게는 "누가 공지라도
 * 올린 것" 처럼 보이고 정작 읽어야 할 문의가 밀려난다.
 *
 * <p>막는 방법은 글자를 지우는 것이 아니라 <b>제로폭 문자(U+200B)를 앞에 붙이는</b> 것이다.
 * 지우면 사용자가 쓴 글자를 우리가 바꾸는 셈이고, 붙이면 눈에 보이는 문자열은 원문 그대로
 * 이면서 마크다운 문법만 무력해진다 — 그래서 아래 테스트는 "제로폭 문자를 뺀 나머지가
 * 원문과 같은가" 를 함께 확인한다.
 *
 * <h2>왜 `InquiryServiceImpl` 이 아니라 여기서 보는가</h2>
 *
 * <p>본문 문자열을 <b>만드는 곳이 `InquiryNotifier`</b> 이기 때문이다. 서비스는 넘기기만
 * 하므로 서비스 테스트로는 문자열을 볼 수 없다. {@code @Async} 도 여기서는 문제가 되지
 * 않는다 — 목으로 둔 협력자를 직접 부르는 단위 테스트라 실행기를 타지 않는다.
 */
class InquiryMattermostBodyTest {

    /** 눈에 보이지 않는 제로폭 공백. 마크다운 문법을 무력화하려고 붙인다. */
    private static final String ZWSP = "\u200B";

    private MattermostNotifier mattermost;
    private InquiryNotifier notifier;

    @BeforeEach
    void setUp() {
        mattermost = mock(MattermostNotifier.class);
        /*
         * 관리자·문의자 메일도 목으로 둔다. 여기서 보려는 것은 Mattermost 본문 하나뿐이고,
         * Gmail 을 실물로 두면 자격증명이 없어 실패한다(그 실패가 접수를 막지 않는다는
         * 것은 `InquiryServiceImplTest` 와 실호출 검증이 본다).
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
     * 때문이다. 지우지 않으면 두 번째 호출에서 "1번 불렸어야 한다" 검증이 깨진다.
     * 대신 <b>한 번의 문의가 알림을 정확히 한 번</b> 부른다는 사실은 그대로 확인한다.
     */
    private String bodyFor(String content) {
        clearInvocations(mattermost);
        notifier.notifyInquiry(content, "user@mupid.test", "문의자");

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(mattermost, times(1)).notify(text.capture());
        return text.getValue();
    }

    /** 사용자 입력이 실리는 부분(마지막 `- 내용:` 줄부터). */
    private String contentPart(String body) {
        int at = body.indexOf("- 내용: ");
        return at < 0 ? body : body.substring(at + "- 내용: ".length());
    }

    // ─────────────────────────────────────────────────────────────
    // 1. H1 — 이 테스트의 본론

    @Test
    @DisplayName("본문이 `# ` 로 시작해도 H1 으로 나가지 않는다")
    void headingAtStartDoesNotBecomeH1() {
        String body = bodyFor("# 긴급 공지입니다");

        assertFalse(body.contains("\n- 내용: # "),
            "`# ` 가 줄 머리에 그대로 남아 H1 으로 렌더링된다:\n" + body);
        assertFalse(body.contains("내용: # "), "H1 표시가 그대로 남았다");
        /* 화면에 보이는 글자는 원문 그대로여야 한다 — 제로폭 문자만 앞에 붙는다. */
        assertEquals(ZWSP + "# 긴급 공지입니다", contentPart(body));
    }

    @Test
    @DisplayName("여러 줄 중 두 번째 줄이 `# ` 로 시작해도 H1 이 되지 않는다")
    void headingOnLaterLineDoesNotBecomeH1() {
        /*
         * 첫 줄만 막으면 새는 구멍이다. 사용자는 줄바꿈 뒤에 제목을 넣는다.
         */
        String body = bodyFor("첫 줄입니다\n# 두 번째 줄이 제목\n셋째 줄");

        assertFalse(body.contains("\n# "), "줄 머리의 `# ` 가 살아 있다:\n" + body);
        assertTrue(body.contains("\n" + ZWSP + "# 두 번째 줄이 제목"),
            "둘째 줄의 `# ` 앞에 제로폭 문자가 없다:\n" + body);
        assertEquals(ZWSP + "# 두 번째 줄이 제목", contentPart(body).split("\n")[1]);
    }

    @Test
    @DisplayName("`#제목` 처럼 공백 없이 붙여도 막는다")
    void headingWithoutSpaceIsAlsoNeutralized() {
        /*
         * 공백 유무로 갈라 두면 한쪽만 새는 구멍이 된다. 렌더러가 여유를 두고 받아들이는
         * 경우가 있으므로 공백 없는 형태도 함께 막는다.
         */
        assertFalse(bodyFor("#제목").contains("내용: #"), "공백 없는 `#제목` 이 그대로 남았다");
        assertTrue(bodyFor("#제목").contains(ZWSP + "#제목"));
    }

    @Test
    @DisplayName("들여쓴 `  # 제목` 도 막고, 제로폭 문자는 들여쓰기 뒤에 온다")
    void indentedHeadingIsNeutralizedAfterIndent() {
        /*
         * 제로폭 문자를 줄 맨 앞에 넣으면 그 줄이 4칸 들여쓰기 코드 블록으로 재해석될 수
         * 있다. 그래서 들여쓰기는 그대로 두고 표시 앞에만 넣는다.
         */
        String body = bodyFor("  # 제목");

        assertFalse(body.contains("내용:   # 제목"), "들여쓴 H1 이 그대로 남았다");
        assertEquals(ZWSP + "# 제목", contentPart(body).stripLeading());
        assertTrue(contentPart(body).startsWith("  " + ZWSP + "#"),
            "제로폭 문자가 들여쓰기 앞에 붙었다 — 코드 블록으로 재해석될 수 있다: "
                + contentPart(body));
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 다른 블록 서식도 함께 막는다

    @Test
    @DisplayName("인용(`> `)·목록(`- `)·수평선(`---`)도 채널 서식을 흔들지 않는다")
    void otherBlockMarkdownIsNeutralized() {
        /* 채널에 남의 알림처럼 보이거나 레이아웃을 흔드는 블록 서식들이다. */
        assertTrue(bodyFor("> 인용문").contains(ZWSP + "> 인용문"), "인용이 살아 있다");
        assertTrue(bodyFor("* 목록").contains(ZWSP + "* 목록"), "목록이 살아 있다");
        assertTrue(bodyFor("---").contains(ZWSP + "---"), "수평선이 살아 있다");
        assertTrue(bodyFor("## 소제목").contains(ZWSP + "## 소제목"), "H2 가 살아 있다");
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 원문을 훼손하지 않는다

    @Test
    @DisplayName("보통 문의는 한 글자도 바뀌지 않는다")
    void plainContentIsUntouched() {
        /*
         * 마크다운을 막는다고 사용자가 쓴 글자를 우리가 고치면 안 된다. 서식 표시가 줄
         * 머리에 없으면 손대지 않는다.
         */
        String plain = "홈에서 추천 곡이 바뀔 때마다 목록이 위로 튀어요. 스크롤 위치가 유지되면 좋겠습니다.";

        assertEquals(plain, contentPart(bodyFor(plain)));
        assertFalse(contentPart(bodyFor(plain)).contains(ZWSP));
    }

    @Test
    @DisplayName("문장 중간의 `#` 는 건드리지 않는다 — 줄 머리만 본다")
    void midLineHashIsNotTouched() {
        /* `C#`·`#태그` 를 문장 안에서 쓴 사람의 글자를 바꾸면 안 된다. */
        String content = "C# 으로 만든 앱에서 #해시태그 검색이 안 돼요";

        assertEquals(content, contentPart(bodyFor(content)));
    }

    @Test
    @DisplayName("인라인 서식(`**굵게**`)은 남긴다 — 원문 확인에 도움이 된다")
    void inlineMarkdownIsKept() {
        /* 막으려는 것은 줄 단위 블록 서식이다. 인라인까지 지우면 원문 확인이 어려워진다. */
        String content = "이 곡 **정말** 좋아요";

        assertEquals(content, contentPart(bodyFor(content)));
    }

    @Test
    @DisplayName("빈 줄과 여러 줄이 그대로 보존된다")
    void blankLinesArePreserved() {
        /* 줄을 이어 붙이다 빈 줄을 먹으면 사용자가 쓴 문단 구분이 사라진다. */
        String content = "첫 문단입니다\n\n둘째 문단입니다";

        assertEquals(content, contentPart(bodyFor(content)));
        assertEquals(2, contentPart(bodyFor(content)).split("\n", -1).length - 1,
            "빈 줄이 사라졌다");
    }

    @Test
    @DisplayName("\\r\\n 줄바꿈도 정규화되고 블록 서식 검사가 걸린다")
    void crlfIsNormalized() {
        /* 윈도우 클라이언트가 보낼 수 있다. `\r` 이 남으면 줄 머리 판정이 어긋난다. */
        String body = bodyFor("첫 줄\r\n# 제목\r\n셋째");

        assertFalse(body.contains("\r"), "\\r 이 남아 있다");
        assertFalse(body.contains("\n# "), "\\r\\n 뒤의 `# ` 가 살아 있다");
        assertTrue(body.contains(ZWSP + "# 제목"));
    }

    // ─────────────────────────────────────────────────────────────
    // 4. 나머지 본문(관리자용 정보)은 그대로

    @Test
    @DisplayName("보낸 사람 정보는 그대로 실린다 — 관리자가 답할 수 있어야 한다")
    void senderInfoIsCarried() {
        String body = bodyFor("내용");

        assertTrue(body.contains("**새 문의가 도착했습니다**"));
        assertTrue(body.contains("- 보낸 사람: 문의자 (user@mupid.test)"),
            "닉네임과 이메일이 함께 실려야 채널에서 바로 답할 수 있다:\n" + body);
    }

    @Test
    @DisplayName("내용이 비어도 예외를 던지지 않는다 — 서비스가 이미 막았지만 방어선을 둔다")
    void nullContentIsTolerated() {
        assertEquals("", InquiryNotifier.toMattermostBody(null));
        assertEquals("", contentPart(bodyFor("")));
    }

    /**
     * 서비스가 trim 을 끝낸 값을 넘긴다는 전제를 여기서도 확인한다 — 이 테스트가 직접
     * {@code notifyInquiry} 를 부르므로 앞뒤 공백을 넘기면 그대로 실린다. 즉 여기서
     * 공백을 보는 것이 아니라 <b>본문에 사용자 입력 외의 줄이 끼지 않는지</b>만 본다.
     */
    @Test
    @DisplayName("본문 구조가 유지된다 — 머리말·보낸 사람·내용 세 줄")
    void bodyStructureIsStable() {
        String body = bodyFor("문의 내용");

        assertEquals(3, body.split("\n", -1).length, "본문 줄 수가 달라졌다:\n" + body);
        assertTrue(body.endsWith("문의 내용"));
    }

}
