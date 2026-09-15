package com.osundosun.mupid.api.auth.service;

import java.util.List;

import com.osundosun.mupid.api.auth.dto.AuthLoginResult;
import com.osundosun.mupid.api.auth.dto.TokenPair;

public interface AuthService {
    
    AuthLoginResult login(String email, String password);

    TokenPair reissueToken(String refreshToken);

    void logout(List<String> tokens);

}
