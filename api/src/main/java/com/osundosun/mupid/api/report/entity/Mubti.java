package com.osundosun.mupid.api.report.entity;

import com.osundosun.mupid.api.report.enumerate.MubtiClass;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 취향 유형(MUBTI).
 *
 * `classA`~`classD` 는 **컬럼 이름을 반드시 명시해야 한다.** 하이버네이트의
 * 기본 네이밍 전략(`CamelCaseToUnderscoresNamingStrategy`)은 대문자 **앞뒤가 모두
 * 소문자일 때만** 밑줄을 넣는다. `classA` 는 대문자가 맨 끝이라 규칙에 걸리지 않아
 * `class_a` 가 아니라 **`classa`** 가 되고, 조회하는 순간
 * `Unknown column 'classa'` 로 터진다.
 *
 * 필드 이름이 소문자 뒤 대문자 한 글자로 끝나는 다른 컬럼을 추가할 때도 같다.
 */
@Entity
@Table(name = "mubti")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Mubti {

    /**
     * MUBTI 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer mubtiId;

    /**
     * 제목
     */
    @Column(nullable = false)
    private String title;

    /**
     * 부제목
     */
    @Column(nullable = false)
    private String subtitle;

    /**
     * 설명
     */
    @Column(nullable = false)
    private String description;

    /**
     * 분류 1
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "class_a", nullable = false)
    private MubtiClass.A classA;
    
    /**
     * 분류 2
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "class_b", nullable = false)
    private MubtiClass.B classB;
    
    /**
     * 분류 3
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "class_c", nullable = false)
    private MubtiClass.C classC;
    
    /**
     * 분류 4
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "class_d", nullable = false)
    private MubtiClass.D classD;

    /**
     * MUBTI 사진
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_file_id", unique = true)
    private ObjectStorageFile imageFile;
    
}
