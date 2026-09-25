package com.osundosun.mupid.api.analytics.sync.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.osundosun.mupid.api.analytics.dto.TrackFeatureRow;
import com.osundosun.mupid.api.analytics.repository.TrackFeatureRepository;
import com.osundosun.mupid.api.analytics.sync.enumerate.AnalyticsSyncKind;
import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;
import com.osundosun.mupid.api.analytics.sync.repository.TrackFeatureSource;
import com.osundosun.mupid.api.analytics.sync.service.TrackSectionSync.RetryOutcome;

import lombok.extern.slf4j.Slf4j;

/**
 * MySQL 곡 특징을 ClickHouse {@code track_features_local} 로 옮기는 <b>유일한 길</b>
 * (S15P21A204-455, 설계 §6.2·§7.2·§7.4·§8).
 *
 * <p>{@link TrackSectionSync} 와 같은 세 장치다 — 미반영 기록({@link #markChanged}, 값을 바꾸는
 * 트랜잭션 <b>안에서</b>), 커밋 뒤 즉시 쓰기, 재시도({@link #retry}). 대조는
 * {@code TrackFeatureReconciler}. 종류는 {@link AnalyticsSyncKind#TRACK_FEATURES} 로 구간과 나눈다
 * (나눈 이유는 {@link AnalyticsSyncKind}).
 *
 * <h2>구간과 다른 것 — 스냅숏을 들고 다니지 않는다</h2>
 *
 * <p>곡 특징은 곡·분석·장르·무드·스트림·구간에 걸쳐 있어 값을 바꾸는 요청 하나가 전부를 알지
 * 못한다. 그래서 커밋 뒤에 <b>MySQL 을 다시 읽어</b> 쓴다({@link TrackFeatureSource}). 곡 하나에
 * 작은 SELECT 여섯 번이다. 대신 겹친 두 요청의 순서가 뒤집혀도 늦게 쓴 쪽이 언제나 지금 값이다.
 *
 * <h2>한 트랜잭션에 한 번</h2>
 *
 * <p>관리자 화면이 장르 행 셋을 한 요청에서 고치거나, 구간 교체가 특징까지 올리는 식으로 같은
 * 트랜잭션에서 같은 곡이 여러 번 올라온다. 미반영 기록은 매번 올리지만(revision 이 오를 뿐이다)
 * 커밋 뒤 쓰기는 곡마다 한 번, 트랜잭션에 한 번의 INSERT 로 묶는다.
 *
 * <h2>ClickHouse 가 죽어도 MySQL 쓰기는 성공한다</h2>
 *
 * <p>여기서 던지는 것은 미반영 기록(MySQL) 실패뿐이다. ClickHouse 쓰기는 커밋 뒤라 예외를 삼키고
 * 기록을 남긴다 — 곡 삭제가 분석 DB 때문에 실패하지 않는다.
 */
@Slf4j
@Component
public class TrackFeatureSync {

    static final AnalyticsSyncKind KIND = AnalyticsSyncKind.TRACK_FEATURES;

    /**
     * 미반영 행의 {@code analysis_version}. 곡 특징은 쓰는 순간 MySQL 에서 버전을 읽으므로 기록에
     * 실을 값이 없다 — 컬럼이 NOT NULL 이라 빈 문자열을 둔다.
     */
    static final String NO_VERSION = "";

    /** 트랜잭션에 묶어 두는 자원 열쇠. 곡 번호 → 이 트랜잭션이 남긴 마지막 revision. */
    private static final Object TX_KEY = new Object();

    private final AnalyticsSyncPendingRepository pendingRepository;
    private final TrackFeatureSource source;
    private final TrackFeatureRepository clickHouse;
    private final TransactionTemplate newTransaction;

