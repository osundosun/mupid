package com.osundosun.mupid.api.music.request;

import com.osundosun.mupid.api.music.enumerate.VocalGender;

import jakarta.validation.constraints.NotNull;

/**
 * 한 곡의 보컬 분석 결과.
 *
 * <p><b>추가가 아니라 교체다.</b> 한 곡의 보컬 판정은 분석 한 번의 결과로 함께
 * 정해진다. 항목마다 따로 넣으면 재분석 중간에 옛 판정과 새 판정이 섞이고, 그
 * 상태로도 조회가 정상으로 보인다. 구간({@code MusicSectionBulkRequest})과
 * 가사({@code PUT /api/v1/music/{musicId}/lyric})가 같은 이유로 통째 교체다.
 *
 * <p><b>{@code vocalGender} 는 비워 보낼 수 있다.</b> 보컬이 없거나
 * ({@code hasVocal=false}) 판정하지 못한 곡이 실제로 있다 — 학습 코퍼스
 * 1,060곡 중 성별을 판정하지 못한 것이 256곡(24%)이다. 그래서 ERD 의
 * {@code ENUM('M','F','ETC')} 를 {@code NOT NULL} 로 조이지 않았고, 여기서도
 * 필수로 받지 않는다. {@code null} 은 "모른다" 이지 "기타" 가 아니다 —
 * {@code ETC} 로 뭉개면 화면이 판정 실패를 판정 결과로 말하게 된다.
 */
public record MusicAnalysisRequest(

    /**
     * 보컬 유무. 이 필드만은 필수다 — ERD 가 {@code NOT NULL} 이다.
     */
    @NotNull(message = "보컬 유무가 입력되지 않았습니다.")
    Boolean hasVocal,

    /**
     * 보컬 성별. {@code M} · {@code F} · {@code ETC}. 모르면 비운다.
     *
     * <p>문자열로 받는 이유: 조회 응답이 {@code vocalGender.name()} 을 그대로
     * 내보내므로 배치가 이미 같은 철자를 쓴다. {@code null} 이면 저장하지 않는다.
     */
    VocalGender vocalGender

) {

}
