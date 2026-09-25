package com.osundosun.mupid.api.vector.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.vector.request.Stage1VectorsRequest;
import com.osundosun.mupid.api.vector.response.Stage1StatusResponse;
import com.osundosun.mupid.api.vector.response.Stage1VectorsResponse;
import com.osundosun.mupid.api.vector.response.VectorPendingResponse;
import com.osundosun.mupid.api.vector.service.VectorService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 배치·CI 용 벡터 API (S15P21A204-457, 설계 §6.6·§6.3 ④-c).
 *
 * <ul>
 *   <li>{@code PUT /stage1/{version}} — 모델 재계산. 1,000구간씩, 다른 벡터는 건드리지 않는다.</li>
 *   <li>{@code GET /stage1/{version}/status} — 재계산 관문. CI {@code model-publish} 가 {@code complete} 를 본다.</li>
 *   <li>{@code GET /pending} — 벡터가 아직 없는 곡. 배치가 읽고 다시 보낸다.</li>
 * </ul>
 */
@SystemRole
@RestController
@RequestMapping("/api/v1/system/vectors")
@RequiredArgsConstructor
public class SystemVectorController {

    private final VectorService vectorService;

    @PutMapping("/stage1/{version}")
    public ResponseEntity<BaseResponse<Stage1VectorsResponse>> putStage1(
        @PathVariable String version,
        @Valid @RequestBody Stage1VectorsRequest request
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "모델 벡터가 덧붙었습니다.",
            vectorService.putStage1(version, request)));
    }

    @GetMapping("/stage1/{version}/status")
    public ResponseEntity<BaseResponse<Stage1StatusResponse>> stage1Status(
        @PathVariable String version
    ) {
        return ResponseEntity.ok(BaseResponse.of(vectorService.stage1Status(version)));
    }

    /**
     * @param after 이 곡 번호 다음부터 (처음은 0)
     * @param limit 한 쪽의 곡 수 (최대 1,000)
     */
    @GetMapping("/pending")
    public ResponseEntity<BaseResponse<VectorPendingResponse>> pending(
        @RequestParam(defaultValue = "0") int after,
        @RequestParam(defaultValue = "500") int limit
    ) {
        return ResponseEntity.ok(BaseResponse.of(vectorService.pending(after, limit)));
    }

}
