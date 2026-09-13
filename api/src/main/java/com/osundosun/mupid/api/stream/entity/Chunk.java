package com.osundosun.mupid.api.stream.entity;

import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
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
     */
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
         * 스트림 번호 (조회 전용)
         */
        @Column(name = "stream_id", insertable = false, updatable = false)
        private Integer streamId;

        /**
         * 청크 순서
         */
        @Column(nullable = false)
        private Integer index;

    }
    
}
