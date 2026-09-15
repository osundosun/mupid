package com.osundosun.mupid.api.music.service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.dto.MusicSectionSummary;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicAnalysis;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.error.AlbumErrorCode;
import com.osundosun.mupid.api.music.error.ArtistErrorCode;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
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
    public MusicPageResponse searchMusic(String keyword, int page, int size) {
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int safePage = Math.max(0, page);
        String trimmed = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        Page<Music> found = repository.search(
            trimmed,
            PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "musicId")));

        List<MusicSummary> items = assembler.assemble(found.getContent());
        return new MusicPageResponse(
            items,
            found.getNumber(),
            found.getSize(),
            found.getTotalElements(),
            found.getTotalPages());
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
