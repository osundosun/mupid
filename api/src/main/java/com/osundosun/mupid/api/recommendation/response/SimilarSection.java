package com.osundosun.mupid.api.recommendation.response;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;

/** 기준 구간과 닮은 구간 하나. */
public record SimilarSection(

    int rankOrder,

    MusicSummary music,

    Integer musicSectionId,

    SectionLabel sectionLabel,

    int startTimeMs,

    int endTimeMs,

    /**
     * 코사인 유사도. **순위 근거일 뿐 확률이 아니다.**
     *
     * 개인 취향이 섞이지 않은 값이라 같은 구간에는 누가 물어도 같다.
     */
    double similarity

) {

}
