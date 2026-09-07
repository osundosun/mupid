package com.osundosun.mupid.api.storage.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="aws.s3")
public record StorageProperties(

    String endpoint,

    String region,

    String accessKey,

    String secretKey,

    String bucketName

) {

}
