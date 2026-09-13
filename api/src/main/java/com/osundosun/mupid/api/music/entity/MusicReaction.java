package com.osundosun.mupid.api.music.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.music.enumerate.ReactionType;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "music_reactions")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MusicReaction {

    /**
     * 유저 번호, 음악 번호 복합키
     */
    @EmbeddedId
    private MusicReactionId musicReactionId;

    /**
     * 반응 유형
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReactionType reactionType;

    /**
     * 생성 일시
     */
    @CreatedDate
    @Column(nullable = false)
    private LocalDateTime createdAt;

    /**
     * 수정 일시
     */
    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

    @Embeddable
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @EqualsAndHashCode
    public static class MusicReactionId {
        
        /**
         * 유저 번호 (조회 전용)
         */
        @Column(name = "user_id", insertable = false, updatable = false)
        private Integer userId;

        /**
         * 음악 번호 (조회 전용)
         */
        @Column(name = "music_id", insertable = false, updatable = false)
        private Integer musicId;

    }
    
}
