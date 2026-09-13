package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.error.ArtistErrorCode;
import com.osundosun.mupid.api.music.repository.ArtistRepository;
import com.osundosun.mupid.api.music.request.ArtistCreateRequest;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.error.StorageErrorCode;
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
