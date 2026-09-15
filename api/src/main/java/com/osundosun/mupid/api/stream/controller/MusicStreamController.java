package com.osundosun.mupid.api.stream.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.stream.dto.StreamResponseItem;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.request.StreamCreateRequest;
import com.osundosun.mupid.api.stream.response.StreamCreateResponse;
import com.osundosun.mupid.api.stream.response.StreamGetResponse;
import com.osundosun.mupid.api.stream.service.StreamService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/music/{musicId}/stream")
@RequiredArgsConstructor
public class MusicStreamController {

    private final StreamService streamService;

    @GetMapping
    public ResponseEntity<BaseResponse<StreamGetResponse>> getStream(
        @PathVariable int musicId
    ) {
        List<Stream> streams = streamService.getStreamByMusicId(musicId);
        StreamGetResponse response = new StreamGetResponse(streams.stream()
            .map(it -> new StreamResponseItem(
                it.getStreamId(),
                it.getMusic().getMusicId(),
                it.getBitrateKbps(),
                it.getCodec().name(),
                it.getDurationMs(),
                it.getChunkCount(),
                it.getStatus().name()
            ))
            .toList());
        return ResponseEntity.ok(BaseResponse.of(response));
    }

    /**
     * 오디오 등록 1단계. 청크를 올릴 자리를 만든다. (PENDING)
     */
    @SystemRole
    @PostMapping
    public ResponseEntity<BaseResponse<StreamCreateResponse>> createStream(
        @PathVariable int musicId,
        @Valid @RequestBody StreamCreateRequest request
    ) {
        int streamId = streamService.createStream(musicId, request);
        return ResponseEntity.ok(BaseResponse.of(
            "스트림이 생성되었습니다.",
            new StreamCreateResponse(streamId)
        ));
    }
    
}
