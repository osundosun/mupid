package com.osundosun.mupid.api.music.service;

import java.util.Arrays;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.code.repository.CodeRepository;
import com.osundosun.mupid.api.music.enumerate.VocalFilter;
import com.osundosun.mupid.api.music.response.ExploreFilterOptionResponse;
import com.osundosun.mupid.api.music.response.VocalFilterOption;
import com.osundosun.mupid.api.onboarding.dto.CodeOption;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ExploreFilterOptionService {

    private final CodeRepository codeRepository;

    /**
     * 탐색 사이드바 선택지. 코드 번호 순서를 화면 순서로 유지한다.
     *
     * <p><b>곡이 하나라도 달린 코드만 내놓는다</b>({@code findMoodsInUse} ·
     * {@code findGenresInUse}). 코드표 전량을 내놓으면 어느 곡에도 붙지 않은 분위기·
     * 장르까지 칩이 되어, 눌러도 결과가 0건인 칩이 섞인다 — 사용자에게는 검색이
     * 고장 난 것으로 보인다. 2026-09-21 기준으로 분위기 8종·장르 8종(둘 다 대분류)이
     * 그 상태였다.
     *
     * <p>선택지가 비는 것 자체는 오류가 아니다. 카탈로그가 비어 있으면 칩도 없다 —
     * 화면은 빈 목록을 그대로 그린다.
     */
    @Transactional(readOnly = true)
    public ExploreFilterOptionResponse getOptions() {
        return new ExploreFilterOptionResponse(
            codeRepository.findMoodsInUse().stream()
                .map(CodeOption::of)
                .toList(),
            codeRepository.findGenresInUse().stream()
                .map(CodeOption::of)
                .toList(),
            Arrays.stream(VocalFilter.values()).map(VocalFilterOption::of).toList());
    }
}
