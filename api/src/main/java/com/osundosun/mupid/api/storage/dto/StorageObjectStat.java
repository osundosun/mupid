package com.osundosun.mupid.api.storage.dto;

/**
 * 오브젝트 스토리지에 실제로 올라간 파일의 메타 정보.
 * 본문을 받지 않고 HEAD 로만 확인한 값이다.
 */
public record StorageObjectStat(

    long sizeBytes,

    String eTag

) {
    
}
