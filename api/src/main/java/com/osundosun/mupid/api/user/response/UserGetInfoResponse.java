package com.osundosun.mupid.api.user.response;

import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

public record UserGetInfoResponse(

    Integer userId,

    String email,

    String nickname,

    String imageUrl,

    Integer birthYear,

    UserGender gender,

    UserRole role,

    UserStatus status

) {
    
}
