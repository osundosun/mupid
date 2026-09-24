package com.osundosun.mupid.api.recommendation.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.ai.catalog.RecommenderCatalogService;
import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.client.RecommenderDto;
import com.osundosun.mupid.api.ai.tool.RecommendReason;
import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.repository.CodeRepository;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.mapping.repository.MusicSectionAnalysisMoodRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.service.MusicSectionService;
import com.osundosun.mupid.api.music.service.MusicSummaryAssembler;
import com.osundosun.mupid.api.onboarding.dto.CodeOption;
import com.osundosun.mupid.api.onboarding.response.OnboardingResultResponse;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;
import com.osundosun.mupid.api.recommendation.dto.RecommendedTrack;
import com.osundosun.mupid.api.recommendation.entity.DailyRecommendation;
import com.osundosun.mupid.api.recommendation.entity.DailyRecommendationTrack;
import com.osundosun.mupid.api.recommendation.repository.DailyRecommendationRepository;
import com.osundosun.mupid.api.recommendation.repository.DailyRecommendationTrackRepository;
import com.osundosun.mupid.api.recommendation.response.DailyRecommendationGroup;
import com.osundosun.mupid.api.recommendation.response.DailyRecommendationResponse;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserStatus;
import com.osundosun.mupid.api.user.error.UserErrorCode;
import com.osundosun.mupid.api.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 일일 추천 조회.
 *
 * 만드는 쪽은 {@link #generate}, 부르는 쪽은 `DailyRecommendationBatch` 다.
 * 화면은 저장된 것만 읽으므로 하루 동안 같은 곡이 나온다.
 *
 * 요청한 날짜에 추천이 없으면 **가장 최근 추천이 있는 날**로 되돌아간다.
 * 파이프라인이 매일 도는 보장이 없는데 화면이 빈 홈을 보여주면 곤란해서다.
 * 응답의 `date` 가 실제로 무엇을 돌려줬는지 말해 준다.
 *
 * <h2>그 되돌림도 빈 경우 — 갓 가입한 사용자</h2>
 *
 * 날짜 폴백은 **이미 한 번이라도 추천이 만들어진 사람**만 구한다. 방금 가입해
 * 배치를 한 번도 타지 않은 사람에게는 되돌아갈 날짜 자체가 없어서 홈이 빈 채로
 * 남았다. 그래서 저장된 추천이 하나도 없으면 {@link #coldStart} 가 온보딩에서
 * 고른 장르·분위기의 조회수 상위 곡을 그 자리에서 골라 내려준다.
 *
 * **폴백은 저장하지 않는다.** `daily_recommendations` 는 배치의 산출물이고,
 * 여기서 만든 것을 끼워 넣으면 배치가 "이미 만들었다" 고 판단해 진짜 추천을
 * 건너뛴다. 응답의 `recommendation_id` 가 0 인 것도 같은 이유다 — 저장된 묶음이
 * 아니라서 가리킬 번호가 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DailyRecommendationServiceImpl implements DailyRecommendationService {

    /**
     * 한 사람에게 하루에 보여 줄 곡 수. **추천 서버의 고정값과 같아야 한다.**
     *
     * <p>추천 서버는 오늘의 모먼트를 4개로 고정하고 다른 값은 400 으로 거절한다
     * (`ai_server/app/internal_api.py` 의 `DAILY_LIMIT`). 여기가 10 이던 채로 그쪽이
     * 먼저 배포되자 정오 배치가 전원 실패했다(2026-09-24).
     */
    static final int DAILY_LIMIT = 4;

    /** `daily_recommendations.title`·`reason` 이 VARCHAR(100) 이다. */
    private static final int TEXT_LIMIT = 100;

    private static final String TITLE = "오늘의 모먼트";

    /**
     * 저장된 추천이 하나도 없을 때 대신 골라 줄 곡 수.
     *
     * <p>배치가 만드는 {@link #DAILY_LIMIT} 을 넘지 않는다. 취향 한 줄로 고른 목록이라
     * 길수록 근거가 옅어지고, 화면은 첫 화면에서 네 곡이면 비어 보이지 않는다.
     */
    private static final int FALLBACK_LIMIT = 4;

    /**
     * 폴백 묶음의 제목.
     *
     * <p>배치가 만든 것과 **같은 제목을 쓰지 않는다.** 이 묶음은 오늘 만들어진
     * 「오늘의 모먼트」가 아니라 그것이 아직 없어서 대신 고른 목록이다.
     */
    private static final String FALLBACK_TITLE = "첫 모먼트";

    /**
     * 폴백 묶음의 번호.
     *
     * <p>저장된 묶음이 아니라 가리킬 번호가 없다. 계약(`recommendation_id`)이 숫자를
     * 요구해 `null` 을 쓸 수 없어 0 으로 둔다 — `daily_recommendation_id` 가
     * AUTO_INCREMENT 라 실제 묶음과 겹치지 않는다. 「오늘의 모먼트」 응답의 이 값을
     * 읽는 곳은 아직 없다(`frontend/lib/recommendations.ts` 의 `fetchTodayMoments`).
     */
    private static final int NO_RECOMMENDATION_ID = 0;

    /**
     * 아무 코드와도 맞지 않는 번호. 빈 목록을 그대로 `IN` 에 넣지 않으려고 쓴다.
     *
     * <p>`codes.code_id` 가 AUTO_INCREMENT 라 음수는 존재할 수 없다.
     */
    private static final int NO_CODE_ID = -1;

    private final DailyRecommendationRepository repository;
    private final DailyRecommendationTrackRepository trackRepository;
    private final MusicSummaryAssembler assembler;
    private final RecommenderClient recommender;
    private final RecommenderCatalogService catalogService;
    private final MusicSectionService musicSectionService;
    private final MusicRepository musicRepository;
    private final MusicSectionAnalysisMoodRepository moodRepository;
    private final CodeRepository codeRepository;
    private final OnboardingService onboardingService;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public boolean generate(int userId, LocalDate date, boolean force) {
        Optional<DailyRecommendation> existing =
            repository.findByUser_UserIdAndRecommendationDate(userId, date);
        if (existing.isPresent() && !force) {
            return false;
        }

        User user = userRepository.findByUserIdAndStatusNot(userId, UserStatus.WITHDRAW)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));

        // 추천 서버는 숫자 ID 를 모른다. 이 번호는 **로그 상관 키**다 — 저장 레코드의
        // 번호는 아래 INSERT 가 발급한다. (사용자·날짜마다 하나로 정해져 재현된다.)
        long correlationId = date.toEpochDay() * 1_000_000L + userId;
        List<RecommenderDto.Item> items = recommender.daily(new RecommenderDto.DailyRequest(
            correlationId, userId, DAILY_LIMIT, List.of(), onboardingOf(userId),
            catalogService.current().body()));
        if (items.isEmpty()) {
            log.info("오늘의 모먼트: 추천 결과가 없다 user_id={} date={}", userId, date);
            return false;
        }

        // 문자열 ID → 곡. 없는 것은 버리고 센다 (구간 적재가 색인보다 낡았다는 뜻).
        List<String> externalIds = items.stream()
            .map(RecommenderDto.Item::externalId).filter(Objects::nonNull).distinct().toList();
        Map<String, MusicSectionAnalysis> sections =
            musicSectionService.findByExternalIds(externalIds);

        LinkedHashMap<Integer, String> reasonByMusic = new LinkedHashMap<>();
        for (RecommenderDto.Item item : items) {
            MusicSectionAnalysis section = sections.get(item.externalId());
            if (section == null) {
                log.warn("추천된 external_id={} 가 DB 에 없다 user_id={}",
                    item.externalId(), userId);
                continue;
            }
            // 같은 곡의 다른 구간이 겹치면 앞선 순위만 남긴다. 오늘의 모먼트는
            // 곡 목록이라 같은 곡이 두 줄로 보이면 안 된다.
            reasonByMusic.putIfAbsent(section.getMusic().getMusicId(),
                RecommendReason.ofTrack(item.reasonCode()));
        }
        if (reasonByMusic.isEmpty()) {
            log.warn("오늘의 모먼트: 추천된 곡이 모두 DB 에 없다 user_id={}", userId);
            return false;
        }

        Integer moodCodeId = dominantMood(reasonByMusic.keySet(), userId);
        if (moodCodeId == null) {
            // `mood_code_id` 가 NOT NULL 이다. 없는 값을 지어내면 화면이 틀린 분위기를
            // 말하게 되므로, 넣지 않고 이유를 남긴다.
            log.warn("오늘의 모먼트: 대표 분위기를 정하지 못했다 — 건너뛴다 user_id={}", userId);
            return false;
        }
        // 번호는 방금 DB 에서 읽었거나 온보딩이 준 것이다. 프록시로 충분하다 —
        // FK 가 어긋나면 INSERT 에서 걸린다.
        Code mood = codeRepository.getReferenceById(moodCodeId);

        existing.ifPresent(row -> {
            trackRepository.deleteByDailyRecommendationTrackId_DailyRecommendationId(
                row.getRecommendationId());
            trackRepository.flush();
            repository.delete(row);
            repository.flush();
        });

        String groupReason = RecommendReason.ofGroup(items.getFirst().reasonCode());
        DailyRecommendation group = repository.save(DailyRecommendation.builder()
            .user(user)
            .title(cut(TITLE))
            .musicCount(reasonByMusic.size())
            .reason(cut(groupReason))
            .moodCode(mood)
            .recommendationDate(date)
            .build());
        repository.flush();

        int rank = 1;
        List<DailyRecommendationTrack> rows = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : reasonByMusic.entrySet()) {
            Music music = musicRepository.getReferenceById(entry.getKey());
            rows.add(DailyRecommendationTrack.builder()
                // 묶음 번호는 `@MapsId` 가 관계에서 채운다. 순번만 담는다.
                .dailyRecommendationTrackId(
                    new DailyRecommendationTrack.DailyRecommendationTrackId(null, rank++))
                .dailyRecommendation(group)
                .music(music)
                .reason(cut(entry.getValue()))
                .build());
        }
        trackRepository.saveAll(rows);
        return true;
    }

    /**
     * 묶음의 대표 분위기.
     *
     * 추천된 곡들의 구간에 붙은 분위기 중 가장 많이 나온 것이다. 분위기가 하나도 없으면
     * 사용자가 온보딩에서 고른 것으로 물러난다 — 그 사람이 직접 고른 값이라 지어낸 것이
     * 아니다. 둘 다 없으면 `null` 이고, 부르는 쪽이 건너뛴다.
     */
    private Integer dominantMood(Collection<Integer> musicIds, int userId) {
        return dominantMood(musicIds, () -> onboardingOrNull(userId));
    }

    /**
     * 위와 같되 온보딩을 이미 읽어 둔 쪽이 그것을 그대로 넘긴다.
     *
     * <p>공급자로 받는 것은 **구간 분위기가 있으면 온보딩을 읽지 않기** 위해서다.
     * 배치는 곡마다 이 판정을 하므로 미리 읽으면 쓰지도 않을 조회가 매번 는다.
     */
    private Integer dominantMood(
        Collection<Integer> musicIds,
        Supplier<OnboardingResultResponse> onboarding
    ) {
        List<Integer> byPopularity = moodRepository.findMoodCodeIdsByPopularity(musicIds);
        if (!byPopularity.isEmpty()) {
            return byPopularity.getFirst();
        }
        OnboardingResultResponse result = onboarding.get();
        if (result != null && result.submitted() && !result.moods().isEmpty()) {
            return result.moods().getFirst().codeId();
        }
        return null;
    }

    /** 온보딩 결과. 읽지 못하면 `null` 이다 — 추천을 못 만들 이유는 아니다. */
    private OnboardingResultResponse onboardingOrNull(int userId) {
        try {
            return onboardingService.getResult(userId);
        } catch (RuntimeException e) {
            log.warn("온보딩을 읽지 못했다 user_id={}", userId, e);
            return null;
        }
    }

    /** 추천 서버에 실어 보낼 온보딩. 없으면 콜드스타트다. */
    private RecommenderDto.Onboarding onboardingOf(int userId) {
        try {
            OnboardingResultResponse r = onboardingService.getResult(userId);
            if (!r.submitted()) {
                return null;
            }
            return new RecommenderDto.Onboarding(
                r.moods().stream().map(CodeOption::name).toList(),
                r.genres().stream().map(CodeOption::name).toList());
        } catch (RuntimeException e) {
            log.warn("온보딩을 읽지 못했다 user_id={} — 콜드스타트로 만든다", userId, e);
            return null;
        }
    }

    /** `title`·`reason` 이 VARCHAR(100) 이다. 넘치면 MySQL 이 조용히 자른다. */
    private static String cut(String text) {
        String value = text == null ? "" : text;
        return value.length() <= TEXT_LIMIT ? value : value.substring(0, TEXT_LIMIT);
    }

    @Override
    public DailyRecommendationResponse getDaily(User user, LocalDate date) {
        LocalDate wanted = date != null ? date : LocalDate.now();
        List<DailyRecommendation> groups = repository.findByUserAndDate(user.getUserId(), wanted);

        if (groups.isEmpty()) {
            LocalDate latest = repository.findLatestDate(user.getUserId());
            if (latest == null) {
                // 되돌아갈 날짜조차 없다 = 이 사람 몫으로 만들어진 추천이 하나도 없다.
                return coldStart(user, wanted);
            }
            if (latest.equals(wanted)) {
                return new DailyRecommendationResponse(wanted, List.of());
            }
            wanted = latest;
            groups = repository.findByUserAndDate(user.getUserId(), latest);
        }

        List<Integer> groupIds = groups.stream()
            .map(DailyRecommendation::getRecommendationId)
            .toList();
        List<DailyRecommendationTrack> tracks = trackRepository.findByRecommendationIds(groupIds);

        // 곡 표현은 한 번에 만든다. 묶음마다 만들면 N+1 이다.
        List<Integer> musicIds = tracks.stream()
            .map(track -> track.getMusic().getMusicId())
            .distinct()
            .toList();
        Map<Integer, MusicSummary> summaries = assembler.byIds(musicIds).stream()
            .collect(Collectors.toMap(MusicSummary::musicId, Function.identity()));

        Map<Integer, List<RecommendedTrack>> byGroup = new LinkedHashMap<>();
        for (DailyRecommendationTrack track : tracks) {
            MusicSummary music = summaries.get(track.getMusic().getMusicId());
            // 추천이 만들어진 뒤 지워진 곡은 뺀다.
            if (music == null) continue;
            byGroup
                .computeIfAbsent(
                    track.getDailyRecommendationTrackId().getDailyRecommendationId(),
                    key -> new ArrayList<>())
                .add(new RecommendedTrack(track.getRankOrder(), track.getReason(), music));
        }

        return new DailyRecommendationResponse(wanted, groups.stream()
            .map(group -> new DailyRecommendationGroup(
                group.getRecommendationId(),
                group.getTitle(),
                group.getReason(),
                group.getMoodCode().getCodeName(),
                group.getMusicCount(),
                byGroup.getOrDefault(group.getRecommendationId(), List.of())))
            .toList());
    }

    /**
     * 저장된 추천이 하나도 없는 사람에게 그 자리에서 골라 주는 묶음.
     *
     * <h2>무엇을 고르나</h2>
     *
     * 온보딩에서 고른 <b>장르와 분위기를 둘 다</b> 가진 곡 중 조회수 상위
     * {@link #FALLBACK_LIMIT} 곡이다. 조회수는 `music.play_count` 를 본다 —
     * 근거는 {@code MusicRepository.findTopIdsByPlayCount} 주석에 있다.
     *
     * <h2>조건에 맞는 곡이 모자라면</h2>
     *
     * <b>비었을 때만 조건을 넓히고, 한 곡이라도 나오면 모자란 대로 내린다.</b>
     * 두 곡뿐이라 해서 취향과 상관없는 곡으로 네 칸을 채우면, 묶음 하나에 사유가
     * 하나뿐인 이 계약에서 그 사유가 절반은 거짓이 된다. 짧아도 맞는 목록이 낫다.
     *
     * <p>넓히는 순서는 (1) 장르 ∧ 분위기 → (2) 장르 ∨ 분위기 → (3) 카탈로그 전체
     * 조회수 상위다. 온보딩을 아직 하지 않은 사람은 (1)(2) 를 건너뛰고 바로 (3) 이다.
     * 온보딩 없이도 <b>예외를 던지지 않는다</b> — 설문을 안 했다는 것은 오류가 아니고,
     * 그 사람에게도 홈은 채워져야 한다.
     *
     * <p>(3) 까지 갔는데도 비면 예전처럼 빈 배열이다. 카탈로그가 통째로 비었다는 뜻이라
     * 서버가 지어낼 수 있는 것이 없다.
     *
     * <h2>거짓말하지 않는다</h2>
     *
     * 제목은 {@link #FALLBACK_TITLE}, 사유는 {@code RecommendReason} 의 기존 문구를
     * 그대로 쓴다 — 취향으로 좁혔으면 `ONBOARDING_MATCH`, 카탈로그 인기순이면
     * `COLD_START`. 배치가 만든 것처럼 꾸미지 않고 문구도 한 곳에만 둔다.
     */
    private DailyRecommendationResponse coldStart(User user, LocalDate date) {
        int userId = user.getUserId();
        OnboardingResultResponse onboarding = onboardingOrNull(userId);
        List<Integer> genreIds = codeIds(onboarding, OnboardingResultResponse::genres);
        List<Integer> moodIds = codeIds(onboarding, OnboardingResultResponse::moods);

        Pageable top = PageRequest.of(0, FALLBACK_LIMIT);
        List<MusicSummary> tracks = List.of();
        if (!genreIds.isEmpty() && !moodIds.isEmpty()) {
            tracks = assembler.byIds(
                musicRepository.findTopIdsByGenresAndMoods(genreIds, moodIds, top));
        }
        if (tracks.isEmpty() && !(genreIds.isEmpty() && moodIds.isEmpty())) {
            tracks = assembler.byIds(musicRepository.findTopIdsByGenresOrMoods(
                orNoMatch(genreIds), orNoMatch(moodIds), top));
        }
        boolean matchedTaste = !tracks.isEmpty();
        if (tracks.isEmpty()) {
            tracks = assembler.byIds(musicRepository.findTopIdsByPlayCount(top));
        }
        if (tracks.isEmpty()) {
            log.info("오늘의 모먼트 폴백: 내려 줄 곡이 없다 user_id={}", userId);
            return new DailyRecommendationResponse(date, List.of());
        }

        List<Integer> musicIds = tracks.stream().map(MusicSummary::musicId).toList();
        String reasonCode = matchedTaste ? "ONBOARDING_MATCH" : "COLD_START";
        String trackReason = RecommendReason.ofTrack(reasonCode);

        int rank = 1;
        List<RecommendedTrack> rows = new ArrayList<>(tracks.size());
        for (MusicSummary music : tracks) {
            rows.add(new RecommendedTrack(rank++, trackReason, music));
        }

        log.info("오늘의 모먼트 폴백: {}곡을 골랐다 user_id={} 취향_일치={}",
            rows.size(), userId, matchedTaste);
        return new DailyRecommendationResponse(date, List.of(new DailyRecommendationGroup(
            NO_RECOMMENDATION_ID,
            FALLBACK_TITLE,
            RecommendReason.ofGroup(reasonCode),
            moodName(dominantMood(musicIds, () -> onboarding)),
            rows.size(),
            rows)));
    }

    /**
     * 묶음의 분위기 이름.
     *
     * <p>정하지 못하면 빈 문자열이다. `mood` 는 저장하는 값이 아니라 화면에 그대로
     * 나가는 말이라, 없는 것을 지어내느니 비워 두는 편이 맞다 — 화면(`fetchTodayMoments`)
     * 도 빈 값을 그대로 다룬다. 저장 경로가 같은 상황에서 묶음을 아예 만들지 않는
     * 것과 다르지만, 그쪽은 `mood_code_id` 가 NOT NULL 이라 넣을 값이 없어서다.
     */
    private String moodName(Integer moodCodeId) {
        if (moodCodeId == null) {
            return "";
        }
        return codeRepository.findById(moodCodeId).map(Code::getCodeName).orElse("");
    }

    /** 온보딩에서 고른 코드 번호. 읽지 못했거나 제출 전이면 빈 목록이다. */
    private static List<Integer> codeIds(
        OnboardingResultResponse onboarding,
        Function<OnboardingResultResponse, List<CodeOption>> axis
    ) {
        if (onboarding == null || !onboarding.submitted()) {
            return List.of();
        }
        return axis.apply(onboarding).stream().map(CodeOption::codeId).toList();
    }

    /** 빈 목록을 `IN` 에 그대로 넣지 않는다. 아무것도 맞지 않는 번호로 바꾼다. */
    private static List<Integer> orNoMatch(List<Integer> codeIds) {
        return codeIds.isEmpty() ? List.of(NO_CODE_ID) : codeIds;
    }

}
