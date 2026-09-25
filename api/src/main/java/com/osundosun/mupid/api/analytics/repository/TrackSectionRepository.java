package com.osundosun.mupid.api.analytics.repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.osundosun.mupid.api.analytics.dto.TrackSectionRow;
import com.osundosun.mupid.api.analytics.sync.dto.SectionDigest;
import com.osundosun.mupid.api.analytics.sync.dto.TrackVersion;

/**
 * 곡의 구간 분석 결과를 ClickHouse {@code track_sections} 에 적재한다 (S15P21A204-435).
 *
 * <h2>analytics 패키지에서 처음으로 쓰는 쪽이다</h2>
 *
 * 나머지 둘({@link ListenAnalyticsRepository} · {@link UserActivityRepository})은 읽기만
 * 한다. 행동 데이터는 Kafka 를 거쳐 들어오지만 <b>구간은 그 길이 없다</b> — 구간을 아는
 * 것은 전처리 배치가 부르는 {@code PUT /api/v1/music/{musicId}/section} 하나뿐이고,
 * 그래서 MySQL 에 넣는 자리에서 ClickHouse 에도 함께 넣는다.
 *
 * <h2>왜 이 표가 채워져야 하는가</h2>
 *
 * {@code mv_user_section_signals} 가 {@code dictGet(dict_track_sections, …)} 로 재생
 * 위치가 어느 구간인지 찾고, 못 찾으면 그 이벤트를 <b>버린다</b>. 구간 표가 비어 있으면
 * 좋아요를 눌러도 {@code user_section_signals} 에 한 줄도 안 남는다. 오류가 아니라
 * 침묵이라 화면만 봐서는 알 수 없다.
 *
 * <h2>교체인데 지우지 않는 이유</h2>
 *
 * {@code ReplicatedReplacingMergeTree} 이고 정렬 키가
 * {@code (analysis_version, track_id, section_index)} 라, 같은 순번을 다시 넣으면 옛 행은
 * 병합 때 사라진다. 그러니 <b>덮이지 않는 것은 새 분석에 없는 순번뿐이다</b> — 재분석으로
 * 구간이 줄어든 곡에서만 생긴다. 그 경우에만 mutation 을 한 번 던진다.
 *
 * <p>mutation 은 파트를 다시 쓰므로 곡마다 무조건 부르면 1,000곡 배치가 1,000번 파트를
 * 재기록한다. 그래서 <b>먼저 남아 있는 순번을 읽어 비교하고</b>, 정말 남는 것이 있을 때만
 * 부른다. 평소(구간 수가 같거나 늘어난 재분석)에는 SELECT 한 번과 INSERT 한 번으로 끝난다.
 *
 * <h2>Distributed 가 아니라 로컬 표에 넣는다</h2>
 *
 * {@code track_sections} 는 {@code mupid_all}(1 샤드 × 2 레플리카)이라 <b>샤딩이 없다.</b>
 * 어느 노드의 {@code track_sections_local} 에 넣어도 복제로 전 노드에 퍼진다 — 샤딩 키를
 * 따져야 하는 {@code listen_events} 와 다르다. 그래서 Distributed 를 거치지 않는다.
 *
 * <p>거치지 않는 쪽이 <b>확실해서</b> 고른 것이기도 하다. Distributed 로 넣으면 기본값이
 * 비동기라 INSERT 가 돌아온 뒤에도 로컬 표에 아직 없을 수 있고, 그것을 끄는
 * {@code SETTINGS distributed_foreground_insert = 1} 은 <b>JDBC 로는 쓸 수 없다</b> —
 * v2 드라이버의 파서가 {@code VALUES} 앞의 {@code SETTINGS} 절을 모른다
 * ({@code mismatched input 'SETTINGS' expecting &#123;FORMAT, SELECT, VALUES, WITH, '('&#125;}).
 * 자리표시자를 하나도 못 찾은 채 넘어가 {@code ArrayIndexOutOfBoundsException} 으로 터진다.
 * HTTP 로 넣는 {@code verify.sh} 에는 이 제약이 없다.
 *
 * <p>읽는 쪽도 같은 이유로 로컬 표를 본다. 쓰기와 읽기가 같은 노드에서 끝나야 바로 아래의
 * 정리 판단이 흔들리지 않는다.
 *
 * <h2>딕셔너리는 다시 읽지 않는다</h2>
 *
 * {@code dict_track_sections} 는 {@code LIFETIME(MIN 300 MAX 600)} 이라 늦어도 10분 안에
 * 새 구간을 집는다. 곡마다 {@code SYSTEM RELOAD DICTIONARY ON CLUSTER} 를 부르면 곡 수만큼
 * 전체 사전을 다시 읽게 되어 배치가 ClickHouse 를 붙들고 앉는다. 즉시 반영이 필요한
 * 시나리오(시드·검증)는 배치 끝에 한 번 부른다.
 *
 * <h2>쓰는 쪽은 이 클래스 하나다 (S15P21A204-451)</h2>
 *
 * 부르는 곳은 {@code analytics.sync.service.TrackSectionSync} 뿐이다 — 구간 교체 커밋 뒤,
 * 그리고 미반영 기록을 재시도할 때. 예전에는 {@code ai_server/tools/push_sections.py} 가
 * ClickHouse 에 HTTP 로 직접 썼는데 걷어냈다. 밀린 곡은 대조 작업이 찾아 미반영 기록에 넣고
 * 재시도가 MySQL 의 현재 구간으로 메운다.
 */
