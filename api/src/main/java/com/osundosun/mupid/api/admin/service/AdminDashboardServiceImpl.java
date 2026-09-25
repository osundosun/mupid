package com.osundosun.mupid.api.admin.service;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.admin.response.AdminDashboardResponse;
import com.osundosun.mupid.api.admin.response.AdminDependencyStatus;
import com.osundosun.mupid.api.admin.response.AdminResourceSummary;
import com.osundosun.mupid.api.analytics.sync.service.AnalyticsSyncMonitor;
import com.osundosun.mupid.api.event.properties.EventProperties;
import com.osundosun.mupid.api.storage.properties.StorageProperties;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/**
 * 관리자 첫 화면이 쓰는 집계.
 *
 * `/api/actuator/health` 는 `show-details: never` 라 `{"status":"UP"}` 밖에 주지 않는다.
 * 무엇이 죽었는지는 알 수 없다. 여기서는 붙어 있는 곳을 하나씩 직접 찔러 본다.
 *
 * 이 검사를 액추에이터 헬스에 붙이지 않는 것은 의도적이다. 운영 스택에서 nginx 는
 * `api` 컨테이너가 healthy 가 된 뒤에야 뜨고, 헬스가 ClickHouse·Kafka·S3 까지 집계하면
 * 분석 계통 하나가 죽었을 때 서비스 전체가 내려간다
 * (`analytics/config/ClickHouseConfig` 의 주석과 같은 이유다). 관리자가 화면을 열 때만
 * 도는 검사라 그 결합이 생기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminDashboardServiceImpl implements AdminDashboardService {

    /**
     * 외부 저장소를 찔러 볼 때 기다리는 시간. 관리자 화면이 통째로 멈추지 않게 짧게 잡는다.
     */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private final EntityManager entityManager;
    private final AdminResourceService resourceService;
    private final DataSource dataSource;
    private final StringRedisTemplate stringRedisTemplate;
    private final S3Client s3Client;
    private final StorageProperties storageProperties;
    private final EventProperties eventProperties;
    private final AnalyticsSyncMonitor analyticsSyncMonitor;

    @Qualifier("clickHouseJdbcTemplate")
    private final JdbcTemplate clickHouseJdbcTemplate;

    @Override
    public AdminDashboardResponse dashboard() {
        List<AdminResourceSummary> resources = resourceService.resources();
        return new AdminDashboardResponse(resources, runtime(), dependencies(), users());
    }

    // ------------------------------------------------------------------ JVM 상태

    private Map<String, Object> runtime() {
        RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
        Runtime hostRuntime = Runtime.getRuntime();

        Map<String, Object> runtime = new LinkedHashMap<>();
        runtime.put("pid", runtimeBean.getPid());
        runtime.put("java", System.getProperty("java.version"));
        runtime.put("startedAt", Instant.ofEpochMilli(runtimeBean.getStartTime()).toString());
        runtime.put("uptimeSeconds", runtimeBean.getUptime() / 1000);
        runtime.put("processors", hostRuntime.availableProcessors());
        runtime.put("threads", ManagementFactory.getThreadMXBean().getThreadCount());
        runtime.put("heapUsed", memoryBean.getHeapMemoryUsage().getUsed());
        runtime.put("heapMax", memoryBean.getHeapMemoryUsage().getMax());
        runtime.put("nonHeapUsed", memoryBean.getNonHeapMemoryUsage().getUsed());
        return runtime;
    }

    // -------------------------------------------------------------- 외부 저장소 상태

    private List<AdminDependencyStatus> dependencies() {
        List<AdminDependencyStatus> statuses = new ArrayList<>();
        statuses.add(probeMysql());
        statuses.add(probeRedis());
        statuses.add(probeClickHouse());
        // 붙어 있는가가 아니라 **MySQL 과 같은가**다. ClickHouse 가 살아 있어도 구간 사본이
        // 밀리면 재생 신호가 버려진다 — 미반영 나이·마지막 대조 불일치로 판정한다 (451).
        statuses.add(analyticsSyncMonitor.status());
        statuses.add(probeStorage());
        statuses.add(probeKafka());
        return statuses;
    }

    private AdminDependencyStatus probeMysql() {
        long started = System.nanoTime();
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            boolean alive = connection.isValid((int) PROBE_TIMEOUT.toSeconds());
            String target = "%s %s".formatted(metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion());
            return alive
                ? up("MySQL", target, started)
                : new AdminDependencyStatus("MySQL", "DOWN", target, elapsed(started), "커넥션이 유효하지 않습니다.");
        } catch (Exception e) {
            return down("MySQL", "-", started, e);
        }
    }

    private AdminDependencyStatus probeRedis() {
        long started = System.nanoTime();
        try (RedisConnection connection = stringRedisTemplate.getRequiredConnectionFactory().getConnection()) {
            connection.ping();
            return up("Redis", "-", started);
        } catch (Exception e) {
            return down("Redis", "-", started, e);
        }
    }

    private AdminDependencyStatus probeClickHouse() {
        long started = System.nanoTime();
        try {
            clickHouseJdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return up("ClickHouse", "-", started);
        } catch (Exception e) {
            return down("ClickHouse", "-", started, e);
        }
    }

    private AdminDependencyStatus probeStorage() {
        long started = System.nanoTime();
        String target = storageProperties.bucketName();
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(target).build());
            return up("오브젝트 스토리지", target, started);
        } catch (Exception e) {
            return down("오브젝트 스토리지", target, started, e);
        }
    }

    /**
     * 브로커에 붙어 클러스터를 물어본다.
     *
     * 프로듀서 빈을 재사용하지 않는 까닭은, 그쪽에는 "지금 붙어 있는가" 를 물을 방법이
     * 없기 때문이다. `send` 는 브로커가 죽어 있어도 `max.block.ms` 만큼 기다렸다 실패해서
     * 상태 확인용으로 쓸 수 없다. 대신 짧은 타임아웃을 준 일회용 AdminClient 를 쓴다.
     */
    private AdminDependencyStatus probeKafka() {
        long started = System.nanoTime();
        String target = eventProperties.kafkaBootstrapServers();

        Properties config = new Properties();
        config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, target);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) PROBE_TIMEOUT.toMillis());
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) PROBE_TIMEOUT.toMillis());

        try (Admin admin = Admin.create(config)) {
            int nodes = admin.describeCluster().nodes().get().size();
            return new AdminDependencyStatus(
                "Kafka", "UP", "%s (브로커 %d)".formatted(target, nodes), elapsed(started), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return down("Kafka", target, started, e);
        } catch (Exception e) {
            return down("Kafka", target, started, e);
        }
    }

    // ------------------------------------------------------------------- 회원 통계

    private Map<String, Object> users() {
        Map<String, Object> byRole = new LinkedHashMap<>();
        for (UserRole role : UserRole.values()) {
            byRole.put(role.name(), entityManager
                .createQuery("select count(u) from User u where u.role = :role", Long.class)
                .setParameter("role", role)
                .getSingleResult());
        }

        Map<String, Object> byStatus = new LinkedHashMap<>();
        for (UserStatus status : UserStatus.values()) {
            byStatus.put(status.name(), entityManager
                .createQuery("select count(u) from User u where u.status = :status", Long.class)
                .setParameter("status", status)
                .getSingleResult());
        }

        Map<String, Object> users = new LinkedHashMap<>();
        users.put("byRole", byRole);
        users.put("byStatus", byStatus);
        return users;
    }

    // -------------------------------------------------------------------- 잡동사니

    private static AdminDependencyStatus up(String name, String target, long started) {
        return new AdminDependencyStatus(name, "UP", target, elapsed(started), null);
    }

    private static AdminDependencyStatus down(String name, String target, long started, Exception e) {
        log.warn("관리자 화면 상태 확인 실패 ({}): {}", name, e.toString());
        return new AdminDependencyStatus(name, "DOWN", target, -1, rootMessage(e));
    }

    private static long elapsed(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String rootMessage(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return "%s: %s".formatted(cause.getClass().getSimpleName(), cause.getMessage());
    }

}
