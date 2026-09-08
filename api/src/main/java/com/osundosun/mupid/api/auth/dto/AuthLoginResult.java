package com.osundosun.mupid.api.auth.dto;

import com.osundosun.mupid.api.user.entity.User;

public record AuthLoginResult(

    String accessToken,

    String refreshToken,

    User user

) {
    
}
