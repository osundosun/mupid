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
     * <p>이 값이 <b>AI 서버로 넘어간다</b>({@code AiRecommendToolImpl} 가
     * {@code CodeOption::name} 을 보낸다). 그래서 색인 어휘와 맞아야 하는 값은
     * 여기다 — 분위기 taxonomy 처럼 색인 태그가 원문인 경우
     * {@code displayName} 쪽에 원문을 둔다.
     */
    @Column(nullable = false)
    private String codeName;

    /**
     * 화면에 보여줄 이름.
     *
     * <p>{@code codeName} 은 <b>AI 색인과 맞추는 값</b>, 이쪽은 <b>사람이 읽는 값</b>이다.
     * 장르 45종은 반대다 — {@code codeName} 이 색인 원문({@code poprock})이고
     * 이 컬럼이 화면용({@code 팝 록})이다. <b>어느 쪽이 색인 값인지는 종류마다 다르므로</b>
     * 코드를 옮길 때 여기를 먼저 확인한다.
     *
     * <p>기존 16행은 {@code codeName} 과 같은 값으로 채워져 있다. 비워 두면 안 된다 —
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
