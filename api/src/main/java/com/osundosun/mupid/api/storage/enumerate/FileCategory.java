package com.osundosun.mupid.api.storage.enumerate;

import com.osundosun.mupid.api.storage.service.StorageImageServiceCallback;
import com.osundosun.mupid.api.user.service.UserImageServiceImpl;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 이미지 저장 용도.
 *
 * <p>들고 있는 것이 둘이다 — 사람이 읽을 이름과 <b>접근 정책</b>. 정책이 여기 붙어 있는
 * 것은 카테고리를 아는 곳이 여기 하나이기 때문이다. 검사할 자리가 늘어나도
 * ({@code StorageImageService} 의 조회·업로드·삭제) 규칙은 {@link ImageAccessPolicy}
 * 한 곳만 본다.
 */
@Getter
@RequiredArgsConstructor
public enum FileCategory {
    
    /** 프로필 사진. 본인만 쓰고 지우며, 읽기는 비공개다. */
    PROFILE("프로필 이미지", ImageAccessPolicy.OWNER, UserImageServiceImpl.class),

    /** 아티스트 이미지. 배치 이상만 쓰고, 읽기는 공개다. */
    ARTIST("아티스트 이미지", ImageAccessPolicy.CATALOG, null),

    /** 앨범 이미지. 배치 이상만 쓰고, 읽기는 공개다. */
    ALBUM("앨범 이미지", ImageAccessPolicy.CATALOG, null),

    /** 곡 커버 이미지. 배치 이상만 쓰고, 읽기는 공개다. */
    TRACK("곡 커버 이미지", ImageAccessPolicy.CATALOG, null),

    /** MuBTI 캐릭터 이미지. 배치 이상만 쓰고, 읽기는 공개다. */
    MUBTI("MuBTI 캐릭터 이미지", ImageAccessPolicy.CATALOG, null),

    /** 스트리밍용 오디오 청크. 배치 이상만 다루고, 읽기도 비공개다. */
    STREAMING("스트리밍용 오디오 청크", ImageAccessPolicy.MACHINE, null),

    /**
     * 곡의 파형 JSON. 배치 이상만 쓰고, 읽기는 로그인한 사람이면 된다.
     *
     * <p>정책이 {@code MACHINE} 인 것은 <b>쓰기 기준</b>이다. 읽기는 이미지 경로가
     * 아니라 {@code /api/v1/music/{musicId}/waveform} 으로 나가므로 이 정책을 거치지
     * 않는다 — 그쪽은 {@code SecurityConfig} 의 기본(인증 필요)이 가른다.
     */
    WAVEFORM("곡 파형", ImageAccessPolicy.MACHINE, null);

    private final String displayName;
    private final ImageAccessPolicy accessPolicy;
    private final Class<? extends StorageImageServiceCallback> service;

}
