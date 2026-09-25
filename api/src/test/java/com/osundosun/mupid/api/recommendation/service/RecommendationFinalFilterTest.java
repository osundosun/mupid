package com.osundosun.mupid.api.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.client.RecommenderDto;
import com.osundosun.mupid.api.ai.tool.TestMusicSummaries;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.service.MusicSectionService;
import com.osundosun.mupid.api.music.service.MusicSummaryAssembler;
import com.osundosun.mupid.api.onboarding.response.OnboardingResultResponse;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;
import com.osundosun.mupid.api.recommendation.request.NextTrackRequest;
import com.osundosun.mupid.api.recommendation.response.NextTrackResponse;
import com.osundosun.mupid.api.recommendation.response.SimilarSectionResponse;

/**
 * 추천 응답의 최종 필터 (S15P21A204-458, 계약 v3) — 규칙 자체와, 그것을 쓰는 이어 듣기·닮은 구간.
 *
 * <p>추천 서버는 {@code limit} 보다 몇 개 더 주고 우리가 MySQL 기준으로 걸러 자른다. 대화 추천은
 * {@code RecommendToolMusicSummaryTest}, 오늘의 모먼트는 {@code DailyRecommendationFinalFilterTest} 가 본다.
 */
class RecommendationFinalFilterTest {

    private final Map<String, MusicSectionAnalysis> sections = new LinkedHashMap<>();
    private final Map<Integer, Music> musics = new LinkedHashMap<>();

    private MusicSectionService sectionService;

    @BeforeEach
    void setUp() {
        for (int id = 1; id <= 6; id++) {
            Music music = Music.builder().musicId(id).title("곡" + id)
                .artist(Artist.builder().artistId(1).name("아티스트").build())
                .album(Album.builder().albumId(1).title("앨범").build())
                .build();
            musics.put(id, music);
            String uid = uid(id);
            sections.put(uid, MusicSectionAnalysis.builder()
                .musicSectionAnalysisId(id * 10).music(music).sectionLabel(SectionLabel.CHORUS)
                .index(1).startTimeMs(0).endTimeMs(15_000).externalId(uid).build());
        }
        sectionService = mock(MusicSectionService.class);
        when(sectionService.findByExternalIds(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            Map<String, MusicSectionAnalysis> out = new LinkedHashMap<>();
            ids.stream().filter(sections::containsKey).forEach(id -> out.put(id, sections.get(id)));
            return out;
        });
    }

    private static String uid(int musicId) {
        return "T%06d#1".formatted(musicId);
    }

    /** 진짜 {@code byIds} 처럼 지운 곡은 돌려주지 않는다. 재생 불가는 표시만 한다. */
    private MusicSummaryAssembler assembler(Set<Integer> deleted, Set<Integer> notPlayable) {
        MusicSummaryAssembler assembler = mock(MusicSummaryAssembler.class);
        when(assembler.byIds(anyList())).thenAnswer(invocation -> {
            List<Integer> ids = invocation.getArgument(0);
            return ids.stream().filter(id -> !deleted.contains(id))
                .map(id -> TestMusicSummaries.summaryOf(musics.get(id), !notPlayable.contains(id)))
                .toList();
        });
        return assembler;
    }

    private static List<RecommenderDto.Item> items(String... externalIds) {
        return IntStream.range(0, externalIds.length)
            .mapToObj(i -> new RecommenderDto.Item(externalIds[i], i + 1, 1.0, "EXPLORE_NEW", null))
            .toList();
    }

    @Test
    @DisplayName("없는 구간·지운 곡·재생 불가·같은 곡을 빼고 순위대로 limit 개로 자른다")
    void dropsThenCuts() {
        MusicSectionAnalysis second = MusicSectionAnalysis.builder()
            .musicSectionAnalysisId(11).music(musics.get(1)).sectionLabel(SectionLabel.VERSE)
            .index(2).startTimeMs(15_000).endTimeMs(30_000).externalId("T000001#2").build();
        sections.put("T000001#2", second);

        RecommendationFinalFilter.Outcome<RecommenderDto.Item> outcome = RecommendationFinalFilter.apply(
            items(uid(1), "T999999#0", uid(2), "T000001#2", uid(3), uid(4), uid(5), uid(6)),
            RecommenderDto.Item::externalId, 3,
            sectionService::findByExternalIds, assembler(Set.of(2), Set.of(3))::byIds,
            LoggerFactory.getLogger(RecommendationFinalFilterTest.class), "추천");

        assertThat(outcome.kept()).extracting(k -> k.music().musicId()).containsExactly(1, 4, 5);
        assertThat(outcome.missingSection()).isEqualTo(1);
        assertThat(outcome.missingMusic()).isEqualTo(1);
        assertThat(outcome.notPlayable()).isEqualTo(1);
        assertThat(outcome.duplicate()).isEqualTo(1);
        // 곡 6 은 버린 것이 아니라 여유분이다.
        assertThat(outcome.cut()).isEqualTo(1);
        assertThat(outcome.dropped()).isEqualTo(4);
    }

    @Test
    @DisplayName("이어 듣기는 여유분까지 온 응답을 limit 으로 자르고, 지운 곡을 빼고 순번을 이어 붙인다")
    void nextTrackCutsToLimit() {
        RecommenderClient recommender = mock(RecommenderClient.class);
        when(recommender.next(any())).thenReturn(items(uid(1), uid(2), uid(3), uid(4), uid(5)));
        OnboardingService onboarding = mock(OnboardingService.class);
        when(onboarding.getResult(anyInt()))
            .thenReturn(new OnboardingResultResponse(15, false, null, List.of(), List.of()));
        when(sectionService.trackExternalIdOf(anyInt())).thenReturn(Optional.empty());

        NextTrackService service = new NextTrackService(recommender, sectionService,
            assembler(Set.of(2), Set.of()), onboarding, mock(RecommendationReasonCache.class));
        NextTrackResponse response = service.next(15, new NextTrackRequest(null, null, null, 3));

        assertThat(response.tracks()).extracting(t -> t.music().musicId()).containsExactly(1, 3, 4);
        assertThat(response.tracks()).extracting(t -> t.rankOrder()).containsExactly(1, 2, 3);
        assertThat(response.shortfall()).isZero();
    }

    @Test
    @DisplayName("닮은 구간도 같은 필터다 — 재생할 수 없는 곡을 빼고 limit 으로 자른다")
    void similarUsesTheSameFilter() {
        RecommenderClient recommender = mock(RecommenderClient.class);
        when(recommender.similar(any())).thenReturn(List.of(
            new RecommenderDto.SimilarItem(uid(2), 1, 0.97),
            new RecommenderDto.SimilarItem(uid(3), 2, 0.95),
            new RecommenderDto.SimilarItem(uid(4), 3, 0.91),
            new RecommenderDto.SimilarItem(uid(5), 4, 0.90)));
        when(sectionService.findSectionOfMusic(1, 10)).thenReturn(Optional.of(sections.get(uid(1))));
        when(sectionService.trackExternalIdOf(1)).thenReturn(Optional.of("T000001"));

        SimilarSectionService service = new SimilarSectionService(recommender, sectionService,
            assembler(Set.of(), Set.of(3)));
        SimilarSectionResponse response = service.similar(1, 10, 2);

        assertThat(response.tracks()).extracting(t -> t.music().musicId()).containsExactly(2, 4);
        assertThat(response.shortfall()).isZero();
    }

}
