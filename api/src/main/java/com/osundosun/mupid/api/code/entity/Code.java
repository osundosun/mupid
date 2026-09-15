package com.osundosun.mupid.api.code.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.code.enumerate.CodeType;

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
@Table(name = "codes")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Code {

    /**
     * 코드 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer codeId;

    /**
     * 코드 용도
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CodeType codeType;

    /**
     * 코드 이름
     */
    @Column(nullable = false)
    private String codeName;

    /**
     * 생성 일시
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
}
