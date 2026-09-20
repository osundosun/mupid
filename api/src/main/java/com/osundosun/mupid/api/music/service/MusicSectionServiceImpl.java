package com.osundosun.mupid.api.music.service;

import java.util.ArrayList;
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

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;
import com.osundosun.mupid.api.code.repository.CodeRepository;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.error.MusicSectionErrorCode;
import com.osundosun.mupid.api.music.mapping.entity.MusicAnalysisInstrument;
import com.osundosun.mupid.api.music.mapping.entity.MusicSectionAnalysisMood;
import com.osundosun.mupid.api.music.mapping.repository.MusicAnalysisInstrumentRepository;
import com.osundosun.mupid.api.music.mapping.repository.MusicSectionAnalysisMoodRepository;
import com.osundosun.mupid.api.music.repository.MusicSectionAnalysisRepository;
import com.osundosun.mupid.api.music.request.MusicSectionBulkLine;
import com.osundosun.mupid.api.music.request.MusicSectionBulkRequest;
import com.osundosun.mupid.api.music.response.MusicSectionBulkResponse;
import com.osundosun.mupid.api.music.response.MusicSectionRef;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class MusicSectionServiceImpl implements MusicSectionService {

    private final MusicService musicService;
    private final MusicSectionAnalysisRepository repository;
    private final MusicSectionAnalysisMoodRepository moodRepository;
    private final MusicAnalysisInstrumentRepository instrumentRepository;
    private final CodeRepository codeRepository;

    @Override
    public MusicSectionBulkResponse replaceSections(int musicId, MusicSectionBulkRequest request) {
        Music music = musicService.requireAlive(musicId);

        // 검증 애너테이션이 먼저 걸러 주지만, 서비스를 직접 부르는 길(시험·배치
        // 코드)에서도 상한이 지켜져야 한다 (`LyricServiceImpl` 과 같은 이유).
        if (request.sections().size() > MusicSectionBulkRequest.MAX_SECTIONS) {
            throw new BusinessException(MusicSectionErrorCode.SECTION_BULK_TOO_MANY);
        }

        validate(request.sections());
        rejectForeignExternalIds(musicId, request.sections());

        // 구간에 달린 연결을 먼저 끊는다. 구간을 지우면 FK 가 걸리고, 영속성
        // 컨텍스트에는 지워진 구간을 가리키는 연결이 남는다. 분위기와 악기 둘 다다.
        List<Integer> oldIds = repository.findByMusic_MusicIdOrderByIndexAsc(musicId).stream()
            .map(MusicSectionAnalysis::getMusicSectionAnalysisId)
            .toList();
        if (!oldIds.isEmpty()) {
            moodRepository.deleteByMusicSectionAnalysisMoodId_MusicSectionAnalysisIdIn(oldIds);
            instrumentRepository
                .deleteByMusicAnalysisInstrumentId_MusicSectionAnalysisIdIn(oldIds);
            moodRepository.flush();
            instrumentRepository.flush();
        }

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

        replaceMoods(request.sections(), rows);
        replaceInstruments(request.sections(), rows);

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

    @Override
    @Transactional(readOnly = true)
    public Optional<String> externalIdOfSection(int musicSectionAnalysisId) {
        return repository.findById(musicSectionAnalysisId)
            .map(MusicSectionAnalysis::getExternalId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MusicSectionAnalysis> findSectionOfMusic(int musicId,
                                                            int musicSectionAnalysisId) {
        return repository.findById(musicSectionAnalysisId)
            .filter(row -> row.getMusic().getMusicId().equals(musicId));
    }

    /**
     * 구간마다 딸린 분위기를 갈아 끼운다.
     *
     * 구간을 통째로 지웠다 다시 만들었으므로 옛 연결은 함께 사라진다(복합키의 한쪽이
     * 구간 번호라 남을 수가 없다). 여기서는 새로 넣기만 한다.
     *
     * **코드표에 없는 이름은 버리고 센다.** 지어내서 넣으면 화면이 틀린 분위기를
     * 말하게 된다. 분위기를 못 고른 곡이 실제로 있으므로(카탈로그의 14%) 비어 있는
     * 것 자체는 오류가 아니다.
     */
    private void replaceMoods(List<MusicSectionBulkLine> lines,
                              List<MusicSectionAnalysis> rows) {
        boolean any = lines.stream()
            .anyMatch(line -> line.moods() != null && !line.moods().isEmpty());
        if (!any) {
            return;
        }
        Map<String, Code> byName = codeRepository
            .findAllByCodeTypeOrderByCodeIdAsc(CodeType.MOOD).stream()
            .collect(Collectors.toMap(Code::getCodeName, code -> code, (a, b) -> a));

        List<MusicSectionAnalysisMood> links = new ArrayList<>();
        Set<String> unknown = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            List<String> moods = lines.get(i).moods();
            if (moods == null) {
                continue;
            }
            MusicSectionAnalysis row = rows.get(i);
            Set<Integer> seen = new HashSet<>();
            for (String name : moods) {
                Code code = byName.get(name == null ? "" : name.strip());
                if (code == null) {
                    unknown.add(String.valueOf(name));
                    continue;
                }
                if (!seen.add(code.getCodeId())) {
                    continue;                       // 같은 구간에 같은 분위기를 두 번
                }
                // `@MapsId` 가 복합키를 관계에서 채운다. 빈 키를 주고 관계만 세운다.
                links.add(new MusicSectionAnalysisMood(
                    new MusicSectionAnalysisMood.MusicSectionAnalysisMoodId(),
                    row, code));
            }
        }
        if (!unknown.isEmpty()) {
            // 조용히 넘기면 "분위기가 왜 비지" 로만 보인다. 이름을 남긴다.
            log.warn("코드표에 없는 분위기 {}건 제외 — {}", unknown.size(), unknown);
        }
        moodRepository.saveAll(links);
    }

    /**
     * 구간마다 들리는 악기를 갈아 끼운다.
     *
     * <p>분위기와 같은 모양이다 — 구간을 통째로 지웠다 다시 만들었으므로 옛 연결은
     * 함께 사라지고, 여기서는 새로 넣기만 한다. 코드표에 없는 이름은 버리고 센다.
     *
     * <p><b>`CodeType.INSTRUMENT` 는 부트스트랩이 심지 않는다.</b> 확정된 목록이
     * 없어서다. 전처리 배치가 `POST /api/v1/code` 로 필요한 것을 먼저 넣고 구간을
     * 올린다 — 그 순서가 아니면 여기서 전부 버려져 악기가 조용히 비어 보인다.
     */
    private void replaceInstruments(List<MusicSectionBulkLine> lines,
                                    List<MusicSectionAnalysis> rows) {
        boolean any = lines.stream()
            .anyMatch(line -> line.instruments() != null && !line.instruments().isEmpty());
        if (!any) {
            return;
        }
        Map<String, Code> byName = codeRepository
            .findAllByCodeTypeOrderByCodeIdAsc(CodeType.INSTRUMENT).stream()
            .collect(Collectors.toMap(Code::getCodeName, code -> code, (a, b) -> a));

        List<MusicAnalysisInstrument> links = new ArrayList<>();
        Set<String> unknown = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            List<String> instruments = lines.get(i).instruments();
            if (instruments == null) {
                continue;
            }
            MusicSectionAnalysis row = rows.get(i);
            Set<Integer> seen = new HashSet<>();
            for (String name : instruments) {
                Code code = byName.get(name == null ? "" : name.strip());
                if (code == null) {
                    unknown.add(String.valueOf(name));
                    continue;
                }
                if (!seen.add(code.getCodeId())) {
                    continue;                       // 같은 구간에 같은 악기를 두 번
                }
                // `@MapsId` 가 복합키를 관계에서 채운다. 빈 키를 주고 관계만 세운다.
                links.add(new MusicAnalysisInstrument(
                    new MusicAnalysisInstrument.MusicAnalysisInstrumentId(), row, code));
            }
        }
        if (!unknown.isEmpty()) {
            // 조용히 넘기면 "악기가 왜 비지" 로만 보인다. 이름을 남긴다.
            log.warn("코드표에 없는 악기 {}건 제외 — {}", unknown.size(), unknown);
        }
        instrumentRepository.saveAll(links);
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
