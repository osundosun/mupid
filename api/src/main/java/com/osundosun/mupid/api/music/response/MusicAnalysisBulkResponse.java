package com.osundosun.mupid.api.music.response;

/**
 * 보컬 분석 적재 결과.
 *
 * <p>조회({@code GET /api/v1/music/{musicId}/analysis})가 아니라 적재의 응답이라
 * "무엇이 들어갔는지" 를 그대로 되돌려준다. 보낸 값과 저장된 값을 대조할 수 있어야
 * 배치가 {@code null} 과 {@code MIX} 를 구분해 확인할 수 있다.
 */
public record MusicAnalysisBulkResponse(

    Integer musicId,
    /** 저장된 보컬 유무. */
    Boolean hasVocal,
    /** 저장된 보컬 성별. {@code M} · {@code F} · {@code MIX} · 비었으면 {@code null}. */
    String vocalGender,
    /** 저장된 측정 BPM. */
    Double bpm,
    /** 저장된 요청 BPM 범위. */
    Integer bpmRequestedMin,
    Integer bpmRequestedMax,
    /** 저장된 언어·스타일. */
    String language,
    String category,
    /** 이전에 판정이 있었는가. 재분석이 덮어썼는지 확인하는 근거다. */
    boolean replaced

) {

}
