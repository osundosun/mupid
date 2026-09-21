package com.osundosun.mupid.api.music.service;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.dto.AlbumSummary;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.error.AlbumErrorCode;
import com.osundosun.mupid.api.music.error.ArtistErrorCode;
import com.osundosun.mupid.api.music.repository.AlbumRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.request.AlbumCreateRequest;
import com.osundosun.mupid.api.music.request.AlbumUpdateRequest;
import com.osundosun.mupid.api.music.response.AlbumDetailResponse;
import com.osundosun.mupid.api.music.response.AlbumPageResponse;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.error.StorageErrorCode;
import com.osundosun.mupid.api.storage.service.StorageImageService;
import com.osundosun.mupid.api.storage.service.StorageService;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class AlbumServiceImpl implements AlbumService {

    /** 한 번에 내려주는 최대 개수. 곡 목록과 같은 값을 쓴다. */
    public static final int MAX_PAGE_SIZE = MusicServiceImpl.MAX_PAGE_SIZE;
    public static final int DEFAULT_PAGE_SIZE = MusicServiceImpl.DEFAULT_PAGE_SIZE;

    private final ArtistService artistService;
    private final AlbumRepository repository;
    private final MusicRepository musicRepository;
    private final MusicSummaryAssembler assembler;
    private final StorageService storageService;

    @Override
    public int createAlbum(AlbumCreateRequest request) {
        Artist artist = artistService.getArtist(request.artistId())
            .orElseThrow(() -> new BusinessException(ArtistErrorCode.ARTIST_NOT_FOUND));
        ObjectStorageFile imageFile = storageService.getFile(request.imageFileId())
            .orElseThrow(() -> new BusinessException(StorageErrorCode.FILE_NOT_FOUND));
        Album newAlbum = Album.builder()
            .artist(artist)
            .title(request.title())
            .imageFile(imageFile)
            .releaseDate(request.releaseDate())
            .build();
        return repository.save(newAlbum).getAlbumId();
    }

    @Override
    @Transactional(readOnly = true)
    public AlbumPageResponse searchAlbums(String keyword, Integer artistId, int page, int size) {
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int safePage = Math.max(0, page);
        String trimmed = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        Page<Album> found = repository.search(
            trimmed, artistId,
            PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "releaseDate")));

        return new AlbumPageResponse(
            found.getContent().stream().map(AlbumServiceImpl::toSummary).toList(),
            found.getNumber(),
            found.getSize(),
            found.getTotalElements(),
            found.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public AlbumDetailResponse getAlbumDetail(int albumId) {
        // 없으면 404, 삭제됐으면 410 이다.
        Album album = requireAlive(albumId);
        // 수록곡은 곡 목록과 같은 표현이다. 화면이 목록에서 쓰던 코드를 그대로 쓴다.
        List<Integer> trackIds = musicRepository.findTrackIds(albumId);
        return new AlbumDetailResponse(toSummary(album), assembler.byIds(trackIds));
    }

    static AlbumSummary toSummary(Album album) {
        return new AlbumSummary(
            album.getAlbumId(),
            album.getTitle(),
            StorageImageService.imageUrl(album.getImageFile()),
            album.getReleaseDate(),
            ArtistServiceImpl.toSummary(album.getArtist()));
    }

    @Override
    public Optional<Album> getAlbum(int albumId) {
        Album album = repository.findByAlbumId(albumId).orElse(null);
        if (album == null || album.getIsDeleted()) {
            return Optional.empty();
        }
        return Optional.of(album);
    }

    /**
     * 앨범을 찾되 <b>왜 없는지</b>까지 가른다. 없으면 404, 삭제됐으면 410.
     *
     * <p>앨범 삭제는 `is_deleted` 를 세우는 소프트 삭제라 행이 남는다
     * (`deleteAlbum`). 그래서 "그런 앨범이 없다" 와 "지워졌다" 를 나눌 수 있다.
     */
    @Override
    public Album requireAlive(int albumId) {
        Album album = repository.findByAlbumId(albumId)
            .orElseThrow(() -> new BusinessException(AlbumErrorCode.ALBUM_NOT_FOUND));
        if (album.getIsDeleted()) {
            throw new BusinessException(AlbumErrorCode.ALBUM_GONE);
        }
        return album;
    }

    @Override
    public void updateAlbum(int albumId, AlbumUpdateRequest request) {
        Album album = requireAlive(albumId);
        if (request.artistId() != null) {
            Artist artist = artistService.getArtist(request.artistId())
                .orElseThrow(() -> new BusinessException(ArtistErrorCode.ARTIST_NOT_FOUND));
            album.setArtist(artist);
        }
        if (request.title() != null && !request.title().isBlank()) {
            album.setTitle(request.title());
        }
        if (request.releaseDate() != null) {
            album.setReleaseDate(request.releaseDate());
        }
    }

    @Override
    public void deleteAlbum(int albumId) {
        // 이미 지워진 앨범을 또 지우면 404 가 아니라 410 이다.
        Album album = requireAlive(albumId);
        album.setIsDeleted(true);
    }
    
}
