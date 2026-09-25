package com.osundosun.mupid.api.system.response;

import java.time.LocalDateTime;

import com.osundosun.mupid.api.system.enumerate.ModelVersionStatus;

/**
 * 채택·폐기의 응답.
 */
public record ModelVersionStatusResponse(

    /** 모델 이름 */
    String name,

    /** 버전 */
    String version,

    /** 바뀐 뒤 상태 */
    ModelVersionStatus status,

    /** 이 모델에서 지금 쓰는 버전 */
    String currentVersion,

    LocalDateTime adoptedAt,

    LocalDateTime retiredAt

) {

}
