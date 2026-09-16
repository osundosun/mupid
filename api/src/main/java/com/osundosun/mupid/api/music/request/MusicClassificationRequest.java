package com.osundosun.mupid.api.music.request;

import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 한 곡의 장르·분위기를 통째로 지정한다.
 *
 * 빈 목록을 보내면 그 분류를 <b>지운다</b>. 곡을 재분석했더니 장르가 하나도 안
 * 나왔을 때, 옛 장르가 남아 있으면 그게 최신인 것처럼 보이기 때문이다.
 *
 * @param genreCodeIds `CodeType.GENRE` 코드 번호
 * @param moodCodeIds  `CodeType.MOOD` 코드 번호
 */
public record MusicClassificationRequest(

    @NotNull(message = "장르 목록은 필수입니다. 비우려면 빈 배열을 보냅니다.")
    @Size(max = 20, message = "한 곡에 붙일 수 있는 장르 수를 넘었습니다.")
    List<Integer> genreCodeIds,

    @NotNull(message = "분위기 목록은 필수입니다. 비우려면 빈 배열을 보냅니다.")
    @Size(max = 20, message = "한 곡에 붙일 수 있는 분위기 수를 넘었습니다.")
    List<Integer> moodCodeIds

) {
}
