package com.osundosun.mupid.api.music.mapping.entity;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.music.entity.Music;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "music_moods")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MusicGenre {

    /**
     * 음악, 분위기 복합키
     */
    @EmbeddedId
    private MusicGenreId musicGenreId;

    /**
     * 음악
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_id", nullable = false)
    private Music music;

    /**
     * 분위기
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "genre_code_id", nullable = false)
    private Code genreCode;

    @Embeddable
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @EqualsAndHashCode
    public static class MusicGenreId {

        /**
         * 음악 번호 (조회 전용)
         */
        @Column(name = "music_id", insertable = false, updatable = false)
        private Integer musicId;

        /**
         * 분위기 번호 (조회 전용)
         */
        @Column(name = "genre_code_id", insertable = false, updatable = false)
        private Integer genreCodeId;

    }
    
}
