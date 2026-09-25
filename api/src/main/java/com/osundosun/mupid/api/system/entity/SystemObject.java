package com.osundosun.mupid.api.system.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.system.enumerate.SystemObjectKind;
import com.osundosun.mupid.api.system.enumerate.SystemObjectStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 배치용 Object Storage API 로 올리는 파일 하나 (저장소 재설계 §6.5).
 *
 * <p>{@code object_storage_files} 와 따로 두는 이유는 <b>완료 전 상태</b>가 있어서다. 그 표는
 * "스토리지에 있는 파일" 의 메타이고 상태 칸이 없다. 여기서는 ① 요청 때 자리를 잡고
 * (신고한 크기·sha256), ③ 완료 때 서버가 직접 확인한 뒤에야 {@code object_storage_files}
 * 행을 만들어 {@link #file} 로 잇는다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "system_objects")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SystemObject {

    /**
     * 오브젝트 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer objectId;

    /**
     * 종류
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SystemObjectKind kind;

    /**
     * 이 파일이 속한 모델 버전 ({@code MODEL} 이면 반드시 있다)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "model_version_id")
    private ModelVersion modelVersion;

    /**
     * 파일명 (예: user_encoder.npz)
     */
    @Column(nullable = false, length = 128)
    private String fileName;

    /**
     * 저장 키 — system/model/{name}/{version}/{file}
     */
    @Column(nullable = false)
    private String storageObjectKey;

    /**
     * 신고한 크기 (bytes)
     */
    @Column(nullable = false)
    private Long sizeBytes;

    /**
     * 신고한 sha256 (소문자 hex). 완료 때 서버가 잰 값과 같아야 한다
     */
    @Column(nullable = false, length = 64)
    private String sha256;

    /**
     * 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SystemObjectStatus status;

    /**
     * 완료 뒤 만들어지는 스토리지 파일 메타
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "file_id")
    private ObjectStorageFile file;

    /**
     * 완료된 시각
     */
    private LocalDateTime completedAt;

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
