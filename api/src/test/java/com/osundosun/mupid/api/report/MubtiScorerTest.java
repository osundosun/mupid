package com.osundosun.mupid.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.analytics.dto.DailyPlaySummary;
import com.osundosun.mupid.api.report.dto.MubtiInput;
import com.osundosun.mupid.api.report.dto.MubtiVerdict;
import com.osundosun.mupid.api.report.enumerate.MubtiClass;
import com.osundosun.mupid.api.report.service.MubtiScorer;

/**
 * MuBTI 판정 규칙 검증. **인프라 없이 돈다** — 스코어러가 리포지토리를 모르고
 * 입력을 `MubtiInput` 한 덩어리로 받기 때문이다.
 *
 * 계수를 만질 일이 잦은 코드라, 만질 때 무엇이 깨지는지 여기서 보이게 한다.
 * 확인하는 것은 계수의 정확한 값이 아니라 **방향**이다 — "끝까지 듣는 사람은 F 다"
 * 같은 것. 값 자체를 못 박으면 조율할 때마다 테스트를 같이 고치게 되고, 그러면
 * 테스트가 아무것도 지키지 못한다.
 */
class MubtiScorerTest {

    private final MubtiScorer scorer = new MubtiScorer();

    /** 5분·3곡 하한을 넉넉히 넘기는 기본 청취. */
    private static Map<Integer, Long> listening(long... msPerTrack) {
        Map<Integer, Long> map = new LinkedHashMap<>();
        for (int i = 0; i < msPerTrack.length; i++) {
            map.put(100 + i, msPerTrack[i]);
        }
        return map;
    }

    private static Map<Integer, Integer> sameArtist(Map<Integer, Long> listening, int artistId) {
        Map<Integer, Integer> map = new HashMap<>();
        listening.keySet().forEach(id -> map.put(id, artistId));
        return map;
    }

    private static Map<Integer, Integer> distinctArtists(Map<Integer, Long> listening) {
        Map<Integer, Integer> map = new HashMap<>();
        int artist = 1;
        for (Integer musicId : listening.keySet()) {
            map.put(musicId, artist++);
        }
        return map;
    }

    private static Map<Integer, Double> popularity(Map<Integer, Long> listening, double percentile) {
        Map<Integer, Double> map = new HashMap<>();
        listening.keySet().forEach(id -> map.put(id, percentile));
        return map;
    }

    /** 곡마다 딱 한 번씩 튼 것으로 본다. 반복이 쟁점이 아닌 테스트의 기본값. */
    private static Map<Integer, Long> playedOnce(Map<Integer, Long> listening) {
        Map<Integer, Long> map = new HashMap<>();
        listening.keySet().forEach(id -> map.put(id, 1L));
        return map;
    }

    /** 주어진 곡들을 `times` 번씩 튼 것으로 본다. */
    private static Map<Integer, Long> playedTimes(Collection<Integer> musicIds, long times) {
        Map<Integer, Long> map = new HashMap<>();
        musicIds.forEach(id -> map.put(id, times));
        return map;
    }

    private static MubtiInput input(
        Map<Integer, Long> listening,
        Map<Integer, Integer> artists,
        Map<Integer, Boolean> hasVocal,
        double catalogVocalShare,
        DailyPlaySummary summary,
        Set<Integer> heardBefore,
        String previous
    ) {
        return input(listening, artists, hasVocal, catalogVocalShare, summary,
            playedOnce(listening), heardBefore, Set.of(), previous);
    }

    private static MubtiInput input(
        Map<Integer, Long> listening,
        Map<Integer, Integer> artists,
        Map<Integer, Boolean> hasVocal,
        double catalogVocalShare,
        DailyPlaySummary summary,
        Map<Integer, Long> playsByMusic,
        Set<Integer> heardBefore,
        Set<Integer> library,
        String previous
    ) {
        return new MubtiInput(
            listening, artists, hasVocal, popularity(listening, 0.5), Set.of(),
            catalogVocalShare, summary, playsByMusic, heardBefore, library, previous);
    }

    private static DailyPlaySummary plays(long ends, long skips, long seekForwards, long plays) {
        return new DailyPlaySummary(ends, skips, plays, seekForwards, 0, plays);
    }

