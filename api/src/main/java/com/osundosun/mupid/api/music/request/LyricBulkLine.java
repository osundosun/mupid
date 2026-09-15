package com.osundosun.mupid.api.music.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 일괄 등록에 실려 오는 가사 한 줄. */
public record LyricBulkLine(

    @NotBlank(message = "가사 내용이 입력되지 않았습니다.")
    @Size(max = 500, message = "가사 한 줄은 500자를 넘을 수 없습니다.")
    String text,

    /**
     * 그 줄이 나오는 곡 내 시점(ms).
     *
     * 같은 값이 여러 줄에 붙어도 된다 — 후렴에서 겹쳐 나오는 줄이 그렇다.
     * 그때는 보낸 순서가 곧 보이는 순서다 (조회가 `time_ms`, `lyric_id` 순).
     */
    @NotNull(message = "가사 표시 타이밍이 입력되지 않았습니다.")
    @PositiveOrZero(message = "올바르지 않은 가사 시간이 입력되었습니다.")
    Integer timeMs

) {

}
