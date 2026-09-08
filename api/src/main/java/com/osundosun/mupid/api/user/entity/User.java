package com.osundosun.mupid.api.user.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

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
 * 회원
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "users")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    /**
     * 유저 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer userId;

    /**
     * 이메일
     */
    @Column(nullable = false, updatable = false)
    private String email;

    /**
     * 비밀번호
     */
    @Column(nullable = false)
    private String password;

    /**
     * 닉네임
     */
    @Column(nullable = false)
    private String nickname;

    /**
     * 프로필 이미지 파일 번호
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "image_file_id", nullable = true)
    private ObjectStorageFile imageFile;

    /**
     * 출생 연도
     */
    @Column(nullable = false)
    private Integer birthYear;

    /**
     * 성별
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserGender gender;

    /**
     * 권한
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserRole role;

    /**
     * 유저 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserStatus status;

    /**
     * 온보딩 제출 일시
     */
    @Column(nullable = true)
    private LocalDateTime onboardingSubmittedAt;

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