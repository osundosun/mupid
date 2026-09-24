package com.osundosun.mupid.api.music.request;

import com.osundosun.mupid.api.music.enumerate.VocalGender;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 한 곡의 분석 결과 — 보컬 판정과 오디오·생성 조건 값.
 *
 * <p><b>추가가 아니라 교체다.</b> 한 곡의 분석은 한 번의 결과로 함께 정해진다.
 * 항목마다 따로 넣으면 재분석 중간에 옛 값과 새 값이 섞이고, 그 상태로도 조회가
 * 정상으로 보인다. 구간({@code MusicSectionBulkRequest})과 가사가 같은 이유로 통째
 * 교체다. 그래서 <b>보내지 않은 필드는 비워진다</b> — 부분 갱신이 필요하면 조회해서
 * 합친 뒤 보낸다. 쓰는 쪽은 분석 파이프라인 하나다({@code register_analysis}).
 *
 * <p><b>{@code hasVocal} 만 필수다</b> — ERD 가 {@code NOT NULL} 이다. 생성 음원은
 * 생성 요청의 보컬 종류가 정답이라 언제나 값이 있다. 보컬 분류기는 확률 0.60 미만이면
 * {@code unknown} 을 내는데, 그 135곡은 전부 연주곡으로 요청한 곡이었고 들어 보니
 * 연주곡이었다(2026-09-23). 나머지 필드의 {@code null} 은 "모른다" 다.
 *
 * <p>BPM·언어·스타일은 추천이 쓴다. 전에는 AI 색인에만 있었다(연동 계약 2026-09-23 절).
 */
public record MusicAnalysisRequest(

    /** 보컬 유무. 생성 요청의 보컬 종류가 있으면 그것을, 없으면 분류기 판정을 보낸다. */
    @NotNull(message = "보컬 유무가 입력되지 않았습니다.")
    Boolean hasVocal,

    /**
     * 보컬 성별. {@code M} · {@code F} · {@code MIX}. 모르면 비운다.
     *
     * <p>{@code null} 을 {@code MIX} 로 뭉개지 않는다 — 화면이 판정 실패를 혼성 보컬로
     * 말하게 된다.
     */
    VocalGender vocalGender,

    /** 측정 BPM. 사람이 듣는 음악의 범위를 벗어나면 측정 오류로 보고 받지 않는다. */
    @DecimalMin(value = "20.0", message = "BPM 은 20 이상이어야 합니다.")
    @DecimalMax(value = "400.0", message = "BPM 은 400 이하여야 합니다.")
    Double bpm,

    /** 생성 때 요청한 BPM 범위의 아래. */
    @Min(value = 20, message = "요청 BPM 은 20 이상이어야 합니다.")
    @Max(value = 400, message = "요청 BPM 은 400 이하여야 합니다.")
    Integer bpmRequestedMin,

    /** 생성 때 요청한 BPM 범위의 위. */
    @Min(value = 20, message = "요청 BPM 은 20 이상이어야 합니다.")
    @Max(value = 400, message = "요청 BPM 은 400 이하여야 합니다.")
    Integer bpmRequestedMax,

    /** 가사 언어. 컬럼이 {@code VARCHAR(32)} 라 넘치면 잘려 저장되므로 여기서 막는다. */
    @Size(max = 32, message = "언어는 32자를 넘을 수 없습니다.")
    String language,

    /** 생성 스타일 원문. 컬럼이 {@code VARCHAR(64)} 다. */
    @Size(max = 64, message = "스타일은 64자를 넘을 수 없습니다.")
    String category

) {

    /** 보컬이 없다고 판정했는데 성별이 있으면 모순이다. */
    @AssertTrue(message = "보컬이 없는 곡에는 보컬 성별을 넣을 수 없습니다.")
    public boolean isVocalGenderConsistent() {
        return vocalGender == null || !Boolean.FALSE.equals(hasVocal);
    }

    /** 범위는 둘 다 있거나 둘 다 없다. 한쪽만 있으면 중간값을 낼 수 없다. */
    @AssertTrue(message = "요청 BPM 범위는 아래와 위를 함께 보내야 하고, 아래가 위보다 클 수 없습니다.")
    public boolean isRequestedBpmRangeValid() {
        if (bpmRequestedMin == null || bpmRequestedMax == null) {
            return bpmRequestedMin == null && bpmRequestedMax == null;
        }
        return bpmRequestedMin <= bpmRequestedMax;
    }

}
