package com.osundosun.mupid.api.email.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.email.enumerate.EmailVerificationPurpose;
import com.osundosun.mupid.api.user.entity.User;

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

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "email_verifications")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailVerification {
    
    /**
     * 인증 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer verificationId;

    /**
     * 유저 번호
     */
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "user_id", updatable = false)
    private User user;

    /**
     * 인증 대상 이메일
     */
    @Column(nullable = false, updatable = false)
    private String email;

    /**
     * 인증 토큰
     */
    @Column(nullable = false, updatable = false)
    private String token;

    /**
     * 인증 용도
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private EmailVerificationPurpose purpose;

    /**
     * 만료 일시
     */
    @Column(nullable = false)
    private LocalDateTime expiredAt;

    /**
     * 인증 완료 일시
     */
    @Column(nullable = false)
    private LocalDateTime verifiedAt;

    /**
     * 발송 일시
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

}
