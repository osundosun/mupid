package com.osundosun.mupid.api.recommendation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.ai.catalog.RecommenderCatalogService;
import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.client.RecommenderDto;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.dto.MusicSummary;
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
    private final RecommenderCatalogService catalogService;
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
                excludeTrack == null ? List.of() : List.of(excludeTrack),
                catalogService.current().body()));

        List<SimilarSection> tracks = withMusic(items);
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

    private List<SimilarSection> withMusic(List<RecommenderDto.SimilarItem> items) {
        List<String> externalIds = items.stream()
            .map(RecommenderDto.SimilarItem::externalId)
            .filter(Objects::nonNull).distinct().toList();
        Map<String, MusicSectionAnalysis> sections =
            musicSectionService.findByExternalIds(externalIds);

        List<Integer> musicIds = sections.values().stream()
            .map(row -> row.getMusic().getMusicId()).distinct().toList();
        Map<Integer, MusicSummary> summaries = assembler.byIds(musicIds).stream()
            .collect(Collectors.toMap(MusicSummary::musicId, Function.identity()));

        List<SimilarSection> out = new ArrayList<>();
        for (RecommenderDto.SimilarItem item : items) {
            MusicSectionAnalysis section = sections.get(item.externalId());
            if (section == null) {
                log.warn("닮은 구간 external_id={} 가 DB 에 없다 — 구간 적재가 색인보다 낡았다",
                    item.externalId());
                continue;
            }
            MusicSummary music = summaries.get(section.getMusic().getMusicId());
            if (music == null) {
                continue;
            }
            out.add(new SimilarSection(
                out.size() + 1,
                music,
                section.getMusicSectionAnalysisId(),
                section.getSectionLabel(),
                section.getStartTimeMs(),
                section.getEndTimeMs(),
                item.similarity()));
        }
        return List.copyOf(out);
    }

}
