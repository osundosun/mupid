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
@Table(name = "music")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Music {
    
    /**
     * 음악 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer musicId;

    /**
     * 앨범
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name="album_id", nullable = false)
    private Album album;

    /**
     * 아티스트
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name="artist_id", nullable = false)
    private Artist artist;

    /**
     * 곡명
     */
    @Column(nullable = false)
    private String title;

    /**
     * 발매일
     */
    @Column(nullable = false)
    private LocalDateTime releaseDate;

    /**
     * 누적 재생 수
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer playCount = 0;

    /**
     * 누적 좋아요 수
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer likeCount = 0;

    /**
     * 곡 커버 이미지 파일. **없을 수 있다.**
     *
     * 앨범·아티스트와 달리 NULL 을 허용한다. 곡마다 표지가 따로 있는 카탈로그
     * (`music_preprocessing` 배치가 올리는 flow_music 데이터)가 있는가 하면,
     * 표지 없이 앨범 커버만 쓰는 곡도 있다. 화면은 이것이 비면 앨범 커버로
     * 내려간다.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_file_id")
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
    @Column(nullable = false, updatable = false)
    @CreatedDate
    private LocalDateTime createdAt;

    /**
     * 수정 일시
     */
    @Column(nullable = false)
    @LastModifiedDate
    private LocalDateTime updatedAt;

}
