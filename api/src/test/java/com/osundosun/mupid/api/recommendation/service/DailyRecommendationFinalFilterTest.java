package com.osundosun.mupid.api.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.client.RecommenderDto;
import com.osundosun.mupid.api.ai.tool.TestMusicSummaries;
import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.repository.CodeRepository;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.mapping.repository.MusicSectionAnalysisMoodRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.service.MusicSectionService;
import com.osundosun.mupid.api.music.service.MusicSummaryAssembler;
import com.osundosun.mupid.api.onboarding.response.OnboardingResultResponse;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;
import com.osundosun.mupid.api.recommendation.entity.DailyRecommendation;
import com.osundosun.mupid.api.recommendation.entity.DailyRecommendationTrack;
import com.osundosun.mupid.api.recommendation.repository.DailyRecommendationRepository;
import com.osundosun.mupid.api.recommendation.repository.DailyRecommendationTrackRepository;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.repository.UserRepository;

/**
 * 오늘의 모먼트의 최종 필터 (S15P21A204-458, 계약 v3).
 *
 * <p>추천 서버는 이제 후보를 ClickHouse 사본에서 고르고, 그 사본은 곡 삭제·스트림 실패를 늦게 안다.
 * 그래서 요청은 4곡인데 응답은 여유분을 얹어 최대 6곡이 온다. 우리는 MySQL 기준으로 지운 곡과 재생할 수
 * 없는 곡을 빼고 <b>앞에서부터 4곡</b>을 저장해야 한다 — 예전에는 거르지도 자르지도 않아서, 지운 곡이
 * 섞이면 저장은 4곡인데 화면은 3곡을 보였다.
 *
 * <p>DB 없이 저장소를 대역으로 둔다. 확인하려는 것은 <b>무엇을 몇 번째로 저장하는가</b>뿐이다.
 */
class DailyRecommendationFinalFilterTest {

    private static final int USER_ID = 1015;
    private static final LocalDate DAY = LocalDate.of(2026, 9, 25);

    private DailyRecommendationRepository repository;
    private DailyRecommendationTrackRepository trackRepository;
    private RecommenderClient recommender;
    private MusicSectionService sectionService;
    private MusicRepository musicRepository;
    private MusicSectionAnalysisMoodRepository moodRepository;
    private CodeRepository codeRepository;
    private DailyRecommendationServiceImpl service;

    /** 곡 1~6 과 각자의 구간. */
    private final Map<String, MusicSectionAnalysis> sections = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        repository = mock(DailyRecommendationRepository.class);
        trackRepository = mock(DailyRecommendationTrackRepository.class);
        recommender = mock(RecommenderClient.class);
        sectionService = mock(MusicSectionService.class);
        musicRepository = mock(MusicRepository.class);
        moodRepository = mock(MusicSectionAnalysisMoodRepository.class);
        codeRepository = mock(CodeRepository.class);
        OnboardingService onboardingService = mock(OnboardingService.class);
        UserRepository userRepository = mock(UserRepository.class);

        for (int id = 1; id <= 6; id++) {
            Music music = Music.builder().musicId(id).title("곡" + id)
                .artist(Artist.builder().artistId(1).name("아티스트").build())
                .album(Album.builder().albumId(1).title("앨범").build())
                .build();
            String uid = "T%06d#1".formatted(id);
            sections.put(uid, MusicSectionAnalysis.builder()
                .musicSectionAnalysisId(id * 10).music(music).sectionLabel(SectionLabel.CHORUS)
                .index(1).startTimeMs(0).endTimeMs(15_000).externalId(uid).build());
        }

