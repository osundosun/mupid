package com.osundosun.mupid.api.analytics.enumerate;

/**
 * AI 선호도 집계의 <b>구분</b> — 재생 하나가 어느 추천에서 나왔는가 (S15P21A204-427).
 *
 * <p>가르는 근거는 그 재생의 {@code PLAY_START} 한 건이다. 추천 표식(`rec_id`·`rec_type`)은
 * {@code PLAY_START} 에만 실리고 뒤따르는 하트비트·스킵에는 없기 때문이다
 * ({@code knowledge/domain/listening-events.md}).
 *
 * <h2>{@code entry_path = 'RECOMMEND'} 만으로 AI 로 치지 않는다</h2>
 *
 * <p>그 값은 화면으로 갈음한 추정이라, 추천 서버가 죽었을 때 카탈로그로 채운 홈 대체
 * 목록이나 닮은 구간(개인화 없음)처럼 <b>AI 가 고르지 않은 재생</b>이 섞인다.
 * {@code rec_id} 가 비어 있지 않아야 추천 응답이 실제로 만든 재생이다 — 둘 다 걸린
 * 것만 AI 로 센다. 나머지는 전부 {@link #NON_AI} 다.
 *
 * <h2>선언 순서가 화면 순서다</h2>
 *
 * <p>SQL 이 이 이름을 문자열로 돌려준다 ({@code AiPreferenceRepository#aggregate}).
 * 이름을 바꾸면 이미 쌓인 집계 행과 어긋나므로 <b>바꾸지 않는다.</b>
 */
public enum AiPreferenceSegment {

    /** 오늘의 모먼트 (`/recommendation/daily`). 배치가 하루 한 번 만든 묶음이다. */
    HOME("오늘의 모먼트", true),

    /**
     * 구간 추천 — 홈 "취향에 이어지는 음악"(`/recommendation/next`)과 챗봇 추천 곡.
     *
     * <p>둘을 가르지 못한다. 둘 다 {@code rec_type = 'SECTION'} 을 싣고 {@code rec_id} 도
     * 같은 모양(밀리초로 시작하는 순번)이라 이벤트만 보고는 어느 화면인지 알 수 없다.
     */
    SECTION("구간 추천 · 챗봇", true),

    /** 사용자가 조건을 걸어 받은 추천. 지금은 싣는 화면이 없어 늘 0 이다. */
    CUSTOM("맞춤 추천", true),

    /**
     * 추천 표식은 있는데 {@code rec_type} 이 비었거나 모르는 값.
     *
     * <p>버리지 않고 따로 센다. AI 합계에서 빠지면 추천 성과가 실제보다 작아 보이고,
     * {@link #NON_AI} 에 넣으면 기준선이 오염된다. 이 칸이 0 이 아니면 프론트가 새
     * `rec_type` 을 보내기 시작했다는 뜻이다.
     */
    UNTYPED("유형 모름", true),

    /** AI 가 고르지 않은 모든 재생 — 검색·재생 목록·큐·최근 들은 곡·아티스트. 비교 기준선. */
    NON_AI("AI 아닌 재생", false);

    private final String label;
    private final boolean ai;

    AiPreferenceSegment(String label, boolean ai) {
        this.label = label;
        this.ai = ai;
    }

    /** 화면에 보이는 이름. */
    public String label() {
        return label;
    }

    /** AI 추천에서 나온 재생인가. AI 합계를 만들 때 더할지를 정한다. */
    public boolean ai() {
        return ai;
    }

    /**
     * 저장된 문자열을 되읽는다. <b>모르는 값은 {@link #UNTYPED} 다</b> — 나중에 구분이
     * 늘어난 표를 옛 코드가 읽어도 행을 잃지 않게 한다.
     */
    public static AiPreferenceSegment parse(String value) {
        for (AiPreferenceSegment segment : values()) {
            if (segment.name().equals(value)) {
                return segment;
            }
        }
        return UNTYPED;
    }

}