@Repository
public class TrackSectionRepository {

    /** 컬럼 순서는 {@code clickhouse/init/01-schema.sql} 의 선언 순서와 같다. */
    private static final String INSERT = """
        INSERT INTO track_sections_local
            (track_id, music_section_analysis_id, analysis_version,
             section_index, section_uid, fn, start_ms, end_ms)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;

    private static final String EXISTING_INDEXES = """
        SELECT DISTINCT section_index
        FROM track_sections_local
        WHERE analysis_version = ? AND track_id = ?
        """;

    /**
     * mutation 은 기본적으로 비동기다. {@code mutations_sync = 1} 로 시작한 노드에서
     * 끝나기를 기다린다 — 기다리지 않으면 "지웠다" 고 로그를 남긴 직후에 조회하면 아직
     * 남아 있다. 두 레플리카 모두를 기다리는 {@code 2} 는 쓰지 않는다. 남은 순번을
     * 치우는 일은 급하지 않고, 배치가 레플리카 동기화까지 붙들 이유가 없다.
     */
    private static final String DELETE_STALE = """
        ALTER TABLE track_sections_local ON CLUSTER mupid_all
        DELETE WHERE analysis_version = '%s' AND track_id = %d AND section_index IN (%s)
        SETTINGS mutations_sync = 1
        """;

    /**
     * {@code mupid_all} 의 레플리카 이름. 데이터와 무관하게 구한다 — 구간을 한 줄도 못 받은
     * 레플리카는 아래 요약 질의의 결과에 <b>나타나지 않으므로</b>, 결과에서 이름을 모으면
     * 통째로 빈 레플리카를 "문제 없음" 으로 넘긴다.
     */
    private static final String REPLICAS = """
        SELECT DISTINCT hostName() FROM clusterAllReplicas('mupid_all', system.one)
        """;

    /**
     * 레플리카마다 곡·버전별 구간 요약.
     *
     * <p>{@code clusterAllReplicas} 는 레플리카 하나하나를 샤드처럼 따로 읽는다.
     * {@code hostName()} 은 그 원격 쪽에서 계산되어 행이 어느 레플리카에서 왔는지 알려 준다.
     * 연결은 헤드리스 서비스 하나({@code clickhouse:8123})라 어느 노드에 붙든 두 레플리카를
     * 각각 본다. 한 레플리카가 안 닿으면 질의 전체가 실패한다 — 반쪽 결과로 "일치" 라고
     * 하지 않는다.
     *
     * <p>{@code FINAL} 로 병합 전 중복을 누른다(설계 §8.2-6). 이어 붙이는 문자열은
     * {@code SectionDigestRepository} 의 MySQL 문장과 <b>같아야 한다</b>.
     */
    private static final String DIGESTS_BY_REPLICA = """
        SELECT hostName() AS replica, track_id, analysis_version,
               count() AS sections,
               sum(CRC32(concat(toString(music_section_analysis_id), '|', toString(section_index), '|',
                                section_uid, '|', toString(fn), '|',
                                toString(start_ms), '|', toString(end_ms)))) AS checksum
        FROM clusterAllReplicas('mupid_all', currentDatabase(), 'track_sections_local') FINAL
        GROUP BY replica, track_id, analysis_version
        """;

    private final JdbcTemplate clickHouse;

    public TrackSectionRepository(@Qualifier("clickHouseJdbcTemplate") JdbcTemplate clickHouse) {
        this.clickHouse = clickHouse;
    }

    /**
     * 한 곡의 구간을 통째로 갈아 끼운다. MySQL 쪽
     * {@code MusicSectionServiceImpl.replaceSections} 와 같은 뜻이다.
     *
     * @param musicId         곡 번호. ClickHouse 에서는 {@code track_id} 다
     * @param analysisVersion 분석 버전. 딕셔너리가 보는 값이어야 판정에 쓰인다
     * @param rows            새 구간 전체. 비어 있으면 아무것도 하지 않는다 —
     *                        요청 쪽이 빈 목록을 막고 있어(`@NotEmpty`) 여기까지 오지
     *                        않지만, 왔을 때 곡의 구간을 통째로 날리지는 않는다
     * @return 넣은 행 수
     */
    public int replaceSections(int musicId, String analysisVersion, List<TrackSectionRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }

        // 넣기 **전에** 읽는다. 넣은 뒤에 읽으면 방금 넣은 순번과 옛 순번을 구분할 수 없다.
        Set<Integer> stale = new LinkedHashSet<>(
            clickHouse.queryForList(EXISTING_INDEXES, Integer.class, analysisVersion, musicId));

        List<Object[]> batch = rows.stream()
            .map(row -> new Object[] {
                row.trackId(), row.musicSectionAnalysisId(), row.analysisVersion(),
                row.sectionIndex(), row.sectionUid(), row.fn(), row.startMs(), row.endMs()})
            .toList();
        clickHouse.batchUpdate(INSERT, batch);

        rows.forEach(row -> stale.remove(row.sectionIndex()));
        if (!stale.isEmpty()) {
            deleteStale(musicId, analysisVersion, new ArrayList<>(stale));
        }
        return rows.size();
    }

    /**
     * 새 분석에 없는 순번을 지운다. <b>덮어쓰기로는 사라지지 않는 행이다.</b>
     *
     * <p>지우지 않으면 구간이 줄어든 곡에서 옛 구간이 계속 살아 있고,
     * {@code dict_track_sections} 가 그 범위의 재생 위치를 <b>없어진 구간</b>으로 판정한다.
     * 추천은 그 구간을 내놓고, 프론트는 곡에 없는 구간을 틀게 된다.
     *
     * <p>{@code ALTER TABLE … DELETE} 는 일반 쿼리가 아니라 mutation 이라 자리표시자
     * 바인딩이 드라이버 판마다 다르게 동작한다. 그래서 문장을 통째로 만들어 던진다.
     * 순번과 곡 번호는 {@code int} 라 주입의 여지가 없고, 버전 문자열만
     * {@link #quote(String)} 로 감싼다.
     */
    private void deleteStale(int musicId, String analysisVersion, List<Integer> indexes) {
        String list = indexes.stream().map(String::valueOf).collect(Collectors.joining(","));
        clickHouse.execute(DELETE_STALE.formatted(quote(analysisVersion), musicId, list));
    }

    /**
     * 레플리카마다 곡·버전별 구간 요약. 구간이 하나도 없는 레플리카도 빈 표로 들어 있다.
     *
     * @return 레플리카 이름 → (곡·버전 → 요약)
     */
    public Map<String, Map<TrackVersion, SectionDigest>> digestsByReplica() {
        Map<String, Map<TrackVersion, SectionDigest>> out = new LinkedHashMap<>();
        for (String replica : clickHouse.queryForList(REPLICAS, String.class)) {
            out.put(replica, new HashMap<>());
        }
        clickHouse.query(DIGESTS_BY_REPLICA, rs -> {
            out.computeIfAbsent(rs.getString("replica"), key -> new HashMap<>()).put(
                new TrackVersion(rs.getInt("track_id"), rs.getString("analysis_version")),
                new SectionDigest(rs.getLong("sections"), rs.getLong("checksum")));
        });
        return out;
    }

    /**
     * ClickHouse 문자열 리터럴 안쪽. 역슬래시를 먼저 늘려야 한다 — 따옴표를 먼저 하면
     * 그때 넣은 역슬래시까지 다시 늘어난다.
     */
    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

}