        when(repository.findByUser_UserIdAndRecommendationDate(USER_ID, DAY)).thenReturn(Optional.empty());
        when(userRepository.findByUserIdAndStatusNot(eq(USER_ID), any()))
            .thenReturn(Optional.of(User.builder().userId(USER_ID).build()));
        when(onboardingService.getResult(anyInt()))
            .thenReturn(new OnboardingResultResponse(USER_ID, false, null, List.of(), List.of()));
        when(sectionService.findByExternalIds(anyList())).thenAnswer(invocation -> {
            List<String> ids = invocation.getArgument(0);
            Map<String, MusicSectionAnalysis> out = new LinkedHashMap<>();
            ids.forEach(id -> {
                if (sections.containsKey(id)) {
                    out.put(id, sections.get(id));
                }
            });
            return out;
        });
        when(moodRepository.findMoodCodeIdsByPopularity(anyCollection())).thenReturn(List.of(32));
        when(codeRepository.getReferenceById(32)).thenReturn(mock(Code.class));
        when(repository.save(any(DailyRecommendation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(musicRepository.getReferenceById(anyInt()))
            .thenAnswer(invocation -> Music.builder().musicId(invocation.getArgument(0)).build());

        service = new DailyRecommendationServiceImpl(repository, trackRepository,
            assembler(Set.of(2), Set.of(4)), recommender, sectionService, musicRepository,
            moodRepository, codeRepository, onboardingService, userRepository);
    }

    /**
     * {@code byIds} 대역. 진짜처럼 <b>지운 곡은 돌려주지 않고</b>, 스트림이 없는 곡은 재생 불가로 준다.
     */
    private MusicSummaryAssembler assembler(Set<Integer> deleted, Set<Integer> notPlayable) {
        MusicSummaryAssembler assembler = mock(MusicSummaryAssembler.class);
        when(assembler.byIds(anyList())).thenAnswer(invocation -> {
            List<Integer> ids = invocation.getArgument(0);
            return ids.stream()
                .filter(id -> !deleted.contains(id))
                .map(id -> {
                    Music music = sections.values().stream().map(MusicSectionAnalysis::getMusic)
                        .filter(m -> m.getMusicId() == id).findFirst().orElseThrow();
                    return TestMusicSummaries.summaryOf(music, !notPlayable.contains(id));
                })
                .toList();
        });
        return assembler;
    }

    private static RecommenderDto.Item item(int musicId, int rank) {
        return new RecommenderDto.Item("T%06d#1".formatted(musicId), rank, 1.0 - rank * 0.1,
            rank == 1 ? "SIMILAR_PREFERRED_MOMENT" : "EXPLORE_NEW", null);
    }

    @Test
    @DisplayName("6개 중 지운 곡·재생 불가 곡을 빼고 앞에서부터 정확히 4곡을 순위대로 저장한다")
    void storesExactlyFourInRankOrder() {
        // 추천 서버가 limit 4 + 여유분 2 를 준다. 2위는 방금 지운 곡, 4위는 스트림이 실패한 곡이다.
        when(recommender.daily(any())).thenReturn(List.of(
            item(1, 1), item(2, 2), item(3, 3), item(4, 4), item(5, 5), item(6, 6)));

        assertThat(service.generate(USER_ID, DAY, false)).isTrue();

        // 요청은 여전히 4 다 — 추천 서버가 다른 값을 400 으로 거절한다(DailyLimitContractTest).
        ArgumentCaptor<RecommenderDto.DailyRequest> request =
            ArgumentCaptor.forClass(RecommenderDto.DailyRequest.class);
        verify(recommender).daily(request.capture());
        assertThat(request.getValue().limit()).isEqualTo(DailyRecommendationServiceImpl.DAILY_LIMIT).isEqualTo(4);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DailyRecommendationTrack>> saved = ArgumentCaptor.forClass(List.class);
        verify(trackRepository).saveAll(saved.capture());
        assertThat(saved.getValue())
            .extracting(t -> t.getMusic().getMusicId(), DailyRecommendationTrack::getRankOrder)
            .containsExactly(
                tuple(1, 1),
                tuple(3, 2),
                tuple(5, 3),
                tuple(6, 4));

        ArgumentCaptor<DailyRecommendation> group = ArgumentCaptor.forClass(DailyRecommendation.class);
        verify(repository).save(group.capture());
        assertThat(group.getValue().getMusicCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("같은 곡의 구간이 두 번 와도 한 줄이고, 여유분이 남으면 자른다")
    void dedupesByMusicAndCutsToLimit() {
        MusicSectionAnalysis sameMusic = MusicSectionAnalysis.builder()
            .musicSectionAnalysisId(11).music(sections.get("T000001#1").getMusic())
            .sectionLabel(SectionLabel.VERSE).index(2).startTimeMs(15_000).endTimeMs(30_000)
            .externalId("T000001#2").build();
        sections.put("T000001#2", sameMusic);
        when(recommender.daily(any())).thenReturn(List.of(
            item(1, 1),
            new RecommenderDto.Item("T000001#2", 2, 0.85, "EXPLORE_NEW", null),
            item(3, 3), item(5, 4), item(6, 5)));

        assertThat(service.generate(USER_ID, DAY, false)).isTrue();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<DailyRecommendationTrack>> saved = ArgumentCaptor.forClass(List.class);
        verify(trackRepository).saveAll(saved.capture());
        // 곡 1 은 앞선 순위 한 줄만, 곡 6 은 네 곡을 채운 뒤라 잘린다.
        assertThat(saved.getValue()).extracting(t -> t.getMusic().getMusicId())
            .containsExactly(1, 3, 5, 6);
    }

    @Test
    @DisplayName("걸러서 한 곡도 안 남으면 저장하지 않는다")
    void storesNothingWhenEverythingIsFiltered() {
        when(recommender.daily(any())).thenReturn(List.of(item(2, 1), item(4, 2)));

        assertThat(service.generate(USER_ID, DAY, false)).isFalse();

        verify(repository, never()).save(any());
    }

}
