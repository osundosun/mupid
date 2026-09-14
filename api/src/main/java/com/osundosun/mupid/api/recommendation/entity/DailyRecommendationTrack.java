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
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @EqualsAndHashCode
    public static class DailyRecommendationTrackId {

        /**
         * 모먼트 번호 (조회 전용)
         */
        @Column(name = "daily_recommendation_id", insertable = false, updatable = false)
        private Integer dailyRecommendationId;

        /**
         * 추천 순위
         */
        @Column(nullable = false)
        private Integer rankOrder;

    }
    
}
