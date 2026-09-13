package com.osundosun.mupid.api.stream.entity;

import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;

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
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "chunks")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Chunk {

    /**
     * 스트림 번호, 청크 순서 복합키
     */
    @EmbeddedId
    private ChunkId chunkId;

    /**
     * 스트림
     *
     * `@MapsId` 로 복합키의 streamId 를 이 연관에서 가져온다.
     * 이게 없으면 Hibernate 가 INSERT 에 stream_id 를 두 번 넣어
     * "No value specified for parameter" 로 깨진다.
     */
    @MapsId("streamId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stream_id", nullable = false)
    private Stream stream;

    /**
     * 청크 길이
     */
    @Column(nullable = false)
    private Integer durationMs;

    /**
     * 파일
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id", nullable = false)
    private ObjectStorageFile file;

    /**
     * 파일 해시
     */
    @Column(nullable = false)
    private String fileHash;

    public Integer getIndex() {
        return chunkId == null ? null : chunkId.getIndex();
    }

    @Embeddable
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor(access = AccessLevel.PROTECTED)
    @EqualsAndHashCode
    public static class ChunkId {

        /**
         * 스트림 번호
         */
        @Column(name = "stream_id")
        private Integer streamId;

        /**
         * 청크 순서
         *
         * `index` 는 MySQL 예약어라 INSERT 컬럼 목록에서 그대로 쓰면 문법 오류가 난다.
         * SELECT 는 별칭이 붙어 통과하므로 조회만 해서는 드러나지 않는다.
         */
        @Column(name = "`index`", nullable = false)
        private Integer index;

    }
    
}
