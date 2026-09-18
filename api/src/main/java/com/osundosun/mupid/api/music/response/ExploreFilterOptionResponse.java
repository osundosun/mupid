package com.osundosun.mupid.api.music.response;

import java.util.List;

import com.osundosun.mupid.api.onboarding.dto.CodeOption;

/** 탐색 사이드바에서 고를 수 있는 마스터 데이터. */
public record ExploreFilterOptionResponse(
    List<CodeOption> moods,
    List<CodeOption> genres,
    List<VocalFilterOption> vocals
) {
}
