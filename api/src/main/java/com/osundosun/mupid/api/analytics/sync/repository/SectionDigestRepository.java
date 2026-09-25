package com.osundosun.mupid.api.analytics.sync.repository;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.osundosun.mupid.api.analytics.sync.dto.SectionDigest;
import com.osundosun.mupid.api.analytics.sync.dto.TrackVersion;

/**
 * MySQL 쪽 구간 요약 (S15P21A204-451). ClickHouse 쪽은
 * {@code TrackSectionRepository#digestsByReplica} 이고, <b>두 문장이 같은 문자열을 만들어야</b>
 * 체크섬이 같아진다. 한쪽을 고치면 다른 쪽도 고친다.
 *
 * <p>JPA 가 아니라 JdbcTemplate 이다. 곡 수만큼의 집계 한 번이고 엔티티가 필요 없다.
 * {@code CONCAT_WS} 는 NULL 을 건너뛰지만 여기 이어 붙이는 컬럼은 전부 NOT NULL 이다.
 * {@code section_label} 은 ENUM 이라 이름 문자열({@code CHORUS})로 이어지고, ClickHouse 의
 * {@code fn} 과 같다.
 */
@Repository
public class SectionDigestRepository {

    private static final String DIGESTS = """
        SELECT music_id, analysis_version, COUNT(*) AS sections,
               SUM(CRC32(CONCAT_WS('|', music_section_analysis_id, `index`, external_id,
                                   section_label, start_time_ms, end_time_ms))) AS checksum
        FROM music_section_analysis
        GROUP BY music_id, analysis_version
        """;

    private final JdbcTemplate mysql;

    public SectionDigestRepository(@Qualifier("jdbcTemplate") JdbcTemplate mysql) {
        this.mysql = mysql;
    }

    /**
     * 구간이 있는 모든 곡·버전의 요약. 삭제된 곡도 넣는다 — ClickHouse 도 곡 삭제를 모르므로
     * (455 전까지) 양쪽 다 그 구간을 들고 있고, 빼면 "ClickHouse 에만 있다" 로 잘못 센다.
     */
    public Map<TrackVersion, SectionDigest> digests() {
        Map<TrackVersion, SectionDigest> out = new HashMap<>();
        mysql.query(DIGESTS, rs -> {
            out.put(
                new TrackVersion(rs.getInt("music_id"), rs.getString("analysis_version")),
                new SectionDigest(rs.getLong("sections"), rs.getLong("checksum")));
        });
        return out;
    }

}
