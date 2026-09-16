package com.osundosun.mupid.api.music.response;

import java.util.List;

/**
 * 구간 일괄 적재 결과.
 *
 * 가사(`LyricBulkResponse`)와 달리 **발급된 번호를 전부 돌려준다.** 부르는 쪽이
 * 그 번호로 `id_map.parquet` 을 만들어야 하기 때문이다. 이것을 돌려주지 않으면
 * AI 가 번호를 알 길이 없어 다시 조회해야 한다.
 */
public record MusicSectionBulkResponse(

    int musicId,

    /** 요청에 실려 온 값을 그대로 되돌려준다. 저장하지는 않는다 — 컬럼이 없다. */
    String analysisVersion,

    /** 이 요청이 지운 기존 구간 수 */
    int replaced,

    /** 새로 만들어진 구간. 요청 순서 그대로다. */
    List<MusicSectionRef> sections

) {

}
