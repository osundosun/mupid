package com.osundosun.mupid.api.system.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.system.response.ModelGetResponse;
import com.osundosun.mupid.api.system.response.ModelVersionStatusResponse;
import com.osundosun.mupid.api.system.service.ModelVersionService;
import com.osundosun.mupid.api.system.service.SystemObjectService;

import lombok.RequiredArgsConstructor;

/**
 * 모델 버전 (저장소 재설계 §6.3 `model_versions`).
 *
 * <p>채택은 CI 가 배포에 성공한 뒤 부르고(§6.3 ④-g), 폐기는 사람이 운영 절차로 부른다.
 * 둘 다 {@code @SystemRole}(SYSTEM·ADMIN) 이다 — 폐기를 관리자 토큰으로도 할 수 있어야
 * 운영자가 CI 계정의 토큰을 꺼내 쓰지 않는다.
 */
@SystemRole
@RestController
@RequestMapping("/api/v1/system/models/{name}")
@RequiredArgsConstructor
public class ModelVersionController {

    private final ModelVersionService modelVersionService;
    private final SystemObjectService systemObjectService;

    /**
     * 버전 목록·상태·파일, 그리고 지금 쓰는(가장 늦게 채택된) 버전.
     */
    @GetMapping
    public ResponseEntity<BaseResponse<ModelGetResponse>> getModel(
        @PathVariable String name
    ) {
        return ResponseEntity.ok(BaseResponse.of(modelVersionService.getModel(name)));
    }

    /**
     * 받기 — 이름·버전·파일명으로. CI 는 {@code models.lock} 에 있는 이 셋으로 바로 받는다.
     */
    @GetMapping("/{version}/files/{fileName}")
    public ResponseEntity<Void> download(
        @PathVariable String name,
        @PathVariable String version,
        @PathVariable String fileName
    ) {
        return SystemObjectController.redirect(systemObjectService.getRedirect(name, version, fileName));
    }

    @PostMapping("/{version}/adopt")
    public ResponseEntity<BaseResponse<ModelVersionStatusResponse>> adopt(
        @PathVariable String name,
        @PathVariable String version
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "모델 버전이 채택되었습니다.",
            modelVersionService.adopt(name, version)));
    }

    @PostMapping("/{version}/retire")
    public ResponseEntity<BaseResponse<ModelVersionStatusResponse>> retire(
        @PathVariable String name,
        @PathVariable String version
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "모델 버전이 폐기되었습니다.",
            modelVersionService.retire(name, version)));
    }

}
