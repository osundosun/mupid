package com.osundosun.mupid.api.analytics.properties;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix="clickhouse")
public record ClickHouseProperties(

    String url,

    String username,

    String password,

    @DefaultValue("8") int maxPoolSize,

    @DefaultValue("1") int minIdle,

    @DefaultValue("3s") Duration connectionTimeout,

    @DefaultValue("3s") Duration validationTimeout

) {

}
