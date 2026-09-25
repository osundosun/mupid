package com.osundosun.mupid.api.recommendation.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.client.RecommenderDto;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.error.MusicSectionErrorCode;
import com.osundosun.mupid.api.music.service.MusicSectionService;
import com.osundosun.mupid.api.music.service.MusicSummaryAssembler;
import com.osundosun.mupid.api.recommendation.response.SimilarSection;
import com.osundosun.mupid.api.recommendation.response.SimilarSectionResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 고른 구간과 <b>소리가 닮은</b> 다른 곡의 구간.
 *
 * <h2>추천과 다르다</h2>
 *
 * <p>개인 취향을 섞지 않는다. 같은 구간에는 <b>누가 물어도 같은 답</b>이 나온다. 그래서
 * `recommendationId` 가 없고 캐시할 수 있으며, 사용자 사이에 공유해도 된다.
 *
 * <p>기준 구간이 속한 곡 자신은 결과에서 뺀다. 같은 곡의 다른 구간을 돌려주면
 * 「닮은 음악」이 아니라 「같은 음악」이 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SimilarSectionService {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 50;

    private final RecommenderClient recommender;
    private final MusicSectionService musicSectionService;
    private final MusicSummaryAssembler assembler;

    public SimilarSectionResponse similar(int musicId, int musicSectionId, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);

        // 남의 곡 구간 번호로 물어보면 404 다. 통과시키면 URL 의 곡과 결과가 어긋난 채
        // 화면이 그려진다.
        MusicSectionAnalysis base = musicSectionService
            .findSectionOfMusic(musicId, musicSectionId)
            .orElseThrow(() -> new BusinessException(
                MusicSectionErrorCode.SECTION_NOT_FOUND));

        String excludeTrack = musicSectionService.trackExternalIdOf(musicId).orElse(null);
        List<RecommenderDto.SimilarItem> items = recommender.similar(
            new RecommenderDto.SimilarRequest(
                base.getExternalId(), size,
                excludeTrack == null ? List.of() : List.of(excludeTrack)));

        List<SimilarSection> tracks = withMusic(items, size);
        return new SimilarSectionResponse(musicSectionId, tracks,
            Math.max(0, size - tracks.size()), EMBEDDING_VERSION);
    }

    /**
     * 임베딩 판.
     *
     * <p>추천 서버도 응답에 싣지만 우리 계약은 우리가 적는다 — 저쪽 값을 그대로 흘리면
     * 저쪽이 바꿨을 때 화면 계약이 말없이 따라 바뀐다.
     */
    private static final String EMBEDDING_VERSION = "mert-v1-95m";

    /**
     * 문자열 ID 를 곡으로 옮기고 최종 필터를 건다({@link RecommendationFinalFilter}). 추천과 같은
     * 규칙이다 — 지운 곡·재생할 수 없는 곡을 빼고, 한 곡은 한 줄, 앞에서부터 {@code limit} 개.
     */
    private List<SimilarSection> withMusic(List<RecommenderDto.SimilarItem> items, int limit) {
        RecommendationFinalFilter.Outcome<RecommenderDto.SimilarItem> outcome =
            RecommendationFinalFilter.apply(items, RecommenderDto.SimilarItem::externalId, limit,
                musicSectionService::findByExternalIds, assembler::byIds, log, "닮은 구간");

        List<SimilarSection> out = new ArrayList<>();
        for (RecommendationFinalFilter.Kept<RecommenderDto.SimilarItem> kept : outcome.kept()) {
            RecommenderDto.SimilarItem item = kept.item();
            MusicSectionAnalysis section = kept.section();
            out.add(new SimilarSection(
                out.size() + 1,
                kept.music(),
                section.getMusicSectionAnalysisId(),
                section.getSectionLabel(),
                section.getStartTimeMs(),
                section.getEndTimeMs(),
                item.similarity()));
        }
        return List.copyOf(out);
    }

}
