package com.osundosun.mupid.api.vector.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.vector.request.MusicVectorsRequest;
import com.osundosun.mupid.api.vector.response.MusicVectorsResponse;
import com.osundosun.mupid.api.vector.service.VectorService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 곡 하나의 벡터 등록 — 새 곡·재분석 (S15P21A204-457, 설계 §6.6·§7.1 ③).
 *
 * <p>분석 배치가 {@code PUT …/section} 바로 뒤에 부른다. 구간·분석 버전이 MySQL 과 같아야 받는다.
 * <b>응답이 나가면 Qdrant 에 이미 쓰였다.</b> 5xx·429 면 같은 본문을 다시 보내면 된다(멱등 — 곡 단위 교체).
 */
@SystemRole
@RestController
@RequestMapping("/api/v1/music/{musicId}/vectors")
@RequiredArgsConstructor
public class MusicVectorController {

    private final VectorService vectorService;

    /**
     * 그 곡의 모든 종류({@code mert}·{@code r}·{@code feat_v1}·{@code stage1_v{N}}·{@code clap})를 한 요청에.
     * 종류별로 나누면 {@code mert} 는 들어갔는데 {@code stage1} 이 없는 반쯤 들어간 곡이 생긴다.
     */
    @PutMapping
    public ResponseEntity<BaseResponse<MusicVectorsResponse>> replace(
        @PathVariable int musicId,
        @Valid @RequestBody MusicVectorsRequest request
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "벡터가 등록되었습니다.",
            vectorService.replaceMusicVectors(musicId, request)));
    }

}
