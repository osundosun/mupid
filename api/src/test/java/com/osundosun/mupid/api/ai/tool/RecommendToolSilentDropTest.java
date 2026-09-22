package com.osundosun.mupid.api.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 추천은 왔는데 곡 정보를 못 붙여 **조용히 버리는** 경우를 드러낸다.
 *
 * <p>전부 버려지면 곡 0건인데 대화는 정상 응답한다 — 사용자에게는 "추천이 안 온다" 로
 * 보인다. 건별 경고만으로는 그 상태가 눈에 띄지 않아, 요약 한 줄을 남기는 것을 못박는다.
 * 원인은 보통 구간 적재가 색인보다 낡은 것이다.
 *
 * <p>공개 계약({@code recommend})으로 들어가 추천 서버만 대역으로 바꾼다. 조건이 없으면
 * {@code next} 경로를 타므로 온보딩·현재 곡 조회는 일어나지 않는다.
 */
class RecommendToolSilentDropTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger)LoggerFactory.getLogger(AiRecommendToolImpl.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    private RecommenderDto.Item item(String externalId) {
        return new RecommenderDto.Item(externalId, 1, 0.9, "QUERY_FILTER_MATCH", null);
    }

    /** 추천 서버가 준 목록과 MySQL 조회 결과를 정해 놓고 툴을 돌린다. */
    private List<AiRecommendedTrack> recommendWith(
        List<RecommenderDto.Item> fromServer,
        Map<String, MusicSectionAnalysis> sections,
        List<Music> catalog
    ) {
        RecommenderClient recommender = mock(RecommenderClient.class);
        MusicSectionService sectionService = mock(MusicSectionService.class);
        MusicRepository musicRepository = mock(MusicRepository.class);
        when(recommender.next(any())).thenReturn(fromServer);
        when(sectionService.findByExternalIds(any())).thenReturn(sections);
        when(musicRepository.findDetailAllByIds(any())).thenReturn(catalog);

        AiRecommendToolImpl tool =
            new AiRecommendToolImpl(recommender, musicRepository, sectionService,
                TestMusicSummaries.assembler(), null,
                mock(RecommendationReasonCache.class));
        // 현재 곡이 없으면 조건 없는 next 경로다. userId 는 온보딩 조회에만 쓰이는데
        // 온보딩 서비스가 null 이므로 추천 요청 자체는 대역으로 바로 간다.
        return tool.recommend(15, null,
            new AiRecommendTool.Arguments("추천해줘", 3, Map.of(), Map.of())).tracks();
    }

    private MusicSectionAnalysis section(Music music, String externalId) {
        return MusicSectionAnalysis.builder()
            .musicSectionAnalysisId(8)
            .music(music)
            .sectionLabel(SectionLabel.CHORUS)
            .index(0)
            .startTimeMs(45_000)
            .endTimeMs(60_000)
            .externalId(externalId)
            .build();
    }

    private Music music(int id, String title) {
        return Music.builder()
            .musicId(id)
            .title(title)
            .artist(Artist.builder().artistId(1).name("아티스트").build())
            .album(Album.builder().albumId(1).title("앨범").build())
            .build();
    }

    private List<String> warnings() {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            if (event.getLevel() == Level.WARN) {
                out.add(event.getFormattedMessage());
            }
        }
        return out;
    }

    @Test
    @DisplayName("전부 버려지면 버린 건수와 남은 곡 수를 한 줄로 남긴다")
    void logsSummaryWhenEverythingIsDropped() {
        // 추천 서버는 "T000901#2" 를 줬지만 DB 에 그 구간이 없다 — 적재가 낡았다.
        List<AiRecommendedTrack> tracks = recommendWith(
            List.of(item("T000901#2"), item("T000902#1")), Map.of(), List.of());

        assertThat(tracks).isEmpty();
        assertThat(warnings()).anySatisfy(w ->
            assertThat(w).contains("추천 2건 중 2건을 DB 에서 찾지 못해 버렸다")
                .contains("구간 없음=2")
                .contains("남은 곡 0건"));
    }

    @Test
    @DisplayName("일부만 버려져도 요약을 남긴다 — 몇 건이 살아남았는지가 핵심이다")
    void logsSummaryWhenPartiallyDropped() {
        Music found = music(7, "살아남은 곡");
        List<AiRecommendedTrack> tracks = recommendWith(
            List.of(item("T000901#2"), item("T000999#9")),
            Map.of("T000901#2", section(found, "T000901#2")),
            List.of(found));

        assertThat(tracks).hasSize(1);
        assertThat(tracks.getFirst().music().title()).isEqualTo("살아남은 곡");
        assertThat(warnings()).anySatisfy(w ->
            assertThat(w).contains("추천 2건 중 1건을 DB 에서 찾지 못해 버렸다")
                .contains("남은 곡 1건"));
    }

    @Test
    @DisplayName("버린 것이 없으면 요약을 남기지 않는다 — 정상 경로를 시끄럽게 만들지 않는다")
    void staysQuietWhenNothingIsDropped() {
        Music found = music(7, "곡");
        List<AiRecommendedTrack> tracks = recommendWith(
            List.of(item("T000901#1")),
            Map.of("T000901#1", section(found, "T000901#1")),
            List.of(found));

        assertThat(tracks).hasSize(1);
        assertThat(warnings()).noneSatisfy(w -> assertThat(w).contains("못해 버렸다"));
    }
}
