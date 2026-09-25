package com.osundosun.mupid.api.vector.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 벡터가 아직(또는 실패로) 없는 곡 — 배치가 다시 보낼 목록 (S15P21A204-457, 설계 §6.6).
 *
 * @param items 곡 번호 순
 * @param next  다음 쪽의 {@code after}. 끝이면 null
 */
public record VectorPendingResponse(List<Item> items, Integer next) {

    /**
     * @param musicId         곡 번호
     * @param analysisVersion MySQL 이 기다리는 분석 버전 — 이 버전의 벡터를 보내야 지워진다
     * @param attempts        Qdrant 쓰기가 실패한 횟수
     * @param lastError       마지막 실패 사유
     * @param createdAt       처음 미반영이 된 시각
     */
    public record Item(int musicId, String analysisVersion, int attempts, String lastError,
                       LocalDateTime createdAt) {
    }

}
