package com.osundosun.mupid.api.ai.tool;

import java.util.Map;

import com.osundosun.mupid.api.music.enumerate.SectionLabel;

/**
 * 추천 사유 코드를 사람이 읽는 문장으로 바꾼다.
 *
 * <p>문장을 여기서 만드는 이유는 두 가지다.
 *
 * <ul>
 *   <li>추천 서버가 만드는 문구에는 내부 ID(`dna3`, `T000901#2`)와 모델 수치(유사도·
 *       확률)가 들어 있었다. 화면이 그대로 그리므로 응답에 실으면 그게 곧 노출이다
 *   <li>문구가 두 곳에 있으면 한쪽만 고쳐진다. 화면에 보이는 말은 화면을 가진 쪽이 정한다
 * </ul>
 *
 * <p><b>모르는 코드에서 실패하지 않는다.</b> 추천 서버가 사유를 늘릴 수 있고, 그때 빈
 * 문자열이 화면에 나가면 안 된다.
 */
public final class RecommendReason {

    private RecommendReason() {
    }

    private static final Map<SectionLabel, String> SECTION = Map.of(
        SectionLabel.INTRO, "인트로",
        SectionLabel.VERSE, "벌스",
        SectionLabel.PRE_CHORUS, "프리코러스",
        SectionLabel.CHORUS, "후렴",
        SectionLabel.BRIDGE, "브릿지",
        SectionLabel.BREAK, "브레이크",
        SectionLabel.INST, "연주",
        SectionLabel.SOLO, "솔로",
        SectionLabel.OUTRO, "아웃트로"
    );

    private static final Map<String, String> TEXT = Map.of(
        "QUERY_SEMANTIC_MATCH", "요청하신 분위기에 가까운 %s 구간이에요.",
        "QUERY_FILTER_MATCH", "요청하신 조건에 맞는 %s 구간이에요.",
        "SIMILAR_PREFERRED_MOMENT", "최근 좋아한 구간과 비슷한 %s 구간이에요.",
        "ONBOARDING_MATCH", "처음 고른 취향에 맞는 %s 구간이에요.",
        "EXPLORE_NEW", "새로 들어볼 만한 %s 구간이에요.",
        "COLD_START", "많이 듣는 %s 구간부터 들려드려요."
    );

    private static final String FALLBACK = "취향에 맞을 것 같아요.";

    /** 구간을 말하지 않는 문구. 「오늘의 모먼트」처럼 곡 목록으로 보여줄 때 쓴다. */
    private static final Map<String, String> TRACK_TEXT = Map.of(
        "QUERY_SEMANTIC_MATCH", "요청하신 분위기에 가까운 곡이에요.",
        "QUERY_FILTER_MATCH", "요청하신 조건에 맞는 곡이에요.",
        "SIMILAR_PREFERRED_MOMENT", "최근 좋아한 구간과 비슷한 곡이에요.",
        "ONBOARDING_MATCH", "처음 고른 취향에 맞는 곡이에요.",
        "EXPLORE_NEW", "새로 들어볼 만한 곡이에요.",
        "COLD_START", "많이 듣는 곡이에요."
    );

    /** 묶음 하나를 설명하는 한 줄. `daily_recommendations.reason` 에 들어간다. */
    private static final Map<String, String> GROUP_TEXT = Map.of(
        "QUERY_SEMANTIC_MATCH", "요청하신 분위기에 가까운 곡들이에요.",
        "QUERY_FILTER_MATCH", "요청하신 조건에 맞는 곡들이에요.",
        "SIMILAR_PREFERRED_MOMENT", "최근 좋아한 구간과 비슷한 곡들이에요.",
        "ONBOARDING_MATCH", "처음 고른 취향에 맞는 곡들이에요.",
        "EXPLORE_NEW", "새로 들어볼 만한 곡들이에요.",
        "COLD_START", "많이 듣는 곡부터 골랐어요."
    );

    /** 구간을 말하지 않는 곡 단위 문구. */
    public static String ofTrack(String reasonCode) {
        return TRACK_TEXT.getOrDefault(reasonCode, FALLBACK);
    }

    /** 묶음 단위 문구. 모르는 코드에서도 빈 문자열을 내보내지 않는다. */
    public static String ofGroup(String reasonCode) {
        return GROUP_TEXT.getOrDefault(reasonCode, FALLBACK);
    }

    public static String of(String reasonCode, SectionLabel label) {
        String template = reasonCode == null ? null : TEXT.get(reasonCode);
        if (template == null) {
            return FALLBACK;
        }
        return template.formatted(SECTION.getOrDefault(label, "이"));
    }

}
