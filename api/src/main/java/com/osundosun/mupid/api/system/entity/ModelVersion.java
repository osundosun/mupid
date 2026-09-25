package com.osundosun.mupid.api.system.entity;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.osundosun.mupid.api.system.enumerate.ModelVersionStatus;

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

/**
 * 모델 버전 하나 (저장소 재설계 §6.3 `model_versions`). 이름·버전이 유일하다.
 *
 * <p><b>파일 목록을 첫 요청이 정한다.</b> 버전에 무엇이 들어가는지 서버가 알아야 "다
 * 모였다" 를 판단할 수 있는데, 그 목록의 정본은 CI 가 들고 있는 {@code models.lock} 의
 * {@code files} 다. 그래서 업로드 요청마다 같은 목록을 싣게 하고, 처음 온 것을 여기에
 * 박아 둔다. 뒤에 다른 목록이 오면 거절한다 — 같은 버전 번호로 다른 묶음을 만들 수 없다는
 * 불변식의 일부다.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "model_versions")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModelVersion {

    /** 목록을 한 칸에 담을 때 쓰는 구분자. 파일명 규칙이 쉼표를 허용하지 않는다 */
    public static final String FILE_NAME_DELIMITER = ",";

    /**
     * 모델 버전 번호
     */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer modelVersionId;

    /**
     * 모델 이름 (예: stage1)
     */
    @Column(nullable = false, length = 32)
    private String name;

    /**
     * 버전 (예: v3)
     */
    @Column(nullable = false, length = 16)
    private String version;

    /**
     * 상태
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ModelVersionStatus status;

    /**
     * 이 버전에 들어가는 파일명 — 정렬해 쉼표로 이었다
     */
    @Column(nullable = false, length = 2048)
    private String fileNames;

    /**
     * manifest.json 의 sha256. 후보가 될 때 채운다
     */
    @Column(length = 64)
    private String manifestSha256;

    /**
     * 후보가 된 시각
     */
    private LocalDateTime candidateAt;

    /**
     * 마지막으로 채택된 시각. 되돌리기로 다시 채택하면 갱신된다
     */
    private LocalDateTime adoptedAt;

    /**
     * 폐기된 시각
     */
    private LocalDateTime retiredAt;

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

    /** 목록을 저장할 모양으로 — 중복을 없애고 정렬해 잇는다. 순서가 달라도 같은 목록이다 */
    public static String joinFileNames(Collection<String> names) {
        return String.join(FILE_NAME_DELIMITER, names.stream().distinct().sorted().toList());
    }

    public List<String> declaredFileNames() {
        return Arrays.asList(fileNames.split(FILE_NAME_DELIMITER));
    }

}
