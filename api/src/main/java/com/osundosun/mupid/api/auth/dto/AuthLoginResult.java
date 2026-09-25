package com.osundosun.mupid.api.auth.dto;

import java.time.Duration;

import com.osundosun.mupid.api.user.entity.User;

public record AuthLoginResult(

    String accessToken,

    String refreshToken,

    /** 리프레시 토큰의 수명. 쿠키 수명이 이것을 따른다 */
    Duration refreshTokenValidity,

    User user

) {
    
}
