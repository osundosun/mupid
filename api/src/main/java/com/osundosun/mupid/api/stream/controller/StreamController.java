package com.osundosun.mupid.api.stream.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.stream.request.StreamUpdateRequest;
import com.osundosun.mupid.api.stream.service.StreamService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@SystemRole
@RestController
@RequestMapping("/api/v1/stream/{streamId}")
@RequiredArgsConstructor
public class StreamController {

    private final StreamService streamService;

    /**
     * 오디오 등록 3단계. 청크를 다 올린 뒤 DONE 으로 올려야 재생 대상이 된다.
     */
    @PatchMapping
    public ResponseEntity<BaseResponse<Void>> updateStream(
        @PathVariable int streamId,
        @Valid @RequestBody StreamUpdateRequest request
    ) {
        streamService.updateStatus(streamId, request.status());
        return ResponseEntity.ok(BaseResponse.message("스트림 상태가 변경되었습니다."));
    }

    /**
     * 스트림·청크·스토리지 파일을 모두 지운다. 같은 코덱·비트레이트로 다시 올릴 때 쓴다.
     */
    @DeleteMapping
    public ResponseEntity<BaseResponse<Void>> deleteStream(
        @PathVariable int streamId
    ) {
        streamService.deleteStream(streamId);
        return ResponseEntity.ok(BaseResponse.message("스트림이 삭제되었습니다."));
    }
    
}
