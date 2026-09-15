package com.osundosun.mupid.api.report.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.analytics.dto.DailyPlaySummary;
import com.osundosun.mupid.api.report.dto.MubtiInput;
import com.osundosun.mupid.api.report.dto.MubtiVerdict;
import com.osundosun.mupid.api.report.enumerate.MubtiClass;

/**
 * MuBTI 판정 규칙 전부. **계수와 임계값은 이 클래스의 상수 말고 어디에도 두지 않는다.**
 * 흩어 놓으면 축끼리 조율이 불가능해진다.
 *
 * <h2>왜 저장하지 않고 매번 계산하는가</h2>
 *
 * 분류 기준과 가중치는 서비스가 굴러가면서 계속 바뀔 값이다. 계산 결과를 테이블에
 * 넣으면 기준을 고쳐도 과거 데이터는 옛 기준으로 남아 새 데이터와 섞인다. 그래서
 * 스킵 분류를 MATERIALIZED VIEW 가 아니라 일반 VIEW 로 둔 것과 같은 판단을 여기서도
 * 한다 — knowledge/decision/0015.
 *
 * <h2>축마다 신호의 질이 다르다</h2>
 *
 * <ul>
 *   <li><b>C축(완주/스킵)</b> 이 가장 깨끗하다. `PLAY_END` 와 `TRACK_SKIP` 이 그대로
 *       답이다.</li>
 *   <li><b>D축(국밥/개척)</b> 도 좋다. "전에 들어본 곡인가" 는 사실 판정이다.</li>
 *   <li><b>B축(트렌드/매니아)</b> 은 카탈로그가 작을 때 재생수 백분위가 노이즈라
 *       집중도(HHI)에 더 무게를 뒀다.</li>
 *   <li><b>A축(보컬/멜로디)</b> 이 가장 약하다. `has_vocal` 은 <b>곡의 속성이지 듣는
 *       방식이 아니다.</b> "후렴만 되감아 듣는가" 를 보려면 구간별 청취량이 필요한데
 *       `track_sections` 를 채우는 파이프라인이 아직 없다. 그래서 `confidence` 를
 *       같이 내보내 화면이 약한 축을 흐리게 그릴 수 있게 한다.</li>
 * </ul>
 */
@Component
public class MubtiScorer {

    // ── 판정할 수 있는 최소 표본 ──────────────────────────────────────────
    //
    // 아침에 두 곡 듣고 "당신은 스킵형" 이라고 단정하면 판정 자체를 못 믿게 된다.
    // 모자라면 유형을 만들지 않고 화면이 "아직 일러요" 를 보여준다.

    /** 5분. 이보다 적게 들은 날은 판정하지 않는다. */
    public static final long MIN_LISTENING_MS = 5 * 60 * 1000L;

    /** 서로 다른 곡 3곡. 한 곡만 반복한 날은 어느 축도 의미가 없다. */
    public static final int MIN_DISTINCT_MUSIC = 3;

    // ── 이력 현상 ────────────────────────────────────────────────────────

    /**
     * 한 번 정해진 글자를 뒤집으려면 반대편 점수가 이만큼은 되어야 한다.
     *
     * 이게 없으면 곡 하나 스킵할 때마다 `F` 와 `S` 가 번갈아 나온다. 실시간으로
     * 갱신되는 화면에서는 그 깜빡임이 곧 "이 판정은 아무 의미 없다" 로 읽힌다.
     */
    private static final double FLIP_MARGIN = 0.15;

    // ── A축: 보컬(V) / 멜로디(I) ────────────────────────────────────────

    /** 기준선 대비 편차를 −1~+1 로 펼치는 배율. 0.4 만큼 치우치면 만점이다. */
    private static final double VOCAL_LIFT_GAIN = 2.5;

    // ── B축: 트렌드(T) / 매니아(M) ──────────────────────────────────────

    private static final double CONCENTRATION_WEIGHT = 0.55;
    private static final double POPULARITY_WEIGHT = 0.30;
    private static final double FRESHNESS_WEIGHT = 0.15;

    // ── C축: 완주(F) / 스킵(S) ──────────────────────────────────────────

    private static final double FINISH_RATE_WEIGHT = 0.70;
    private static final double SEEK_OUT_WEIGHT = 0.30;

    /** 재생 한 번에 앞으로감기 두 번이면 "스킵형" 쪽 만점으로 본다. */
    private static final double SEEK_OUT_SATURATION = 2.0;

