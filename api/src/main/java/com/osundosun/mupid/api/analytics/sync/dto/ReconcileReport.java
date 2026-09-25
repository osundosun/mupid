package com.osundosun.mupid.api.analytics.sync.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

/**
 * 대조 한 번의 결과 (S15P21A204-451, 설계 §8.2-4).
 *
 * @param finishedAt         끝난 시각 (KST)
 * @param checkedTracks      MySQL 에 구간이 있는 곡 수 — 검사한 곡
 * @param mismatchedTracks   한 레플리카에서라도 어긋난 곡. 미반영 기록에 넣는다
 * @param mismatchByReplica  레플리카마다 어긋난 곡 수
 * @param onlyInClickHouse   MySQL 에 구간이 하나도 없는데 ClickHouse 에는 있는 곡 수.
 *                           교체로는 고칠 수 없어(빈 목록은 쓰지 않는다) 세기만 한다 — 455 의 몫
 * @param staleVersions      MySQL 과 다른 옛 분석 버전 행이 ClickHouse 에 남은 곡 수. 불일치로
 *                           세지 않는다 — 딕셔너리는 현재 버전만 본다
 * @param error              대조 자체가 실패했으면 사유. 성공이면 null
 */
public record ReconcileReport(
    LocalDateTime finishedAt,
    int checkedTracks,
    Set<TrackVersion> mismatchedTracks,
    Map<String, Integer> mismatchByReplica,
    int onlyInClickHouse,
    int staleVersions,
    String error
) {

    public static ReconcileReport failed(LocalDateTime finishedAt, String error) {
        return new ReconcileReport(finishedAt, 0, Set.of(), Map.of(), 0, 0, error);
    }

    public boolean succeeded() {
        return error == null;
    }

    /** 지표 "마지막 대조의 불일치 수". 곡 수다(레플리카 둘 다 어긋나도 한 곡). */
    public int mismatchCount() {
        return mismatchedTracks.size();
    }

}
