package com.osundosun.mupid.api.event.enumerate;

/**
 * 이 재생에 어떻게 들어왔는가.
 *
 * `AUTOPLAY` 는 두지 않는다. 인플레이어 다음곡 추천이 없으므로 검색 결과를 이어 듣는
 * 것도 `SEARCH` 로 남겨야, 나중에 "검색 유입이었는데 추천이 정확했다" 를 구분해
 * 검색 노출 순서를 조정할 수 있다.
 */
public enum EntryPath {

    RECOMMEND,
    SEARCH,
    PLAYLIST

}
