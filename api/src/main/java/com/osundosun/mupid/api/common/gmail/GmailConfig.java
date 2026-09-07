package com.osundosun.mupid.api.common.gmail;

import java.io.IOException;
import java.security.GeneralSecurityException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.gmail.Gmail;
import com.google.auth.http.HttpCredentialsAdapter;
import com.google.auth.oauth2.UserCredentials;

import lombok.RequiredArgsConstructor;

@Configuration 
@RequiredArgsConstructor
public class GmailConfig {

    private final GmailProperties props;
    
    @Bean
    public Gmail gmailClient() throws GeneralSecurityException, IOException {
        UserCredentials credentials = UserCredentials.newBuilder()
            .setClientId(props.clientId())
            .setClientSecret(props.clientSecret())
            .setRefreshToken(props.token())
            .build();
        return new Gmail.Builder(
                    GoogleNetHttpTransport.newTrustedTransport(),
                    GsonFactory.getDefaultInstance(),
                    new HttpCredentialsAdapter(credentials)
                )
                .setApplicationName("Email-Service")
                .build();
    }

}
