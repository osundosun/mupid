package com.osundosun.mupid.api.system.enumerate;

/**
 * 배치용 오브젝트 하나의 상태.
 *
 * <p>{@code UPLOADING} 은 "자리만 잡혔다" 이다. 바이트가 올라왔는지, 신고한 sha256 과
 * 맞는지는 완료 단계에서 서버가 스토리지를 직접 읽어 본 뒤에야 안다. 그래서 받기는
 * {@code COMPLETED} 만 내보낸다.
 */
public enum SystemObjectStatus {

    /** ① 요청이 자리를 잡았다. ② 업로드를 받을 수 있다 */
    UPLOADING,

    /** ③ 완료 — 서버가 sha256 을 확인했다. 더는 덮어쓸 수 없다 */
    COMPLETED

}