    // ── D축: 국밥(C) / 개척(E) ──────────────────────────────────────────
    //
    // 탐색량과 반복량을 **각각 절대량으로** 재고 뺀다. 비율(새 곡 / 전체)로 재지 않는다.
    //
    // 비율은 제로섬이라 "플리를 계속 돌리면서 동시에 새 곡도 활발히 디깅하는 사람" 을
    // 표현할 수 없다 — 두 행동이 서로의 분모가 되어 깎아 먹는다. 그 사람은 개척형이다.
    //
    // 세는 대상도 청취 시간이 아니라 **재생 시도(PLAY_START)** 다. 하트비트가 5초
    // 간격이라 그 전에 넘긴 곡은 청취 시간이 0 이고, 빠르게 디깅할수록 축이 눈을 감는다.

    /** 하루에 처음 만진 곡이 이만큼이면 탐색 만점. */
    private static final int DISCOVERY_SATURATION = 8;

    /** 보관함 곡을 평균 이만큼 더 반복하면 국밥 만점. (1 + 2 = 곡당 3회) */
    private static final double COMFORT_REPEAT_SATURATION = 2.0;

    /**
     * 탐색이 국밥보다 무겁다. <b>이 비대칭이 이 축의 핵심 의도다.</b>
     *
     * 플레이리스트의 최애곡을 계속 돌리면서 동시에 새 곡을 활발히 디깅하는 사람은
     * 개척형이어야 한다. 비율로 재면 두 행동이 서로를 깎아 먹어 그 사람을 표현할 수
     * 없다. 그래서 두 성향을 <b>각각 절대량으로</b> 재고, 탐색 쪽에 더 큰 계수를 준다.
     */
    private static final double EXPLORE_WEIGHT = 1.2;

    private static final double COMFORT_WEIGHT = 0.5;

    /**
     * 판정한다. 표본이 모자라면 `null` 이다 — 호출부가 `sufficient: false` 로 내보낸다.
     */
    public MubtiVerdict judge(MubtiInput input) {
        Map<Integer, Long> listening = input.listeningMsByMusic();
        long totalMs = listening.values().stream().mapToLong(Long::longValue).sum();
        if (totalMs < MIN_LISTENING_MS || listening.size() < MIN_DISTINCT_MUSIC) {
            return null;
        }

        Axis a = scoreVocal(input, listening, totalMs);
        Axis b = scoreTaste(input, listening, totalMs);
        Axis c = scorePlayback(input.playSummary());
        Axis d = scoreDiscovery(input, listening, totalMs);

        String previous = input.previousCode();
        MubtiClass.A pickedA = pick(a.score(), MubtiClass.A.V, MubtiClass.A.I, charAt(previous, 0), MubtiClass.A.class);
        MubtiClass.B pickedB = pick(b.score(), MubtiClass.B.M, MubtiClass.B.T, charAt(previous, 1), MubtiClass.B.class);
        MubtiClass.C pickedC = pick(c.score(), MubtiClass.C.F, MubtiClass.C.S, charAt(previous, 2), MubtiClass.C.class);
        MubtiClass.D pickedD = pick(d.score(), MubtiClass.D.E, MubtiClass.D.C, charAt(previous, 3), MubtiClass.D.class);

        List<MubtiVerdict.AxisScore> axes = new ArrayList<>(4);
        axes.add(new MubtiVerdict.AxisScore("A", pickedA, pickedA.opposite(), a.score(), a.confidence()));
        axes.add(new MubtiVerdict.AxisScore("B", pickedB, pickedB.opposite(), b.score(), b.confidence()));
        axes.add(new MubtiVerdict.AxisScore("C", pickedC, pickedC.opposite(), c.score(), c.confidence()));
        axes.add(new MubtiVerdict.AxisScore("D", pickedD, pickedD.opposite(), d.score(), d.confidence()));

        return new MubtiVerdict(pickedA, pickedB, pickedC, pickedD, axes);
    }

    /**
     * A축. 보컬곡을 얼마나 편식하는가.
     *
     * **절대 비중이 아니라 카탈로그 기준선 대비 편차**다. 카탈로그의 90%가 보컬곡이면
     * 절대 비중으로는 모두가 보컬파가 되어 축이 죽는다.
     *
     * 분석되지 않은 곡은 분자·분모 양쪽에서 빠진다. `has_vocal` 이 없는 것을 `false`
     * 로 읽으면 분석이 덜 된 카탈로그에서 모두가 멜로디파가 된다.
     */
    private Axis scoreVocal(MubtiInput input, Map<Integer, Long> listening, long totalMs) {
        long analyzedMs = 0;
        long vocalMs = 0;
        for (Map.Entry<Integer, Long> entry : listening.entrySet()) {
            Boolean hasVocal = input.hasVocalByMusic().get(entry.getKey());
            if (hasVocal == null) continue;
            analyzedMs += entry.getValue();
            if (hasVocal) vocalMs += entry.getValue();
        }

        // 근거가 없으면 판정하지 않는다. 기본값 V 로 두되 확신도 0 을 실어 보낸다 —
        // 축을 비우면 화면이 16유형 중 하나를 그리지 못한다.
        if (analyzedMs == 0) {
            return new Axis(0.0, 0.0);
        }

        double myShare = (double)vocalMs / analyzedMs;
        double score = clamp((myShare - input.catalogVocalShare()) * VOCAL_LIFT_GAIN);
        // 분석된 곡으로 들은 시간의 비중이 곧 이 판정의 근거량이다.
        return new Axis(score, (double)analyzedMs / totalMs);
    }

