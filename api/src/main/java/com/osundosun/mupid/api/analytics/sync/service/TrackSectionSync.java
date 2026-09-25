package com.osundosun.mupid.api.analytics.sync.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.osundosun.mupid.api.analytics.dto.TrackSectionRow;
import com.osundosun.mupid.api.analytics.repository.TrackSectionRepository;
import com.osundosun.mupid.api.analytics.sync.enumerate.AnalyticsSyncKind;
import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.repository.MusicSectionAnalysisRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * MySQL 구간을 ClickHouse {@code track_sections_local} 로 옮기는 <b>유일한 길</b>
 * (S15P21A204-451, 저장소 재설계 §8).
 *
 * <h2>불변식</h2>
 *
 * <p>커밋된 MySQL 구간 변경은 유한한 시간 안에 ClickHouse 에 같은 내용으로 반영된다.
 * 세 장치가 그것을 지킨다.
 *
 * <ol>
 *   <li><b>미반영 기록</b>({@link #markPending}) — 구간을 바꾸는 트랜잭션 <b>안에서</b>
 *       {@code analytics_sync_pending} 에 행을 남긴다. 커밋과 한 몸이라 빠지지 않는다.</li>
 *   <li><b>커밋 뒤 즉시 쓰기</b>({@link #pushAfterCommit}) — 예전처럼 커밋 뒤 바로 쓰고,
 *       성공하면 그 행을 지운다. 평소에는 이것으로 끝난다.</li>
 *   <li><b>재시도</b>({@link #retry}) — 남은 행을 {@code TrackSectionRetryJob} 이 1분마다
 *       곡 단위로 다시 쓴다. <b>MySQL 의 지금 구간</b>을 읽어 통째로 교체하므로 몇 번을
 *       써도 결과가 같다.</li>
 * </ol>
 *
 * <p>여기에 {@code TrackSectionReconciler} 가 매일 양쪽을 대조해 어긋난 곡을 1 로 되돌린다
 * — 기록 자체가 빠진 경우(이 클래스를 거치지 않은 쓰기, 레플리카 한쪽만 잃은 경우)의 그물이다.
 *
 * <h2>ClickHouse 가 죽어도 MySQL 적재는 성공한다</h2>
 *
 * <p>MySQL 이 정본이다. 분석 DB 가 죽었다고 구간 적재가 실패하면 배치가 곡마다 선다
 * ({@code ClickHouseConfig} 가 헬스체크를 떼어 둔 것과 같은 판단). 쓰기 실패는 미반영
 * 기록으로 남고 재시도가 메운다 — 예전처럼 로그만 남고 사라지지 않는다.
 *
 * <h2>커밋 뒤의 MySQL 작업은 새 트랜잭션이다</h2>
 *
 * <p>{@code afterCommit} 에서는 원래 트랜잭션의 자원이 아직 묶여 있어, 그냥 쓰면 이미 끝난
 * 트랜잭션에 "참여" 하려다 아무것도 커밋하지 않는다({@link TransactionSynchronization#afterCommit}
 * 문서). 그래서 미반영 행을 지우고 실패를 적는 일은 {@code REQUIRES_NEW} 로 한다.
 */
@Slf4j
@Component
public class TrackSectionSync {

    static final AnalyticsSyncKind KIND = AnalyticsSyncKind.CLICKHOUSE;

    /** {@code last_error} 컬럼 길이. 넘기면 실패를 적다가 또 실패한다. */
    private static final int MAX_ERROR_LENGTH = 500;

    private final AnalyticsSyncPendingRepository pendingRepository;
    private final MusicSectionAnalysisRepository sectionRepository;
    private final TrackSectionRepository trackSectionRepository;
    private final TransactionTemplate newTransaction;

    public TrackSectionSync(AnalyticsSyncPendingRepository pendingRepository,
                            MusicSectionAnalysisRepository sectionRepository,
                            TrackSectionRepository trackSectionRepository,
                            PlatformTransactionManager transactionManager) {
        this.pendingRepository = pendingRepository;
        this.sectionRepository = sectionRepository;
        this.trackSectionRepository = trackSectionRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 재시도 한 곡의 결과. */
    public enum RetryOutcome {
        /** ClickHouse 에 썼고 미반영 행을 지웠다. */
        SYNCED,
        /** 다른 인스턴스가 잡고 있거나 이미 누가 처리했다. */
        SKIPPED,
        /** MySQL 에 구간이 없다. 교체로는 지울 수 없어 행만 치웠다 — 서빙에서는 곡 특징이 가린다. */
        NOTHING_TO_WRITE,
        /** ClickHouse 쓰기가 실패해 실패를 적었다. */
        FAILED
    }

    /**
     * 구간을 바꾸는 트랜잭션 <b>안에서</b> 부른다. 곡을 미반영으로 올리고 그 revision 을 준다.
     *
     * @return 이 트랜잭션이 남긴 revision. 커밋 뒤 쓰기가 성공했을 때 이 값일 때만 지운다
     */
    public long markPending(int musicId, String analysisVersion) {
        pendingRepository.upsert(musicId, KIND.name(), analysisVersion);
        // 바로 위 upsert 가 행을 잠갔으므로 커밋 전까지 다른 쪽이 못 바꾼다.
        return pendingRepository.findRevision(musicId, KIND.name()).orElseThrow();
    }

    /**
     * 구간 교체 한 번: 미반영으로 올리고, 커밋되면 방금 넣은 구간을 ClickHouse 에 쓴다.
     *
     * <p>{@code rows} 는 flush 로 번호가 채워진 뒤의 MySQL 행이다. 커밋 뒤에 다시 읽지 않고
     * 이 스냅숏을 쓴다 — 두 요청이 겹쳐 스냅숏이 옛것이 되더라도, 뒤 요청이 revision 을
     * 올려 두었으므로 앞 요청의 성공이 그 행을 지우지 못하고 재시도가 지금 구간으로 덮는다.
     */
    public void recordAndPushAfterCommit(int musicId, String analysisVersion,
                                         List<MusicSectionAnalysis> rows) {
        List<TrackSectionRow> sections = toRows(musicId, rows);

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // 트랜잭션 밖에서 직접 불린 경우. 기록을 자기 트랜잭션으로 남기고, 기다릴 커밋이
            // 없으므로 바로 넣는다.
            Long own = newTransaction.execute(status -> markPending(musicId, analysisVersion));
            pushAfterCommit(musicId, analysisVersion, sections, own);
            return;
        }
        long revision = markPending(musicId, analysisVersion);
        // 커밋 **뒤에** 넣는다. 트랜잭션 안에서 넣으면 뒤따르는 MySQL 작업이 실패해 롤백됐을
        // 때 ClickHouse 에만 구간이 남는다. ClickHouse 에는 되돌릴 트랜잭션이 없다.
        // 커밋 뒤 실행이라 @Transactional 시험에서는 돌지 않는다(매번 롤백하므로) —
        // 이 경로의 판단은 TrackSectionSyncTest 가 직접 부른다.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                pushAfterCommit(musicId, analysisVersion, sections, revision);
            }
        });
    }

    /**
     * 커밋 뒤 즉시 쓰기. 성공하면 자기 revision 의 미반영 행을 지우고, 실패하면 실패를 적는다.
     * 어느 쪽이든 예외를 던지지 않는다 — 이미 커밋된 요청을 실패로 돌려줄 수는 없다.
     *
     * @return ClickHouse 에 썼는가
     */
    public boolean pushAfterCommit(int musicId, String analysisVersion,
                                   List<TrackSectionRow> sections, long revision) {
        try {
            trackSectionRepository.replaceSections(musicId, analysisVersion, sections);
        } catch (RuntimeException e) {
            // 곡 번호와 구간 수를 남긴다. 미반영 행이 있으니 재시도가 메우지만, 계속
            // 실패하면 사람이 볼 근거가 이 로그와 last_error 다.
            log.error("ClickHouse track_sections 적재 실패 — musicId={} version={} 구간 {}건. "
                + "MySQL 은 적재됐고 미반영 기록이 남았다. 재시도 작업이 1분 안에 다시 쓴다.",
                musicId, analysisVersion, sections.size(), e);
            recordFailure(musicId, e);
            return false;
        }
        try {
            newTransaction.executeWithoutResult(status ->
                pendingRepository.deleteIfRevision(musicId, KIND.name(), revision));
        } catch (RuntimeException e) {
            // 지우지 못해도 틀리지 않는다 — 재시도가 같은 구간을 한 번 더 쓸 뿐이다.
            log.warn("미반영 기록을 지우지 못했다. 재시도가 한 번 더 쓴다. musicId={}", musicId, e);
        }
        return true;
    }

    /**
     * 미반영 곡 하나를 다시 쓴다. <b>자기 트랜잭션에서</b> 행을 잠그고, MySQL 의 지금 구간을
     * 읽어 교체한 뒤 행을 지운다.
     *
     * <p>잠금은 {@code FOR UPDATE SKIP LOCKED} 다. 인스턴스가 여럿이어도 한 곡을 동시에 두 번
     * 쓰지 않고, 잡힌 곡은 기다리지 않고 건너뛴다. 스케줄러는 인스턴스마다 도는데
     * ({@code ApiApplication}) Redis 선점 대신 행 잠금을 쓰는 것은 일을 <b>나눠 가지게</b> 하려는
     * 것이다 — 선점은 한 인스턴스만 돌게 할 뿐이다.
     */
    public RetryOutcome retry(int musicId) {
        return newTransaction.execute(status -> {
            Optional<Long> claimed = pendingRepository.claim(musicId, KIND.name());
            if (claimed.isEmpty()) {
                return RetryOutcome.SKIPPED;
            }

            List<MusicSectionAnalysis> current =
                sectionRepository.findByMusic_MusicIdOrderByIndexAsc(musicId);
            if (current.isEmpty()) {
                // 구간을 모두 지운 곡(관리자 화면). 교체는 빈 목록을 받으면 아무것도 하지 않아
                // ClickHouse 쪽 행이 남는다. 서빙에는 안 보인다 — 그 곡의 track_features 버전이
                // 빈 문자열이라 v_serving_sections 가 고르지 않는다 (S15P21A204-455).
                pendingRepository.deletePending(musicId, KIND.name());
                log.warn("미반영 곡에 MySQL 구간이 없어 기록만 치웠다. ClickHouse 쪽은 그대로다. "
                    + "musicId={}", musicId);
                return RetryOutcome.NOTHING_TO_WRITE;
            }

            String version = current.get(0).getAnalysisVersion();
            try {
                trackSectionRepository.replaceSections(musicId, version, toRows(musicId, current));
            } catch (RuntimeException e) {
                // ClickHouse 쪽 예외라 JPA 트랜잭션은 멀쩡하다. 실패를 적고 커밋한다.
                pendingRepository.markFailed(musicId, KIND.name(), describe(e));
                log.warn("미반영 곡 재시도 실패 musicId={} version={}: {}",
                    musicId, version, describe(e));
                return RetryOutcome.FAILED;
            }
            pendingRepository.deletePending(musicId, KIND.name());
            return RetryOutcome.SYNCED;
        });
    }

    /**
     * 대조 작업이 어긋난 곡을 미반영으로 올린다. 자기 트랜잭션이다.
     */
    public void enqueue(int musicId, String analysisVersion) {
        newTransaction.executeWithoutResult(status ->
            pendingRepository.upsert(musicId, KIND.name(), analysisVersion));
    }

    /**
     * MySQL 구간 행 → ClickHouse 행. 버전은 행마다 자기 것을 싣는다.
     */
    static List<TrackSectionRow> toRows(int musicId, List<MusicSectionAnalysis> rows) {
        return rows.stream()
            .map(row -> new TrackSectionRow(
                musicId,
                row.getMusicSectionAnalysisId(),
                row.getAnalysisVersion(),
                row.getIndex(),
                row.getExternalId(),
                row.getSectionLabel().name(),
                row.getStartTimeMs(),
                row.getEndTimeMs(),
                row.getEnergy()))
            .toList();
    }

    private void recordFailure(int musicId, RuntimeException cause) {
        try {
            newTransaction.executeWithoutResult(status ->
                pendingRepository.markFailed(musicId, KIND.name(), describe(cause)));
        } catch (RuntimeException e) {
            // MySQL 까지 안 닿는 경우. 미반영 행은 이미 커밋돼 있으니 사유만 비어 있을 뿐이다.
            log.warn("미반영 실패 사유를 적지 못했다. musicId={}", musicId, e);
        }
    }

    /** 가장 안쪽 원인. JDBC 예외는 겉이 {@code DataAccessException} 이라 그것만으로는 모른다. */
    static String describe(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String text = "%s: %s".formatted(cause.getClass().getSimpleName(), cause.getMessage());
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

}
