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
     * 화면에 내보내는 이름.
     *
     * <p>`code_name` 과 따로 둔다. `code_name` 은 온보딩 설문·추천이 주고받는 식별자라
     * 한 번 정하면 바꾸기 어렵고, 표기만 바꾸고 싶을 때 이 자리를 고친다. 지금은 둘이
     * 같은 값이다 — 갈라질 일이 생기기 전까지는 seed 가 같은 값을 넣는다.
     */
    @Column(nullable = false)
    private String displayName;

    /**
     * 생성 일시
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
}
