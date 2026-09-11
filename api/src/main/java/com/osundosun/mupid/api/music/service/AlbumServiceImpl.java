package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.error.AlbumErrorCode;
import com.osundosun.mupid.api.music.error.ArtistErrorCode;
import com.osundosun.mupid.api.music.repository.AlbumRepository;
import com.osundosun.mupid.api.music.request.AlbumCreateRequest;
import com.osundosun.mupid.api.music.request.AlbumUpdateRequest;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class AlbumServiceImpl implements AlbumService {

    private final ArtistService artistService;
    private final AlbumRepository repository;

    @Override
    public int createAlbum(AlbumCreateRequest request) {
        Artist artist = artistService.getArtist(request.artistId())
            .orElseThrow(() -> new BusinessException(ArtistErrorCode.ARTIST_NOT_FOUND));
        Album newAlbum = Album.builder()
            .artist(artist)
            .title(request.title())
            .releaseDate(request.releaseDate())
            .build();
        return repository.save(newAlbum).getAlbumId();
    }

    @Override
    public Optional<Album> getAlbum(int albumId) {
        Album album = repository.findById(albumId).orElse(null);
        if (album == null || album.getIsDeleted()) {
            return Optional.empty();
        }
        return Optional.of(album);
    }

    @Override
    public void updateAlbum(int albumId, AlbumUpdateRequest request) {
        Album album = getAlbum(albumId)
            .orElseThrow(() -> new BusinessException(AlbumErrorCode.ALBUM_NOT_FOUND));
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
        Album album = getAlbum(albumId)
            .orElseThrow(() -> new BusinessException(AlbumErrorCode.ALBUM_NOT_FOUND));
        album.setIsDeleted(true);
    }
    
}
