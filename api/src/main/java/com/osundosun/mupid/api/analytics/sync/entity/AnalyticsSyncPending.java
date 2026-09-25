package com.osundosun.mupid.api.analytics.sync.entity;

import java.time.LocalDateTime;

import com.osundosun.mupid.api.analytics.sync.enumerate.AnalyticsSyncKind;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 사본에 아직 반영되지 않은 곡 (S15P21A204-451, 저장소 재설계 §8.2-1).
 *
 * <p><b>구간 교체와 같은 트랜잭션에서 남긴다.</b> 커밋과 한 몸이라 "MySQL 에는 들어갔는데
 * 기록은 빠졌다" 가 생기지 않는다. 커밋 뒤 ClickHouse 쓰기가 성공하면 지우고, 실패하면
 * 남은 행을 재시도 작업이 가져간다. 곡·종류마다 한 행이고, 같은 곡이 다시 오면 갱신한다.
 *
 * <p>쓰기는 이 엔티티로 하지 않는다 — 올리기·가져가기가 {@code ON DUPLICATE KEY UPDATE}·
 * {@code FOR UPDATE SKIP LOCKED} 라 네이티브 문장이다
 * ({@code AnalyticsSyncPendingRepository}). 엔티티는 관리자 화면·schema-check 가 이 표를
 * 알게 하려고 둔다.
 */
@Entity
@Table(name = "analytics_sync_pending")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AnalyticsSyncPending {

    @EmbeddedId
    private AnalyticsSyncPendingId id;

    /** 반영할 분석 버전. 구간 교체 요청이 실어 온 값이다. */
    @Column(nullable = false, length = 20)
    private String analysisVersion;

    /**
     * 같은 곡이 다시 기록될 때마다 1 씩 오른다.
     *
     * <p>커밋 뒤 쓰기는 <b>자기가 남긴 값일 때만</b> 행을 지운다. 같은 곡의 두 요청이 겹쳐
     * 앞의 것이 성공하고 뒤의 것이 실패했을 때, 앞의 성공이 뒤의 미반영을 지우면 그 곡이
     * 조용히 빠진다.
     */
    @Column(nullable = false)
    private Long revision = 1L;

    /** 재시도 작업이 시도한 횟수. 성공하면 행째 사라지므로 남아 있는 값은 실패 횟수다. */
    @Column(nullable = false)
    private Integer attempts = 0;

    /** 마지막 실패 사유. 관리자 화면에서 "왜 안 가지" 에 답한다. */
    @Column(length = 500)
    private String lastError;

    /**
     * <b>처음</b> 미반영이 된 시각. 다시 기록돼도 그대로다 — "가장 오래된 미반영의 나이" 가
     * 이것을 본다. 같은 곡이 계속 다시 올라온다고 나이가 0 으로 돌아가면 밀린 것이 안 보인다.
     */
    @Column(nullable = false, insertable = false, updatable = false)
    private LocalDateTime createdAt;

    /** 마지막으로 기록·시도한 시각. 재시도 작업이 이 순서로 가져간다. */
    @Column(nullable = false, insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    @Embeddable
    @Getter
    @AllArgsConstructor
    @NoArgsConstructor
    @EqualsAndHashCode
    public static class AnalyticsSyncPendingId {

        /**
         * 곡 번호. FK 를 걸지 않는다 — 곡은 논리 삭제라 FK 가 지켜 줄 것이 없고, 뒤에 붙을
         * 벡터 종류는 곡이 지워진 뒤에도 "지워라" 를 들고 있어야 한다.
         */
        @Column(name = "music_id")
        private Integer musicId;

        @Enumerated(EnumType.STRING)
        @Column(name = "kind", length = 16)
        private AnalyticsSyncKind kind;

    }

}