    @Test
    @DisplayName("5분을 못 채운 하루는 판정하지 않는다")
    void tooShort() {
        Map<Integer, Long> listening = listening(60_000, 60_000, 60_000);
        MubtiVerdict verdict = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(3, 0, 0, 3), Set.of(), null));
        assertNull(verdict, "총 3분인데 판정이 나왔다");
    }

    @Test
    @DisplayName("두 곡만 들은 하루는 판정하지 않는다")
    void tooFewTracks() {
        Map<Integer, Long> listening = listening(600_000, 600_000);
        MubtiVerdict verdict = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(2, 0, 0, 2), Set.of(), null));
        assertNull(verdict, "서로 다른 곡이 2곡인데 판정이 나왔다");
    }

    @Test
    @DisplayName("끝까지 듣는 사람은 완주형(F), 넘기는 사람은 스킵형(S)")
    void playbackAxis() {
        Map<Integer, Long> listening = listening(300_000, 300_000, 300_000, 300_000);

        MubtiVerdict finisher = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(10, 1, 0, 11), Set.of(), null));
        assertNotNull(finisher);
        assertEquals(MubtiClass.C.F, finisher.classC());

        MubtiVerdict skipper = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(1, 12, 8, 13), Set.of(), null));
        assertNotNull(skipper);
        assertEquals(MubtiClass.C.S, skipper.classC());
    }

    @Test
    @DisplayName("처음 만지는 곡이 많으면 개척형(E), 익숙한 곡만 반복하면 국밥형(C)")
    void discoveryAxis() {
        Map<Integer, Long> listening = listening(300_000, 300_000, 300_000, 300_000);

        MubtiVerdict explorer = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(4, 0, 0, 4), Set.of(), null));
        assertNotNull(explorer);
        assertEquals(MubtiClass.D.E, explorer.classD());

        // 전부 최근에 들은 곡이고 곡당 세 번씩 반복했다.
        MubtiVerdict comfort = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(12, 0, 0, 12), playedTimes(listening.keySet(), 3),
            listening.keySet(), Set.of(), null));
        assertNotNull(comfort);
        assertEquals(MubtiClass.D.C, comfort.classD());
    }

    @Test
    @DisplayName("보관함(좋아요·플리) 곡을 반복하면 최근에 안 들었어도 국밥형(C)")
    void libraryTracksCountAsFamiliar() {
        Map<Integer, Long> listening = listening(300_000, 300_000, 300_000, 300_000);

        // 최근 3일 기록은 없지만 전부 보관함에 담아 둔 곡이다.
        MubtiVerdict verdict = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(12, 0, 0, 12), playedTimes(listening.keySet(), 3),
            Set.of(), listening.keySet(), null));

        assertNotNull(verdict);
        assertEquals(MubtiClass.D.C, verdict.classD(),
            "보관함에 담아 둔 곡을 반복해 듣는데 개척형이 나왔다");
    }

    @Test
    @DisplayName("플리를 반복하면서 새 곡도 활발히 디깅하면 개척형(E)")
    void heavyDiggingWinsOverHeavyRepeat() {
        // 이 축의 의도를 그대로 옮긴 테스트다. 플레이리스트의 최애곡을 계속 돌리면서
        // 동시에 새 곡을 빠르게 넘기며 디깅하는 사람은 **개척형이어야 한다.**
        // 비율로 재던 옛 공식은 두 행동이 서로를 깎아 먹어 이 사람을 국밥형으로 냈다.
        Map<Integer, Long> listening = new HashMap<>();
        Set<Integer> library = new HashSet<>();
        Map<Integer, Long> playsByMusic = new HashMap<>();

        // 보관함 최애 3곡 — 길게, 그리고 네 번씩 반복해서 들었다.
        for (int i = 0; i < 3; i++) {
            listening.put(500 + i, 600_000L);
            library.add(500 + i);
            playsByMusic.put(500 + i, 4L);
        }
        // 새로 디깅한 10곡 — 대부분 몇 초 만에 넘겨서 청취 시간이 거의 없다.
        // (5초 하트비트 전에 넘긴 곡은 listening 에 아예 없다는 것까지 재현한다)
        for (int i = 0; i < 10; i++) {
            playsByMusic.put(900 + i, 1L);
            if (i < 3) listening.put(900 + i, 4_000L);
        }

        Map<Integer, Integer> artists = new HashMap<>();
        playsByMusic.keySet().forEach(id -> artists.put(id, id));

        MubtiVerdict verdict = scorer.judge(new MubtiInput(
            listening, artists, Map.of(), popularity(listening, 0.5), Set.of(),
            0.5, plays(2, 9, 4, 22), playsByMusic, Set.of(), library, null));

        assertNotNull(verdict);
        assertEquals(MubtiClass.D.E, verdict.classD(),
            "새 곡 10개를 디깅했는데 플리 반복 때문에 국밥형이 됐다");
        assertTrue(axis(verdict, "D").score() > 0);
    }

    @Test
    @DisplayName("긴 익숙한 곡이 청취 시간을 가져가도, 새 곡을 많이 찾았으면 개척형(E)")
    void discoveryIsNotDrownedByLongFamiliarTracks() {
        // 실제로 겪은 하루를 그대로 옮긴 회귀 테스트다.
        // 7곡 중 5곡이 처음 듣는 곡(71%)인데, 익숙한 두 곡이 하필 카탈로그에서 가장
        // 길어서 청취 시간의 65%를 가져갔다. 시간만 보던 옛 공식은 여기서 국밥형(C)을
        // 냈다 — 곡을 다섯 개나 새로 찾아 들은 하루인데도.
        Map<Integer, Long> listening = listening(
            932_977, 475_789,                                   // 전에 들은 긴 곡 2개
            254_581, 223_739, 193_150, 33_440, 26_911);         // 처음 듣는 곡 5개
        Set<Integer> heardBefore = Set.of(100, 101);

        MubtiVerdict verdict = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(3, 2, 1, 16), heardBefore, null));

        assertNotNull(verdict);
        assertEquals(MubtiClass.D.E, verdict.classD(),
            "곡 수로는 5/7 이 새 곡인데 국밥형이 나왔다");
        assertTrue(axis(verdict, "D").score() > 0);
    }

    @Test
    @DisplayName("재생 횟수가 많아도 그것만으로 국밥형이 되지는 않는다")
    void manyPlaysAloneDoNotMeanComfort() {
        // 이것저것 눌러 보며 고르는 행동도 재생 수를 올린다. 예전에는 그 반복 강도를
        // 빼는 항이 있어서 탐색이 반복으로 오인됐다.
        Map<Integer, Long> listening = listening(300_000, 300_000, 300_000, 300_000);
        MubtiVerdict verdict = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(2, 20, 15, 40), Set.of(), null));

        assertNotNull(verdict);
        assertEquals(MubtiClass.D.E, verdict.classD(),
            "전부 처음 듣는 곡인데 재생 수가 많다고 국밥형이 됐다");
    }

    @Test
    @DisplayName("한 아티스트만 파면 매니아형(M), 고루 들으면 트렌드형(T)")
    void tasteAxis() {
        Map<Integer, Long> listening = listening(300_000, 300_000, 300_000, 300_000);

        MubtiVerdict mania = scorer.judge(input(
            listening, sameArtist(listening, 7), Map.of(), 0.5,
            plays(4, 0, 0, 4), Set.of(), null));
        assertNotNull(mania);
        assertEquals(MubtiClass.B.M, mania.classB());

        MubtiVerdict trend = scorer.judge(new MubtiInput(
            listening, distinctArtists(listening), Map.of(),
            popularity(listening, 0.95), Set.copyOf(listening.keySet()),
            0.5, plays(4, 0, 0, 4), playedOnce(listening), Set.of(), Set.of(), null));
        assertNotNull(trend);
        assertEquals(MubtiClass.B.T, trend.classB());
    }

    @Test
    @DisplayName("보컬 판정은 절대 비중이 아니라 카탈로그 기준선 대비 편차다")
    void vocalAxisComparesAgainstBaseline() {
        Map<Integer, Long> listening = listening(300_000, 300_000, 300_000, 300_000);
        Map<Integer, Boolean> allVocal = new HashMap<>();
        listening.keySet().forEach(id -> allVocal.put(id, true));

        // 카탈로그가 거의 전부 보컬곡이면, 보컬곡만 들었다는 사실은 취향이 아니다.
        MubtiVerdict inVocalCatalog = scorer.judge(input(
            listening, distinctArtists(listening), allVocal, 1.0,
            plays(4, 0, 0, 4), Set.of(), null));
        assertNotNull(inVocalCatalog);
        assertEquals(0.0, axis(inVocalCatalog, "A").score(), 1e-9,
            "기준선과 같은데 편차가 잡혔다");

        // 연주곡이 흔한 카탈로그에서 보컬곡만 골라 들었다면 그건 취향이다.
        MubtiVerdict inMixedCatalog = scorer.judge(input(
            listening, distinctArtists(listening), allVocal, 0.3,
            plays(4, 0, 0, 4), Set.of(), null));
        assertNotNull(inMixedCatalog);
        assertEquals(MubtiClass.A.V, inMixedCatalog.classA());
        assertTrue(axis(inMixedCatalog, "A").score() > 0);
    }

    @Test
    @DisplayName("보컬 분석이 없으면 판정하지 않고 확신도 0 을 실어 보낸다")
    void vocalAxisWithoutAnalysis() {
        Map<Integer, Long> listening = listening(300_000, 300_000, 300_000, 300_000);
        MubtiVerdict verdict = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(4, 0, 0, 4), Set.of(), null));
        assertNotNull(verdict);
        // 축을 비우면 화면이 16유형 중 하나를 그리지 못한다. 기본값을 두되 확신도로 알린다.
        assertEquals(0.0, axis(verdict, "A").confidence(), 1e-9);
        assertEquals(MubtiClass.A.V, verdict.classA());
    }

    @Test
    @DisplayName("점수가 0 근처에서 조금 흔들려도 직전 판정을 지킨다")
    void hysteresisHoldsTheLetter() {
        // 완주 6 : 스킵 5 — 완주형이지만 아슬아슬하다.
        Map<Integer, Long> listening = listening(300_000, 300_000, 300_000, 300_000);
        MubtiVerdict borderline = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(5, 6, 0, 11), Set.of(), null));
        assertNotNull(borderline);
        assertEquals(MubtiClass.C.S, borderline.classC(), "스킵이 더 많은데 완주형이 나왔다");

        // 같은 하루에 완주 하나가 더해져 부호가 뒤집혔다. 아직 바꾸지 않는다.
        MubtiVerdict held = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(6, 6, 0, 12), Set.of(), previousWith(borderline, 2, 'S')));
        assertNotNull(held);
        assertEquals(MubtiClass.C.S, held.classC(), "한 곡 차이로 유형이 뒤집혔다");
    }

    @Test
    @DisplayName("충분히 기울면 직전 판정을 버리고 바꾼다")
    void hysteresisYieldsToStrongEvidence() {
        Map<Integer, Long> listening = listening(300_000, 300_000, 300_000, 300_000);
        MubtiVerdict flipped = scorer.judge(input(
            listening, distinctArtists(listening), Map.of(), 0.5,
            plays(20, 1, 0, 21), Set.of(), "VTSC"));
        assertNotNull(flipped);
        assertEquals(MubtiClass.C.F, flipped.classC(), "명백한 완주형인데 스킵형으로 붙들렸다");
    }

    @Test
    @DisplayName("저장된 코드가 규칙과 어긋나도 판정이 깨지지 않는다")
    void brokenPreviousCodeIsIgnored() {
        Map<Integer, Long> listening = listening(300_000, 300_000, 300_000, 300_000);
        for (String broken : new String[] {"", "XX", "ZZZZ", "VTFCE"}) {
            MubtiVerdict verdict = scorer.judge(input(
                listening, distinctArtists(listening), Map.of(), 0.5,
                plays(10, 1, 0, 11), Set.of(), broken));
            assertNotNull(verdict, "저장된 코드가 '%s' 일 때 판정이 사라졌다".formatted(broken));
            assertEquals(4, verdict.code().length());
        }
    }

    @Test
    @DisplayName("코드는 네 축을 순서대로 이어 붙인 네 글자다")
    void codeShape() {
        Map<Integer, Long> listening = listening(300_000, 300_000, 300_000, 300_000);
        MubtiVerdict verdict = scorer.judge(input(
            listening, sameArtist(listening, 3), Map.of(), 0.5,
            plays(10, 1, 0, 11), Set.of(), null));
        assertNotNull(verdict);
        assertEquals(
            "%s%s%s%s".formatted(
                verdict.classA().name(), verdict.classB().name(),
                verdict.classC().name(), verdict.classD().name()),
            verdict.code());
        assertEquals(4, verdict.axes().size());
    }

    private static MubtiVerdict.AxisScore axis(MubtiVerdict verdict, String name) {
        return verdict.axes().stream()
            .filter(it -> it.axis().equals(name))
            .findFirst()
            .orElseThrow();
    }

    /** 직전 판정 코드에서 한 글자만 바꾼 값. */
    private static String previousWith(MubtiVerdict verdict, int index, char letter) {
        char[] code = verdict.code().toCharArray();
        code[index] = letter;
        return new String(code);
    }

}
