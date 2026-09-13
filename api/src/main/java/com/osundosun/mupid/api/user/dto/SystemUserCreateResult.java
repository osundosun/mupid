package com.osundosun.mupid.api.user.dto;

import com.osundosun.mupid.api.user.entity.User;

/**
 * 생성된 배치 계정과, 이 호출에서만 볼 수 있는 평문 비밀번호.
 */
public record SystemUserCreateResult(

    User user,

    String password

) {
    
}
