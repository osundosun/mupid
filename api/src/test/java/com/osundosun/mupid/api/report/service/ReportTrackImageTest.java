package com.osundosun.mupid.api.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.report.response.ReportResponse;

/** 리포트 트리맵이 앨범 표지 대신 곡별 표지를 고르는지 검증한다. */
class ReportTrackImageTest {

    @Test
    @DisplayName("트리맵은 앨범 이미지가 아니라 TRACK 이미지를 내려준다")
    void treemapUsesTrackImage() {
        MusicSummary music = summary("/api/v1/storage/image/track", "/api/v1/storage/image/album");

        List<ReportResponse.TrackListening> tiles = ReportServiceImpl.toTiles(
            new LinkedHashMap<>(Map.of(1, 30_000L)), Map.of(1, music), 30_000L);

        assertEquals("/api/v1/storage/image/track", tiles.getFirst().imageUrl());
    }

    @Test
    @DisplayName("TRACK 이미지가 없을 때 앨범 이미지로 바꿔치기하지 않는다")
    void missingTrackImageStaysNull() {
        MusicSummary music = summary(null, "/api/v1/storage/image/album");

        List<ReportResponse.TrackListening> tiles = ReportServiceImpl.toTiles(
            new LinkedHashMap<>(Map.of(1, 30_000L)), Map.of(1, music), 30_000L);

        assertNull(tiles.getFirst().imageUrl());
    }

    private static MusicSummary summary(String trackImageUrl, String albumImageUrl) {
        return new MusicSummary(
            1,
            "곡",
            LocalDateTime.of(2026, 9, 17, 0, 0),
            180_000,
            true,
            0,
            0,
            trackImageUrl,
            new MusicSummary.ArtistSummary(2, "아티스트", "/api/v1/storage/image/artist"),
            new MusicSummary.AlbumSummary(
                3, "앨범", albumImageUrl, LocalDateTime.of(2026, 9, 17, 0, 0)),
            List.of(),
            List.of());
    }
}
