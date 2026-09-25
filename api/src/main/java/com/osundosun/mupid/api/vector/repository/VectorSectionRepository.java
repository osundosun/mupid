package com.osundosun.mupid.api.vector.repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 벡터 쪽이 MySQL 에서 읽는 것 (S15P21A204-457). 전부 읽기이고, 곡·구간의 정본은 여전히
 * {@code music_section_analysis} 다.
 *
 * <p><b>"살아 있는 곡" 만 센다</b>({@code music.is_deleted = 0}). 삭제된 곡은 벡터를 받지 않고(등록이
 * 410 으로 거절한다), 대조가 그 곡의 포인트를 지운다(설계 §7.4) — 그러니 관문의 분모에도 넣지 않는다.
 * ClickHouse 구간 대조({@code SectionDigestRepository})가 삭제된 곡을 넣는 것과 반대인데, 저쪽은
 * 구간 사본이 삭제와 무관하게 남기 때문이다.
 *
 * <p>JdbcTemplate 이다 — 곡 수만큼의 행을 한 번에 읽고 엔티티가 필요 없다.
 */
@Repository
public class VectorSectionRepository {

    private static final String ALIVE = """
        FROM music_section_analysis s
        JOIN music m ON m.music_id = s.music_id
        WHERE m.is_deleted = 0
        """;

    private final JdbcTemplate mysql;

    public VectorSectionRepository(@Qualifier("jdbcTemplate") JdbcTemplate mysql) {
        this.mysql = mysql;
    }

    /** 구간 하나의 지문 — Qdrant payload 와 같은 칸이다. */
    public record Fingerprint(String analysisVersion, long startMs, long endMs) {
    }

    /** 관문의 분모 — 살아 있는 곡의 구간 수. */
    public long countAliveSections() {
        Long count = mysql.queryForObject("SELECT COUNT(*) " + ALIVE, Long.class);
        return count == null ? 0 : count;
    }

    /** 살아 있는 곡마다 구간 지문. 대조의 MySQL 쪽이다. 곡 → (external_id → 지문). */
    public Map<Integer, Map<String, Fingerprint>> aliveFingerprints() {
        Map<Integer, Map<String, Fingerprint>> out = new HashMap<>();
        mysql.query("SELECT s.music_id, s.external_id, s.analysis_version, s.start_time_ms, s.end_time_ms "
                + ALIVE, rs -> {
            out.computeIfAbsent(rs.getInt("music_id"), id -> new LinkedHashMap<>())
                .put(rs.getString("external_id"), new Fingerprint(rs.getString("analysis_version"),
                    rs.getLong("start_time_ms"), rs.getLong("end_time_ms")));
        });
        return out;
    }

    /** 이 가운데 MySQL 에 있는 {@code external_id}. 삭제된 곡의 것도 넣는다 — 여기서는 "있는가" 만 본다. */
    public Set<String> knownExternalIds(Collection<String> externalIds) {
        Set<String> out = new HashSet<>();
        List<String> ids = List.copyOf(externalIds);
        // IN 목록은 1,000개(재계산 한 묶음)까지라 한 번에 보낸다.
        String marks = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        mysql.query("SELECT external_id FROM music_section_analysis WHERE external_id IN (" + marks + ")",
            rs -> {
                out.add(rs.getString(1));
            }, ids.toArray());
        return out;
    }

}
