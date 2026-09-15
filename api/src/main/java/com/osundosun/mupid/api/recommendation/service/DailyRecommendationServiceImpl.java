package com.osundosun.mupid.api.recommendation.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.service.MusicSummaryAssembler;
import com.osundosun.mupid.api.recommendation.dto.RecommendedTrack;
import com.osundosun.mupid.api.recommendation.entity.DailyRecommendation;
import com.osundosun.mupid.api.recommendation.entity.DailyRecommendationTrack;
import com.osundosun.mupid.api.recommendation.repository.DailyRecommendationRepository;
import com.osundosun.mupid.api.recommendation.repository.DailyRecommendationTrackRepository;
import com.osundosun.mupid.api.recommendation.response.DailyRecommendationGroup;
import com.osundosun.mupid.api.recommendation.response.DailyRecommendationResponse;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * 일일 추천 조회.
 *
 * **만드는 쪽은 여기 없다.** `daily_recommendations` 를 채우는 것은 추천
 * 파이프라인의 몫이고, 아직 없다. 이 서비스는 읽기만 한다.
 *
 * 요청한 날짜에 추천이 없으면 **가장 최근 추천이 있는 날**로 되돌아간다.
 * 파이프라인이 매일 도는 보장이 없는데 화면이 빈 홈을 보여주면 곤란해서다.
 * 응답의 `date` 가 실제로 무엇을 돌려줬는지 말해 준다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DailyRecommendationServiceImpl implements DailyRecommendationService {

    private final DailyRecommendationRepository repository;
    private final DailyRecommendationTrackRepository trackRepository;
    private final MusicSummaryAssembler assembler;

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
