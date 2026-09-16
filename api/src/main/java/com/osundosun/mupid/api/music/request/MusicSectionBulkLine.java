package com.osundosun.mupid.api.music.request;

import com.osundosun.mupid.api.music.enumerate.SectionLabel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 일괄 적재에 실려 오는 구간 하나. */
public record MusicSectionBulkLine(

    /** 곡 안 순번. 0 부터. 한 요청 안에서 겹치면 안 된다. */
    @NotNull(message = "구간 순번이 입력되지 않았습니다.")
    @PositiveOrZero(message = "구간 순번은 0 이상이어야 합니다.")
    Integer sectionIndex,

    /**
     * AI 문자열 구간 ID(`T000148#3`).
     *
     * **64자 상한을 여기서 먼저 막는다.** 컬럼이 `VARCHAR(64)` 라 넘치면 MySQL 이
     * 조용히 잘라 저장하는데, 잘린 값끼리는 1:1 검사까지 통과해 매핑이 어긋난 채
     * 서비스가 올라온다. 실측 최댓값은 36자지만 flow ID 는 제목 길이만큼 늘어난다.
     */
    @NotBlank(message = "구간 external_id 가 입력되지 않았습니다.")
    @Size(max = 64, message = "구간 external_id 는 64자를 넘을 수 없습니다.")
    String externalId,

    /** ERD `section_label` ENUM. AI 는 소문자를 만들므로 보내는 쪽이 대문자로 맞춘다. */
    @NotNull(message = "구간 종류가 입력되지 않았습니다.")
    SectionLabel sectionLabel,

    @NotNull(message = "구간 시작 시간이 입력되지 않았습니다.")
    @PositiveOrZero(message = "구간 시작 시간은 0 이상이어야 합니다.")
    Integer startTimeMs,

    @NotNull(message = "구간 종료 시간이 입력되지 않았습니다.")
    @PositiveOrZero(message = "구간 종료 시간은 0 이상이어야 합니다.")
    Integer endTimeMs

) {

}
