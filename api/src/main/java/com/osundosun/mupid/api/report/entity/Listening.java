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
@EntityListeners(AuditingEntityListener.class)
@Table(name = "listening")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Listening {

    /**
     * 리포트 번호, 음악 번호 복합키
     */
    @EmbeddedId
    private ListeningId listeningId;

     /**
     * 리포트
     */
    @MapsId("reportId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "report_id", nullable = false)
    private Report report;

    /**
     * 음악
     */
    @MapsId("musicId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "music_id", nullable = false)
    private Music music;

    /**
     * 청취 시간
     */
    @Column(nullable = false)
    private Integer listeningMs;

    /**
     * 연관관계만 주고 복합키는 `@MapsId` 에 맡긴다.
     *
     * 빌더로 `listeningId` 를 따로 채우면 연관관계가 가리키는 번호와 어긋날 수 있고,
     * 안 채우면 PK 가 빈 채로 INSERT 된다. 한쪽만 쓰는 이 통로를 두어 그 실수를 막는다.
     */
    public static Listening of(Report report, Music music, int listeningMs) {
        return Listening.builder()
            .listeningId(new ListeningId(report.getReportId(), music.getMusicId()))
            .report(report)
            .music(music)
            .listeningMs(listeningMs)
            .build();
    }

    @Embeddable
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @EqualsAndHashCode
    public static class ListeningId implements Serializable {

        /**
         * 리포트 번호
         */
        @Column(name = "report_id")
        private Integer reportId;

        /**
         * 음악 번호
         */
        @Column(name = "music_id")
        private Integer musicId;

    }

}
