package com.osundosun.mupid.api.system.response;

import com.osundosun.mupid.api.system.enumerate.ModelVersionStatus;
import com.osundosun.mupid.api.system.enumerate.SystemObjectStatus;

/**
 * ① 요청·③ 완료의 응답.
 *
 * <p>① 에서 {@code status} 가 이미 {@code COMPLETED} 면 같은 파일이 전에 올라가 확인까지
 * 끝났다는 뜻이다(멱등). 올리는 쪽은 ②·③ 을 건너뛰면 된다.
 */
public record SystemObjectResponse(

    /** 오브젝트 번호 */
    int objectId,

    /** 종류 */
    String kind,

    /** 모델 이름 */
    String name,

    /** 버전 */
    String version,

    /** 파일명 */
    String fileName,

    /** 크기 (bytes) */
    long sizeBytes,

    /** sha256 */
    String sha256,

    /** 오브젝트 상태 */
    SystemObjectStatus status,

    /** ② 업로드를 보낼 주소 ({@code PUT}). 완료된 오브젝트면 null */
    String uploadUrl,

    /** 이 파일이 속한 모델 버전의 상태 */
    ModelVersionStatus modelStatus

) {

}
