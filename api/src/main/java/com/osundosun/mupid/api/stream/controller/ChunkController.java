package com.osundosun.mupid.api.stream.controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.stream.dto.ChunkResponseItem;
import com.osundosun.mupid.api.stream.dto.ChunkStorageUrl;
import com.osundosun.mupid.api.stream.entity.Chunk;
import com.osundosun.mupid.api.stream.response.ChunkGetResponse;
import com.osundosun.mupid.api.stream.service.ChunkService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/chunk/{streamId}")
@RequiredArgsConstructor
public class ChunkController {

    private final ChunkService chunkService;

    @GetMapping
    public ResponseEntity<BaseResponse<ChunkGetResponse>> getChunks(
        @PathVariable int streamId
    ) {
        List<Chunk> chunks = chunkService.getChunkAll(streamId);
        ChunkGetResponse response = new ChunkGetResponse(chunks.stream()
            .sorted((a, b) -> Integer.compare(a.getIndex(), b.getIndex())) /* 청크 순서별로 오름차순 정렬 */
            .map(it -> new ChunkResponseItem(
                it.getIndex(), /* 청크 순서 */
                it.getDurationMs(), /* 청크 길이 */
                it.getFileHash())) /* 파일 해시 */
            .toList());
        return ResponseEntity.ok(BaseResponse.of(response));
    }

    @GetMapping("/{index}")
    public ResponseEntity<BaseResponse<Void>> getChunkData(
        @PathVariable int streamId,
        @PathVariable int index
    ) {
        ChunkStorageUrl storageUrl = chunkService.getStorageUrl(streamId, index);
        /**
         * X-Accel-Redirect 헤더가 반환되면 바로 클라이언트로 돌아가지 않고 Nginx에서 해당 URL로 리다이렉트한
         * 결과를 받아서 클라이언트에게 데이터를 보내주게 된다.
         * 
         * SpringBoot는 인증과 스토리지 키를 조회하는 역할이며 실제 데이터는 오브젝트 스토리지에서 반환하는 흐름이다.
         */
        return ResponseEntity.ok()
                .header("X-Accel-Redirect", storageUrl.url())
                .header(HttpHeaders.CONTENT_TYPE, storageUrl.contentType())
                .build();
    }
    
}
