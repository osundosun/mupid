package com.osundosun.mupid.api.music.entity;


import com.google.auto.value.AutoValue.Builder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "lyrics")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Lyric {
    
    /**
     * 가사 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer lyricId;

    /**
     * 음악
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_id", nullable = false)
    private Music music;

    /**
     * 순서
     */
    @Column(nullable = false)
    private Integer index;

    /**
     * 가사 내용
     */
    @Column(nullable = false)
    private String text;

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

}
