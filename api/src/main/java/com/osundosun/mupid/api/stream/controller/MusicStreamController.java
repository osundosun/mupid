package com.osundosun.mupid.api.stream.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.stream.dto.StreamResponseItem;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.response.StreamGetResponse;
import com.osundosun.mupid.api.stream.service.StreamService;

import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;


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
    
}
