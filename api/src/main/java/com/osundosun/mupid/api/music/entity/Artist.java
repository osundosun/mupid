package com.osundosun.mupid.api.music.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
@EntityListeners(AuditingEntityListener.class)
@Table(name = "artists")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Artist {

    /**
     * 아티스트 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer artistId;

    /**
     * 아티스트명
     */
    @Column(nullable = false)
    private String name;

    /**
     * 이미지 파일
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "file_id")
    private ObjectStorageFile imageFile;

    /**
     * 삭제 여부
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isDeleted = false;

    /**
     * 생성 일시
     */
    @Column(nullable = false)
    @CreatedDate
    private LocalDateTime createdAt;

    /**
     * 수정 일시
     */
    @Column(nullable = false)
    @LastModifiedDate
    private LocalDateTime updatedAt;
    
}
