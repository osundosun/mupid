package com.osundosun.mupid.api.stream.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor 
public enum StreamStatus {
    
    PENDING("인코딩 대기중"),
    IN_PROGRESS("인코딩 진행중"),
    DONE("인코딩 완료"),
    FAILED("인코딩 실패");

    private final String displayName;

}
