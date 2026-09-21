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
     *
     * <p><b>영문 내부 코드다.</b> 이 값이 <b>AI 서버로 넘어간다</b>({@code AiRecommendToolImpl} 가
     * {@code CodeOption::name} 을 보낸다). 무드 17종({@code serene}·{@code melancholic} …)과
     * 장르 45종({@code poprock})이 모두 이 방향이다. 한국어를 여기에 넣으면 같은 무드가
     * 두 번호로 갈라진다 — 운영에서 {@code melancholic} 과 {@code 애상적인} 이 따로
     * 들어가 매핑 테이블마다 다른 번호를 쓰던 것을 2026-09-21 에 합쳤다.
     */
    @Column(nullable = false)
    private String codeName;

    /**
     * 화면에 보여줄 이름.
     *
     * <p>{@code codeName} 은 <b>영문 내부 코드</b>, 이쪽은 <b>사람이 읽는 한국어</b>다
     * (무드 {@code 잔잔한}, 장르 {@code 팝 록}). 장르 대분류 8종처럼 아직 한국어
     * 표시명이 없는 행은 {@code codeName} 과 같은 값으로 채워져 있다.
     *
     * <p>비워 두면 안 된다 —
     * {@code POST /api/v1/code} 가 값이 없을 때 {@code codeName} 으로 채우지만,
     * 손으로 넣은 행이 빈 채로 남으면 화면이 빈 칸을 그린다.
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
