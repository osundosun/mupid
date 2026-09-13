package com.osundosun.mupid.api.auth.dto;

public record TokenPair(

    String accessToken,

    String refreshToken

) {
    
}
