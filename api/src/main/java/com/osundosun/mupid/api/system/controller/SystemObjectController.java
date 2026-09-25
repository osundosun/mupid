package com.osundosun.mupid.api.system.controller;

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
import com.osundosun.mupid.api.stream.controller.ChunkController;
import com.osundosun.mupid.api.system.dto.SystemObjectRedirect;
import com.osundosun.mupid.api.system.request.SystemObjectCreateRequest;
import com.osundosun.mupid.api.system.response.SystemObjectResponse;
import com.osundosun.mupid.api.system.service.SystemObjectService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 배치용 Object Storage API — 모델 가중치 파일 (저장소 재설계 §6.5).
 *
 * <p>음악 청크(결정 기록 0016)와 같은 길이다. <b>바이트는 이 컨트롤러로 들어오지 않는다.</b>
 * 올리기는 Nginx 가 {@code auth_request} 로 여기에 허가만 묻고 Filer 로 바로 흘리며, 받기는
 * 여기서 {@code X-Accel-Redirect} 만 돌려주고 Nginx 가 Filer 에서 읽어 보낸다.
 *
 * <p>올리는 쪽은 CI({@code system-ci}) 뿐이고, 받는 쪽은 CI·분석 배치다. ai_server 는 이 API 를
 * 부르지 않는다 — 가중치는 CI 가 이미지에 굽는다(§6.3).
 */
@SystemRole
@RestController
@RequestMapping("/api/v1/system/objects")
@RequiredArgsConstructor
public class SystemObjectController {

    private final SystemObjectService systemObjectService;

    /**
     * ① 요청. 자리를 잡고 오브젝트 번호를 돌려준다.
     */
    @PostMapping
    public ResponseEntity<BaseResponse<SystemObjectResponse>> request(
        @Valid @RequestBody SystemObjectCreateRequest request
    ) {
        return ResponseEntity.ok(BaseResponse.of(systemObjectService.request(request)));
    }

    /**
     * ② 업로드 허가 전용. Nginx 가 {@code PUT /upload/system/{objectId}} 를 받을 때 본문 없이
     * 먼저 부른다. 200 이 나가야 Nginx 가 본문을 Filer 로 흘려보낸다.
     */
    @GetMapping("/{objectId}/upload-auth")
    public ResponseEntity<Void> authorizeUpload(
        @PathVariable int objectId,
        @RequestHeader(name = "X-Original-Content-Length", required = false) Long contentLength
    ) {
        String storagePath = systemObjectService.authorizeUpload(objectId, contentLength);
        return ResponseEntity.ok()
            .header(ChunkController.STORAGE_PATH_HEADER, storagePath)
            .build();
    }

    /**
     * ③ 완료. 스토리지에서 흘려 읽어 sha256 을 확인한다. 본문은 없다.
     */
    @PostMapping("/{objectId}/complete")
    public ResponseEntity<BaseResponse<SystemObjectResponse>> complete(
        @PathVariable int objectId
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "오브젝트가 완료되었습니다.",
            systemObjectService.complete(objectId)));
    }

    /**
     * 받기. 완료된 오브젝트만 내보낸다.
     */
    @GetMapping("/{objectId}")
    public ResponseEntity<Void> download(
        @PathVariable int objectId
    ) {
        return redirect(systemObjectService.getRedirect(objectId));
    }

    /**
     * {@code X-Accel-Redirect} 가 붙으면 Nginx 는 이 응답을 내보내지 않고 그 내부 URI 를 따라가
     * 받은 바이트를 보낸다. 스프링만 띄운 곳에서는 본문 없는 200 이 된다.
     */
    static ResponseEntity<Void> redirect(SystemObjectRedirect target) {
        return ResponseEntity.ok()
            .header("X-Accel-Redirect", target.url())
            .header(HttpHeaders.CONTENT_TYPE, target.contentType())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"%s\"".formatted(target.fileName()))
            .build();
    }

}
