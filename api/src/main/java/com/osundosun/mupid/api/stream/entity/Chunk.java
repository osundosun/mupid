package com.osundosun.mupid.api.stream.entity;

import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "stream_chunks")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Chunk {

    /**
     * 청크 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer chunkId;

    /**
     * 스트림
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stream_id", nullable = false)
    private Stream stream;

    /**
     * 청크 순서
     */
    @Column(nullable = false)
    private Integer index;

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
    
}
