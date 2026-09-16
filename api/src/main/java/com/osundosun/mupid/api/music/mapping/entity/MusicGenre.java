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
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "music_genres")
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MusicGenre {

    /**
     * 음악, 장르 복합키
     */
    @EmbeddedId
    private MusicGenreId musicGenreId;

    /**
     * 음악
     *
     * <p>`@MapsId` 로 복합키의 한쪽을 이 관계에서 채운다. 예전에는 관계와
     * `@EmbeddedId` 가 같은 컬럼을 각자 매핑해, <b>읽기는 되는데 INSERT 가 컬럼을
     * 두 번 실어</b> 터졌다. 이 표에 쓴 적이 없어 드러나지 않았다 —
     * `MusicSectionAnalysisMood` 와 `DailyRecommendationTrack` 도 같은 모양이었고
     * 먼저 고쳤다.
     */
    @MapsId("musicId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_id", nullable = false)
    private Music music;

    /**
     * 장르
     */
    @MapsId("genreCodeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "genre_code_id", nullable = false)
    private Code genreCode;

    @Embeddable
    @Getter
    @AllArgsConstructor
    // `@MapsId` 가 값을 채우므로 바깥에서 빈 키를 만들 수 있어야 한다.
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class MusicGenreId {

        /**
         * 음악 번호.
         *
         * <p>`@MapsId` 가 관계에서 채운다. `insertable = false` 를 두면 안 된다 —
         * 그러면 INSERT 에서 값이 빠진다.
         */
        @Column(name = "music_id")
        private Integer musicId;

        /**
         * 장르 번호. `@MapsId` 가 관계에서 채운다.
         */
        @Column(name = "genre_code_id")
        private Integer genreCodeId;

    }

}
