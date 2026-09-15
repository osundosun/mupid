package com.osundosun.mupid.api.playlist.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PlaylistTrackAddRequest(

    @Positive(message = "음악 번호가 올바르지 않습니다.")
    @NotNull(message = "음악 번호가 입력되지 않았습니다.")
    Integer musicId

) {

}
