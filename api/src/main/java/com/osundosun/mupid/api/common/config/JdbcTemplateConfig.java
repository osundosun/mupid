package com.osundosun.mupid.api.common.config;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MySQL 용 JdbcTemplate 을 명시적으로 되살린다.
 *
 * analytics/config/ClickHouseConfig 가 clickHouseJdbcTemplate 을 등록하는 순간
 * JdbcTemplateAutoConfiguration 이 @ConditionalOnMissingBean(JdbcOperations.class)
 * 조건에 걸려 물러나면서, 원래 자동 설정되던 MySQL JdbcTemplate 이 사라진다.
 * 여기서 다시 정의하고 @Primary 를 붙여 기존 동작을 유지한다.
 *
 * DataSource 는 건드리지 않는다. ClickHouse 풀을 빈으로 올리지 않았기 때문에
 * DataSource 타입 빈은 MySQL 하나뿐이고, DataSourceAutoConfiguration 이 그대로 돈다.
 */
@Configuration
public class JdbcTemplateConfig {

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

}
