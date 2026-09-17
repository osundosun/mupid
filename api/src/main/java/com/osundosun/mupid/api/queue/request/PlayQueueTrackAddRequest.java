package com.osundosun.mupid.api.queue.request;

import jakarta.validation.constraints.NotNull;

public record PlayQueueTrackAddRequest(

    @NotNull Integer musicId

) {

}
