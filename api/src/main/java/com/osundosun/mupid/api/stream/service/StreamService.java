package com.osundosun.mupid.api.stream.service;

import java.util.List;

import com.osundosun.mupid.api.stream.entity.Stream;

public interface StreamService {

    List<Stream> getStreamByMusicId(int musicId);
    
}
