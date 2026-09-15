package com.osundosun.mupid.api.storage.enumerate;

import com.osundosun.mupid.api.storage.service.StorageImageServiceCallback;
import com.osundosun.mupid.api.user.service.UserImageServiceImpl;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum FileCategory {
    
    PROFILE("프로필 이미지", UserImageServiceImpl.class),
    ARTIST("아티스트 이미지", null),
    ALBUM("앨범 이미지", null),
    TRACK("곡 커버 이미지", null),
    STREAMING("스트리밍용 오디오 청크", null);

    private final String displayName;
    private final Class<? extends StorageImageServiceCallback> service;

}
