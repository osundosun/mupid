package com.osundosun.mupid.api.vector.service;

import com.osundosun.mupid.api.vector.request.MusicVectorsRequest;
import com.osundosun.mupid.api.vector.request.Stage1VectorsRequest;
import com.osundosun.mupid.api.vector.response.MusicVectorsResponse;
import com.osundosun.mupid.api.vector.response.Stage1StatusResponse;
import com.osundosun.mupid.api.vector.response.Stage1VectorsResponse;
import com.osundosun.mupid.api.vector.response.VectorPendingResponse;

/**
 * 벡터 등록 (S15P21A204-457, 설계 §6.6). Qdrant 에 쓰는 길은 이 서비스 하나다(원칙 1).
 */
public interface VectorService {

    /** 곡 하나의 모든 벡터를 검증하고 Qdrant 에 쓴 뒤 돌려준다. 그 곡의 옛 포인트 중 이번에 없는 것은 지운다. */
    MusicVectorsResponse replaceMusicVectors(int musicId, MusicVectorsRequest request);

    /** 여러 구간에 {@code stage1_v{version}} 만 덧붙인다. 다른 벡터·payload 는 그대로다. */
    Stage1VectorsResponse putStage1(String version, Stage1VectorsRequest request);

    /** 재계산 관문 — 전 구간에 채워졌는가. */
    Stage1StatusResponse stage1Status(String version);

    /** 벡터가 아직 없는 곡 — 배치가 다시 보낼 목록. */
    VectorPendingResponse pending(int after, int limit);

}
