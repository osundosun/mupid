package com.osundosun.mupid.api.storage.dto;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.MediaType;

public record StorageFileDto(

    String filename,

    MediaType mediaType,

    byte[] data

) {
   
    public String getEncodedFilename() {
        return URLEncoder
                .encode(filename, StandardCharsets.UTF_8)
                .replaceAll("\\+", "%20"); /* 공백 처리 */
    }

}
