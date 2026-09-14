package com.osundosun.mupid.api.music.entity;

import com.osundosun.mupid.api.music.enumerate.VocalGender;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "music_analysis")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MusicAnalysis {

    /**
     * 음악 번호 (1:1 관계)
     */
    @Id
    @Column(name = "music_id")
    private Integer musicId;

    /**
     * 음악
     */
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_id", nullable = false)
    private Music music;

    /**
     * 보컬 유무
     */
    @Column(nullable = false)
    private Boolean hasVocal;

    /**
     * 보컬 성별
     */
    @Enumerated(EnumType.STRING)
    @Column
    private VocalGender vocalGender;
    
}
