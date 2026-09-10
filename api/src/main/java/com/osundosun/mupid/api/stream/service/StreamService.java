package com.osundosun.mupid.api.stream.service;

import com.osundosun.mupid.api.stream.entity.Stream;

public interface StreamService {

    Stream getStreamByMusicId(int musicId);
    
}
