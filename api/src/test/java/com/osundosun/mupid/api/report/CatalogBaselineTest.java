package com.osundosun.mupid.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.report.service.CatalogBaseline;

/**
 * 카탈로그 대중성 백분위 검증. `Snapshot` 이 순수 계산이라 인프라 없이 돈다.
 *
 * 여기서 지키는 것은 하나다 — <b>한 번도 재생되지 않은 곡을 분모에서 빠뜨리지 않는 것.</b>
 * 재생 로그(ClickHouse)에는 재생된 곡만 나오므로, 그대로 쓰면 1000곡 카탈로그에서
 * 50곡만 재생됐을 때 배열 길이가 50 이 되어 백분위가 통째로 뒤집힌다.
 */
class CatalogBaselineTest {

    private static CatalogBaseline.Snapshot snapshot(List<Integer> playCountsAsc, Map<Integer, Long> plays) {
        return new CatalogBaseline.Snapshot(
            playCountsAsc, plays, 0.5, Instant.now().plusSeconds(3600));
    }

    @Test
    @DisplayName("재생 안 된 곡이 분모에 들어가야 인기곡이 상위로 나온다")
    void unplayedTracksMustCountInTheDenominator() {
        // 카탈로그 10곡 중 2곡만 재생됐다. 나머지 8곡은 0 회다.
        List<Integer> withZeros = List.of(0, 0, 0, 0, 0, 0, 0, 0, 5, 40);
        CatalogBaseline.Snapshot correct = snapshot(withZeros, Map.of(500, 40L, 501, 5L));

        // 40회 재생된 곡은 카탈로그 상위여야 한다.
        assertTrue(correct.percentileOfTrack(500) >= 0.9,
            "가장 많이 재생된 곡이 상위로 안 나왔다");
        // 한 번도 재생 안 된 곡은 최하위다.
        assertEquals(0.0, correct.percentileOfTrack(999), 1e-9);

        // 0 을 빼먹으면 같은 곡이 "2곡 중 위쪽" 으로만 읽힌다 — 분해능이 사라진다.
        CatalogBaseline.Snapshot broken = snapshot(List.of(5, 40), Map.of(500, 40L, 501, 5L));
        assertEquals(0.5, broken.percentileOfTrack(500), 1e-9);
        assertEquals(0.0, broken.percentileOfTrack(501), 1e-9);
    }

    @Test
    @DisplayName("재생 기록이 없는 곡은 0 회로 본다")
    void unknownTrackIsTreatedAsNeverPlayed() {
        CatalogBaseline.Snapshot s = snapshot(List.of(0, 0, 3, 9), Map.of(1, 9L, 2, 3L));
        assertEquals(s.percentileOf(0), s.percentileOfTrack(12345), 1e-9);
    }

    @Test
    @DisplayName("카탈로그가 비면 중립값 0.5 를 돌려준다")
    void emptyCatalogIsNeutral() {
        CatalogBaseline.Snapshot s = snapshot(List.of(), Map.of());
        assertEquals(0.5, s.percentileOfTrack(1), 1e-9);
    }

    @Test
    @DisplayName("전 곡이 0 회면 모든 곡의 백분위가 같다 — 대중성 항이 죽는 조건")
    void allZeroCatalogCarriesNoInformation() {
        // MySQL `music.play_count` 를 쓰던 때가 정확히 이 상태였다. 축이 왜 죽었는지를
        // 테스트로 남겨 둔다 — 다시 이 상태가 되면 B축 대중성은 아무 일도 하지 않는다.
        CatalogBaseline.Snapshot s = snapshot(List.of(0, 0, 0, 0, 0), Map.of());
        double first = s.percentileOfTrack(1);
        assertEquals(first, s.percentileOfTrack(2), 1e-9);
        assertEquals(first, s.percentileOfTrack(3), 1e-9);
    }

}
