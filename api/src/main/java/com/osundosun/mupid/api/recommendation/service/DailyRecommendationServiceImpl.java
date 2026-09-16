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
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DailyRecommendationServiceImpl implements DailyRecommendationService {

    /** 한 사람에게 하루에 보여 줄 곡 수. */
    private static final int DAILY_LIMIT = 10;

    /** `daily_recommendations.title`·`reason` 이 VARCHAR(100) 이다. */
    private static final int TEXT_LIMIT = 100;

    private static final String TITLE = "오늘의 모먼트";

    private final DailyRecommendationRepository repository;
    private final DailyRecommendationTrackRepository trackRepository;
    private final MusicSummaryAssembler assembler;
    private final RecommenderClient recommender;
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
            correlationId, userId, DAILY_LIMIT, List.of(), onboardingOf(userId)));
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
        List<Integer> byPopularity = moodRepository.findMoodCodeIdsByPopularity(musicIds);
        if (!byPopularity.isEmpty()) {
            return byPopularity.getFirst();
        }
        try {
            OnboardingResultResponse onboarding = onboardingService.getResult(userId);
            if (onboarding.submitted() && !onboarding.moods().isEmpty()) {
                return onboarding.moods().getFirst().codeId();
            }
        } catch (RuntimeException e) {
            log.warn("온보딩을 읽지 못했다 user_id={}", userId, e);
        }
        return null;
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
            if (latest == null || latest.equals(wanted)) {
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

}
