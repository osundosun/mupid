package com.osundosun.mupid.api.recommendation.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.music.entity.Music;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "daily_recommendation_tracks")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyRecommendationTrack {

    /**
     * 모먼트 번호, 추천 순위 복합키
     */
    @EmbeddedId
    private DailyRecommendationTrackId dailyRecommendationTrackId;

    /**
     * 모먼트
     */
    /**
     * 속한 묶음.
     *
     * <p>`@MapsId` 로 복합키의 한쪽을 이 관계에서 채운다. 예전에는 관계와
     * `@EmbeddedId` 가 같은 컬럼을 각자 매핑해 **읽기는 되는데 INSERT 가 컬럼을 두 번
     * 실어** 터졌다(`No value specified for parameter 5`). 이 표에 쓴 적이 없어
     * 드러나지 않았다 — `MusicSectionAnalysisMood` 도 같은 모양이었다.
     */
    @MapsId("dailyRecommendationId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "daily_recommendation_id", nullable = false)
    private DailyRecommendation dailyRecommendation;

    /**
     * 음악
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_id", nullable = false)
    private Music music;

    /**
     * 곡별 추천 사유
     */
    @Column(nullable = false)
    private String reason;

    /**
     * 생성 일시
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Integer getRankOrder() {
        return dailyRecommendationTrackId == null ? null : dailyRecommendationTrackId.getRankOrder();
    }

    @Embeddable
    @Getter
    @AllArgsConstructor
    // `@MapsId` 가 묶음 번호를 채우므로 바깥에서 순번만 담은 키를 만든다.
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class DailyRecommendationTrackId {

        /**
         * 모먼트 번호 (조회 전용)
         */
        /**
         * 묶음 번호. `@MapsId` 가 관계에서 채운다.
         *
         * <p>`insertable = false` 를 두면 안 된다 — INSERT 에서 값이 빠진다.
         */
        @Column(name = "daily_recommendation_id")
        private Integer dailyRecommendationId;

        /**
         * 추천 순위
         */
        @Column(nullable = false)
        private Integer rankOrder;

    }
    
}
