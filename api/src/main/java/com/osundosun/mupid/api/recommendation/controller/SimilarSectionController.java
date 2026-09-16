package com.osundosun.mupid.api.recommendation.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.recommendation.response.SimilarSectionResponse;
import com.osundosun.mupid.api.recommendation.service.SimilarSectionService;

import lombok.RequiredArgsConstructor;

/**
 * 고른 구간과 닮은 다른 곡의 구간.
 *
 * <p>곡과 구간에 매달린 것이라 경로가 `/api/v1/music/{musicId}/section/{sectionId}` 다.
 *
 * <p><b>`GET` 인 이유</b>: 부수효과가 없고 URL 만으로 결과가 정해진다. 개인 취향을
 * 섞지 않으므로 캐시할 수 있고 사용자 사이에 공유해도 된다.
 *
 * <p><b>로그인은 요구한다.</b> 개인화가 없어도 카탈로그 전체를 구간 단위로 훑을 수 있는
 * 입구라, 공개하면 크롤링 통로가 된다.
 */
@RestController
@RequestMapping("/api/v1/music/{musicId}/section/{sectionId}")
@RequiredArgsConstructor
public class SimilarSectionController {

    private final SimilarSectionService similarSectionService;

    @GetMapping("/similar")
    public ResponseEntity<BaseResponse<SimilarSectionResponse>> similar(
        @PathVariable int musicId,
        @PathVariable int sectionId,
        @RequestParam(required = false) Integer limit
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            similarSectionService.similar(musicId, sectionId, limit)));
    }

}
