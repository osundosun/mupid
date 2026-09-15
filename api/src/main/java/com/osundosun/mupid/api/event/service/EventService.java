package com.osundosun.mupid.api.event.service;

import com.osundosun.mupid.api.event.request.EventCollectRequest;
import com.osundosun.mupid.api.event.response.EventCollectResponse;

public interface EventService {

    EventCollectResponse collectEvents(Integer userId, EventCollectRequest dto);

}
