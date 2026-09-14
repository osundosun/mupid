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
    @Column(nullable = false)
    private MubtiClass.A classA;
    
    /**
     * 분류 2
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MubtiClass.B classB;
    
    /**
     * 분류 3
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MubtiClass.C classC;
    
    /**
     * 분류 4
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MubtiClass.D classD;

    /**
     * MUBTI 사진
     */
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "image_file_id", unique = true)
    private ObjectStorageFile imageFile;
    
}
