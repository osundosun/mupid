package com.osundosun.mupid.api.stream.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.stream.entity.Chunk;
import com.osundosun.mupid.api.stream.response.ChunkGetResponse;
import com.osundosun.mupid.api.stream.response.ChunkGetRangeResponse;
import com.osundosun.mupid.api.stream.service.ChunkService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/chunk/{streamId}")
@RequiredArgsConstructor
public class ChunkController {

    private final ChunkService chunkService;

    @GetMapping("/{index}")
    public ResponseEntity<BaseResponse<ChunkGetResponse>> getChunk(
        @PathVariable int streamId,
        @PathVariable int index
    ) {
        Chunk chunk = chunkService.getChunk(streamId, index);
        ChunkGetResponse response = new ChunkGetResponse(
            chunk.getIndex(), /* 청크 순서 */
            chunk.getDurationMs(), /* 청크 길이 */
            chunkService.getChunkUrl(chunk.getChunkId()), /* 파일 URL */
            chunk.getFileHash() /* 파일 해시 */
        );
        return ResponseEntity.ok(BaseResponse.of(response));
    }

    @GetMapping
    public ResponseEntity<BaseResponse<ChunkGetRangeResponse>> getChunkRange(
        @PathVariable int streamId,
        @RequestParam Integer from,
        @RequestParam Integer to
    ) {
        List<Chunk> chunks = chunkService.getChunkRange(streamId, from, to);
        ChunkGetRangeResponse response = new ChunkGetRangeResponse(
            chunks
                .stream()
                .map(it -> new ChunkGetResponse(
                    it.getIndex(), /* 청크 순서 */
                    it.getDurationMs(), /* 청크 길이 */
                    chunkService.getChunkUrl(it.getChunkId()), /* 파일 URL */
                    it.getFileHash() /* 파일 해시 */
                ))
                .toList()
        );
        return ResponseEntity.ok(BaseResponse.of(response));
    }

}
