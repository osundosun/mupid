package com.osundosun.mupid.api.auth.response;

public record AuthLoginResponse(

    String accessToken,

    int userId,

    String userRole

) {
    
}
