package com.osundosun.mupid.api.music.entity;

import com.osundosun.mupid.api.music.enumerate.SectionLabel;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "music_section_analysis")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MusicSectionAnalysis {

    /**
     * 음악 구간 분석 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer musicSectionAnalysisId;

    /**
     * 음악
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_id", nullable = false)
    private Music music;

    /**
     * 섹션 이름
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SectionLabel sectionLabel;

    /**
     * 섹션 순서
     */
    @Column(nullable = false)
    private Integer index;

    /**
     * 시작 시간
     */
    @Column(nullable = false)
    private Integer startTimeMs;

    /**
     * 종료 시간
     */
    @Column(nullable = false)
    private Integer endTimeMs;

    /**
     * 벡터DB ID
     */
    @Column(nullable = false)
    private String externalId;
    
}
