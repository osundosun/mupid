package com.osundosun.mupid.api.event.request;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record EventCollectRequest(

    @NotEmpty(message = "이벤트가 입력되지 않았습니다.")
    List<@Valid EventItemRequest> events

) {

}
