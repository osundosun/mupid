package com.osundosun.mupid.api.analytics.sync.service;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.osundosun.mupid.api.analytics.sync.enumerate.AnalyticsSyncKind;
import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * "구간은 있는데 벡터가 아직 없다" 를 미반영 기록({@code kind = VECTOR})으로 든다 (S15P21A204-457,
 * 설계 §6.6·§8.2-1·§10.1-14).
 *
 * <h2>ClickHouse 종류와 다른 점 — Spring 이 다시 쓰지 않는다</h2>
 *
 * <p>ClickHouse 사본은 MySQL 에서 다시 만들 수 있어 재시도 작업이 메운다. 벡터는 오디오에서 만든
 * 파생 데이터라 원본이 배치(JupyterHub 전체 색인)에 있고, Spring 은 벡터를 보관하지 않는다. 그래서
 * 이 행은 <b>배치에게 보내는 할 일 목록</b>이다 — {@code GET /api/v1/system/vectors/pending} 으로 읽고
 * 곡마다 {@code PUT /api/v1/music/{id}/vectors} 로 다시 보낸다. 재시도 작업은 이 종류를 돌지 않는다.
 *
 * <h2>언제 올리고 언제 지우나</h2>
 *
 * <ul>
 *   <li><b>올린다</b> — 구간을 바꾸는 트랜잭션 안에서({@link #markPending}). 구간이 바뀌면 그 분석 버전의
 *       벡터가 새로 필요하다: 곡 전체 교체(요구 1), 관리자 화면의 구간 CRUD(경계·라벨 — 라벨은 {@code X}
 *       원핫에 들어가 {@code feat_v1}·{@code stage1} 을 다시 계산해야 한다, 설계 §7.5). 대조가 Qdrant 와
 *       어긋난 곡을 찾았을 때도({@link #enqueue}).</li>
 *   <li><b>지운다</b> — 벡터 등록이 Qdrant 에 쓴 뒤, 검증 때 읽은 revision 일 때만({@link #clear}). 그 사이
 *       구간이 다시 바뀌었으면 행은 뒤의 변경 몫이다 — {@code TrackSectionSync} "왜 revision 인가" 와 같다.</li>
 * </ul>
 */
@Slf4j
@Component
public class VectorSync {

    public static final AnalyticsSyncKind KIND = AnalyticsSyncKind.VECTOR;

    private final AnalyticsSyncPendingRepository pendingRepository;
    private final TransactionTemplate newTransaction;

    public VectorSync(AnalyticsSyncPendingRepository pendingRepository,
                      PlatformTransactionManager transactionManager) {
        this.pendingRepository = pendingRepository;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 구간을 바꾸는 트랜잭션 <b>안에서</b> 부른다. 커밋과 한 몸이다. */
    public void markPending(int musicId, String analysisVersion) {
        pendingRepository.upsert(musicId, KIND.name(), analysisVersion);
    }

    /** 지금의 revision. 벡터 등록이 검증할 때 읽어 두고, 쓴 뒤 {@link #clear} 에 준다. */
    public Optional<Long> revision(int musicId) {
        return pendingRepository.findRevision(musicId, KIND.name());
    }

    /**
     * 벡터를 쓴 뒤 부른다. 자기 트랜잭션이다. 행이 없었으면({@code revision} 이 비었으면) 할 일이 없다 —
     * 이미 있는 곡을 다시 보낸 것이다. 지우지 못해도 틀리지 않는다 — 다음 대조가 Qdrant 를 보고 치운다.
     */
    public void clear(int musicId, Optional<Long> revision) {
        if (revision.isEmpty()) {
            return;
        }
        try {
            newTransaction.executeWithoutResult(status ->
                pendingRepository.deleteIfRevision(musicId, KIND.name(), revision.get()));
        } catch (RuntimeException e) {
            log.warn("벡터 미반영 기록을 지우지 못했다. 배치가 한 번 더 보낼 뿐이다. musicId={}", musicId, e);
        }
    }

    /** Qdrant 쓰기가 실패했을 때 사유를 남긴다(행이 있을 때만). 관리자 화면에서 "왜 안 가지" 에 답한다. */
    public void recordFailure(int musicId, String reason) {
        try {
            newTransaction.executeWithoutResult(status ->
                pendingRepository.markFailed(musicId, KIND.name(), reason));
        } catch (RuntimeException e) {
            log.warn("벡터 미반영 실패 사유를 적지 못했다. musicId={}", musicId, e);
        }
    }

    /** 대조가 어긋난 곡을 넣는다. 자기 트랜잭션이다. */
    public void enqueue(int musicId, String analysisVersion) {
        newTransaction.executeWithoutResult(status ->
            pendingRepository.upsert(musicId, KIND.name(), analysisVersion));
    }

}
