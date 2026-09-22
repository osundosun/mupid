package com.osundosun.mupid.api.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.client.RecommenderDto;
import com.osundosun.mupid.api.ai.dto.AiRecommendedTrack;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.service.MusicSectionService;
import com.osundosun.mupid.api.recommendation.service.RecommendationReasonCache;

/**
 * 추천 응답이 **곡 표현을 통째로** 싣는지 못박는다 (S15P21A204-424).
 *
 * <p>예전에는 제목·아티스트·앨범·표지만 납작하게 실었다. 그것만으로는 화면이 줄을 그려도
 * 재생할 수 없어서, 화면이 자기가 들고 있는 **앞쪽 100곡** 카탈로그에서 같은 곡을 다시
 * 찾아 부족한 값을 채웠다. 추천은 전체 카탈로그에서 나오므로 대부분 그 100곡 밖이었고,
 * 화면은 찾지 못한 추천을 통째로 버렸다 — 배포 환경에서 "추천받은 3곡 중 3곡은 지금
 * 재생할 수 있는 목록에 없어 빼뒀어요" 만 뜨고 곡이 하나도 안 보였다.
 *
 * <p>그래서 여기서 두 가지를 고정한다.
 * <ol>
 *   <li>곡이 {@code MusicSummary} 로 온다 — 화면이 카탈로그를 볼 이유가 없어진다.
 *   <li><b>버린 것이 있어도 곡과 구간의 짝이 밀리지 않는다.</b> 곡 표현을 한 번에
 *       조립하고 자리로 맞물리므로, 중간이 빠졌을 때 한 칸 밀리면 A 곡에 B 구간이
 *       붙는다 — 화면에는 "이 곡의 코러스" 라면서 다른 곡의 시간이 적힌다.
 * </ol>
 */
class RecommendToolMusicSummaryTest {

    private RecommenderDto.Item item(String externalId) {
        return new RecommenderDto.Item(externalId, 1, 0.9, "QUERY_FILTER_MATCH", null);
    }

    private Music music(int id, String title) {
        return Music.builder()
            .musicId(id)
            .title(title)
            .artist(Artist.builder().artistId(id).name("아티스트" + id).build())
            .album(Album.builder().albumId(id).title("앨범" + id).build())
            .build();
    }

    private MusicSectionAnalysis section(Music music, String externalId, int startMs) {
        return MusicSectionAnalysis.builder()
            .musicSectionAnalysisId(music.getMusicId() * 10)
            .music(music)
            .sectionLabel(SectionLabel.CHORUS)
            .index(0)
            .startTimeMs(startMs)
            .endTimeMs(startMs + 15_000)
            .externalId(externalId)
            .build();
    }

    private List<AiRecommendedTrack> recommendWith(
        List<RecommenderDto.Item> fromServer,
        Map<String, MusicSectionAnalysis> sections,
        List<Music> catalog
    ) {
        return recommendWith(fromServer, sections, catalog, Set.of());
    }

    private List<AiRecommendedTrack> recommendWith(
        List<RecommenderDto.Item> fromServer,
        Map<String, MusicSectionAnalysis> sections,
        List<Music> catalog,
        Set<Integer> notPlayable
    ) {
        RecommenderClient recommender = mock(RecommenderClient.class);
        MusicSectionService sectionService = mock(MusicSectionService.class);
        MusicRepository musicRepository = mock(MusicRepository.class);
        when(recommender.next(any())).thenReturn(fromServer);
        when(sectionService.findByExternalIds(any())).thenReturn(sections);
        when(musicRepository.findDetailAllByIds(any())).thenReturn(catalog);

        AiRecommendToolImpl tool = new AiRecommendToolImpl(
            recommender, musicRepository, sectionService,
            TestMusicSummaries.assembler(notPlayable),
            null, mock(RecommendationReasonCache.class));
        return tool.recommend(15, null,
            new AiRecommendTool.Arguments("추천해줘", 3, Map.of(), Map.of())).tracks();
    }

    @Test
    @DisplayName("곡을 MusicSummary 로 싣는다 — 화면이 카탈로그에서 다시 찾을 필요가 없다")
    void carriesFullMusicSummary() {
        Music found = music(7, "실린 곡");
        List<AiRecommendedTrack> tracks = recommendWith(
            List.of(item("T000901#1")),
            Map.of("T000901#1", section(found, "T000901#1", 45_000)),
            List.of(found));

        assertThat(tracks).hasSize(1);
        AiRecommendedTrack track = tracks.getFirst();
        assertThat(track.music()).isNotNull();
        assertThat(track.music().musicId()).isEqualTo(7);
        assertThat(track.music().title()).isEqualTo("실린 곡");
        assertThat(track.music().artist().name()).isEqualTo("아티스트7");
        assertThat(track.music().album().title()).isEqualTo("앨범7");
        // 화면이 줄을 그리고 **재생까지** 하려면 이 둘이 있어야 한다. 납작한 제목·표지만
        // 오던 시절에 화면이 카탈로그를 뒤진 이유가 정확히 이것이다.
        assertThat(track.music().durationMs()).isNotNull();
        assertThat(track.music().playable()).isTrue();
    }