    /**
     * B축. 좁고 깊게(M) 인가 넓고 대중적으로(T) 인가.
     *
     * 집중도는 아티스트별 청취 시간 점유율의 허핀달 지수다. 아티스트가 적으면 지수가
     * 자동으로 높아지므로 `(HHI − 1/n) / (1 − 1/n)` 로 정규화한다 — 그래야 "두
     * 아티스트를 반반 들었다" 가 집중이 아니라 분산으로 읽힌다.
     */
    private Axis scoreTaste(MubtiInput input, Map<Integer, Long> listening, long totalMs) {
        Map<Integer, Long> byArtist = new HashMap<>();
        long knownArtistMs = 0;
        for (Map.Entry<Integer, Long> entry : listening.entrySet()) {
            Integer artistId = input.artistByMusic().get(entry.getKey());
            if (artistId == null) continue;
            byArtist.merge(artistId, entry.getValue(), Long::sum);
            knownArtistMs += entry.getValue();
        }

        double concentration = 0.0;
        if (knownArtistMs > 0 && byArtist.size() > 1) {
            double hhi = 0.0;
            for (long ms : byArtist.values()) {
                double share = (double)ms / knownArtistMs;
                hhi += share * share;
            }
            double floor = 1.0 / byArtist.size();
            concentration = (hhi - floor) / (1.0 - floor);
        } else if (byArtist.size() == 1) {
            // 하루 종일 한 아티스트만 들었다. 정규화 식은 0/0 이 되지만 뜻은 명백하다.
            concentration = 1.0;
        }

        // 백분위와 신곡 비중은 청취 시간으로 가중한다. 30초 튼 곡과 4분 들은 곡을
        // 같은 무게로 세면 스킵한 곡이 취향을 좌우한다.
        double popularity = 0.0;
        long ratedMs = 0;
        long freshMs = 0;
        for (Map.Entry<Integer, Long> entry : listening.entrySet()) {
            Double percentile = input.popularityPercentileByMusic().get(entry.getKey());
            if (percentile != null) {
                popularity += percentile * entry.getValue();
                ratedMs += entry.getValue();
            }
            if (input.freshMusicIds().contains(entry.getKey())) {
                freshMs += entry.getValue();
            }
        }
        popularity = ratedMs == 0 ? 0.5 : popularity / ratedMs;
        double freshShare = (double)freshMs / totalMs;

        double score = clamp(
            CONCENTRATION_WEIGHT * (concentration * 2 - 1)
                - POPULARITY_WEIGHT * (popularity * 2 - 1)
                - FRESHNESS_WEIGHT * (freshShare * 2 - 1));
        return new Axis(score, byArtist.isEmpty() ? 0.0 : (double)knownArtistMs / totalMs);
    }

    /**
     * C축. 끝까지 듣는가(F) 넘기며 찾는가(S).
     *
     * `PLAYBACK_ABANDON`(앱이 닫혀 끊김)과 `PLAY_ERROR` 는 세지 않는다. 의도한 스킵이
     * 아닌 것을 스킵으로 세면 네트워크가 나쁜 사용자가 전부 스킵형이 된다 —
     * knowledge/domain/listening-events.md 가 `PLAY_STALL` 을 굳이 남기는 이유와 같다.
     */
    private Axis scorePlayback(DailyPlaySummary summary) {
        long decided = summary.ends() + summary.skips();
        if (decided == 0) {
            return new Axis(0.0, 0.0);
        }

        double finishRate = (double)summary.ends() / decided;
        double seekOutRate = summary.plays() == 0
            ? 0.0
            : (double)summary.seekForwards() / summary.plays();

        double score = clamp(
            FINISH_RATE_WEIGHT * (finishRate * 2 - 1)
                - SEEK_OUT_WEIGHT * clamp(seekOutRate / SEEK_OUT_SATURATION));
        // 판정 근거가 된 재생이 10회는 되어야 확신한다.
        return new Axis(score, Math.min(1.0, decided / 10.0));
    }

