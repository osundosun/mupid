package com.osundosun.mupid.api.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.osundosun.mupid.api.analytics.dto.TrackFeatureRow;
import com.osundosun.mupid.api.analytics.repository.TrackFeatureRepository;

/**
 * 곡 특징을 <b>실제 ClickHouse</b> 에 넣고 레플리카마다 다시 읽는다 (S15P21A204-455).
 *
 * <p>CI 에는 ClickHouse 가 없어 {@code MUPID_TEST_CLICKHOUSE_URL} 이 있을 때만 돈다. 확인하려는 것은
 * JDBC 가 배열·Nullable(Bool)·Nullable 수를 제대로 싣고 받는가 — 모의 객체로는 볼 수 없다.
 * 표는 측정 도구가 따로 만든 DB 에 둔다({@code ai_server/tools/bench_feature_store.py --keep},
 * 기본 {@code mupid_bench455}). {@code default} DB 에는 쓰지 않는다.
 *
 * <pre>
 *   MUPID_TEST_CLICKHOUSE_URL=jdbc:clickhouse://localhost:18123/mupid_bench455
 *   MUPID_TEST_CLICKHOUSE_PASSWORD=…  (없으면 MUPID_CLICKHOUSE_PASSWORD)
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "MUPID_TEST_CLICKHOUSE_URL", matches = ".+")
class TrackFeatureClickHouseTest {

    private static JdbcTemplate clickHouse() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.clickhouse.jdbc.ClickHouseDriver");
        ds.setUrl(System.getenv("MUPID_TEST_CLICKHOUSE_URL"));
        ds.setUsername("mupid");
        String password = System.getenv("MUPID_TEST_CLICKHOUSE_PASSWORD");
        ds.setPassword(password != null ? password : System.getenv("MUPID_CLICKHOUSE_PASSWORD"));
        return new JdbcTemplate(ds);
    }

    @Test
    @DisplayName("넣은 값이 두 레플리카에서 그대로 읽히고 체크섬이 같다")
    void roundTripsThroughBothReplicas() throws InterruptedException {
        JdbcTemplate jdbc = clickHouse();
        TrackFeatureRow full = new TrackFeatureRow(990_001, "T990001", 201_345, 123.456, 90, 110,
            "ko", "ballad", List.of("pop_ballad", "k_ballad"), List.of("serene"), true, "F",
            false, true, "v1");
        TrackFeatureRow unknown = new TrackFeatureRow(990_002, "", null, null, null, null,
            null, null, List.of(), List.of(), null, null, true, false, "");
        TrackFeatureRepository repository = new TrackFeatureRepository(jdbc);

        repository.replace(List.of(full, unknown));
        Map<String, Map<Integer, TrackFeatureRow>> replicas =
            await(repository, rows -> unknown.equals(rows.get(990_002)));

        assertThat(replicas).hasSize(2);
        for (Map<Integer, TrackFeatureRow> rows : replicas.values()) {
            assertThat(rows.get(990_001)).isEqualTo(full);
            assertThat(rows.get(990_002)).isEqualTo(unknown);
            assertThat(rows.get(990_001).digest()).isEqualTo(full.digest());
        }

        // 곡 삭제 — 다시 넣으면 FINAL 이 새 행만 본다.
        TrackFeatureRow deleted = new TrackFeatureRow(990_001, "T990001", 201_345, 123.456, 90, 110,
            "ko", "ballad", List.of("pop_ballad", "k_ballad"), List.of("serene"), true, "F",
            true, true, "v1");
        Thread.sleep(2);
        repository.replace(List.of(deleted));
        assertThat(await(repository, rows -> deleted.equals(rows.get(990_001))).values())
            .allSatisfy(rows -> assertThat(rows.get(990_001).isDeleted()).isTrue());
    }

    /** 다른 레플리카가 파트를 받아 오기를 잠깐(최대 5초) 기다린다. */
    private static Map<String, Map<Integer, TrackFeatureRow>> await(
        TrackFeatureRepository repository, Predicate<Map<Integer, TrackFeatureRow>> ready)
        throws InterruptedException {
        Map<String, Map<Integer, TrackFeatureRow>> replicas = Map.of();
        for (int i = 0; i < 20; i++) {
            replicas = repository.rowsByReplica();
            if (!replicas.isEmpty() && replicas.values().stream().allMatch(ready)) {
                break;
            }
            Thread.sleep(250);
        }
        return replicas;
    }

}
