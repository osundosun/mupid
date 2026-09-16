package com.osundosun.mupid.api.music.error;

import org.springframework.http.HttpStatus;

import com.osundosun.mupid.api.common.error.ErrorCode;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum MusicSectionErrorCode implements ErrorCode {

    /**
     * 구간 종료 시간이 시작 시간보다 앞서거나 같습니다.
     */
    SECTION_INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "구간 종료 시간은 시작 시간보다 커야 합니다."),

    /**
     * 한 곡 안에서 구간 순번이 겹칩니다.
     */
    SECTION_INDEX_DUPLICATED(HttpStatus.BAD_REQUEST, "구간 순번이 중복되었습니다."),

    /**
     * 한 번에 올릴 수 있는 구간 수를 넘었습니다.
     */
    SECTION_BULK_TOO_MANY(HttpStatus.BAD_REQUEST, "한 번에 올릴 수 있는 구간 수를 넘었습니다."),

    /**
     * 같은 `external_id` 가 이미 **다른 곡**에 붙어 있습니다.
     *
     * 이 값은 AI 색인과 DB 를 잇는 유일한 근거라 곡 전체에서 고유해야 합니다.
     * 겹친 채로 넣으면 추천이 엉뚱한 곡을 가리키면서도 조회는 정상으로 보입니다.
     */
    SECTION_EXTERNAL_ID_DUPLICATED(HttpStatus.CONFLICT, "이미 다른 곡에 쓰인 external_id 입니다.");

    private final HttpStatus status;
    private final String message;

}
