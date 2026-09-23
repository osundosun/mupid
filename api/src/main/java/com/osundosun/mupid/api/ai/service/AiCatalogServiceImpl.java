package com.osundosun.mupid.api.ai.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.ai.dto.AiCatalogCodeRow;
import com.osundosun.mupid.api.ai.dto.AiCatalogTrackRow;
import com.osundosun.mupid.api.ai.repository.AiCatalogRepository;
import com.osundosun.mupid.api.ai.response.AiCatalogPageResponse;
import com.osundosun.mupid.api.ai.response.AiCatalogSectionResponse;
import com.osundosun.mupid.api.ai.response.AiCatalogTrackResponse;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;

import lombok.RequiredArgsConstructor;

/**
 * 곡 번호로 페이지를 자르고, 표마다 한 번씩 읽어 애플리케이션에서 붙인다.
 *
 * <p>질의 수는 페이지당 여섯 번으로 고정이다(곡·장르·무드·구간·악기·순간무드).
 * 조인 하나로 묶으면 1:N 이 곱해져 같은 제목이 수십 줄로 부풀고, 그 상태에서
 * {@code DISTINCT} 를 걸면 이번에는 태그가 사라진다.
 */
@Service
@RequiredArgsConstructor
public class AiCatalogServiceImpl implements AiCatalogService {

    private final AiCatalogRepository aiCatalogRepository;

    @Override
    @Transactional(readOnly = true)
    public AiCatalogPageResponse getCatalogPage(int page, int size) {
        Page<Integer> ids = aiCatalogRepository.findCatalogMusicIds(PageRequest.of(page, size));
        List<Integer> musicIds = ids.getContent();
        LocalDateTime generatedAt = LocalDateTime.now();

        if (musicIds.isEmpty()) {
            return new AiCatalogPageResponse(
                page, size, ids.getTotalPages(), ids.getTotalElements(),
                generatedAt, List.of());
        }

        Map<Integer, List<String>> genres = group(aiCatalogRepository.findGenreRows(musicIds));
        Map<Integer, List<String>> moods = group(aiCatalogRepository.findMoodRows(musicIds));

        List<MusicSectionAnalysis> sections = aiCatalogRepository.findSections(musicIds);
        List<Integer> sectionIds = sections.stream()
            .map(MusicSectionAnalysis::getMusicSectionAnalysisId)
            .toList();
        // 구간이 없으면 `IN ()` 이 된다. 빈 목록으로 질의하면 Hibernate 가 문법 오류를
        // 내거나 조용히 전체를 훑는다 — 어느 쪽도 원하지 않으므로 아예 부르지 않는다.
        Map<Integer, List<String>> instruments = sectionIds.isEmpty()
            ? Map.of() : group(aiCatalogRepository.findInstrumentRows(sectionIds));
        Map<Integer, List<String>> sectionMoods = sectionIds.isEmpty()
            ? Map.of() : group(aiCatalogRepository.findSectionMoodRows(sectionIds));

        Map<Integer, List<AiCatalogSectionResponse>> byMusic = new LinkedHashMap<>();
        for (MusicSectionAnalysis s : sections) {
            int id = s.getMusicSectionAnalysisId();
            byMusic.computeIfAbsent(s.getMusic().getMusicId(), k -> new ArrayList<>())
                .add(new AiCatalogSectionResponse(
                    s.getExternalId(),
                    id,
                    s.getSectionLabel().name(),
                    s.getIndex(),
                    s.getStartTimeMs(),
                    s.getEndTimeMs(),
                    instruments.getOrDefault(id, List.of()),
                    sectionMoods.getOrDefault(id, List.of())));
        }

        Map<Integer, AiCatalogTrackRow> rows = new LinkedHashMap<>();
        for (AiCatalogTrackRow r : aiCatalogRepository.findTrackRows(musicIds)) {
            rows.put(r.musicId(), r);
        }

        // 페이지가 정한 순서를 그대로 지킨다. `findTrackRows` 의 IN 은 순서를
        // 보장하지 않으므로 그 결과 순서로 응답을 만들면 페이지마다 차례가 흔들린다.
        List<AiCatalogTrackResponse> tracks = new ArrayList<>(musicIds.size());
        for (Integer musicId : musicIds) {
            AiCatalogTrackRow r = rows.get(musicId);
            if (r == null) {
                // 페이지를 고른 뒤 곡이 지워진 경우다. 빼고 보낸다 — 지워진 곡을
                // 추천 후보로 실어 보내지 않는 것이 이 API 의 본래 목적이다.
                continue;
            }
            tracks.add(new AiCatalogTrackResponse(
                musicId,
                r.title(),
                r.hasVocal(),
                r.vocalGender() == null ? null : r.vocalGender().name(),
                genres.getOrDefault(musicId, List.of()),
                moods.getOrDefault(musicId, List.of()),
                byMusic.getOrDefault(musicId, List.of())));
        }

        return new AiCatalogPageResponse(
            page, size, ids.getTotalPages(), ids.getTotalElements(), generatedAt, tracks);
    }

    /** 소유자별 코드 이름. 질의가 이미 정렬해 두었으므로 순서를 유지하는 자료구조를 쓴다. */
    private static Map<Integer, List<String>> group(List<AiCatalogCodeRow> rows) {
        Map<Integer, List<String>> out = new LinkedHashMap<>();
        for (AiCatalogCodeRow row : rows) {
            out.computeIfAbsent(row.ownerId(), k -> new ArrayList<>()).add(row.codeName());
        }
        return out;
    }

}
