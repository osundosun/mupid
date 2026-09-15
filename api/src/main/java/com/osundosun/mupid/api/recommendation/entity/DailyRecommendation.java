package com.osundosun.mupid.api.recommendation.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
@EntityListeners(AuditingEntityListener.class)
@Table(name = "daily_recommendations")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DailyRecommendation {

    /**
     * 모먼트 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "daily_recommendation_id")
    private Integer recommendationId;

    /**
     * 유저 번호
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 모먼트 제목
     */
    @Column(nullable = false)
    private String title;

    /**
     * 수록곡 개수
     */
    @Column(nullable = false)
    private Integer musicCount;

    /**
     * 추천 사유
     */
    @Column(nullable = false)
    private String reason;

    /**
     * 분위기
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mood_code_id", nullable = false)
    private Code moodCode;

    /**
     * 추천 기준일
     *
     * 컬럼이 `DATE` 라 시각이 없다. `LocalDateTime` 으로 잡으면 쓸 때 잘린다.
     */
    @Column(nullable = false)
    private LocalDate recommendationDate;

    /**
     * 생성 일시
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
}
