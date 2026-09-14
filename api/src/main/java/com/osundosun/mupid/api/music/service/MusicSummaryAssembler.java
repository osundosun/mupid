package com.osundosun.mupid.api.music.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.mapping.repository.MusicGenreRepository;
import com.osundosun.mupid.api.music.mapping.repository.MusicMoodRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.storage.service.StorageImageService;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.enumerate.StreamStatus;
import com.osundosun.mupid.api.stream.repository.StreamRepository;

import lombok.RequiredArgsConstructor;

/**
 * 곡을 화면용 표현(`MusicSummary`)으로 바꾼다.
 *
 * 서비스가 아니라 조립기로 따로 둔 이유: 곡 목록·좋아요 목록·최근 감상·앨범
 * 수록곡이 모두 같은 표현을 쓰는데, 이걸 `MusicService` 에 두면 앨범 서비스가
 * 곡 서비스를 부르고 곡 서비스가 앨범 서비스를 부르는 **순환 의존**이 생긴다.
 * 조립기는 리포지토리만 알면 되므로 누구나 부를 수 있다.
 */
@Component
@RequiredArgsConstructor
public class MusicSummaryAssembler {

    private final MusicRepository musicRepository;
    private final StreamRepository streamRepository;
    private final MusicGenreRepository genreRepository;
    private final MusicMoodRepository moodRepository;

    /**
     * 곡 번호로 목록 표현을 만든다. **순서는 넘긴 그대로** 지킨다.
     * 삭제된 곡은 빠지므로 결과가 더 짧을 수 있다.
     */
    @Transactional(readOnly = true)
    public List<MusicSummary> byIds(List<Integer> musicIds) {
        if (musicIds == null || musicIds.isEmpty()) return List.of();
        Map<Integer, Music> found = musicRepository.findDetailAllByIds(musicIds).stream()
            .collect(Collectors.toMap(Music::getMusicId, Function.identity()));
        // IN 절은 순서를 보장하지 않는다. 넘긴 순서로 다시 세운다.
        return assemble(musicIds.stream()
            .map(found::get)
            .filter(music -> music != null)
            .toList());
    }

    /**
     * 이미 읽어 둔 곡들을 목록 표현으로 바꾼다.
     *
     * 재생 길이·장르·분위기는 곡마다 조회하면 N+1 이라 페이지 단위로 한 번에
     * 가져와 메모리에서 묶는다.
     */
    @Transactional(readOnly = true)
    public List<MusicSummary> assemble(List<Music> musics) {
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
                    artist.getArtistId(), artist.getName(), StorageImageService.imageUrl(artist.getImageFile())),
                new MusicSummary.AlbumSummary(
                    album.getAlbumId(), album.getTitle(),
                    StorageImageService.imageUrl(album.getImageFile()), album.getReleaseDate()),
                genres.getOrDefault(music.getMusicId(), List.of()),
                moods.getOrDefault(music.getMusicId(), List.of()));
        }).toList();
    }

}
