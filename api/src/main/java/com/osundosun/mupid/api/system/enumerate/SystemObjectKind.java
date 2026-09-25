package com.osundosun.mupid.api.system.enumerate;

import java.util.Optional;
import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 배치용 Object Storage API 로 오가는 파일의 종류.
 *
 * <p>지금은 {@link #MODEL} 하나다. 이 길(요청 → Nginx 직행 업로드 → 완료)은 <b>버전별로
 * 보관되고 CI 가 받아 가는 파일</b>을 위한 것이라, 종류를 늘리는 것은 그런 파일이 새로
 * 생길 때뿐이다. 벡터는 파일이 아니라 데이터라 이 길로 오지 않는다(저장소 재설계 §6.6).
 */
@Getter
@RequiredArgsConstructor
public enum SystemObjectKind {

    /**
     * 모델 묶음 파일 (저장소 재설계 §6.3). 확장자는 묶음에 실제로 들어가는 것만 연다.
     *
     * <p>{@code .pt}(학습 원본)는 일부러 뺐다. 설계가 "Object Storage 에 싣지 않는다"
     * 고 정한 파일이라, 실수로 올라오면 여기서 막는다.
     */
    MODEL("model", Set.of("npz", "npy", "json", "onnx"));

    /** 저장 키의 두 번째 마디 — {@code system/{prefix}/…} */
    private final String prefix;

    private final Set<String> allowedExtensions;

    /** 대소문자를 가리지 않고 찾는다. 모르는 값이면 빈 값이다. */
    public static Optional<SystemObjectKind> find(String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (SystemObjectKind kind : values()) {
            if (kind.name().equalsIgnoreCase(value.trim())) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

}
