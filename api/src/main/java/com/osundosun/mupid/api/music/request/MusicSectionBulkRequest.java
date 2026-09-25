package com.osundosun.mupid.api.music.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 한 곡의 구간 분석 결과 전체.
 *
 * **추가가 아니라 교체다.** 한 곡의 구간은 분석 한 번의 결과로 함께 정해진다.
 * 개별로 넣으면 재분석 중간에 옛 구간과 새 구간이 섞이고, 그 상태로도 조회가
 * 정상으로 보인다. 가사(`PUT /api/v1/music/{musicId}/lyric`)가 같은 이유로
 * 통째 교체다.
 *
 * 빈 목록을 막아 둔 것도 가사와 같다 — 배치의 실수 하나가 곡의 구간을 통째로
 * 날리면 그 곡은 추천 대상에서 조용히 사라진다(`decision/0006`: 구간 분석이
 * 없는 곡은 추천 대상이 될 수 없다).
 */
public record MusicSectionBulkRequest(

    /**
     * 분석 버전. 현재 `v1`.
     *
     * MySQL `music_section_analysis.analysis_version` 과 ClickHouse `track_sections`
     * 양쪽에 **같은 값**으로 들어간다 (S15P21A204-451 전에는 ClickHouse 에만 남았다).
     * 재시도·대조 작업은 MySQL 의 이 값을 읽어 ClickHouse 에 쓴다. 그리고 `v_track_sections_current` 가 **`v1` 만** 고르므로,
     * 다른 값을 보내면 표에는 들어가되 딕셔너리가 보지 않아 구간 판정에 전혀 쓰이지
     * 않는다 — 오류 없이 그 곡의 행동 신호만 조용히 사라진다.
     */
    @NotBlank(message = "분석 버전이 입력되지 않았습니다.")
    @Size(max = 20, message = "분석 버전은 20자를 넘을 수 없습니다.")
    String analysisVersion,

    @NotEmpty(message = "구간이 입력되지 않았습니다.")
    @Size(max = MAX_SECTIONS, message = "한 번에 올릴 수 있는 구간은 200개입니다.")
    List<@Valid MusicSectionBulkLine> sections

) {

    /**
     * 한 요청에 담을 수 있는 구간 수.
     *
     * 지금 카탈로그는 곡당 중앙값 9구간, 최대 25구간이라 여유가 크다. 상한을 두는
     * 것은 잘못된 요청 하나가 트랜잭션을 오래 붙들지 못하게 하려는 것이다.
     */
    public static final int MAX_SECTIONS = 200;

}
