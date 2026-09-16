package com.osundosun.mupid.api.music.response;

/**
 * 적재된 구간 하나의 발급 번호.
 *
 * `musicSectionAnalysisId` 와 `externalId` 의 짝이 **AI 가 `id_map` 을 만드는
 * 유일한 근거**다. 숫자 ID 를 AI 가 만들지 않는다 — 문자열을 해시하거나 순번을
 * 붙여 만들면 그럴듯하게 돌다가 실제 DB 와 어긋난 순간 엉뚱한 곡을 추천한다.
 */
public record MusicSectionRef(

    int musicSectionAnalysisId,

    int sectionIndex,

    String externalId

) {

}
