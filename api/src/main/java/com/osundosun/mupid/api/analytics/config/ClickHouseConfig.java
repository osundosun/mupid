package com.osundosun.mupid.api.analytics.config;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import com.osundosun.mupid.api.analytics.properties.ClickHouseProperties;
import com.zaxxer.hikari.HikariDataSource;

import lombok.RequiredArgsConstructor;

/**
 * ClickHouse 는 JPA 로 다루지 않는다. 엔티티/트랜잭션 모델이 맞지 않고 쓰는 쿼리도
 * 집계와 대량 INSERT 뿐이라 JdbcTemplate 으로 충분하다.
 *
 * 커넥션 풀을 일부러 빈으로 등록하지 않는다. HikariDataSource 를 @Bean 으로 노출하면
 * DataSource 타입 빈이 두 개가 되면서 두 가지가 따라온다.
 *
 *   1. DataSourceAutoConfiguration 이 @ConditionalOnMissingBean(DataSource.class) 로
 *      물러나서, JPA/Batch 가 쓰는 MySQL DataSource 를 우리가 직접 재현해야 한다.
 *   2. DataSource 빈마다 헬스 컨트리뷰터가 자동으로 붙어 /api/actuator/health 가
 *      ClickHouse 상태까지 집계한다. 그러면 분석 DB 가 죽었을 때 API 컨테이너가
 *      unhealthy 로 바뀌고, nginx 의 depends_on: service_healthy 가 걸려 서비스 전체가
 *      내려간다. 인증/회원 같은 핵심 기능은 ClickHouse 와 무관하므로 과한 결합이다.
 *
 * 풀은 이 설정 클래스가 직접 들고 있다가 DisposableBean 으로 닫는다. ClickHouse 상태를
 * 따로 노출하고 싶으면 별도 헬스 그룹으로 분리해 컨테이너 헬스체크와 떼어놓을 것.
 */
@Configuration
@RequiredArgsConstructor
public class ClickHouseConfig implements DisposableBean {

    private static final String DRIVER_CLASS_NAME = "com.clickhouse.jdbc.ClickHouseDriver";

    private final ClickHouseProperties props;

    private HikariDataSource dataSource;

    @Bean
    public JdbcTemplate clickHouseJdbcTemplate() {
        this.dataSource = createDataSource();
        return new JdbcTemplate(this.dataSource);
    }

    @Override
    public void destroy() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    private HikariDataSource createDataSource() {
        // HikariConfig 를 넘기는 생성자는 풀을 즉시 띄우기 때문에 ClickHouse 가 떠 있지
        // 않으면 애플리케이션 기동 자체가 실패한다. 기본 생성자 + setter 로 만들면 첫
        // getConnection() 때 풀이 초기화되므로, 분석 DB 가 죽어도 API 는 뜬다.
        HikariDataSource created = new HikariDataSource();
        created.setPoolName("clickhouse-pool");
        created.setDriverClassName(DRIVER_CLASS_NAME);
        created.setJdbcUrl(props.url());
        created.setUsername(props.username());
        created.setPassword(props.password());
        created.setMaximumPoolSize(props.maxPoolSize());
        created.setMinimumIdle(props.minIdle());
        created.setConnectionTimeout(props.connectionTimeout().toMillis());
        created.setValidationTimeout(props.validationTimeout().toMillis());
        // ClickHouse 에는 우리가 쓰는 형태의 트랜잭션이 없다. 커밋을 미루지 않는다.
        created.setAutoCommit(true);
        return created;
    }

}
