package com.osundosun.mupid.api.music.request;

import com.osundosun.mupid.api.music.enumerate.SectionLabel;

import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
    Integer endTimeMs,

    /**
     * 이 구간의 분위기. **코드 이름**으로 받는다 (`잔잔한`·`신나는`…).
     *
     * 번호는 우리가 붙인다 — AI 는 MySQL 이 발급한 숫자를 모른다. 코드표에 없는
     * 이름은 버리고 센다. 비워 둬도 된다(분위기를 못 고른 곡이 실제로 14% 있다).
     */
    List<@Size(max = 50) String> moods,

    /**
     * 이 구간에서 들리는 악기. <b>코드 이름</b>으로 받는다 (`보컬`·`드럼`·`베이스`…).
     *
     * 분위기와 같은 규칙이다 — 번호는 우리가 붙이고, 코드표에 없는 이름은 버리고
     * 센다. 비워 둬도 된다. 악기는 곡이 아니라 구간에 달린다
     * (`music_analysis_instruments` 의 외래키가 구간 번호다).
     */
    List<@Size(max = 50) String> instruments,

    /**
     * 구간 에너지 — 오디오 RMS(0~1). 모르면 비운다.
     *
     * 추천이 이어 듣기 흐름 감점과 "더 차분하게" 조건에 쓴다. 예전에는 AI 색인에만
     * 있었다(연동 계약 2026-09-23 절).
     */
    @DecimalMin(value = "0.0", message = "구간 에너지는 0 이상이어야 합니다.")
    @DecimalMax(value = "1.0", message = "구간 에너지는 1 이하여야 합니다.")
    Double energy

) {

}
