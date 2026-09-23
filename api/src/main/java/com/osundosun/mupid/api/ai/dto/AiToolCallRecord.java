package com.osundosun.mupid.api.ai.dto;

import java.util.Map;

/**
 * 대화 한 턴에서 일어난 **툴 호출 한 건의 기록** (S15P21A204-409).
 *
 * <p>이것이 없던 동안 화면에는 LLM 의 자연어 답변만 보였다. 사용자가 "3곡 골라뒀어요" 를
 * 믿으려면 근거가 화면에 있어야 하는데, 어떤 도구를 어떤 조건으로 불렀고 몇 건이 돌아왔는지
 * — 또는 **실패했는지** — 를 볼 곳이 없었다.
 *
 * <h2>왜 응답에 싣는가</h2>
 *
 * <p>서버 로그에만 남기면 사용자는 여전히 알 수 없다. 실패가 조용히 삼켜지던 자리
 * ({@code AiChatServiceImpl.runTool} 이 {@code RECOMMENDER_UNAVAILABLE} 을 삼키고 대화를
 * 이어 가던 곳)를 **화면까지 끌어올린다.**
 *
 * <h2>접지 않는다</h2>
 *
 * <p>곡 목록은 곡 번호로 접지만({@code LinkedHashMap}) 이 기록은 **접지 않는다.**
 * 실제로 실행한 호출을 전부 남긴다. 다만 추천은 요청 한 번에 한 번만 실행하므로
 * ({@code AiChatServiceImpl.runTool}, S15P21A204-437) {@code recommend_music} 기록은
 * 많아야 한 줄이다.
 */
public record AiToolCallRecord(

    /**
     * 모델에게 선언한 툴 이름. 응답의 `tool_calls[].function.name` 과 같다 —
     * 지금은 {@code recommend_music} 하나뿐이다.
     *
     * <p><b>화면에 그대로 그리지 않는다.</b> 개발자용 식별자이고, 사용자에게는
     * {@link #label} 을 보여준다. 화면은 이 값을 시험용 훅(`data-tool`)으로만 쓴다.
     */
    String name,

    /**
     * 사용자에게 읽히는 이름. {@code recommend_music} → {@code 음악 추천}.
     *
     * <p>내부 이름을 화면에 그대로 노출하면 사용자는 그것이 무엇을 한 것인지 알 수 없다.
     * 이름을 옮기는 판단은 **서버가** 한다 — 화면마다 다른 문구가 생기는 것을 막고,
     * 툴이 늘어도 한 곳만 고치면 되게 하려는 것이다.
     */
    String label,

    /**
     * 모델이 넘긴 인자. 사용자가 **"왜 이 곡이 나왔나"** 를 이해하는 근거다.
     *
     * <p>{@code query}(원한 느낌 한 문장)·{@code limit}·{@code filters}·{@code exclude} 중
     * 모델이 실제로 채운 것만 들어간다. 아무것도 안 채웠으면 빈 맵이다(null 이 아니다).
     */
    Map<String, Object> arguments,

    /**
     * 툴이 정상적으로 돌았는가.
     *
     * <p><b>실패해도 대화는 이어진다.</b> 다만 그 사실이 화면에 남아야 사용자가
     * "추천이 왜 안 왔는지" 를 알 수 있다. 예전에는 이 실패가 로그로만 사라졌다.
     */
    boolean succeeded,

    /**
     * 결과 요약으로 보여줄 곡 수. 실패했거나 곡이 없으면 0 이다.
     */
    int trackCount,

    /**
     * 실패 사유를 사람이 읽는 문장으로. 성공이면 {@code null} 이다.
     *
     * <p>화면은 이 문장을 그대로 쓴다 — "도구 호출 → 실패(추천 서버가 응답하지 않습니다.)"
     * 가 보이면 사용자가 상황을 안다. 빈 문자열로 두지 않는 이유는 화면이 {@code null} 과
     * "사유 없음" 을 구분할 수 있어야 하기 때문이다.
     */
    String failureReason

) {

    /** 성공한 호출. */
    public static AiToolCallRecord succeeded(
        String name, String label, Map<String, Object> arguments, int trackCount
    ) {
        return new AiToolCallRecord(name, label, arguments, true, trackCount, null);
    }

    /** 실패한 호출. 곡 수는 언제나 0 이다. */
    public static AiToolCallRecord failed(
        String name, String label, Map<String, Object> arguments, String failureReason
    ) {
        return new AiToolCallRecord(name, label, arguments, false, 0, failureReason);
    }

}
