package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.dto.ArtistSummary;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.response.ArtistPageResponse;
import com.osundosun.mupid.api.music.error.ArtistErrorCode;
import com.osundosun.mupid.api.music.repository.ArtistRepository;
import com.osundosun.mupid.api.music.request.ArtistCreateRequest;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.error.StorageErrorCode;
import com.osundosun.mupid.api.storage.service.StorageImageService;
import com.osundosun.mupid.api.storage.service.StorageService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class ArtistServiceImpl implements ArtistService {

    private final ArtistRepository repository;
    private final StorageService storageService;

    @Override
    public int createArtist(ArtistCreateRequest request) {
        ObjectStorageFile imageFile = storageService.getFile(request.imageFileId())
            .orElseThrow(() -> new BusinessException(StorageErrorCode.FILE_NOT_FOUND));
        Artist newArtist = Artist.builder()
            .name(request.name())
            .imageFile(imageFile)
            .build();
        return repository.save(newArtist).getArtistId();
    }

    /** 한 번에 내려주는 최대 개수. 곡 목록과 같은 값을 쓴다. */
    public static final int MAX_PAGE_SIZE = MusicServiceImpl.MAX_PAGE_SIZE;
    public static final int DEFAULT_PAGE_SIZE = MusicServiceImpl.DEFAULT_PAGE_SIZE;

    @Override
    @Transactional(readOnly = true)
    public ArtistPageResponse searchArtists(String keyword, int page, int size) {
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int safePage = Math.max(0, page);
        String trimmed = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        Page<Artist> found = repository.search(
            trimmed, PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.ASC, "name")));

        return new ArtistPageResponse(
            found.getContent().stream().map(ArtistServiceImpl::toSummary).toList(),
            found.getNumber(),
            found.getSize(),
            found.getTotalElements(),
            found.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public ArtistSummary getArtistSummary(int artistId) {
        // 없으면 404, 삭제됐으면 410 이다 (`requireAlive` 가 가른다).
        return toSummary(requireAlive(artistId));
    }

    /** 다른 서비스(앨범 상세 등)도 같은 표현을 쓰도록 정적으로 둔다. */
    static ArtistSummary toSummary(Artist artist) {
        return new ArtistSummary(
            artist.getArtistId(),
            artist.getName(),
            StorageImageService.imageUrl(artist.getImageFile()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Artist> getArtist(int artistId) {
        Artist artist = repository.findByArtistId(artistId).orElse(null);
        if (artist == null || artist.getIsDeleted()) {
            return Optional.empty();
        }
        return Optional.of(artist);
    }

    /**
     * 아티스트를 찾되 <b>왜 없는지</b>까지 가른다. 없으면 404, 삭제됐으면 410.
     *
     * <p>아티스트 삭제는 `is_deleted` 를 세우는 소프트 삭제라 행이 남는다
     * (`deleteArtist`). 그래서 "그런 아티스트가 없다" 와 "지워졌다" 를 나눌 수 있다.
     */
    @Override
    @Transactional(readOnly = true)
    public Artist requireAlive(int artistId) {
        Artist artist = repository.findByArtistId(artistId)
            .orElseThrow(() -> new BusinessException(ArtistErrorCode.ARTIST_NOT_FOUND));
        if (artist.getIsDeleted()) {
            throw new BusinessException(ArtistErrorCode.ARTIST_GONE);
        }
        return artist;
    }

    @Override
    public boolean renameArtist(int artistId, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Artist artist = requireAlive(artistId);
        artist.setName(name);
        return true;
    }

    @Override
    public void deleteArtist(int artistId) {
        // 이미 지워진 아티스트를 또 지우면 404 가 아니라 410 이다.
        Artist artist = requireAlive(artistId);
        artist.setIsDeleted(true);
    }
    
}
