package com.osundosun.mupid.api.history.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.common.enumerate.Selector;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlayHistory {

    /**
     * 재생 기록 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer playHistoryId;

    /**
     * 유저
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 음악
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_id", nullable = false)
    private Music music;

    /**
     * 음악 선곡 주체
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Selector selector;

    /**
     * 추천 사유
     */
    @Column
    private String recommendationReason;

    /**
     * 재생 일시
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
}
