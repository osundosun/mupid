package com.osundosun.mupid.api.system.service;

import com.osundosun.mupid.api.system.dto.SystemObjectRedirect;
import com.osundosun.mupid.api.system.request.SystemObjectCreateRequest;
import com.osundosun.mupid.api.system.response.SystemObjectResponse;

/**
 * 배치용 Object Storage API (저장소 재설계 §6.5). 음악 청크(결정 기록 0016)와 같은
 * 요청 → 업로드 → 완료 3단계이고, 바이트는 스프링 요청 본문으로 들어오지 않는다.
 */
public interface SystemObjectService {

    /**
     * ① 자리를 잡는다. 같은 파일(이름·버전·파일명·sha256)이 이미 있으면 그것을 돌려준다.
     */
    SystemObjectResponse request(SystemObjectCreateRequest request);

    /**
     * ② Nginx {@code auth_request} 에 답한다. 본문을 흘려보낼 Filer 경로를 돌려준다.
     */
    String authorizeUpload(int objectId, Long contentLength);

    /**
     * ③ 스토리지에서 흘려 읽어 sha256 을 확인하고 완료한다. 모델 버전의 파일이 모두
     * 완료되면 버전을 후보로 올린다.
     */
    SystemObjectResponse complete(int objectId);

    /**
     * 받기 — 완료된 오브젝트의 {@code X-Accel-Redirect} 대상.
     */
    SystemObjectRedirect getRedirect(int objectId);

    /**
     * 받기 — 이름·버전·파일명으로.
     */
    SystemObjectRedirect getRedirect(String name, String version, String fileName);

}
