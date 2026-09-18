package com.osundosun.mupid.api.music.service;

import java.util.Arrays;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.code.enumerate.CodeType;
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

    /** 코드 번호 순서를 화면 순서로 유지한다. */
    @Transactional(readOnly = true)
    public ExploreFilterOptionResponse getOptions() {
        return new ExploreFilterOptionResponse(
            codeRepository.findAllByCodeTypeOrderByCodeIdAsc(CodeType.MOOD).stream()
                .map(CodeOption::of)
                .toList(),
            codeRepository.findAllByCodeTypeOrderByCodeIdAsc(CodeType.GENRE).stream()
                .map(CodeOption::of)
                .toList(),
            Arrays.stream(VocalFilter.values()).map(VocalFilterOption::of).toList());
    }
}
