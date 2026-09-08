package com.osundosun.mupid.api.common.gmail;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gmail")
public record GmailProperties(
    
    String clientId,

    String clientSecret,

    String token,

    String sendAddress

) {

}
