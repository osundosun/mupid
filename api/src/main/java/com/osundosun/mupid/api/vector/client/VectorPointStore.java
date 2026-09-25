package com.osundosun.mupid.api.vector.client;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Qdrant 컬렉션 하나에 대한 쓰기·조회 (S15P21A204-457).
 *
 * <p>벡터 서비스가 아는 것은 이 인터페이스뿐이다. 운영은 {@link QdrantRestClient}(REST), 시험은
 * 메모리 판({@code InMemoryVectorPointStore}) — 판단(검증·교체·덧붙이기)을 Qdrant 없이 확인하고,
 * REST 본문의 모양은 로컬 Qdrant 왕복으로 따로 확인한다.
 *
 * <p>실패는 {@code BusinessException(VectorErrorCode…)} 로 던진다. <b>삼키지 않는다</b> —
 * "Qdrant 에 쓴 뒤 응답한다"(설계 §6.6)가 이 계약이다.
 */
public interface VectorPointStore {

    /** 포인트 하나. {@code vectors} 는 이름 → float32 벡터, {@code payload} 는 정의 파일의 필드. */
    record Point(UUID id, Map<String, float[]> vectors, Map<String, Object> payload) {
    }

    /** 포인트 하나의 payload. 대조가 읽는다. */
    record StoredPoint(UUID id, Map<String, Object> payload) {
    }

    /** 스크롤 한 쪽. {@code next} 가 null 이면 끝이다. */
    record Page(List<StoredPoint> points, Object next) {
    }

    /** 컬렉션의 named vector 이름들. 컬렉션이 없으면 {@code VECTOR_COLLECTION_MISSING}. */
    Set<String> vectorNames();

    /** named vector 를 덧붙인다. 이미 있는 포인트는 그 벡터가 빈 채로 남는다. */
    void addVector(String name, Map<String, Object> params);

    /** 포인트를 통째로 쓴다(같은 id 면 모든 벡터·payload 를 갈아 끼운다). {@code wait=true}. */
    void upsert(List<Point> points);

    /** 그 곡({@code track_id})의 포인트 중 {@code keep} 에 없는 것을 지운다. */
    void deleteTrackPointsExcept(int trackId, Collection<UUID> keep);

    /** 곡들의 포인트를 모두 지운다. 대조가 삭제된 곡에 쓴다(설계 §7.4). */
    void deleteTracks(Collection<Integer> trackIds);

    /** 있는 포인트의 벡터 몇 개만 바꾼다 — 다른 벡터·payload 는 그대로다. {@code wait=true}. */
    void updateVectors(Map<UUID, Map<String, float[]>> vectors);

    /** 이 가운데 컬렉션에 있는 id. */
    Set<UUID> existing(Collection<UUID> ids);

    /** 그 벡터를 가진 포인트 수(정확히 센다). */
    long countWithVector(String name);

    /** payload 를 한 쪽씩 읽는다. 벡터는 읽지 않는다. */
    Page scroll(Object offset, int limit, List<String> payloadFields);

}
