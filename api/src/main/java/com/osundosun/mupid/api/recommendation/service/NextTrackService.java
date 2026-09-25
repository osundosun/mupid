package com.osundosun.mupid.api.recommendation.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.ai.client.RecommenderClient;
import com.osundosun.mupid.api.ai.client.RecommenderDto;
import com.osundosun.mupid.api.ai.error.AiErrorCode;
import com.osundosun.mupid.api.ai.tool.RecommendReason;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.service.MusicSectionService;
import com.osundosun.mupid.api.music.service.MusicSummaryAssembler;
import com.osundosun.mupid.api.onboarding.dto.CodeOption;
import com.osundosun.mupid.api.onboarding.response.OnboardingResultResponse;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;
import com.osundosun.mupid.api.recommendation.request.NextTrackRequest;
import com.osundosun.mupid.api.recommendation.response.NextTrackResponse;
import com.osundosun.mupid.api.recommendation.response.NextTrack;
import com.osundosun.mupid.api.recommendation.service.RecommendationReasonCache;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 이어 듣기 — 지금까지의 재생 이력을 보고 다음 곡을 고른다.
 *
 * <p>「오늘의 모먼트」와 달리 <b>저장하지 않는다.</b> 수시로 불리고 그때그때의 행동을
 * 반영해야 하므로 미리 만들어 둘 것이 없다. 홈의 "당신을 위한 추천" 도 같은 경로다 —
 * 목록 길이만 다르다.
 *
 * <p>행동 이력은 추천 서버가 ClickHouse 에서 직접 읽는다. 우리가 넘기는 것은 누구인지
 * (`userId`)와 지금 듣던 곡뿐이다.
 *
 * <p><b>사유 문장은 여기서 만들어지고, 여기서만 볼 수 있다.</b> 화면은 재생을 시작할 때
 * 사유를 이벤트에 싣지 않고 진입 경로와 상관 키(`rec_id`)만 되돌려주므로, 사유를 남기려면
 * 응답을 내보내는 이 자리에서 적어 둬야 한다 ({@link RecommendationReasonCache}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NextTrackService {

    /**
     * 추천 묶음 번호.
     *
     * <p>자동재생은 「오늘의 추천」과 무관하게 수시로 불리므로 `daily_recommendations`
     * 행을 만드는 방식이 맞지 않는다. 저장하지 않는 추천이라 이 번호의 쓸모는 <b>재생
     * 이벤트와 이어 붙이는 것</b>(`rec_id`) 하나다. 그래서 채번도 프로세스 안에서 한다.
     *
     * <p>인스턴스가 여러 벌이면 번호가 겹칠 수 있다. 겹쳐도 사용자가 다르면 이벤트
     * 분석에서 갈리고, 저장되는 값이 아니라 손해가 없다. 저장해야 할 때가 오면 그때
     * DB 채번으로 옮긴다.
     */
    private static final AtomicLong SEQUENCE = new AtomicLong(System.currentTimeMillis());

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 50;

    private final RecommenderClient recommender;
    private final MusicSectionService musicSectionService;
    private final MusicSummaryAssembler assembler;
    private final OnboardingService onboardingService;
    private final RecommendationReasonCache reasonCache;

    public NextTrackResponse next(int userId, NextTrackRequest request) {
        int limit = request.limit() == null
            ? DEFAULT_LIMIT
            : Math.clamp(request.limit(), 1, MAX_LIMIT);

        // 추천 서버는 숫자 ID 를 모른다. 지금 듣던 곡을 문자열로 옮겨 보낸다.
        String currentTrack = request.currentMusicId() == null ? null
            : musicSectionService.trackExternalIdOf(request.currentMusicId()).orElse(null);
        String currentSection = request.currentMusicSectionId() == null ? null
            : musicSectionService.externalIdOfSection(request.currentMusicSectionId())
                .orElse(null);

        List<String> excludeTracks = new ArrayList<>(
            toExternalTracks(request.excludeMusicIds()));
        if (currentTrack != null && !excludeTracks.contains(currentTrack)) {
            // 지금 듣던 곡을 다시 주면 "다음 곡" 이 아니다.
            excludeTracks.add(currentTrack);
        }

        long recommendationId = SEQUENCE.incrementAndGet();
        List<RecommenderDto.Item> items = recommender.next(new RecommenderDto.NextRequest(
            recommendationId, userId, limit, List.copyOf(excludeTracks),
            currentTrack, currentSection, onboardingOf(userId)));

        List<NextTrack> tracks = withMusic(items, limit);
        // 사유를 화면이 되돌려줄 때까지 들고 있는다. 이 응답에는 사유 문장이 있지만
        // 재생 이벤트에는 없으므로, 여기서 적어 두지 않으면 재생 기록에 남길 값이 없다.
        for (NextTrack track : tracks) {
            reasonCache.remember(recommendationId, track.music().musicId(), track.reason());
        }
        // 후보가 모자라면 억지로 채우지 않는다. 몇 개 모자란지 적어 둔다.
        return new NextTrackResponse(recommendationId, tracks,
            Math.max(0, limit - tracks.size()));
    }

    private List<String> toExternalTracks(List<Integer> musicIds) {
        if (musicIds == null || musicIds.isEmpty()) {
            return List.of();
        }
        return musicIds.stream()
            .map(id -> musicSectionService.trackExternalIdOf(id).orElse(null))
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    /**
     * 문자열 ID 를 곡으로 옮기고 최종 필터를 건다({@link RecommendationFinalFilter}).
     *
     * <p>DB 에 없는 구간·지운 곡·재생할 수 없는 곡은 <b>버리고 센다.</b> 화면이 재생할 수 없는 줄을
     * 그리게 되기 때문이다. 추천 서버는 그 몫까지 {@code limit} 보다 몇 개 더 주므로 여기서 자른다.
     */
    private List<NextTrack> withMusic(List<RecommenderDto.Item> items, int limit) {
        RecommendationFinalFilter.Outcome<RecommenderDto.Item> outcome = RecommendationFinalFilter.apply(
            items, RecommenderDto.Item::externalId, limit,
            musicSectionService::findByExternalIds, assembler::byIds, log, "이어 듣기 추천");

        List<NextTrack> out = new ArrayList<>();
        for (RecommendationFinalFilter.Kept<RecommenderDto.Item> kept : outcome.kept()) {
            RecommenderDto.Item item = kept.item();
            MusicSectionAnalysis section = kept.section();
            out.add(new NextTrack(
                out.size() + 1,                      // 버린 것이 있어도 순번은 이어 붙인다
                kept.music(),
                section.getMusicSectionAnalysisId(),
                section.getSectionLabel(),
                section.getStartTimeMs(),
                section.getEndTimeMs(),
                item.reasonCode(),
                RecommendReason.of(item.reasonCode(), section.getSectionLabel())));
        }
        return List.copyOf(out);
    }

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
            log.warn("온보딩을 읽지 못했다 user_id={} — 콜드스타트로 추천한다", userId, e);
            return null;
        }
    }

    /** 주소가 없으면 부르지 않는다. 화면이 "준비되지 않았다" 를 구분할 수 있어야 한다. */
    public void requireConfigured() {
        if (!recommender.isConfigured()) {
            throw new BusinessException(AiErrorCode.RECOMMENDER_NOT_CONFIGURED);
        }
    }

}
