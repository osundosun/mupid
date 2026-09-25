package com.osundosun.mupid.api.system.enumerate;

/**
 * 모델 버전의 상태 (저장소 재설계 §6.3 `model_versions`).
 *
 * <pre>
 * UPLOADING ──(선언한 파일이 모두 완료)──▶ CANDIDATE ──(배포 성공, CI)──▶ ADOPTED
 *                                             │                             │
 *                                             └──────(사람이 운영 절차로)────┴──▶ RETIRED
 * </pre>
 *
 * <p>설계 표에는 후보·채택·폐기 셋만 있다. {@code UPLOADING} 은 그 앞, 파일이 아직 다
 * 모이지 않은 때다 — 이 상태를 따로 두지 않으면 "반쯤 올라간 버전" 과 "후보" 를 조회
 * 결과로 가를 수 없다.
 */
public enum ModelVersionStatus {

    /** 파일을 받는 중. 채택할 수 없다 */
    UPLOADING,

    /** 선언한 파일이 모두 완료됐다 (§6.3 ④-d) */
    CANDIDATE,

    /** 배포에 성공했다 (§6.3 ④-g). 가장 늦게 채택된 것이 "지금 쓰는 버전" 이다 */
    ADOPTED,

    /** 폐기. 파일은 되돌리기를 위해 남긴다 — 받기는 계속 된다 */
    RETIRED

}
