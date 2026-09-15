package com.osundosun.mupid.api.music.mapping.entity;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "music_section_analysis_mood")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MusicSectionAnalysisMood {

    /**
     * 음악 구간 분석 번호, 분위기 번호 복합키
     */
    @EmbeddedId
    private MusicSectionAnalysisMoodId musicSectionAnalysisMoodId;

    /**
     * 음악 구간 분석
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_section_analysis_id", nullable = false)
    private MusicSectionAnalysis musicSectionAnalysis;

    /**
     * 분위기
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mood_code_id", nullable = false)
    private Code moodCodeId;

    @Embeddable
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @EqualsAndHashCode
    public static class MusicSectionAnalysisMoodId {

        /**
         * 음악 구간 분석 번호 (조회 전용)
         */
        @Column(name = "music_section_analysis_id", insertable = false, updatable = false)
        private Integer musicSectionAnalysisId;

        /**
         * 분위기 번호 (조회 전용)
         */
        @Column(name = "mood_code_id", insertable = false, updatable = false)
        private Integer moodCodeId;

    }
    
}
