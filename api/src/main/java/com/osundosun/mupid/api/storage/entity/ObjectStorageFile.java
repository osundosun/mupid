package com.osundosun.mupid.api.storage.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "object_storage_files")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ObjectStorageFile {

    /**
     * 파일 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer fileId;

    /**
     * 파일 유형
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileType fileType;

    /**
     * 사용 용도
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private FileCategory category;

    /**
     * 원 파일 명
     */
    @Column(nullable = false)
    private String originalFileName;

    /**
     * 저장된 파일 명
     */
    @Column(nullable = false)
    private String storageObjectKey;

    /**
     * 파일 크기
     */
    @Column(nullable = false)
    private Integer fileSizeBytes;

    /**
     * MIME 타입
     */
    @Column(nullable = false)
    private String mime;

    /**
     * 삭제 여부
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    /**
     * 생성 일시
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /**
     * 수정 일시
     */
    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;

}
