package com.osundosun.mupid.api.music.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 한 곡의 가사 전체.
 *
 * **추가가 아니라 교체다.** 이 요청이 그 곡의 가사를 통째로 대신한다.
 *
 * 빈 목록을 막아 둔 것은 의도한 제약이다. 교체 의미로는 "전부 지워라" 가
 * 맞지만, 배치의 실수 하나가 곡 가사를 통째로 날린다. 가사를 비우는 길은
 * 따로 두지 않았다 — 필요해지면 그때 `DELETE` 를 만든다.
 */
public record LyricBulkRequest(

    @NotEmpty(message = "가사가 입력되지 않았습니다.")
    @Size(max = MAX_LINES, message = "한 번에 올릴 수 있는 가사는 500줄입니다.")
    List<@Valid LyricBulkLine> lines

) {

    /**
     * 한 요청에 담을 수 있는 줄 수.
     *
     * 지금 카탈로그의 최대는 한 곡 75줄이라 여유가 크다. 상한을 두는 것은
     * 잘못된 요청 하나가 트랜잭션을 오래 붙들지 못하게 하려는 것이다.
     */
    public static final int MAX_LINES = 500;

}
