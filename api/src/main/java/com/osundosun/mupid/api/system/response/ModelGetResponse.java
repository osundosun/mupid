package com.osundosun.mupid.api.system.response;

import java.time.LocalDateTime;
import java.util.List;

import com.osundosun.mupid.api.system.enumerate.ModelVersionStatus;
import com.osundosun.mupid.api.system.enumerate.SystemObjectStatus;

/**
 * 모델 하나의 버전 목록.
 *
 * <p>{@code currentVersion} 은 {@code ADOPTED} 중 <b>가장 늦게 채택된</b> 버전이다. 채택해도
 * 이전 채택 버전은 그대로 두므로(폐기는 사람이 한다) {@code ADOPTED} 가 여럿일 수 있다.
 */
public record ModelGetResponse(

    /** 모델 이름 */
    String name,

    /** 지금 쓰는 버전. 채택된 것이 없으면 null */
    String currentVersion,

    /** 버전 목록 (최근에 만든 것부터) */
    List<VersionItem> versions

) {

    public record VersionItem(

        /** 버전 */
        String version,

        /** 상태 */
        ModelVersionStatus status,

        /** manifest.json 의 sha256. 후보가 되기 전엔 null */
        String manifestSha256,

        /** 선언된 파일 수 */
        int declaredFileCount,

        /** 요청된 파일 (선언됐지만 아직 요청이 없는 파일은 빠진다) */
        List<FileItem> files,

        LocalDateTime createdAt,

        LocalDateTime candidateAt,

        LocalDateTime adoptedAt,

        LocalDateTime retiredAt

    ) {

    }

    public record FileItem(

        /** 오브젝트 번호 — 받기 {@code GET /api/v1/system/objects/{objectId}} */
        int objectId,

        /** 파일명 */
        String fileName,

        /** 크기 (bytes) */
        long sizeBytes,

        /** sha256 */
        String sha256,

        /** 상태 */
        SystemObjectStatus status

    ) {

    }

}