    /**
     * D축. 익숙한 것을 반복하는가(C) 낯선 것을 찾는가(E).
     *
     * <h2>비율이 아니라 빈도로 잰다</h2>
     *
     * 두 성향을 <b>각각 절대량으로</b> 재고 뺀다.
     *
     * <pre>
     *   explore = clamp(처음 만진 곡 수 / 8)
     *   comfort = clamp((익숙한 곡의 평균 재생 횟수 − 1) / 2)
     *   score   = clamp(explore × 1.2 − comfort × 0.5)
     * </pre>
     *
     * 비율(새 곡 / 전체)로 재면 <b>"플레이리스트를 계속 돌리면서 동시에 새 곡도 활발히
     * 디깅하는 사람"</b> 을 표현할 수 없다. 두 행동이 서로의 분모가 되어 깎아 먹기
     * 때문이다. 그 사람은 개척형이어야 하므로, 탐색 계수를 국밥 계수보다 크게 둔다.
     *
     * <h2>청취 시간이 아니라 재생 시도를 센다</h2>
     *
     * 하트비트가 5초 간격이라 <b>그 전에 넘긴 곡은 청취 시간이 0 이고 집계에 나타나지도
     * 않는다.</b> "빠르게 넘기며 디깅한다" 가 정확히 그 모양이라, 시간으로 재면 가장
     * 활발히 탐색한 사람이 아무것도 안 한 것처럼 보인다. 그래서 `PLAY_START` 를 센다.
     *
     * <h2>무엇을 "익숙한 곡" 으로 보는가</h2>
     *
     * 최근 며칠 안에 들었거나(`heardBeforeMusicIds`), 좋아요·플레이리스트로 직접 담아
     * 둔 곡(`libraryMusicIds`)이다. 후자가 국밥형 정의의 핵심 — "검증된 최애곡" 은
     * 최근에 들었는지와 무관하게 익숙한 곡이다.
     *
     * 최근 며칠의 기준은 <b>그날 이전</b>이다. 오늘 아침에 튼 곡이 오후에 익숙한 곡이
     * 되어 버리면 같은 하루 안에서 축이 시간대에 따라 흔들린다.
     */
    private Axis scoreDiscovery(MubtiInput input, Map<Integer, Long> listening, long totalMs) {
        // 그날 손댄 곡 전부. 재생 시도(PLAY_START)가 기준이고, 하트비트만 남은 곡도
        // 합친다 — PLAY_START 가 유실돼도 축이 통째로 죽지 않게.
        Set<Integer> touched = new HashSet<>(input.playsByMusic().keySet());
        touched.addAll(listening.keySet());
        if (touched.isEmpty()) {
            return new Axis(0.0, 0.0);
        }

        int discovered = 0;
        int comfortTracks = 0;
        long comfortPlays = 0;
        for (Integer musicId : touched) {
            boolean familiar = input.heardBeforeMusicIds().contains(musicId)
                || input.libraryMusicIds().contains(musicId);
            if (familiar) {
                comfortTracks++;
                // PLAY_START 가 없는 곡은 최소 1회 튼 것으로 본다.
                comfortPlays += Math.max(1L, input.playsByMusic().getOrDefault(musicId, 1L));
            } else {
                discovered++;
            }
        }

        double explore = clamp((double)discovered / DISCOVERY_SATURATION);
        double comfortRepeat = comfortTracks == 0 ? 1.0 : (double)comfortPlays / comfortTracks;
        double comfort = clamp((comfortRepeat - 1) / COMFORT_REPEAT_SATURATION);

        double score = clamp(explore * EXPLORE_WEIGHT - comfort * COMFORT_WEIGHT);
        return new Axis(score, Math.min(1.0, touched.size() / 6.0));
    }

    /**
     * 점수와 직전 판정으로 글자를 고른다.
     *
     * 직전 판정이 있으면 **반대편으로 `FLIP_MARGIN` 만큼 넘어가야** 바뀐다. 0 근처에서
     * 조회할 때마다 글자가 뒤집히는 것을 막는 장치다.
     */
    private <T extends Enum<T>> T pick(double score, T positive, T negative, Character previous, Class<T> type) {
        T now = score >= 0 ? positive : negative;
        if (previous == null) {
            return now;
        }
        T held;
        try {
            held = Enum.valueOf(type, String.valueOf(previous));
        } catch (IllegalArgumentException e) {
            // 저장된 코드가 이 축의 글자가 아니다. 규칙이 바뀐 뒤 남은 값일 수 있다.
            return now;
        }
        if (held == now) {
            return now;
        }
        return Math.abs(score) >= FLIP_MARGIN ? now : held;
    }

    private static Character charAt(String code, int index) {
        return code == null || code.length() != 4 ? null : code.charAt(index);
    }

    private static double clamp(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    /** 축 하나의 중간 계산 결과. */
    private record Axis(double score, double confidence) {

    }

}
