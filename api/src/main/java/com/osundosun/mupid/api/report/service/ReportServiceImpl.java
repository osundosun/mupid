package com.osundosun.mupid.api.report.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.analytics.dto.DailyPlaySummary;
import com.osundosun.mupid.api.analytics.dto.DailyTrackListening;
import com.osundosun.mupid.api.analytics.dto.TrackPlayCount;
import com.osundosun.mupid.api.analytics.repository.ListenAnalyticsRepository;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.entity.MusicAnalysis;
import com.osundosun.mupid.api.music.enumerate.ReactionType;
import com.osundosun.mupid.api.music.repository.MusicAnalysisRepository;
import com.osundosun.mupid.api.music.repository.MusicReactionRepository;
import com.osundosun.mupid.api.music.service.MusicSummaryAssembler;
import com.osundosun.mupid.api.playlist.repository.PlaylistTrackRepository;
import com.osundosun.mupid.api.report.dto.MubtiInput;
import com.osundosun.mupid.api.report.dto.MubtiVerdict;
import com.osundosun.mupid.api.report.entity.Listening;
import com.osundosun.mupid.api.report.entity.Mubti;
import com.osundosun.mupid.api.report.entity.Report;
import com.osundosun.mupid.api.report.enumerate.KeywordKind;
import com.osundosun.mupid.api.report.enumerate.ReportSource;
import com.osundosun.mupid.api.report.error.ReportErrorCode;
import com.osundosun.mupid.api.report.repository.ListeningRepository;
import com.osundosun.mupid.api.report.repository.MubtiRepository;
import com.osundosun.mupid.api.report.repository.ReportRepository;
import com.osundosun.mupid.api.report.response.ReportResponse;
import com.osundosun.mupid.api.storage.service.StorageImageService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * '내 취향' 리포트. ClickHouse 집계 + MySQL 곡 메타 + MuBTI 판정을 한 응답으로 묶는다.
 *
 * <h2>하루의 경계</h2>
 *
 * `Asia/Seoul` 기준이다. `listen_events.occurred_at` 이
 * `DateTime64(3, 'Asia/Seoul')` 이라 ClickHouse 쪽과 시간대를 맞출 필요가 없다.
 *
 * <h2>왜 조회마다 다시 집계하는가</h2>
 *
 * 판정 기준은 계속 바뀔 값이라 계산 결과를 저장하면 기준을 고쳐도 과거 데이터가
 * 따라오지 않는다 (decision/0015). 저장하는 것은 그날의 결론(`reports.mubti_id`)과
 * 곡별 청취량(`listening`)뿐이고, 축 점수는 응답에만 실린다.
 *
 * <h2>지연</h2>
 *
 * 방금 들은 곡이 여기 반영되기까지 평균 20초, 최악 45초 걸린다 — 하트비트 5초 +
 * 프론트 큐 5초 + ClickHouse 의 `kafka_flush_interval_ms` 15초다. 그 15초는 작은
 * 파트가 쌓여 병합 압력이 오르는 것을 막으려고 일부러 올려 둔 값이라 이 화면 때문에
 * 낮추지 않는다. 화면은 30초 폴링으로 그 지연을 덮는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportServiceImpl implements ReportService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    /** 트리맵에 그릴 타일 수 상한. 그보다 잘게 나뉘면 화면에서 읽히지 않는다. */
    private static final int MAX_TRACKS = 40;

    /** 화면의 키워드 칩 개수. */
    private static final int MAX_KEYWORDS = 6;

    /**
     * "최근에 들어본 곡" 을 어디까지 거슬러 볼 것인가.
     *
     * 3일이다. 예전에 한 번 스쳐 간 곡을 다시 찾아 듣는 것은 오히려 탐색에 가깝고,
     * 창을 길게 잡으면 카탈로그가 클수록 "처음 만지는 곡" 이 구조적으로 줄어든다.
     */
    private static final int HEARD_BEFORE_LOOKBACK_DAYS = 3;

    /** 발매 후 이 기간 안이면 신곡으로 본다 (B축 트렌드). */
    private static final int FRESH_RELEASE_DAYS = 90;

    /** 전에 들어본 곡 집합은 그날 안에 변하지 않는다. */
    private static final Duration HEARD_BEFORE_TTL = Duration.ofHours(6);

    /** 직전 판정은 그날이 지나면 의미가 없다. */
    private static final Duration VERDICT_TTL = Duration.ofHours(26);

    /** 오늘치 스냅샷을 이 간격보다 자주 쓰지 않는다. */
    private static final Duration SNAPSHOT_THROTTLE = Duration.ofMinutes(5);

    private final ListenAnalyticsRepository analytics;
    private final MusicSummaryAssembler assembler;
    private final MusicAnalysisRepository analysisRepository;
    private final MusicReactionRepository reactionRepository;
    private final PlaylistTrackRepository playlistTrackRepository;
    private final CatalogBaseline baseline;
    private final MubtiScorer scorer;
    private final MubtiRepository mubtiRepository;
    private final ReportRepository reportRepository;
    private final ListeningRepository listeningRepository;
    private final ReportSnapshotWriter snapshotWriter;
    private final CacheService cacheService;

    @Override
    public ReportResponse getDailyReport(int userId, LocalDate date) {
        LocalDate today = LocalDate.now(ZONE);
        if (date == null || date.isAfter(today)) {
            throw new BusinessException(ReportErrorCode.REPORT_INVALID_DATE);
        }

        // 지난 날짜는 이미 확정된 사실이다. 저장돼 있으면 ClickHouse 를 건드리지 않는다.
        if (date.isBefore(today)) {
            Optional<Report> stored = reportRepository
                .findByUserAndStartDate(userId, date.atStartOfDay());
            if (stored.isPresent()) {
                return fromStored(stored.get(), date);
            }
        }

        Computed computed = compute(userId, date, date.equals(today));

        // 적재. 오늘치는 5분에 한 번만, 지난 날짜는 없을 때 그 자리에서 채운다.
        if (computed.verdict() != null) {
            boolean shouldWrite = !date.equals(today)
                || cacheService.setIfAbsent(
                    "%d:%s".formatted(userId, date), CacheKind.ReportSnapshot, SNAPSHOT_THROTTLE);
            if (shouldWrite) {
                storeQuietly(userId, date, computed);
            }
        }

        return computed.response();
    }

    @Override
    public boolean storeDailyReport(int userId, LocalDate date) {
        Computed computed = compute(userId, date, false);
        if (computed.verdict() == null) {
            return false;
        }
        snapshotWriter.store(userId, date, computed.mubti(), computed.listeningMsByMusic());
        return true;
    }

    /**
     * 적재가 실패해도 조회는 성공시킨다.
     *
     * 적재는 부수 효과다. 여기서 터져 화면이 500 이 되면 볼 수 있었던 리포트를 못 보게
     * 된다 — 수집기가 "최근 감상" 사본 저장 실패를 삼키는 것과 같은 판단이다
     * (decision/0018).
     *
     * 삼키는 자리가 <b>여기</b> 인 이유: `@Transactional` 안에서 예외를 잡으면
     * 트랜잭션이 이미 rollback-only 라 커밋 때 `UnexpectedRollbackException` 이 다시
     * 튀어나온다. 다른 빈의 경계 밖에서 잡아야 진짜로 삼켜진다.
     */
    private void storeQuietly(int userId, LocalDate date, Computed computed) {
        try {
            snapshotWriter.store(userId, date, computed.mubti(), computed.listeningMsByMusic());
        } catch (RuntimeException e) {
            // 같은 (user_id, start_date) 를 두 인스턴스가 동시에 쓰면 유니크 제약이
            // 한쪽을 막는다. 남은 행의 내용은 같으므로 다시 시도하지 않는다.
            log.warn("리포트 스냅샷 저장 실패 user_id={} date={}", userId, date, e);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 집계
    // ─────────────────────────────────────────────────────────────

    /**
     * ClickHouse 와 MySQL 을 읽어 응답을 만든다.
     *
     * @param withHysteresis 오늘치처럼 같은 날 여러 번 조회되는 경우에만 켠다.
     *                       지난 날짜를 확정 저장할 때 직전 판정에 끌려가면 안 된다.
     */
    private Computed compute(int userId, LocalDate date, boolean withHysteresis) {
        List<DailyTrackListening> raw;
        DailyPlaySummary playSummary;
        Map<Integer, Long> playsByMusic = new HashMap<>();
        Set<Integer> heardBefore;
        try {
            raw = analytics.trackListening(userId, date);
            playSummary = analytics.playSummary(userId, date);
            // 재생 시도는 청취 시간과 따로 읽는다. 5초 하트비트 전에 넘긴 곡은
            // 청취 집계(raw)에 행이 없어서 D축이 디깅을 보지 못한다.
            for (TrackPlayCount row : analytics.playStartsByTrack(userId, date)) {
                playsByMusic.put(row.trackId(), row.plays());
            }
            heardBefore = heardBefore(userId, date);
        } catch (DataAccessException e) {
            // 분석 클러스터가 죽어도 이 화면 하나만 실패한다. 인증·회원 같은 핵심
            // 기능은 ClickHouse 와 무관하므로 컨테이너 헬스로 번지면 안 된다 —
            // ClickHouseConfig 가 헬스 컨트리뷰터를 떼어 둔 것과 같은 판단이다.
            log.warn("취향 리포트 집계 실패 user_id={} date={}", userId, date, e);
            throw new BusinessException(ReportErrorCode.REPORT_ANALYTICS_UNAVAILABLE);
        }

        long rawTotalMs = raw.stream().mapToLong(DailyTrackListening::listenedMs).sum();
        if (raw.isEmpty()) {
            return new Computed(empty(date, playSummary), null, null, Map.of());
        }

        // 곡 메타는 화면에 필요한 것과 판정에 필요한 것이 같다. 한 번만 조립한다.
        List<Integer> musicIds = raw.stream().map(DailyTrackListening::trackId).toList();
        Map<Integer, MusicSummary> musics = new HashMap<>();
        for (MusicSummary summary : assembler.byIds(musicIds)) {
            musics.put(summary.musicId(), summary);
        }

        // MySQL 에 없는 track_id 는 그릴 수 없어 버린다. 버린 양은 응답에 싣는다 —
        // 조용히 사라지면 track_id 와 music_id 가 어긋나도 아무도 모른다 (C5).
        Map<Integer, Long> listeningMs = new LinkedHashMap<>();
        long matchedMs = 0;
        for (DailyTrackListening row : raw) {
            if (!musics.containsKey(row.trackId())) continue;
            listeningMs.merge(row.trackId(), row.listenedMs(), Long::sum);
            matchedMs += row.listenedMs();
        }
        long unmatchedMs = rawTotalMs - matchedMs;

        if (listeningMs.isEmpty()) {
            ReportResponse response = empty(date, playSummary);
            return new Computed(
                new ReportResponse(
                    response.date(), response.generatedAt(), response.source(),
                    0, unmatchedMs, List.of(), List.of(), null, response.sample()),
                null, null, Map.of());
        }

        MubtiVerdict verdict = scorer.judge(buildInput(
            userId, date, listeningMs, musics, playSummary, playsByMusic, heardBefore, withHysteresis));

        ReportResponse.Mubti mubtiView = null;
        Mubti mubtiRow = null;
        if (verdict != null) {
            mubtiRow = mubtiRepository
                .findByClassAAndClassBAndClassCAndClassD(
                    verdict.classA(), verdict.classB(), verdict.classC(), verdict.classD())
                .orElseThrow(() -> new BusinessException(ReportErrorCode.REPORT_MUBTI_NOT_SEEDED));
            mubtiView = toMubtiView(mubtiRow, verdict);
            if (withHysteresis) {
                cacheService.set(
                    "%d:%s".formatted(userId, date), verdict.code(), CacheKind.ReportVerdict, VERDICT_TTL);
            }
        }

        ReportResponse response = new ReportResponse(
            date,
            LocalDateTime.now(ZONE),
            ReportSource.LIVE,
            matchedMs,
            unmatchedMs,
            toTiles(listeningMs, musics, matchedMs),
            toKeywords(listeningMs, musics, matchedMs),
            mubtiView,
            new ReportResponse.Sample(
                verdict != null, playSummary.plays(), listeningMs.size(), matchedMs));

        return new Computed(response, verdict, mubtiRow, listeningMs);
    }

    private MubtiInput buildInput(
        int userId,
        LocalDate date,
        Map<Integer, Long> listeningMs,
        Map<Integer, MusicSummary> musics,
        DailyPlaySummary playSummary,
        Map<Integer, Long> playsByMusic,
        Set<Integer> heardBefore,
        boolean withHysteresis
    ) {
        CatalogBaseline.Snapshot catalog = baseline.get();

        Map<Integer, Integer> artistByMusic = new HashMap<>();
        Map<Integer, Double> popularity = new HashMap<>();
        Set<Integer> fresh = new HashSet<>();
        LocalDateTime freshFrom = date.minusDays(FRESH_RELEASE_DAYS).atStartOfDay();
        for (Integer musicId : listeningMs.keySet()) {
            MusicSummary music = musics.get(musicId);
            artistByMusic.put(musicId, music.artist().artistId());
            // `music.play_count` 가 아니라 ClickHouse 재생 로그 기준이다. 그 컬럼은
            // 올리는 코드가 없어 전 곡이 0 이라 백분위가 상수가 된다 (CatalogBaseline).
            popularity.put(musicId, catalog.percentileOfTrack(musicId));
            if (music.releaseDate() != null && music.releaseDate().isAfter(freshFrom)) {
                fresh.add(musicId);
            }
        }

        // 분석 행이 없는 곡은 **키를 만들지 않는다.** `false` 로 채우면 분석이 덜 된
        // 카탈로그에서 모두가 멜로디파가 된다.
        Map<Integer, Boolean> hasVocal = new HashMap<>();
        for (MusicAnalysis analysis : analysisRepository.findByMusicIdIn(List.copyOf(listeningMs.keySet()))) {
            if (analysis.getHasVocal() != null) {
                hasVocal.put(analysis.getMusicId(), analysis.getHasVocal());
            }
        }

        String previous = withHysteresis
            ? cacheService.get("%d:%s".formatted(userId, date), CacheKind.ReportVerdict)
            : null;

        // 보관함 = 좋아요 + 플레이리스트 수록곡. 국밥형 정의의 "검증된 최애곡" 이다.
        // 표를 새로 만들지 않고 이미 있는 두 표만 읽는다.
        Set<Integer> library = new HashSet<>(
            reactionRepository.findMusicIds(userId, ReactionType.LIKE));
        library.addAll(playlistTrackRepository.findMusicIdsByUser(userId));

        return new MubtiInput(
            listeningMs, artistByMusic, hasVocal, popularity, fresh,
            catalog.vocalShare(), playSummary, playsByMusic, heardBefore, library, previous);
    }

    /**
     * 그날 이전에 들어본 곡. 90일치를 훑는 가장 무거운 질의라 따로 캐시한다.
     *
     * 이 값은 **그날 안에 변하지 않는다.** 오늘치 집계와 수명이 다르므로 리포트 전체를
     * 한 덩어리로 캐시하지 않고 이 조각만 길게 들고 있는다.
     */
    private Set<Integer> heardBefore(int userId, LocalDate date) {
        String key = "%d:%s".formatted(userId, date);
        String cached = cacheService.get(key, CacheKind.ReportHeardTracks);
        if (cached != null) {
            if (cached.isEmpty()) return Set.of();
            try {
                Set<Integer> parsed = new HashSet<>();
                for (String piece : cached.split(",")) {
                    parsed.add(Integer.parseInt(piece));
                }
                return parsed;
            } catch (NumberFormatException e) {
                // 캐시가 깨졌다. 리포트를 500 으로 만들 이유는 없다 — 다시 물어본다.
                log.warn("전에 들어본 곡 캐시가 깨져 있다. key={}", key, e);
            }
        }

        List<Integer> ids = analytics.tracksHeardBefore(userId, date, HEARD_BEFORE_LOOKBACK_DAYS);
        cacheService.set(
            key,
            String.join(",", ids.stream().map(String::valueOf).toList()),
            CacheKind.ReportHeardTracks,
            HEARD_BEFORE_TTL);
        return new HashSet<>(ids);
    }

    // ─────────────────────────────────────────────────────────────
    // 응답 조립
    // ─────────────────────────────────────────────────────────────

    private List<ReportResponse.TrackListening> toTiles(
        Map<Integer, Long> listeningMs, Map<Integer, MusicSummary> musics, long totalMs
    ) {
        return listeningMs.entrySet().stream()
            .sorted(Map.Entry.<Integer, Long>comparingByValue().reversed())
            .limit(MAX_TRACKS)
            .map(entry -> {
                MusicSummary music = musics.get(entry.getKey());
                return new ReportResponse.TrackListening(
                    music.musicId(),
                    music.title(),
                    music.artist().name(),
                    music.album().title(),
                    music.album().imageUrl(),
                    entry.getValue(),
                    totalMs == 0 ? 0 : (double)entry.getValue() / totalMs);
            })
            .toList();
    }

    /**
     * 분위기·장르를 **청취 시간으로 가중**해 상위 몇 개.
     *
     * 개수로 세면 30초 튼 곡과 4분 들은 곡이 같은 무게가 된다. 화면이 쓰던 로컬 계산
     * (저장한 순간의 태그 개수)을 그대로 대체하는 자리다.
     */
    private List<ReportResponse.Keyword> toKeywords(
        Map<Integer, Long> listeningMs, Map<Integer, MusicSummary> musics, long totalMs
    ) {
        Map<String, Long> moodMs = new HashMap<>();
        Map<String, Long> genreMs = new HashMap<>();
        for (Map.Entry<Integer, Long> entry : listeningMs.entrySet()) {
            MusicSummary music = musics.get(entry.getKey());
            for (String mood : music.moods()) moodMs.merge(mood, entry.getValue(), Long::sum);
            for (String genre : music.genres()) genreMs.merge(genre, entry.getValue(), Long::sum);
        }

        List<ReportResponse.Keyword> all = new ArrayList<>();
        moodMs.forEach((name, ms) -> all.add(new ReportResponse.Keyword(
            name, KeywordKind.MOOD, totalMs == 0 ? 0 : (double)ms / totalMs)));
        genreMs.forEach((name, ms) -> all.add(new ReportResponse.Keyword(
            name, KeywordKind.GENRE, totalMs == 0 ? 0 : (double)ms / totalMs)));

        all.sort(Comparator.comparingDouble(ReportResponse.Keyword::weight).reversed());
        return all.size() <= MAX_KEYWORDS ? all : all.subList(0, MAX_KEYWORDS);
    }

    private ReportResponse.Mubti toMubtiView(Mubti row, MubtiVerdict verdict) {
        return new ReportResponse.Mubti(
            verdict.code(),
            row.getTitle(),
            row.getSubtitle(),
            row.getDescription(),
            StorageImageService.imageUrl(row.getImageFile()),
            verdict.axes().stream()
                .map(axis -> new ReportResponse.Axis(
                    axis.axis(),
                    axis.picked().name(),
                    axis.picked().getDisplayName(),
                    axis.opposite().name(),
                    axis.opposite().getDisplayName(),
                    axis.score(),
                    axis.confidence()))
                .toList());
    }

    /**
     * 저장된 스냅샷을 응답으로 바꾼다.
     *
     * 축 점수는 저장하지 않으므로 `score` 가 0, `confidence` 가 0 으로 나간다 —
     * 확정된 유형만 남기고 해석은 저장하지 않는다는 원칙(decision/0015)의 결과다.
     * 화면은 `source` 가 `STORED` 면 막대를 그리지 않는다.
     */
    private ReportResponse fromStored(Report report, LocalDate date) {
        List<Listening> rows = listeningRepository.findByReportId(report.getReportId());
        long totalMs = rows.stream().mapToLong(Listening::getListeningMs).sum();

        List<Integer> musicIds = rows.stream().map(it -> it.getListeningId().getMusicId()).toList();
        Map<Integer, MusicSummary> musics = new HashMap<>();
        for (MusicSummary summary : assembler.byIds(musicIds)) {
            musics.put(summary.musicId(), summary);
        }

        Map<Integer, Long> listeningMs = new LinkedHashMap<>();
        for (Listening row : rows) {
            int musicId = row.getListeningId().getMusicId();
            if (musics.containsKey(musicId)) {
                listeningMs.put(musicId, (long)row.getListeningMs());
            }
        }

        Mubti mubti = report.getMubti();
        List<ReportResponse.Axis> axes = List.of(
            storedAxis("A", mubti.getClassA(), mubti.getClassA().opposite()),
            storedAxis("B", mubti.getClassB(), mubti.getClassB().opposite()),
            storedAxis("C", mubti.getClassC(), mubti.getClassC().opposite()),
            storedAxis("D", mubti.getClassD(), mubti.getClassD().opposite()));

        return new ReportResponse(
            date,
            report.getCreatedAt(),
            ReportSource.STORED,
            totalMs,
            0,
            toTiles(listeningMs, musics, totalMs),
            toKeywords(listeningMs, musics, totalMs),
            new ReportResponse.Mubti(
                "%s%s%s%s".formatted(
                    mubti.getClassA().name(), mubti.getClassB().name(),
                    mubti.getClassC().name(), mubti.getClassD().name()),
                mubti.getTitle(),
                mubti.getSubtitle(),
                mubti.getDescription(),
                StorageImageService.imageUrl(mubti.getImageFile()),
                axes),
            // 재생 수는 저장하지 않는다. 지난 날짜의 표본 크기는 곡 수와 청취 시간으로 본다.
            new ReportResponse.Sample(true, null, listeningMs.size(), totalMs));
    }

    private static ReportResponse.Axis storedAxis(
        String axis,
        com.osundosun.mupid.api.report.enumerate.MubtiClass.Axis picked,
        com.osundosun.mupid.api.report.enumerate.MubtiClass.Axis opposite
    ) {
        return new ReportResponse.Axis(
            axis, picked.name(), picked.getDisplayName(),
            opposite.name(), opposite.getDisplayName(), 0, 0);
    }

    /** 그날 들은 것이 없을 때. 오류가 아니라 빈 리포트다. */
    private ReportResponse empty(LocalDate date, DailyPlaySummary playSummary) {
        return new ReportResponse(
            date, LocalDateTime.now(ZONE), ReportSource.LIVE, 0, 0,
            List.of(), List.of(), null,
            new ReportResponse.Sample(false, playSummary.plays(), 0, 0));
    }

    /**
     * 집계 결과 한 벌. 응답과, 적재에 필요한 원재료를 함께 들고 다닌다.
     *
     * `verdict` 가 null 이면 표본이 모자라 판정하지 않은 것이고, 그때는 적재도 하지
     * 않는다 — `reports.mubti_id` 가 NOT NULL 이다.
     */
    private record Computed(
        ReportResponse response,
        MubtiVerdict verdict,
        Mubti mubti,
        Map<Integer, Long> listeningMsByMusic
    ) {

    }

}
