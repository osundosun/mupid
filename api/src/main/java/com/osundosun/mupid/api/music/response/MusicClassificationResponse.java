package com.osundosun.mupid.api.music.response;

import java.util.List;

/**
 * 교체 뒤 그 곡의 분류.
 *
 * 보낸 것을 그대로 되돌려 주지 않고 <b>저장된 것</b>을 돌려준다. 중복을 걷어 내므로
 * 개수가 달라질 수 있고, 배치가 그 차이를 보고 입력을 고칠 수 있어야 한다.
 */
public record MusicClassificationResponse(
    Integer musicId,
    List<Integer> genreCodeIds,
    List<Integer> moodCodeIds
) {
}
