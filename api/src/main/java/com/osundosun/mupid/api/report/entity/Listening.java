package com.osundosun.mupid.api.report.entity;

import java.io.Serializable;

import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.music.entity.Music;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "listening")
@Getter
@Setter
@Builder
@AllArgsConstructor
public class Listening {

    /**
     * 리포트 번호, 음악 번호 복합키
     */
    @EmbeddedId
    private ListeningId listeningId;

     /**
     * 리포트
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    /**
     * 음악
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_id", nullable = false)
    private Music music;

    /**
     * 청취 시간
     */
    @Column(nullable = false)
    private Integer listeningMs;

    @Embeddable
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @EqualsAndHashCode
    public static class ListeningId implements Serializable {

        /**
         * 리포트 번호 (조회 전용)
         */
        @Column(name = "report_id", insertable = false, updatable = false)
        private Integer reportId;

        /**
         * 음악 번호 (조회 전용)
         */
        @Column(name = "music_id", insertable = false, updatable = false)
        private Integer musicId;

    }
    
}
