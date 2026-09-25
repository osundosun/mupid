package com.osundosun.mupid.api.analytics.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.osundosun.mupid.api.analytics.dto.TrackFeatureRow;

/**
 * 곡 단위 특징을 ClickHouse {@code track_features_local} 에 쓴다 (S15P21A204-455, 설계 §6.2).
 *
 * <p>부르는 곳은 {@code analytics.sync.service.TrackFeatureSync} 하나다 — 쓰기 관문은 Spring
 * 하나이고(원칙 1), AI 는 읽기만 한다.
 *
 * <h2>교체는 INSERT 한 번이다</h2>
 *
 * <p>정렬 키가 {@code track_id} 하나라 곡마다 행이 하나다. 같은 곡을 다시 넣으면
 * {@code version}(쓴 시각, epoch ms)이 큰 행이 병합 때 남는다 — 구간처럼 "새 분석에 없는 순번"
 * 이 생기지 않으므로 mutation 이 없다. 곡을 지워도 행을 지우지 않고 {@code is_deleted} 를 세운
 * 행을 넣는다. 병합 전에는 옛 행이 함께 보이므로 <b>읽는 쪽은 FINAL</b> 을 붙인다.
 *
 * <p>시각을 버전으로 쓰므로 인스턴스끼리 시계가 틀리면 늦게 쓴 쪽이 질 수 있다. 쓰는 값은
 * 언제나 <b>쓰는 순간의 MySQL</b>이라(스냅숏을 들고 다니지 않는다) 진 쪽도 옛 값이 아니고,
 * 어긋나더라도 대조가 잡는다.
 *
 * <h2>로컬 표에 넣는다</h2>
 *
 * <p>{@code mupid_all}(1 샤드 × 2 레플리카)이라 어느 노드에 넣어도 복제로 퍼진다.
 * {@link TrackSectionRepository} 와 같은 이유(Distributed 로 넣으면 비동기이고, 그것을 끄는
 * {@code SETTINGS} 절을 JDBC 가 못 쓴다)로 Distributed 를 거치지 않는다.
 */
@Repository
public class TrackFeatureRepository {

    /** 컬럼 순서는 {@code clickhouse/init/01-schema.sql} 의 선언 순서와 같다. */
    private static final String INSERT = """
        INSERT INTO track_features_local
            (track_id, track, duration_ms, bpm, bpm_requested_min, bpm_requested_max,
             language, category, genres, moods, has_vocal, vocal_gender,
             is_deleted, playable, analysis_version, version)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String REPLICAS = """
        SELECT DISTINCT hostName() FROM clusterAllReplicas('mupid_all', system.one)
        """;

    /**
     * 레플리카마다 곡 특징 전부. {@link TrackSectionRepository} 의 구간 요약과 같은 방식으로
     * 두 레플리카를 따로 읽는다 — 한쪽이 안 닿으면 질의 전체가 실패한다.
     *
     * <p>배열은 {@code ','} 로 이어 받는다. 체크섬({@link TrackFeatureRow#digest})이 같은 규칙으로
     * 이으므로 되돌려 쪼갠 값으로 세도 같다. JDBC 배열 변환을 읽는 쪽에서 피하려는 것이다.
     * {@code has_vocal} 은 -1(모름)·0·1 로 받는다.
     */
    private static final String ROWS_BY_REPLICA = """
        SELECT hostName() AS replica, track_id, track, duration_ms, bpm,
               bpm_requested_min, bpm_requested_max, language, category,
               arrayStringConcat(genres, ',') AS genres, arrayStringConcat(moods, ',') AS moods,
               multiIf(isNull(has_vocal), -1, assumeNotNull(has_vocal), 1, 0) AS has_vocal,
               vocal_gender, is_deleted, playable, analysis_version
        FROM clusterAllReplicas('mupid_all', currentDatabase(), 'track_features_local') FINAL
        """;

    private final JdbcTemplate clickHouse;
    private final LongSupplier clock;

    @Autowired
    public TrackFeatureRepository(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouse) {
        this(clickHouse, System::currentTimeMillis);
    }

    TrackFeatureRepository(JdbcTemplate clickHouse, LongSupplier clock) {
        this.clickHouse = clickHouse;
        this.clock = clock;
    }

    /**
     * 곡 특징을 갈아 끼운다. 여러 곡을 한 번에 넣어도 된다(배치 INSERT 한 번).
     *
     * @return 넣은 행 수
     */
    public int replace(List<TrackFeatureRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        long version = clock.getAsLong();
        List<Object[]> batch = rows.stream()
            .map(row -> new Object[] {
                row.trackId(), row.track(), row.durationMs(), row.bpm(),
                row.bpmRequestedMin(), row.bpmRequestedMax(), row.language(), row.category(),
                row.genres().toArray(String[]::new), row.moods().toArray(String[]::new),
                row.hasVocal(), row.vocalGender(), row.isDeleted(), row.playable(),
                row.analysisVersion(), version})
            .toList();
        clickHouse.batchUpdate(INSERT, batch);
        return rows.size();
    }

    /**
     * 레플리카마다 곡 번호 → 특징. 행이 하나도 없는 레플리카도 빈 표로 들어 있다.
     */
    public Map<String, Map<Integer, TrackFeatureRow>> rowsByReplica() {
        Map<String, Map<Integer, TrackFeatureRow>> out = new LinkedHashMap<>();
        for (String replica : clickHouse.queryForList(REPLICAS, String.class)) {
            out.put(replica, new HashMap<>());
        }
        clickHouse.query(ROWS_BY_REPLICA, rs -> {
            TrackFeatureRow row = read(rs);
            out.computeIfAbsent(rs.getString("replica"), key -> new HashMap<>())
                .put(row.trackId(), row);
        });
        return out;
    }

    static TrackFeatureRow read(ResultSet rs) throws SQLException {
        int hasVocal = rs.getInt("has_vocal");
        return new TrackFeatureRow(
            rs.getInt("track_id"),
            rs.getString("track"),
            nullableInt(rs, "duration_ms"),
            nullableDouble(rs, "bpm"),
            nullableInt(rs, "bpm_requested_min"),
            nullableInt(rs, "bpm_requested_max"),
            rs.getString("language"),
            rs.getString("category"),
            split(rs.getString("genres")),
            split(rs.getString("moods")),
            hasVocal < 0 ? null : hasVocal == 1,
            rs.getString("vocal_gender"),
            rs.getBoolean("is_deleted"),
            rs.getBoolean("playable"),
            rs.getString("analysis_version"));
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : (int)value;
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }

    private static List<String> split(String joined) {
        return joined == null || joined.isEmpty() ? List.of() : Arrays.asList(joined.split(",", -1));
    }

}
