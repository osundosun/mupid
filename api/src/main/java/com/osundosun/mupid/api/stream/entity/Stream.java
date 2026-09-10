package com.osundosun.mupid.api.stream.entity;

import com.google.auto.value.AutoValue.Builder;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.stream.enumerate.Codec;
import com.osundosun.mupid.api.stream.enumerate.StreamStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name="streams")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stream {
    
    /**
     * 스트림 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer streamId;

    /**
     * 음악
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(nullable = false)
    private Music music;

    /**
     * 비트레이트
     */
    @Column(nullable = false)
    private Integer bitrateKbps;

    /**
     * 코덱 종류
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Codec codec;

    /**
     * 재생 길이
     */
    @Column(nullable = false)
    private Integer durationMs;

    /**
     * 청크 개수
     */
    @Column(nullable = false)
    private Integer chunkCount;

    /**
     * 인코딩 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StreamStatus status;

}
