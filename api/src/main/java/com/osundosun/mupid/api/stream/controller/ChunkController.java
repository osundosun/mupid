package com.osundosun.mupid.api.stream.controller;

import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.stream.dto.ChunkResponseItem;
import com.osundosun.mupid.api.stream.dto.ChunkStorageUrl;
import com.osundosun.mupid.api.stream.entity.Chunk;
import com.osundosun.mupid.api.stream.request.ChunkCommitRequest;
import com.osundosun.mupid.api.stream.response.ChunkGetResponse;
import com.osundosun.mupid.api.stream.response.ChunkUploadResponse;
import com.osundosun.mupid.api.stream.service.ChunkService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/chunk/{streamId}")
@RequiredArgsConstructor
public class ChunkController {

    /**
     * nginx 가 업로드 본문을 흘려보낼 스토리지 경로를 담아 돌려주는 헤더.
     */
    public static final String STORAGE_PATH_HEADER = "X-Storage-Path";

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

    /**
     * 오디오 등록 2-a 단계. **업로드 허가 전용 엔드포인트.**
     *
     * nginx 의 `auth_request` 가 `PUT /storage/upload/chunk/{streamId}/{index}` 를 받을 때
     * 본문 없이 이 엔드포인트를 먼저 호출한다. 여기서 200 이 나가야 nginx 가 본문을
     * 오브젝트 스토리지로 흘려보낸다. 즉 음원 바이트는 JVM 을 거치지 않는다.
     *
     * 다운로드의 X-Accel-Redirect 와 같은 발상이고, 방향만 반대다.
     */
    @SystemRole
    @GetMapping("/{index}/upload-auth")
    public ResponseEntity<Void> authorizeUpload(
        @PathVariable int streamId,
        @PathVariable int index,
        @RequestHeader(name = "X-Original-Content-Length", required = false) Long contentLength
    ) {
        String storagePath = chunkService.prepareUpload(streamId, index, contentLength);
        return ResponseEntity.ok()
                .header(STORAGE_PATH_HEADER, storagePath)
                .build();
    }

    /**
     * 오디오 등록 2-b 단계. 스토리지에 올라간 청크의 메타데이터를 확정한다.
     *
     * 스프링은 바이트를 보지 못했으므로 길이·해시는 올린 쪽이 알려주고,
     * 서버는 오브젝트가 실제로 있고 크기가 맞는지만 HEAD 로 대조한다.
     */
    @SystemRole
    @PostMapping("/{index}")
    public ResponseEntity<BaseResponse<ChunkUploadResponse>> commitChunk(
        @PathVariable int streamId,
        @PathVariable int index,
        @Valid @RequestBody ChunkCommitRequest request
    ) {
        Chunk chunk = chunkService.commitChunk(streamId, index, request);
        return ResponseEntity.ok(BaseResponse.of(
            "청크가 등록되었습니다.",
            new ChunkUploadResponse(
                streamId,
                chunk.getIndex(),
                chunk.getFile().getFileId(),
                chunk.getFileHash()
            )
        ));
    }
    
}
