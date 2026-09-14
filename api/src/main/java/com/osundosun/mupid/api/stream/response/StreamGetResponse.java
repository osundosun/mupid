package com.osundosun.mupid.api.stream.response;

import java.util.List;

import com.osundosun.mupid.api.stream.dto.StreamResponseItem;

public record StreamGetResponse(

    List<StreamResponseItem> streams

) {
    
}
