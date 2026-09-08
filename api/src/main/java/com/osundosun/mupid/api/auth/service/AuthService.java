package com.osundosun.mupid.api.auth.service;

import java.util.List;

import com.osundosun.mupid.api.auth.dto.AuthLoginResult;

public interface AuthService {
    
    AuthLoginResult login(String email, String password);

    String reissueAccessToken(String refreshToken);

    void logout(List<String> tokens);

}
