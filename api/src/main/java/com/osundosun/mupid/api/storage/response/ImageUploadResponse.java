package com.osundosun.mupid.api.storage.response;

public record ImageUploadResponse(

    /**
     * 업로드된 파일 번호. 아티스트·앨범 등록의 image_file_id 로 쓴다.
     */
    int fileId

) {
    
}
