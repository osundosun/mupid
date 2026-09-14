package com.osundosun.mupid.api.playlist.entity;

import java.io.Serializable;
import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.common.enumerate.Selector;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "playlist_tracks") 
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlaylistTrack {

    /**
     * 플레이리스트 번호, 트랙 순서 복합키
     */
    @EmbeddedId
    private PlaylistTrackId playlistTrackId;

    /**
     * 플레이리스트
     *
     * `@MapsId` 로 복합키의 playlistId 를 이 연관에서 가져온다. 이게 없으면 Hibernate 가
     * INSERT 에 playlist_id 를 바인딩하지 못해 "No value specified for parameter" 로 깨진다
     * (chunks 에서 이미 한 번 밟았다).
     */
    @MapsId("playlistId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "playlist_id", nullable = false)
    private Playlist playlist;

    /**
     * 음악
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_id", nullable = false)
    private Music music;

    /**
     * 선곡 주체
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Selector selector;

    /**
     * 추가한 유저
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * 추가 일시
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Integer getTrackOrder() {
        return playlistTrackId == null ? null : playlistTrackId.getTrackOrder();
    }

    @Embeddable
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @EqualsAndHashCode
    public static class PlaylistTrackId implements Serializable {

        /**
         * 플레이리스트 번호
         */
        @Column(name = "playlist_id")
        private Integer playlistId;

        /**
         * 트랙 순서
         */
        @Column(nullable = false)
        private Integer trackOrder;

    }
    
}
