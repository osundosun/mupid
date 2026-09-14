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
        return toSummary(repository.findDetail(artistId)
            .orElseThrow(() -> new BusinessException(ArtistErrorCode.ARTIST_NOT_FOUND)));
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
        Artist artist = repository.findById(artistId).orElse(null);
        if (artist == null || artist.getIsDeleted()) {
            return Optional.empty();
        }
        return Optional.of(artist);
    }

    @Override
    public boolean renameArtist(int artistId, String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        Artist artist = getArtist(artistId)
            .orElseThrow(() -> new BusinessException(ArtistErrorCode.ARTIST_NOT_FOUND));
        artist.setName(name);
        return true;
    }

    @Override
    public void deleteArtist(int artistId) {
        Artist artist = getArtist(artistId)
            .orElseThrow(() -> new BusinessException(ArtistErrorCode.ARTIST_NOT_FOUND));
        artist.setIsDeleted(true);
    }
    
}
