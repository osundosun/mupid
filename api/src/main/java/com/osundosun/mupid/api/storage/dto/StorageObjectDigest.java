package com.osundosun.mupid.api.storage.dto;

/**
 * 스토리지에 있는 오브젝트를 끝까지 읽어 잰 값.
 *
 * <p>{@link StorageObjectStat} 은 HEAD 라 스토리지가 말해 주는 크기만 안다. 이쪽은 본문을
 * <b>흘려 읽으며</b> 직접 센 바이트 수와 SHA-256 이다 — 올린 쪽이 신고한 해시를 서버가
 * 대조할 수 있는 유일한 길이다.
 */
public record StorageObjectDigest(

    long sizeBytes,

    /** 소문자 hex 64자 */
    String sha256

) {

}
