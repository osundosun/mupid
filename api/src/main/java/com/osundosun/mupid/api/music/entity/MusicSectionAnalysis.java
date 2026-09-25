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

    /** 기존 행이 전부 이 버전으로 적재됐다 (2026-09-25 대조). DDL 의 DEFAULT 와 같다. */
    public static final String DEFAULT_ANALYSIS_VERSION = "v1";

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
     *
     * `index` 는 MySQL 예약어라 백틱이 없으면 INSERT·SELECT 가 문법 오류로
     * 터진다. `Chunk` 가 이미 같은 것을 밟았다.
     */
    @Column(name = "`index`", nullable = false)
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

    /**
     * 구간 에너지 — 오디오 RMS(0~1). 분석 파이프라인이 잰다. 모르면 비운다.
     *
     * 추천이 이어 듣기 흐름 감점·"더 차분하게" 조건·학습 특징(`rms_pct`)에 쓴다.
     * 예전에는 AI 색인에만 있어서 구간 경계를 고쳐도 옛 값이 남았다
     * (연동 계약 2026-09-23 절).
     */
    @Column
    private Double energy;

    /**
     * 구간 분석 버전 (`v1`). 구간 교체 요청이 싣는 값이다.
     *
     * 예전에는 ClickHouse `track_sections` 에만 남았다. 재시도·대조 작업이 MySQL 만 보고
     * ClickHouse 에 **같은 값**을 쓰려면 여기 있어야 한다 (S15P21A204-451). 딕셔너리가 보는
     * 버전과 다르면 ClickHouse 에 들어가도 구간 판정에 쓰이지 않는다.
     *
     * 필드 초기값을 두는 것은 관리자 화면처럼 기본 생성자로 만드는 길에서도 NOT NULL 이
     * 지켜지게 하려는 것이다. DB 기본값은 하이버네이트가 null 을 명시해 넣으면 안 먹는다.
     */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String analysisVersion = DEFAULT_ANALYSIS_VERSION;

}
