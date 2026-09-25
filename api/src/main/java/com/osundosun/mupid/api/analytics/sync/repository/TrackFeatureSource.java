package com.osundosun.mupid.api.analytics.sync.repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.osundosun.mupid.api.analytics.dto.TrackFeatureRow;

/**
 * MySQL 에서 곡 특징을 만든다 — ClickHouse {@code track_features} 에 쓸 값의 <b>유일한 원천</b>
 * (S15P21A204-455).
 *
 * <p>커밋 뒤 쓰기·재시도·대조가 모두 여기서 읽는다. 요청이 들고 온 값(스냅숏)을 쓰지 않고
 * <b>쓰는 순간의 MySQL</b> 을 읽는다 — 곡 특징은 다섯 표(곡·분석·장르·무드·스트림)와 구간에
 * 걸쳐 있어 한 요청이 전부를 알지 못하고, 두 요청이 겹쳐도 늦게 쓴 쪽이 늘 지금 값이다.
 *
 * <p>값의 규칙은 추천 본문 {@code RecommenderCatalogServiceImpl.build} 와 같다. 거기가 후보만
 * 고르는 데 비해 여기는 모든 곡을 싣고 후보 조건을 {@code is_deleted}·{@code playable} 로 남긴다.
 *
 * <ul>
 *   <li>곡 길이: {@code DONE} 스트림 {@code duration_ms} 의 최댓값</li>
 *   <li>문자열 곡 ID·분석 버전: 시작 시각이 가장 이른 구간의 {@code external_id} {@code #} 앞과
 *       그 행의 {@code analysis_version}</li>
 *   <li>장르·무드: 코드 이름, 코드 번호순 — 순서가 흔들리면 같은 곡이 다른 본문이 된다</li>
 * </ul>
 *
 * <p>JPA 가 아니라 JdbcTemplate 이다({@code SectionDigestRepository} 와 같은 판단). 표마다 한
 * 번씩 읽어 붙인다 — 조인 하나로 묶으면 장르 × 무드 곱이 된다.
 */
@Repository
public class TrackFeatureSource {

    private static final String MUSIC = "SELECT music_id, is_deleted FROM music";

    private static final String DURATION = """
        SELECT music_id, MAX(duration_ms) AS duration_ms FROM streams
        WHERE status = 'DONE'%s GROUP BY music_id
        """;

    private static final String ANALYSIS = """
        SELECT music_id, has_vocal, vocal_gender, bpm, bpm_requested_min, bpm_requested_max,
               language, category
        FROM music_analysis
        """;

    private static final String GENRES = """
        SELECT g.music_id, c.code_name FROM music_genres g JOIN codes c ON c.code_id = g.genre_code_id
        %s ORDER BY g.music_id, c.code_id
        """;

    private static final String MOODS = """
        SELECT m.music_id, c.code_name FROM music_moods m JOIN codes c ON c.code_id = m.mood_code_id
        %s ORDER BY m.music_id, c.code_id
        """;

    /**
     * 곡마다 첫 구간. 추천 본문은 구간을 (곡, 시작 시각) 순으로 읽어 처음 본 곡 ID 를 쓴다 —
     * 같은 규칙을 창 함수로 옮겼다. 시작 시각이 같은 구간이 둘이면 번호가 작은 쪽이다.
     */
    private static final String FIRST_SECTION = """
        SELECT music_id, external_id, analysis_version FROM (
            SELECT music_id, external_id, analysis_version,
                   ROW_NUMBER() OVER (PARTITION BY music_id
                                      ORDER BY start_time_ms, music_section_analysis_id) AS rn
            FROM music_section_analysis%s
        ) first_section WHERE rn = 1
        """;

    private final JdbcTemplate mysql;

    public TrackFeatureSource(@Qualifier("jdbcTemplate") JdbcTemplate mysql) {
        this.mysql = mysql;
    }

    /** 모든 곡. 대조 작업이 쓴다. */
    public Map<Integer, TrackFeatureRow> loadAll() {
        return load(null);
    }

    /**
     * 곡 하나. MySQL 에 곡이 없으면 null 이다 — 곡은 논리 삭제라 보통은 없을 일이 없다.
     */
    public TrackFeatureRow load(int musicId) {
        return load(List.of(musicId)).get(musicId);
    }

