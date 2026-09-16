package com.osundosun.mupid.api.recommendation.response;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;

/**
 * 이어 듣기로 고른 곡 하나.
 *
 * <p>구간 정보가 함께 온다 — 곡 전체가 아니라 <b>이 구간</b>을 권한 것이고, 화면은 그
 * 지점부터 틀 수 있다.
 */
public record NextTrack(

    /** 목록 안의 순위. 1부터. */
    int rankOrder,

    MusicSummary music,

    Integer musicSectionId,

    SectionLabel sectionLabel,

    int startTimeMs,

    int endTimeMs,

    /** 왜 골랐는가. 화면이 자체 문구를 쓰고 싶으면 이 값으로 갈아끼운다. */
    String reasonCode,

    /** 사람이 읽는 이유. 모르는 코드에서도 비지 않는다. */
    String reason

) {

}
