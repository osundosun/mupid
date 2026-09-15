package com.osundosun.mupid.api.music.response;

/**
 * 가사 일괄 등록 결과.
 *
 * 만들어진 `lyric_id` 목록은 돌려주지 않는다. 교체 의미라 부르는 쪽이 번호를
 * 기억할 이유가 없고, 곡 하나에 수십 줄이라 목록이 응답을 채우기만 한다.
 * 번호가 필요하면 `GET /api/v1/music/{musicId}/lyric` 이 있다.
 */
public record LyricBulkResponse(

    int musicId,

    /** 이 요청이 지운 기존 가사 줄 수 */
    int deleted,

    /** 새로 넣은 줄 수 */
    int created

) {

}
