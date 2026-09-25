package com.osundosun.mupid.api.auth.service;

import java.util.List;

import com.osundosun.mupid.api.auth.dto.AuthLoginResult;
import com.osundosun.mupid.api.auth.dto.TokenPair;

public interface AuthService {
    
    default AuthLoginResult login(String email, String password) {
        return login(email, password, null);
    }

    /**
     * @param refreshTokenValidityDays 리프레시 토큰 만료(일). ROLE_SYSTEM 만, 180일까지. null 이면 기본값
     */
    AuthLoginResult login(String email, String password, Integer refreshTokenValidityDays);

    TokenPair reissueToken(String refreshToken);

    void logout(List<String> tokens);

}
