package com.osundosun.mupid.api.system.dto;

/**
 * 받기 응답에 실을 것 — Nginx 가 따라갈 내부 URI 와 내보낼 형식.
 */
public record SystemObjectRedirect(

    /** {@code X-Accel-Redirect} 값. Nginx internal {@code /storage/} 를 거친다 */
    String url,

    String contentType,

    String fileName

) {

}
