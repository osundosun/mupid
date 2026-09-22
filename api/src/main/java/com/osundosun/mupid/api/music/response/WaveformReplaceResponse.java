package com.osundosun.mupid.api.music.response;

/**
 * 파형 등록 결과.
 *
 * <p>`file_id` 를 돌려주는 것은 배치가 올린 것이 어느 파일이 됐는지 로그에 남길
 * 수 있게 하려는 것이다. 다시 올려도 이 번호는 바뀌지 않는다 — 같은 키면 기존
 * 행을 갱신한다.
 */
public record WaveformReplaceResponse(

    int musicId,

    /** 저장된 칸 수. 보낸 값의 길이와 같다. */
    int sampleCount,

    /** `object_storage_files` 의 파일 번호 */
    int fileId

) {

}