    /**
     * 주어진 곡들(null 이면 전부). 곡 번호순이다.
     */
    public Map<Integer, TrackFeatureRow> load(Collection<Integer> musicIds) {
        if (musicIds != null && musicIds.isEmpty()) {
            return Map.of();
        }
        Map<Integer, Boolean> deleted = new LinkedHashMap<>();
        mysql.query(MUSIC + where("music_id", musicIds, true) + " ORDER BY music_id",
            rs -> {
                deleted.put(rs.getInt("music_id"), rs.getBoolean("is_deleted"));
            });
        if (deleted.isEmpty()) {
            return Map.of();
        }

        Map<Integer, Integer> duration = new HashMap<>();
        mysql.query(DURATION.formatted(where("music_id", musicIds, false)), rs -> {
            duration.put(rs.getInt("music_id"), rs.getInt("duration_ms"));
        });

        Map<Integer, Object[]> analysis = new HashMap<>();
        mysql.query(ANALYSIS + where("music_id", musicIds, true), rs -> {
            analysis.put(rs.getInt("music_id"), new Object[] {
                rs.getObject("has_vocal") == null ? null : rs.getBoolean("has_vocal"),
                rs.getString("vocal_gender"),
                rs.getObject("bpm") == null ? null : rs.getDouble("bpm"),
                rs.getObject("bpm_requested_min") == null ? null : rs.getInt("bpm_requested_min"),
                rs.getObject("bpm_requested_max") == null ? null : rs.getInt("bpm_requested_max"),
                rs.getString("language"),
                rs.getString("category")});
        });

        Map<Integer, List<String>> genres = codes(GENRES, "g.music_id", musicIds);
        Map<Integer, List<String>> moods = codes(MOODS, "m.music_id", musicIds);

        Map<Integer, String[]> first = new HashMap<>();
        mysql.query(FIRST_SECTION.formatted(where("music_id", musicIds, true)), rs -> {
            first.put(rs.getInt("music_id"),
                new String[] {rs.getString("external_id"), rs.getString("analysis_version")});
        });

        Map<Integer, TrackFeatureRow> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Boolean> music : deleted.entrySet()) {
            int musicId = music.getKey();
            Object[] a = analysis.get(musicId);
            String[] section = first.get(musicId);
            out.put(musicId, new TrackFeatureRow(
                musicId,
                section == null ? "" : trackOf(section[0]),
                duration.get(musicId),
                a == null ? null : (Double)a[2],
                a == null ? null : (Integer)a[3],
                a == null ? null : (Integer)a[4],
                a == null ? null : (String)a[5],
                a == null ? null : (String)a[6],
                genres.getOrDefault(musicId, List.of()),
                moods.getOrDefault(musicId, List.of()),
                a == null ? null : (Boolean)a[0],
                a == null ? null : (String)a[1],
                music.getValue(),
                duration.containsKey(musicId),
                section == null ? "" : section[1]));
        }
        return out;
    }

    /**
     * {@code external_id} 의 {@code #} 앞. 추천 본문과 같다 — {@code #} 가 없거나 맨 앞이면 곡
     * ID 가 없는 것이다(본문은 그 구간을 싣지 않는다).
     */
    static String trackOf(String externalId) {
        String uid = externalId == null ? "" : externalId.strip();
        int hash = uid.indexOf('#');
        return hash <= 0 ? "" : uid.substring(0, hash);
    }

    private Map<Integer, List<String>> codes(String sql, String column, Collection<Integer> ids) {
        Map<Integer, List<String>> out = new HashMap<>();
        mysql.query(sql.formatted(where(column, ids, true)), rs -> {
            out.computeIfAbsent(rs.getInt(1), key -> new ArrayList<>()).add(rs.getString(2));
        });
        return out;
    }

    /**
     * 곡 번호 조건. 번호는 {@code int} 라 문장에 바로 넣는다 — 주입의 여지가 없고, 수가 정해지지
     * 않은 {@code IN} 을 자리표시자로 만들지 않아도 된다.
     */
    private static String where(String column, Collection<Integer> ids, boolean first) {
        if (ids == null) {
            return "";
        }
        String list = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return (first ? " WHERE " : " AND ") + column + " IN (" + list + ")";
    }

}
