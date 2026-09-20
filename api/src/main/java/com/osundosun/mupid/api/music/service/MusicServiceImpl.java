package com.osundosun.mupid.api.music.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.common.error.GlobalErrorCode;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.dto.MusicSectionSummary;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicAnalysis;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.MusicSort;
import com.osundosun.mupid.api.music.enumerate.VocalFilter;
import com.osundosun.mupid.api.music.error.AlbumErrorCode;
import com.osundosun.mupid.api.music.error.ArtistErrorCode;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.request.MusicSortRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.mapping.repository.MusicGenreRepository;
import com.osundosun.mupid.api.music.mapping.repository.MusicMoodRepository;
import com.osundosun.mupid.api.music.mapping.repository.MusicAnalysisInstrumentRepository;
import com.osundosun.mupid.api.music.mapping.repository.MusicSectionAnalysisMoodRepository;
import com.osundosun.mupid.api.music.repository.MusicAnalysisRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.repository.MusicSectionAnalysisRepository;
import com.osundosun.mupid.api.music.response.MusicAnalysisResponse;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.error.StorageErrorCode;
import com.osundosun.mupid.api.storage.service.StorageImageService;
import com.osundosun.mupid.api.storage.service.StorageService;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.enumerate.StreamStatus;
import com.osundosun.mupid.api.stream.repository.StreamRepository;
import com.osundosun.mupid.api.music.request.MusicAddRequest;
import com.osundosun.mupid.api.music.request.MusicUpdateRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class MusicServiceImpl implements MusicService {

    private final ArtistService artistService;
    private final AlbumService albumServce;
    /** 한 번에 내려주는 최대 곡 수. 더 크게 요청해도 여기서 자른다. */
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final MusicRepository repository;
    private final MusicSummaryAssembler assembler;
    private final MusicAnalysisRepository analysisRepository;
    private final MusicSectionAnalysisRepository sectionRepository;
    private final MusicSectionAnalysisMoodRepository sectionMoodRepository;
    private final MusicAnalysisInstrumentRepository instrumentRepository;
    private final StorageService storageService;

    @Override
    public int addMusic(MusicAddRequest request) {
        Album album = albumServce.getAlbum(request.albumId())
            .orElseThrow(() -> new BusinessException(AlbumErrorCode.ALBUM_NOT_FOUND));
        Artist artist = artistService.getArtist(request.artistId())
            .orElseThrow(() -> new BusinessException(ArtistErrorCode.ARTIST_NOT_FOUND));
        Music music = Music.builder()
            .album(album)
            .artist(artist)
            .title(request.title())
            .releaseDate(request.releaseDate())
            .imageFile(findImageFile(request.imageFileId()))
            .build();
        return repository.save(music).getMusicId();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Music> getMusic(int musicId) {
        Music music = repository.findById(musicId).orElse(null);
        if (music == null || music.getIsDeleted()) {
            return Optional.empty();
        }
        return Optional.of(music);
    }

    @Override
    @Transactional(readOnly = true)
    public MusicPageResponse searchMusic(
        String keyword, Collection<VocalFilter> vocalFilters, int page, int size) {
        return searchMusic(keyword, vocalFilters, List.of(), List.of(), page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public MusicPageResponse searchMusic(
        String keyword,
        Collection<VocalFilter> vocalFilters,
        Collection<String> moodCodeNames,
        Collection<String> genreCodeNames,
        int page,
        int size) {
        return searchMusic(
            keyword, vocalFilters, moodCodeNames, genreCodeNames, null, page, size);
    }

    @Override
    @Transactional(readOnly = true)
    public MusicPageResponse searchMusic(
        String keyword,
        Collection<VocalFilter> vocalFilters,
        Collection<String> moodCodeNames,
        Collection<String> genreCodeNames,
        MusicSortRequest sortRequest,
        int page,
        int size) {
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int safePage = Math.max(0, page);
        String trimmed = (keyword == null || keyword.isBlank()) ? null : keyword.trim();
        VocalFilterQuery vocal = VocalFilterQuery.of(vocalFilters);
        List<String> moods = normalizedNames(moodCodeNames);
        List<String> genres = normalizedNames(genreCodeNames);
        MusicSortRequest sort = MusicSortRequest.orDefault(sortRequest);

        /*
         * 검색어가 있으면 정렬 기준을 보지 않는다.
         *
         * 관련도순은 이 경로의 결과 자체를 만드는 규칙이라, 기준을 갈아끼우면 "무엇을
         * 찾았는가" 가 "어떻게 줄 세웠는가" 에 밀린다. 더 나쁜 것은 `q` 와 함께 온
         * `sort` 가 조용히 무시된다는 점인데, 그건 지금 고르지 않기로 한 것이므로
         * 요청을 되돌려 사용자가 알게 한다(모르는 `vocal` 값을 400 으로 돌리는 것과
         * 같은 판단).
         */
        if (trimmed != null) {
            if (sort.explicitlyRequested()) {
                throw new BusinessException(GlobalErrorCode.INVALID_REQUEST_BODY);
            }
            return searchIndexed(trimmed, vocal, moods, genres, safePage, safeSize);
        }

        Page<Music> found = repository.findPageFiltered(
            vocal.filtered(), vocal.wantFemale(), vocal.wantMale(), vocal.wantMixed(),
            vocal.wantInstrumental(), !moods.isEmpty(), queryNames(moods),
            !genres.isEmpty(), queryNames(genres),
            PageRequest.of(safePage, safeSize, pageableSortOf(sort)));

        List<MusicSummary> items = assembler.assemble(found.getContent());
        return new MusicPageResponse(
            items,
            found.getNumber(),
            found.getSize(),
            found.getTotalElements(),
            found.getTotalPages());
    }

    /**
     * 정렬 절을 만든다. <b>파라미터가 없었으면 붙이지 않는다.</b>
     *
     * <p>이 한 줄이 하위 호환의 전부다. {@code sort} 를 보내지 않은 호출은 지금까지와
     * 같은 쿼리(정렬 절 없음 → {@code music_id} 오름차순)를 타야 한다. 기본값을
     * 명시적으로 붙이면 결과는 같겠지만 "예전과 완전히 같다" 는 보장이 쿼리 수준에서
     * 사라진다 — 나중에 기본값을 바꾸는 순간 옛 클라이언트의 순서가 함께 움직인다.
     */
    private static Sort pageableSortOf(MusicSortRequest sort) {
        return sort.isRequested() ? sort.sort().toSort() : Sort.unsorted();
    }

    private MusicPageResponse searchIndexed(
        String keyword,
        VocalFilterQuery vocal,
        List<String> moods,
        List<String> genres,
        int page,
        int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<Integer> ids = keyword.codePointCount(0, keyword.length()) < 2
            ? repository.searchIdsByPrefix(
                keyword, vocal.filtered(), vocal.wantFemale(), vocal.wantMale(),
                vocal.wantMixed(), vocal.wantInstrumental(), !moods.isEmpty(), queryNames(moods),
                !genres.isEmpty(), queryNames(genres), pageable)
            : repository.searchIdsFullText(
                toBooleanPrefixQuery(keyword), vocal.filtered(), vocal.wantFemale(),
                vocal.wantMale(), vocal.wantMixed(), vocal.wantInstrumental(),
                !moods.isEmpty(), queryNames(moods), !genres.isEmpty(), queryNames(genres), pageable);

        if (ids.isEmpty()) {
            return new MusicPageResponse(
                List.of(), ids.getNumber(), ids.getSize(), ids.getTotalElements(), ids.getTotalPages());
        }

        Map<Integer, Music> musicById = repository.findDetailAllByIds(ids.getContent()).stream()
            .collect(Collectors.toMap(Music::getMusicId, Function.identity()));
        List<Music> ordered = ids.getContent().stream()
            .map(musicById::get)
            .filter(java.util.Objects::nonNull)
            .toList();

        return new MusicPageResponse(
            assembler.assemble(ordered),
            ids.getNumber(),
            ids.getSize(),
            ids.getTotalElements(),
            ids.getTotalPages());
    }

    private static List<String> normalizedNames(Collection<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        return names.stream()
            .filter(java.util.Objects::nonNull)
            .flatMap(name -> java.util.Arrays.stream(name.split(",")))
            .map(String::trim)
            .filter(name -> !name.isEmpty())
            .distinct()
            .toList();
    }

    /** 빈 IN 절은 DB마다 문법이 달라, 필터가 꺼졌을 때는 도달 불가능한 값을 넣는다. */
    private static List<String> queryNames(List<String> names) {
        return names.isEmpty() ? List.of("__NO_FILTER__") : names;
    }

    /** Boolean 연산자는 버리고 모든 단어를 prefix 필수어로 만든다. */
    static String toBooleanPrefixQuery(String keyword) {
        return java.util.regex.Pattern.compile("[^\\p{L}\\p{N}]+")
            .splitAsStream(keyword.strip())
            .filter(token -> !token.isBlank())
            .map(token -> "+" + token + "*")
            .collect(Collectors.joining(" "));
    }

    @Override
    @Transactional(readOnly = true)
    public MusicSummary getMusicSummary(int musicId) {
        Music music = repository.findDetail(musicId)
            .orElseThrow(() -> new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND));
        return assembler.assemble(List.of(music)).get(0);
    }

    @Override
    @Transactional(readOnly = true)
    public MusicAnalysisResponse getAnalysis(int musicId) {
        if (!repository.existsByMusicIdAndIsDeletedFalse(musicId)) {
            throw new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND);
        }
        MusicAnalysis analysis = analysisRepository.findById(musicId).orElse(null);
        List<MusicSectionAnalysis> sections =
            sectionRepository.findByMusic_MusicIdOrderByIndexAsc(musicId);

        // 분석 행도 없고 구간도 없으면 아직 분석 전이다.
        if (analysis == null && sections.isEmpty()) {
            return new MusicAnalysisResponse(musicId, false, null, null, List.of());
        }

        List<Integer> sectionIds = sections.stream()
            .map(MusicSectionAnalysis::getMusicSectionAnalysisId)
            .toList();

        Map<Integer, List<String>> moods = sectionIds.isEmpty() ? Map.of()
            : sectionMoodRepository
                .findByMusicSectionAnalysisMoodId_MusicSectionAnalysisIdIn(sectionIds)
                .stream()
                .collect(Collectors.groupingBy(
                    it -> it.getMusicSectionAnalysisMoodId().getMusicSectionAnalysisId(),
                    Collectors.mapping(it -> it.getMoodCodeId().getCodeName(), Collectors.toList())));

        Map<Integer, List<String>> instruments = sectionIds.isEmpty() ? Map.of()
            : instrumentRepository
                .findByMusicAnalysisInstrumentId_MusicSectionAnalysisIdIn(sectionIds)
                .stream()
                .collect(Collectors.groupingBy(
                    it -> it.getMusicAnalysisInstrumentId().getMusicSectionAnalysisId(),
                    Collectors.mapping(it -> it.getInstrumentCode().getCodeName(), Collectors.toList())));

        return new MusicAnalysisResponse(
            musicId,
            true,
            analysis == null ? null : analysis.getHasVocal(),
            analysis == null || analysis.getVocalGender() == null
                ? null : analysis.getVocalGender().name(),
            sections.stream().map(section -> new MusicSectionSummary(
                section.getMusicSectionAnalysisId(),
                section.getIndex(),
                section.getSectionLabel().name(),
                section.getStartTimeMs(),
                section.getEndTimeMs(),
                moods.getOrDefault(section.getMusicSectionAnalysisId(), List.of()),
                instruments.getOrDefault(section.getMusicSectionAnalysisId(), List.of())
            )).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isExists(int musicId) {
        Music music = repository.findById(musicId).orElse(null);
        if (music == null || music.getIsDeleted()) {
            return false;
        }
        return true;
    }

    @Override
    public void updateMusic(int musicId, MusicUpdateRequest request) {
        Music music = getMusic(musicId)
            .orElseThrow(() -> new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND));
        if (request.albumId() != null) {
            Album album = albumServce.getAlbum(request.albumId())
                .orElseThrow(() -> new BusinessException(AlbumErrorCode.ALBUM_NOT_FOUND));
            music.setAlbum(album);
        }
        if (request.artistId() != null) {
            Artist artist = artistService.getArtist(request.artistId())
                .orElseThrow(() -> new BusinessException(ArtistErrorCode.ARTIST_NOT_FOUND));
            music.setArtist(artist);
        }
        if (request.title() != null && !request.title().isBlank()) {
            music.setTitle(request.title());
        }
        if (request.releaseDate() != null) {
            music.setReleaseDate(request.releaseDate());
        }
        if (request.imageFileId() != null) {
            music.setImageFile(findImageFile(request.imageFileId()));
        }
    }

    /**
     * 곡 커버 파일을 찾는다. 번호가 없으면 커버가 없는 곡이라 null 이다.
     *
     * 번호를 줬는데 그런 파일이 없으면 조용히 넘기지 않고 막는다. 배치가 잘못된
     * 번호를 보내면 커버 없는 곡이 말없이 쌓이고, 나중에 어느 곡이 왜 비었는지
     * 추적할 수 없다.
     */
    private ObjectStorageFile findImageFile(Integer imageFileId) {
        if (imageFileId == null) {
            return null;
        }
        return storageService.getFile(imageFileId)
            .orElseThrow(() -> new BusinessException(StorageErrorCode.FILE_NOT_FOUND));
    }

    @Override
    public void deleteMusic(int musicId) {
        Music music = getMusic(musicId)
            .orElseThrow(() -> new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND));
        music.setIsDeleted(true);
    }

}
