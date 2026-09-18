package com.osundosun.mupid.api.music.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.common.error.GlobalErrorCode;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.enumerate.VocalFilter;
import com.osundosun.mupid.api.music.response.MusicAnalysisResponse;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.music.service.MusicServiceImpl;

import lombok.RequiredArgsConstructor;

/**
 * 곡 조회.
 *
 * 등록 계열(`MusicController`)은 클래스에 `@SystemRole` 이 붙어 있다. 조회는
 * 로그인 전에도 필요해서(첫 방문자가 온보딩 전에 곡을 둘러본다) 컨트롤러를
 * 나눴다. 공개 여부는 `SecurityConfig` 가 정한다.
 */
@RestController
@RequestMapping("/api/v1/music")
@RequiredArgsConstructor
public class MusicQueryController {

    private final MusicService musicService;

    /**
     * 곡 목록·검색.
     *
     * @param q       제목·아티스트명·앨범명 부분 일치. 비우면 전체
     * @param vocal   보컬 필터. `female`·`male`·`instrumental` 중 고른 것들.
     *                반복해 여러 개를 보낼 수 있고(`vocal=female&vocal=male`),
     *                쉼표로 이어 보내도 된다(`vocal=female,male`). 비우면 걸지 않는다
     * @param page    0부터 센다
     * @param size    기본 20, 최대 100
     */
    @GetMapping
    public ResponseEntity<BaseResponse<MusicPageResponse>> searchMusic(
        @RequestParam(required = false) String q,
        @RequestParam(name = "vocal", required = false) List<String> vocal,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "" + MusicServiceImpl.DEFAULT_PAGE_SIZE) int size
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            musicService.searchMusic(q, parseVocalFilters(vocal), page, size)));
    }

    /**
     * 쿼리로 온 보컬 값을 enum 으로 옮긴다.
     *
     * <p>두 가지 모양을 다 받는다 — 반복 파라미터(`vocal=female&vocal=male`)와
     * 쉼표 목록(`vocal=female,male`). 화면은 쉼표 쪽을, 사람이 Bruno 로 칠 때는
     * 반복 쪽을 쓰기 쉽다. **둘이 같은 뜻이어야 해서** 여기서 한 번에 펴 준다.
     *
     * <p>모르는 값은 조용히 버리지 않는다. 버리면 `vocal=banana` 가 "보컬 전체" 가
     * 되어 사용자가 고른 것과 다른 결과를 보게 된다. 같은 이유로 빈 문자열은
     * 무시한다 — `vocal=` 은 "고르지 않음" 이지 잘못된 값이 아니다.
     */
    private static List<VocalFilter> parseVocalFilters(List<String> vocal) {
        if (vocal == null || vocal.isEmpty()) {
            return List.of();
        }
        return vocal.stream()
            .filter(value -> value != null && !value.isBlank())
            .flatMap(value -> java.util.Arrays.stream(value.split(",")))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(MusicQueryController::toVocalFilter)
            .toList();
    }

    private static VocalFilter toVocalFilter(String value) {
        return java.util.Arrays.stream(VocalFilter.values())
            .filter(filter -> filter.name().equalsIgnoreCase(value))
            .findFirst()
            .orElseThrow(() -> new BusinessException(GlobalErrorCode.INVALID_REQUEST_BODY));
    }

    @GetMapping("/{musicId}")
    public ResponseEntity<BaseResponse<MusicSummary>> getMusic(
        @PathVariable int musicId
    ) {
        return ResponseEntity.ok(BaseResponse.of(musicService.getMusicSummary(musicId)));
    }

    /**
     * 곡 분석 결과(구간·보컬·악기).
     *
     * 분석 파이프라인이 아직 없어 대부분 `analyzed: false` 로 온다.
     */
    @GetMapping("/{musicId}/analysis")
    public ResponseEntity<BaseResponse<MusicAnalysisResponse>> getAnalysis(
        @PathVariable int musicId
    ) {
        return ResponseEntity.ok(BaseResponse.of(musicService.getAnalysis(musicId)));
    }

}
