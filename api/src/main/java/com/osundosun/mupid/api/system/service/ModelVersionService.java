package com.osundosun.mupid.api.system.service;

import com.osundosun.mupid.api.system.response.ModelGetResponse;
import com.osundosun.mupid.api.system.response.ModelVersionStatusResponse;

/**
 * 모델 버전의 상태 전이와 조회 (저장소 재설계 §6.3 `model_versions`).
 *
 * <p>후보는 파일 완료가 올린다({@link SystemObjectService#complete(int)}). 여기서는 그 뒤 —
 * 채택(CI, 배포 성공 뒤)과 폐기(사람, 운영 절차) — 를 맡는다.
 */
public interface ModelVersionService {

    ModelGetResponse getModel(String name);

    /**
     * 채택. 이전 채택 버전은 그대로 둔다. 이미 채택된 버전을 다시 채택하면 채택 시각만
     * 새로 찍는다 — {@code models.lock} 을 되돌린 배포가 바로 그 경우다.
     */
    ModelVersionStatusResponse adopt(String name, String version);

    /**
     * 폐기. 지금 쓰는 버전은 폐기할 수 없다. 파일은 지우지 않는다.
     */
    ModelVersionStatusResponse retire(String name, String version);

}
