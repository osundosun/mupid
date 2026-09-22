package com.osundosun.mupid.api.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;

/**
 * 툴이 돌려주는 추천 곡 하나.
 *
 * 추천 단위가 곡이 아니라 **구간**인 이유는
 * `api/knowledge/decision/0006-section-as-recommendation-unit.md` 에 있다.
 *
 * <h2>곡은 {@link MusicSummary} 로 통째로 싣는다</h2>
 *
 * 예전에는 {@code title}·{@code artist}·{@code album}·{@code imageUrl} 만 납작하게
 * 실어 보냈다. 화면이 그린 줄을 **재생할 수 있으려면** 곡 번호 말고도 길이·스트림
 * 유무가 필요한데 그 값들이 없어서, 화면은 자기가 들고 있는 카탈로그에서 같은
 * {@code musicId} 를 **다시 찾아** 부족한 값을 채웠다.
 *
 * <p>그 카탈로그는 앱이 시작할 때 받아 둔 **앞쪽 100곡**이다
 * ({@code frontend/lib/stream-catalog.ts} 의 {@code CATALOG_SIZE}). 추천은 전체
 * 카탈로그에서 나오므로 대부분 그 100곡 밖이었고, 화면은 찾지 못한 추천을 통째로
 * 버렸다 — 사용자에게는 "3곡 추천했다" 는 말만 남고 곡은 하나도 보이지 않았다
 * (S15P21A204-424).
 *
 * <p>그래서 {@code 곡 목록 조회} 와 **같은 곡 표현**을 싣는다. 이제 화면은 카탈로그를
 * 보지 않고 이 값만으로 줄을 그린다 — `이어 듣기`·`비슷한 곡`·`데일리 추천` 이
 * {@code RecommendedTrack} 으로 이미 하던 방식과 같다. 추천이 덧붙이는 것은 순위·구간·
 * 사유뿐이라 곡을 **감싸는** 모양으로 둔다.
 */
public record AiRecommendedTrack(

    /** 추천 묶음 안의 순위. 1부터. */
    int rankOrder,

    /**
     * 곡. 목록·상세와 같은 표현이다.
     *
     * <p>{@code playable} 이 false 면 재생 가능한 스트림이 없다는 뜻이다. 화면은 그 줄을
     * 그리지 않고 몇 곡이 그렇게 빠졌는지만 알린다 — 눌러도 안 나는 줄을 목록에 남기지
     * 않으려는 것인데, 이제 그 판단을 **카탈로그 멤버십이 아니라 실제 신호**로 한다.
     */
    MusicSummary music,

    /** 추천된 구간. 곡 전체가 아니라 이 구간부터 들려준다. */
    Integer musicSectionId,

    SectionLabel sectionLabel,

    int startTimeMs,

    int endTimeMs,

    /** 추천 점수. 화면에는 "일치율" 로 보인다. */
    double score,

    /**
     * 사유 분류. 추천 서버가 정한다.
     *
     * `SIMILAR_PREFERRED_MOMENT` 청취 이력에서 만든 선호 구간과 닮았다
     * `ONBOARDING_MATCH`         이력이 없어 온보딩 답변으로 뽑았다
     * `QUERY_SEMANTIC_MATCH`     조건 검색 + 의미 유사도
     * `QUERY_FILTER_MATCH`       조건 검색 (필터만)
     * `EXPLORE_NEW`              탐색 슬롯
     * `COLD_START`               근거가 없다
     *
     * **늘 수 있다.** 모르는 값에서 실패하지 말 것 — 화면은 `reason` 문장을 그대로 쓰면 된다.
     */
    String reasonCode,

    /** 사람이 읽는 추천 이유. 화면에 그대로 나간다. */
    String reason

) {

    /**
     * 곡 번호. 부르는 쪽이 {@code music().musicId()} 를 외우지 않게 두는 지름길이다.
     *
     * <p><b>직렬화하지 않는다.</b> 레코드 접근자와 생김새가 같아 그냥 두면 JSON 에
     * {@code music_id} 가 곡 안팎으로 두 번 실릴 수 있다. 곡 번호가 나가는 자리는
     * {@code music.music_id} 하나다 — 두 자리가 되면 어느 쪽이 참인지 묻게 된다.
     */
    @JsonIgnore
    public int musicId() {
        return music.musicId();
    }

}
