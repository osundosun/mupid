package com.osundosun.mupid.api.music.entity;

import com.osundosun.mupid.api.music.enumerate.VocalGender;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "music_analysis")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MusicAnalysis {

    /**
     * 음악 번호 (1:1 관계)
     */
    @Id
    @Column(name = "music_id")
    private Integer musicId;

    /**
     * 음악
     */
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_id", nullable = false)
    private Music music;

    /**
     * 보컬 유무
     */
    @Column(nullable = false)
    private Boolean hasVocal;

    /**
     * 보컬 성별
     */
    @Enumerated(EnumType.STRING)
    @Column
    private VocalGender vocalGender;

    /**
     * 측정 BPM. 분석 파이프라인이 오디오에서 잰다. 자기상관 추정이라 옥타브 오류가 있다 —
     * 요청 범위(아래)가 있으면 추천은 그쪽을 먼저 쓴다.
     */
    @Column
    private Double bpm;

    /**
     * 생성 때 요청한 BPM 범위. 생성 음원에만 있다.
     */
    @Column(columnDefinition = "SMALLINT")
    private Integer bpmRequestedMin;

    @Column(columnDefinition = "SMALLINT")
    private Integer bpmRequestedMax;

    /**
     * 가사 언어(`korean`·`english`·`japanese`…). 생성 조건에서 온다. 모르면 비운다.
     */
    @Column(length = 32)
    private String language;

    /**
     * 생성 스타일 원문(`female_korean_ballad`). 추천이 보컬·언어 접두어를 떼어
     * 스타일 조건으로 쓴다.
     */
    @Column(length = 64)
    private String category;

}
