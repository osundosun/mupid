package com.osundosun.mupid.api.stream.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.response.StreamGetResponse;
import com.osundosun.mupid.api.stream.service.StreamService;

import lombok.RequiredArgsConstructor;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;


@RestController
@RequestMapping("/api/v1/stream")
@RequiredArgsConstructor
public class StreamController {

    private final StreamService streamService;

    @GetMapping("/{musicId}")
    public ResponseEntity<BaseResponse<StreamGetResponse>> getStream(
        @PathVariable int musicId
    ) {
        Stream stream = streamService.getStreamByMusicId(musicId);
        return ResponseEntity.ok(BaseResponse.of(
            new StreamGetResponse(
                stream.getStreamId(),
                stream.getMusic().getMusicId(),
                stream.getBitrateKbps(),
                stream.getCodec().name(),
                stream.getDurationMs(),
                stream.getChunkCount(),
                stream.getStatus().name()
            )
        ));
    }
    
}
