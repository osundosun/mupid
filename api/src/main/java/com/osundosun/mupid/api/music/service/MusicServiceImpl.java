package com.osundosun.mupid.api.music.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.AlbumErrorCode;
import com.osundosun.mupid.api.music.error.ArtistErrorCode;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.mapping.repository.MusicGenreRepository;
import com.osundosun.mupid.api.music.mapping.repository.MusicMoodRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
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
    private final StreamRepository streamRepository;
    private final MusicGenreRepository genreRepository;
    private final MusicMoodRepository moodRepository;

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
    public MusicPageResponse searchMusic(String keyword, int page, int size) {
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int safePage = Math.max(0, page);
        String trimmed = (keyword == null || keyword.isBlank()) ? null : keyword.trim();

        Page<Music> found = repository.search(
            trimmed,
            PageRequest.of(safePage, safeSize, Sort.by(Sort.Direction.DESC, "musicId")));

        List<MusicSummary> items = toSummaries(found.getContent());
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
        return toSummaries(List.of(music)).get(0);
    }

    /**
     * 곡 목록을 화면용 표현으로 바꾼다.
     *
     * 재생 길이·장르·분위기는 곡마다 조회하면 N+1 이라 페이지 단위로 한 번에
     * 가져와 메모리에서 묶는다.
     */
    private List<MusicSummary> toSummaries(List<Music> musics) {
        if (musics.isEmpty()) return List.of();
        List<Integer> ids = musics.stream().map(Music::getMusicId).toList();

        // 같은 곡에 여러 스트림이 있으면 비트레이트가 가장 높은 것을 기준으로 둔다.
        Map<Integer, Stream> bestStream = streamRepository
            .findAllByMusicIdInAndStatus(ids, StreamStatus.DONE)
            .stream()
            .collect(Collectors.toMap(
                Stream::getMusicId,
                Function.identity(),
                (a, b) -> a.getBitrateKbps() >= b.getBitrateKbps() ? a : b));

        Map<Integer, List<String>> genres = genreRepository
            .findByMusicGenreId_MusicIdIn(ids)
            .stream()
            .collect(Collectors.groupingBy(
                it -> it.getMusicGenreId().getMusicId(),
                Collectors.mapping(it -> it.getGenreCode().getCodeName(), Collectors.toList())));

        Map<Integer, List<String>> moods = moodRepository
            .findByMusicMoodId_MusicIdIn(ids)
            .stream()
            .collect(Collectors.groupingBy(
                it -> it.getMusicMoodId().getMusicId(),
                Collectors.mapping(it -> it.getMoodCode().getCodeName(), Collectors.toList())));

        return musics.stream().map(music -> {
            Stream stream = bestStream.get(music.getMusicId());
            Artist artist = music.getArtist();
            Album album = music.getAlbum();
            return new MusicSummary(
                music.getMusicId(),
                music.getTitle(),
                music.getReleaseDate(),
                stream == null ? null : stream.getDurationMs(),
                stream != null,
                music.getPlayCount(),
                music.getLikeCount(),
                new MusicSummary.ArtistSummary(
                    artist.getArtistId(), artist.getName(), imageUrlOf(artist.getImageFile())),
                new MusicSummary.AlbumSummary(
                    album.getAlbumId(), album.getTitle(),
                    imageUrlOf(album.getImageFile()), album.getReleaseDate()),
                genres.getOrDefault(music.getMusicId(), List.of()),
                moods.getOrDefault(music.getMusicId(), List.of()));
        }).toList();
    }

    /**
     * 저장 키(`image/album/커버`)를 화면이 그대로 쓸 수 있는 조회 URL 로 바꾼다.
     * 키 형식은 StorageImageService.getStorageKey 와 짝이다.
     */
    private String imageUrlOf(ObjectStorageFile file) {
        if (file == null || file.getIsDeleted()) return null;
        String[] parts = file.getStorageObjectKey().split("/", 3);
        if (parts.length < 3) return null;
        return "/api/v1/storage/image/%s/%s".formatted(
            parts[1].toUpperCase(),
            URLEncoder.encode(parts[2], StandardCharsets.UTF_8).replace("+", "%20"));
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