    public TrackFeatureSync(AnalyticsSyncPendingRepository pendingRepository,
                            TrackFeatureSource source,
                            TrackFeatureRepository clickHouse,
                            PlatformTransactionManager transactionManager) {
        this.pendingRepository = pendingRepository;
        this.source = source;
        this.clickHouse = clickHouse;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 곡 특징이 바뀌는 트랜잭션 <b>안에서</b> 부른다. 미반영으로 올리고, 커밋되면 그 곡의 지금
     * 특징을 ClickHouse 에 쓴다. 트랜잭션 밖이면 기록을 자기 트랜잭션으로 남기고 바로 쓴다.
     */
    public void markChanged(int musicId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            Long own = newTransaction.execute(status -> markPending(musicId));
            // 기록을 못 읽었으면 0 — 어떤 revision 과도 맞지 않아 행이 남고 재시도가 쓴다.
            pushAfterCommit(Map.of(musicId, own == null ? 0L : own));
            return;
        }
        long revision = markPending(musicId);
        @SuppressWarnings("unchecked")
        Map<Integer, Long> changed = (Map<Integer, Long>)TransactionSynchronizationManager.getResource(TX_KEY);
        if (changed == null) {
            Map<Integer, Long> fresh = new LinkedHashMap<>();
            TransactionSynchronizationManager.bindResource(TX_KEY, fresh);
            // 커밋 **뒤에** 쓴다. 트랜잭션 안에서 쓰면 롤백됐을 때 ClickHouse 에만 값이 남는다.
            // 커밋 뒤 실행이라 @Transactional 시험에서는 돌지 않는다 — TrackFeatureSyncTest 가
            // pushAfterCommit 을 직접 부른다.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pushAfterCommit(fresh);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(TX_KEY);
                }
            });
            changed = fresh;
        }
        changed.put(musicId, revision);
    }

    long markPending(int musicId) {
        pendingRepository.upsert(musicId, KIND.name(), NO_VERSION);
        return pendingRepository.findRevision(musicId, KIND.name()).orElseThrow();
    }

    /**
     * 커밋 뒤 즉시 쓰기. MySQL 의 지금 특징을 읽어 한 번에 넣고, 성공하면 곡마다 <b>자기
     * revision 일 때만</b> 미반영 행을 지운다. 실패하면 실패를 적고 행을 남긴다. 예외를 던지지 않는다.
     *
     * @return ClickHouse 에 썼는가
     */
    public boolean pushAfterCommit(Map<Integer, Long> revisions) {
        try {
            // 여섯 번의 SELECT 가 한 시점을 보게 새 트랜잭션에서 읽는다(REPEATABLE READ).
            Map<Integer, TrackFeatureRow> rows =
                newTransaction.execute(status -> source.load(revisions.keySet()));
            clickHouse.replace(rows == null ? List.of() : List.copyOf(rows.values()));
        } catch (RuntimeException e) {
            log.error("ClickHouse track_features 적재 실패 — 곡 {}. MySQL 은 커밋됐고 미반영 기록이 "
                + "남았다. 재시도 작업이 1분 안에 다시 쓴다.", revisions.keySet(), e);
            revisions.keySet().forEach(musicId -> recordFailure(musicId, e));
            return false;
        }
        for (Map.Entry<Integer, Long> entry : revisions.entrySet()) {
            try {
                newTransaction.executeWithoutResult(status ->
                    pendingRepository.deleteIfRevision(entry.getKey(), KIND.name(), entry.getValue()));
            } catch (RuntimeException e) {
                log.warn("미반영 기록을 지우지 못했다. 재시도가 한 번 더 쓴다. musicId={}", entry.getKey(), e);
            }
        }
        return true;
    }

    /**
     * 미반영 곡 하나를 다시 쓴다. {@link TrackSectionSync#retry} 와 같은 잠금
     * ({@code FOR UPDATE SKIP LOCKED}) 아래에서 MySQL 의 지금 특징을 읽어 넣는다. 멱등이다.
     */
    public RetryOutcome retry(int musicId) {
        return newTransaction.execute(status -> {
            Optional<Long> claimed = pendingRepository.claim(musicId, KIND.name());
            if (claimed.isEmpty()) {
                return RetryOutcome.SKIPPED;
            }
            TrackFeatureRow row = source.load(musicId);
            if (row == null) {
                // MySQL 에 곡 행이 없다. 곡은 논리 삭제라 보통은 없는 일이고, 넣을 값이 없다.
                pendingRepository.deletePending(musicId, KIND.name());
                log.warn("미반영 곡이 MySQL 에 없어 기록만 치웠다. musicId={}", musicId);
                return RetryOutcome.NOTHING_TO_WRITE;
            }
            try {
                clickHouse.replace(List.of(row));
            } catch (RuntimeException e) {
                pendingRepository.markFailed(musicId, KIND.name(), TrackSectionSync.describe(e));
                log.warn("미반영 곡 특징 재시도 실패 musicId={}: {}", musicId, TrackSectionSync.describe(e));
                return RetryOutcome.FAILED;
            }
            pendingRepository.deletePending(musicId, KIND.name());
            return RetryOutcome.SYNCED;
        });
    }

    /** 대조 작업이 어긋난 곡을 미반영으로 올린다. 자기 트랜잭션이다. */
    public void enqueue(int musicId) {
        newTransaction.executeWithoutResult(status ->
            pendingRepository.upsert(musicId, KIND.name(), NO_VERSION));
    }

    private void recordFailure(int musicId, RuntimeException cause) {
        try {
            newTransaction.executeWithoutResult(status ->
                pendingRepository.markFailed(musicId, KIND.name(), TrackSectionSync.describe(cause)));
        } catch (RuntimeException e) {
            log.warn("미반영 실패 사유를 적지 못했다. musicId={}", musicId, e);
        }
    }

}
