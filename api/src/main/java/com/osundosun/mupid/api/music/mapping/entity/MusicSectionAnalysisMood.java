package com.osundosun.mupid.api.music.mapping.entity;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
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
     *
     * <p>`@MapsId` 로 복합키의 한쪽을 이 관계에서 채운다. 예전에는 관계와 `@EmbeddedId`
     * 가 같은 컬럼을 각자 매핑해, **읽기는 되는데 INSERT 가 컬럼을 두 번 실어** 터졌다
     * (`No value specified for parameter 3`). 이 표에 쓴 적이 없어 드러나지 않았다.
     */
    @MapsId("musicSectionAnalysisId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_section_analysis_id", nullable = false)
    private MusicSectionAnalysis musicSectionAnalysis;

    /**
     * 분위기
     */
    @MapsId("moodCodeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mood_code_id", nullable = false)
    private Code moodCodeId;

    @Embeddable
    @Getter
    @AllArgsConstructor
    // `@MapsId` 가 값을 채우므로 바깥에서 빈 키를 만들 수 있어야 한다.
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class MusicSectionAnalysisMoodId {

        /**
         * 음악 구간 분석 번호.
         *
         * <p>`@MapsId` 가 관계에서 채운다. `insertable = false` 를 두면 안 된다 —
         * 그러면 INSERT 에서 값이 빠진다.
         */
        @Column(name = "music_section_analysis_id")
        private Integer musicSectionAnalysisId;

        /**
         * 분위기 번호.
         */
        @Column(name = "mood_code_id")
        private Integer moodCodeId;

    }
    
}
