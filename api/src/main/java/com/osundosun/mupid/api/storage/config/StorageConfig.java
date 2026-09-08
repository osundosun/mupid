package com.osundosun.mupid.api.storage.config;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.osundosun.mupid.api.storage.properties.StorageProperties;

import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;

@Configuration
@RequiredArgsConstructor
public class StorageConfig {

    private final StorageProperties props;

    @Bean
    public S3Client s3Client() {
        AwsBasicCredentials credential = AwsBasicCredentials.create(props.accessKey(), props.secretKey());
        S3ClientBuilder builder = S3Client.builder()
            .region(Region.of(props.region()))
            .credentialsProvider(
                StaticCredentialsProvider.create(credential)
            );
        if (props.endpoint() != null &&  !props.endpoint().isBlank()) {
            builder
                .endpointOverride(URI.create(props.endpoint()))
                .serviceConfiguration(
                    S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build()
                );
        }
        return builder.build();
    }
    
}
