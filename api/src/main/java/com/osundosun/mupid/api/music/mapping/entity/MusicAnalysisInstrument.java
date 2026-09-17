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
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 구간에 들리는 악기.
 *
 * <p><b>곡이 아니라 구간에 달린다.</b> 외래키가 `music_section_analysis_id` 다 —
 * 같은 곡이라도 인트로에는 피아노만, 후렴에는 드럼·베이스가 들어간다.
 */
@Entity
@Table(name = "music_analysis_instruments")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MusicAnalysisInstrument {

    /**
     * 음악 구간 분석 번호, 악기 번호 복합키
     */
    @EmbeddedId
    private MusicAnalysisInstrumentId musicAnalysisInstrumentId;

    /**
     * 음악 구간 분석
     *
     * <p>`@MapsId` 로 복합키의 한쪽을 이 관계에서 채운다. 예전에는 관계와
     * `@EmbeddedId` 가 같은 컬럼을 각자 매핑해, <b>읽기는 되는데 INSERT 가 컬럼을
     * 두 번 실어</b> 터졌다 (`No value specified for parameter N`). 이 표에 쓴 적이
     * 없어 드러나지 않았다 — `MusicSectionAnalysisMood` · `MusicGenre` · `MusicMood`
     * 도 같은 모양이었고 먼저 고쳤다.
     */
    @MapsId("musicSectionAnalysisId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_section_analysis_id", nullable = false)
    private MusicSectionAnalysis musicSectionAnalysis;

    /**
     * 악기
     */
    @MapsId("instrumentCodeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "instrument_code_id", nullable = false)
    private Code instrumentCode;

    @Embeddable
    @Getter
    @AllArgsConstructor
    // `@MapsId` 가 값을 채우므로 바깥에서 빈 키를 만들 수 있어야 한다.
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class MusicAnalysisInstrumentId {

        /**
         * 음악 구간 분석 번호.
         *
         * <p>`@MapsId` 가 관계에서 채운다. `insertable = false` 를 두면 안 된다 —
         * 그러면 INSERT 에서 값이 빠진다.
         */
        @Column(name = "music_section_analysis_id")
        private Integer musicSectionAnalysisId;

        /**
         * 악기 번호. `@MapsId` 가 관계에서 채운다.
         */
        @Column(name = "instrument_code_id")
        private Integer instrumentCodeId;

    }

}
