package com.osundosun.mupid.api.playlist.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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

/**
 * 플레이리스트
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "playlists")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Playlist {

    /**
     * 플레이리스트 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer playlistId;

    /**
     * 소유 유저 번호 (null 이면 시스템/추천 생성)
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "user_id", nullable = true, updatable = false)
    private User user;

    /**
     * 플레이리스트 제목
     */
    @Column(nullable = false)
    private String title;

    /**
     * 수록곡 수 (역정규화)
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer musicCount = 0;

    /**
     * 삭제 일시 (소프트 삭제)
     */
    @Column(nullable = true)
    private LocalDateTime deletedAt;

    /**
     * 생성 일시
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 수정 일시
     */
    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

}
