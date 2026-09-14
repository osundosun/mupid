package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.AlbumErrorCode;
import com.osundosun.mupid.api.music.error.ArtistErrorCode;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.request.MusicAddRequest;
import com.osundosun.mupid.api.music.request.MusicUpdateRequest;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class MusicServiceImpl implements MusicService {

    private final ArtistService artistService;
    private final AlbumService albumServce;
    private final MusicRepository repository;

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
    }

    @Override
    public void deleteMusic(int musicId) {
        Music music = getMusic(musicId)
            .orElseThrow(() -> new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND));
        music.setIsDeleted(true);
    }

}