    @Test
    @DisplayName("musicId() 는 곡 표현에서 읽는다 — 부르는 쪽 코드가 그대로 산다")
    void musicIdStillReadable() {
        Music found = music(11, "곡");
        List<AiRecommendedTrack> tracks = recommendWith(
            List.of(item("T000901#1")),
            Map.of("T000901#1", section(found, "T000901#1", 1_000)),
            List.of(found));

        assertThat(tracks.getFirst().musicId()).isEqualTo(11);
        assertThat(tracks.getFirst().musicId()).isEqualTo(tracks.getFirst().music().musicId());
    }

    @Test
    @DisplayName("가운데가 버려져도 곡과 구간의 짝이 밀리지 않는다")
    void pairingSurvivesADropInTheMiddle() {
        Music first = music(1, "첫 곡");
        Music third = music(3, "셋째 곡");
        // 가운데(`T000902#1`) 는 DB 에 구간이 없어 버려진다.
        Map<String, MusicSectionAnalysis> sections = new LinkedHashMap<>();
        sections.put("T000901#1", section(first, "T000901#1", 10_000));
        sections.put("T000903#1", section(third, "T000903#1", 30_000));

        List<AiRecommendedTrack> tracks = recommendWith(
            List.of(item("T000901#1"), item("T000902#1"), item("T000903#1")),
            sections,
            List.of(first, third));

        assertThat(tracks).hasSize(2);
        // 밀렸다면 "첫 곡" 에 30초 구간이 붙는다.
        assertThat(tracks.get(0).music().title()).isEqualTo("첫 곡");
        assertThat(tracks.get(0).startTimeMs()).isEqualTo(10_000);
        assertThat(tracks.get(0).musicSectionId()).isEqualTo(10);
        assertThat(tracks.get(1).music().title()).isEqualTo("셋째 곡");
        assertThat(tracks.get(1).startTimeMs()).isEqualTo(30_000);
        assertThat(tracks.get(1).musicSectionId()).isEqualTo(30);
        // 버린 것이 있어도 순번은 1 부터 이어 붙는다.
        assertThat(tracks.get(0).rankOrder()).isEqualTo(1);
        assertThat(tracks.get(1).rankOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("재생할 수 없는 곡은 내려보내지 않는다 — 화면이 거르게 두지 않는다")
    void dropsTracksWithoutAPlayableStream() {
        Music playable = music(1, "들을 수 있는 곡");
        Music silent = music(2, "스트림이 아직 없는 곡");

        List<AiRecommendedTrack> tracks = recommendWith(
            List.of(item("T000901#1"), item("T000902#1")),
            new LinkedHashMap<>(Map.of(
                "T000901#1", section(playable, "T000901#1", 10_000),
                "T000902#1", section(silent, "T000902#1", 20_000))),
            List.of(playable, silent),
            Set.of(2));

        /*
         * 예전에는 두 곡을 다 내려보내고 화면이 걸러 "2곡 중 1곡은 아직 재생할 수 없어
         * 빼뒀어요" 라고 알렸다. 사용자가 할 수 있는 일이 없는 의문만 남는다.
         */
        assertThat(tracks).hasSize(1);
        assertThat(tracks.getFirst().music().title()).isEqualTo("들을 수 있는 곡");
    }

    @Test
    @DisplayName("재생 불가를 걷어낸 뒤에도 순번은 1 부터 이어 붙는다 — 건수와 순위가 어긋나지 않는다")
    void rankStaysContiguousAfterDroppingUnplayable() {
        Music first = music(1, "첫 곡");
        Music silent = music(2, "스트림 없는 곡");
        Music third = music(3, "셋째 곡");

        Map<String, MusicSectionAnalysis> sections = new LinkedHashMap<>();
        sections.put("T000901#1", section(first, "T000901#1", 10_000));
        sections.put("T000902#1", section(silent, "T000902#1", 20_000));
        sections.put("T000903#1", section(third, "T000903#1", 30_000));

        List<AiRecommendedTrack> tracks = recommendWith(
            List.of(item("T000901#1"), item("T000902#1"), item("T000903#1")),
            sections,
            List.of(first, silent, third),
            Set.of(2));

        // 추천 건수(`track_count`)는 이 목록의 크기다. 여기가 2 여야 화면이 "3곡 중" 이라고
        // 말하지 않는다.
        assertThat(tracks).hasSize(2);
        assertThat(tracks.get(0).rankOrder()).isEqualTo(1);
        assertThat(tracks.get(1).rankOrder()).isEqualTo(2);
        // 곡과 구간의 짝도 밀리지 않는다.
        assertThat(tracks.get(0).music().title()).isEqualTo("첫 곡");
        assertThat(tracks.get(0).startTimeMs()).isEqualTo(10_000);
        assertThat(tracks.get(1).music().title()).isEqualTo("셋째 곡");
        assertThat(tracks.get(1).startTimeMs()).isEqualTo(30_000);
    }
}
