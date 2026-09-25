package com.osundosun.mupid.api.analytics.sync.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.osundosun.mupid.api.analytics.sync.entity.AnalyticsSyncPending;
import com.osundosun.mupid.api.analytics.sync.entity.AnalyticsSyncPending.AnalyticsSyncPendingId;
import com.osundosun.mupid.api.analytics.sync.enumerate.AnalyticsSyncKind;

/**
 * 미반영 기록 (S15P21A204-451).
 *
 * <p><b>종류는 문자열로 받는다</b>({@code AnalyticsSyncKind.name()}). 네이티브 문장이라
 * 열거형 변환이 끼지 않는다.
 *
 * <p>전부 MySQL 8 문법이다 — {@code INSERT … AS new ON DUPLICATE KEY UPDATE}(8.0.19),
 * {@code FOR UPDATE SKIP LOCKED}(8.0.1). 새 라이브러리(ShedLock 등) 없이 여러 인스턴스가
 * 같은 곡을 동시에 잡지 않게 하는 것이 SKIP LOCKED 다.
 */
public interface AnalyticsSyncPendingRepository
    extends JpaRepository<AnalyticsSyncPending, AnalyticsSyncPendingId> {

    /**
     * 곡을 미반영으로 올린다. 이미 있으면 버전을 바꾸고 {@code revision} 을 올린다.
     *
     * <p>{@code created_at}·{@code attempts} 는 건드리지 않는다. 계속 실패하던 곡이 다시
     * 올라왔다고 나이와 실패 횟수가 0 으로 돌아가면 밀린 것이 안 보인다.
     */
    @Modifying
    @Query(value = """
        INSERT INTO analytics_sync_pending (music_id, kind, analysis_version)
        VALUES (:musicId, :kind, :version) AS new
        ON DUPLICATE KEY UPDATE
            analysis_version = new.analysis_version,
            revision = analytics_sync_pending.revision + 1,
            updated_at = CURRENT_TIMESTAMP
        """, nativeQuery = true)
    int upsert(@Param("musicId") int musicId, @Param("kind") String kind,
               @Param("version") String analysisVersion);

    /**
     * 지금의 {@code revision}. 같은 트랜잭션에서 {@link #upsert} 바로 뒤에 읽는다 — 그 행은
     * 우리가 잠그고 있어 커밋 전까지 다른 쪽이 못 바꾼다.
     */
    @Query(value = """
        SELECT revision FROM analytics_sync_pending WHERE music_id = :musicId AND kind = :kind
        """, nativeQuery = true)
    Optional<Long> findRevision(@Param("musicId") int musicId, @Param("kind") String kind);

    /**
     * 커밋 뒤 쓰기가 성공했을 때 지운다. <b>자기가 남긴 revision 일 때만</b> 지운다 —
     * 그 사이 같은 곡이 다시 기록됐으면 그 행은 뒤의 요청 몫이다.
     */
    @Modifying
    @Query(value = """
        DELETE FROM analytics_sync_pending
        WHERE music_id = :musicId AND kind = :kind AND revision = :revision
        """, nativeQuery = true)
    int deleteIfRevision(@Param("musicId") int musicId, @Param("kind") String kind,
                         @Param("revision") long revision);

    /**
     * 재시도할 곡 번호. 오래 손대지 않은 것부터 준다.
     *
     * <p>{@code updated_at} 순이라 실패한 곡은 뒤로 간다({@link #markFailed} 가 시각을 올린다).
     * 고칠 수 없는 곡 하나가 매 회차 맨 앞에 서서 나머지를 막지 않는다.
     */
    @Query(value = """
        SELECT music_id FROM analytics_sync_pending
        WHERE kind = :kind
        ORDER BY updated_at, music_id
        LIMIT :limit
        """, nativeQuery = true)
    List<Integer> findCandidates(@Param("kind") String kind, @Param("limit") int limit);

    /**
     * 곡 하나를 가져간다. 다른 인스턴스가 잡고 있으면 기다리지 않고 비어서 돌아온다.
     *
     * <p>잠금은 호출한 트랜잭션이 끝날 때 풀린다. 그 사이 같은 곡의 구간 교체가 들어오면
     * 그 요청의 {@link #upsert} 가 잠금을 기다린다 — ClickHouse 쓰기 한 번 동안이다.
     * 기다린 뒤에는 우리가 지운 자리에 새 행을 넣으므로 그 요청의 미반영이 빠지지 않는다.
     */
    @Query(value = """
        SELECT revision FROM analytics_sync_pending
        WHERE music_id = :musicId AND kind = :kind
        FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    Optional<Long> claim(@Param("musicId") int musicId, @Param("kind") String kind);

    @Modifying
    @Query(value = """
        DELETE FROM analytics_sync_pending WHERE music_id = :musicId AND kind = :kind
        """, nativeQuery = true)
    int deletePending(@Param("musicId") int musicId, @Param("kind") String kind);

    /** 실패를 적고 시각을 올린다 — 다음 회차에서 뒤로 간다. */
    @Modifying
    @Query(value = """
        UPDATE analytics_sync_pending
        SET attempts = attempts + 1, last_error = :error, updated_at = CURRENT_TIMESTAMP
        WHERE music_id = :musicId AND kind = :kind
        """, nativeQuery = true)
    int markFailed(@Param("musicId") int musicId, @Param("kind") String kind,
                   @Param("error") String error);

    @Query(value = "SELECT COUNT(*) FROM analytics_sync_pending WHERE kind = :kind",
        nativeQuery = true)
    long countPending(@Param("kind") String kind);

    /**
     * 한 종류의 미반영 곡을 곡 번호 순으로 준다 — {@code after} 다음부터 {@code limit} 개. 배치가 벡터를
     * 다시 보낼 목록을 쪽 단위로 읽는다({@code GET /api/v1/system/vectors/pending}, S15P21A204-457).
     */
    @Query(value = """
        SELECT p FROM AnalyticsSyncPending p
        WHERE p.id.kind = :kind AND p.id.musicId > :after
        ORDER BY p.id.musicId
        LIMIT :limit
        """)
    List<AnalyticsSyncPending> findPage(@Param("kind") AnalyticsSyncKind kind,
                                        @Param("after") int after, @Param("limit") int limit);

    /**
     * 가장 오래된 미반영의 나이(초). 없으면 null.
     *
     * <p>DB 에서 뺀다. {@code created_at} 은 MySQL 의 {@code CURRENT_TIMESTAMP} 로 찍히므로
     * 자바 시계와 비교하면 시간대 설정에 따라 9시간씩 어긋난다.
     */
    @Query(value = """
        SELECT TIMESTAMPDIFF(SECOND, MIN(created_at), CURRENT_TIMESTAMP)
        FROM analytics_sync_pending WHERE kind = :kind
        """, nativeQuery = true)
    Long oldestAgeSeconds(@Param("kind") String kind);

}
