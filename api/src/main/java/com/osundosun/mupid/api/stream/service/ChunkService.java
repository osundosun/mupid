package com.osundosun.mupid.api.stream.service;

import java.util.List;

import com.osundosun.mupid.api.stream.dto.ChunkStorageUrl;
import com.osundosun.mupid.api.stream.entity.Chunk;
import com.osundosun.mupid.api.stream.request.ChunkCommitRequest;

public interface ChunkService {

    Chunk getChunk(int streamId, int index);

    List<Chunk> getChunkAll(int streamId);

    ChunkStorageUrl getStorageUrl(int streamId, int index);

    /**
     * 업로드를 허가하고 저장 경로를 알려준다. **바이트는 받지 않는다.**
     *
     * nginx 의 `auth_request` 가 이 결과를 받아 본문을 스토리지로 직접 흘려보낸다.
     * 큰 음원 파일이 JVM 힙을 거치지 않게 하려는 것이다.
     *
     * @param contentLength 클라이언트가 선언한 본문 길이. 모르면 null
     * @return 스토리지 업스트림 기준 오브젝트 경로 (예: {@code /buckets/mupid/music/1/opus128/chunk_0000.rtp})
     */
    String prepareUpload(int streamId, int index, Long contentLength);

    /**
     * 스토리지에 올라간 청크의 메타데이터를 확정한다.
     * 오브젝트가 실제로 있고 크기가 맞을 때만 기록한다.
     */
    Chunk commitChunk(int streamId, int index, ChunkCommitRequest request);
    
}
