package com.osundosun.mupid.api.stream.response;

public record ChunkUploadResponse(

    int streamId,

    int index,

    /**
     * 스토리지에 저장된 파일 번호
     */
    int fileId,

    /**
     * 서버가 수신한 바이트로 직접 계산한 SHA-256
     */
    String fileHash

) {
    
}
