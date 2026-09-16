package com.osundosun.mupid.api.music.service;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.error.MusicSectionErrorCode;
import com.osundosun.mupid.api.music.repository.MusicSectionAnalysisRepository;
import com.osundosun.mupid.api.music.request.MusicSectionBulkLine;
import com.osundosun.mupid.api.music.request.MusicSectionBulkRequest;
import com.osundosun.mupid.api.music.response.MusicSectionBulkResponse;
import com.osundosun.mupid.api.music.response.MusicSectionRef;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class MusicSectionServiceImpl implements MusicSectionService {

    private final MusicService musicService;
    private final MusicSectionAnalysisRepository repository;

    @Override
    public MusicSectionBulkResponse replaceSections(int musicId, MusicSectionBulkRequest request) {
        Music music = musicService.getMusic(musicId)
            .orElseThrow(() -> new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND));

        // 검증 애너테이션이 먼저 걸러 주지만, 서비스를 직접 부르는 길(시험·배치
        // 코드)에서도 상한이 지켜져야 한다 (`LyricServiceImpl` 과 같은 이유).
        if (request.sections().size() > MusicSectionBulkRequest.MAX_SECTIONS) {
            throw new BusinessException(MusicSectionErrorCode.SECTION_BULK_TOO_MANY);
        }

        validate(request.sections());
        rejectForeignExternalIds(musicId, request.sections());

        // 지운 뒤 넣으므로 먼저 내보낸다. 안 그러면 같은 트랜잭션 안에서 INSERT 가
        // DELETE 보다 앞서 나가 유니크 제약에 걸린다 — 같은 곡을 다시 적재할 때
        // `external_id` 가 그대로이기 때문에 실제로 부딪힌다.
        int replaced = repository.deleteByMusic_MusicId(musicId);
        repository.flush();

        // 받은 순서 그대로 넣는다. `section_index` 로 다시 정렬하지 않는다 —
        // 순번과 시간 순서가 어긋난 입력이 있으면 그대로 드러나야 한다.
        List<MusicSectionAnalysis> rows = request.sections().stream()
            .map(line -> MusicSectionAnalysis.builder()
                .music(music)
                .sectionLabel(line.sectionLabel())
                .index(line.sectionIndex())
                .startTimeMs(line.startTimeMs())
                .endTimeMs(line.endTimeMs())
                .externalId(line.externalId().strip())
                .build())
            .toList();
        repository.saveAll(rows);
        // 번호를 응답에 실어야 하므로 여기서 채번을 강제한다. IDENTITY 전략이라
        // flush 전에는 `musicSectionAnalysisId` 가 null 이다.
        repository.flush();

        return new MusicSectionBulkResponse(
            musicId,
            request.analysisVersion(),
            replaced,
            rows.stream()
                .map(row -> new MusicSectionRef(
                    row.getMusicSectionAnalysisId(), row.getIndex(), row.getExternalId()))
                .toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, MusicSectionAnalysis> findByExternalIds(Collection<String> externalIds) {
        if (externalIds == null || externalIds.isEmpty()) {
            return Map.of();
        }
        Map<String, MusicSectionAnalysis> out = new LinkedHashMap<>();
        for (MusicSectionAnalysis row : repository.findByExternalIdIn(Set.copyOf(externalIds))) {
            out.put(row.getExternalId(), row);
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> trackExternalIdOf(int musicId) {
        return repository.findByMusic_MusicIdOrderByIndexAsc(musicId).stream()
            .findFirst()
            .map(row -> row.getExternalId().split("#", 2)[0]);
    }

    /** 요청 안에서 닫히는 검사. DB 를 보지 않는다. */
    private void validate(List<MusicSectionBulkLine> sections) {
        Set<Integer> indexes = new HashSet<>();
        Set<String> externalIds = new HashSet<>();
        for (MusicSectionBulkLine line : sections) {
            if (line.endTimeMs() <= line.startTimeMs()) {
                throw new BusinessException(MusicSectionErrorCode.SECTION_INVALID_TIME_RANGE);
            }
            if (!indexes.add(line.sectionIndex())) {
                throw new BusinessException(MusicSectionErrorCode.SECTION_INDEX_DUPLICATED);
            }
            // 같은 요청 안의 중복도 409 로 본다. 아래 DB 검사와 사유가 같고,
            // 여기서 놓치면 유니크 제약이 원인을 감춘 채 터진다.
            if (!externalIds.add(line.externalId().strip())) {
                throw new BusinessException(MusicSectionErrorCode.SECTION_EXTERNAL_ID_DUPLICATED);
            }
        }
    }

    /**
     * `external_id` 가 **다른 곡**에 이미 붙어 있으면 거절한다.
     *
     * 같은 곡에 붙어 있는 것은 정상이다 — 재적재라서 곧 지워진다.
     */
    private void rejectForeignExternalIds(int musicId, List<MusicSectionBulkLine> sections) {
        Set<String> wanted = sections.stream()
            .map(line -> line.externalId().strip())
            .collect(Collectors.toSet());
        boolean taken = repository.findByExternalIdIn(wanted).stream()
            .anyMatch(row -> !row.getMusic().getMusicId().equals(musicId));
        if (taken) {
            throw new BusinessException(MusicSectionErrorCode.SECTION_EXTERNAL_ID_DUPLICATED);
        }
    }

}
