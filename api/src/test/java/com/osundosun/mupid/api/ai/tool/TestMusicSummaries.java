package com.osundosun.mupid.api.ai.tool;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.service.MusicSummaryAssembler;

/**
 * 시험용 곡 조립기 대역 (S15P21A204-424).
 *
 * <p>진짜 {@link MusicSummaryAssembler} 는 리포지토리 넷(스트림·장르·분위기·곡)을 물고
 * 있어 단위 시험에서 세울 수 없다. 대신 **넘긴 엔티티를 그대로 옮기는** 대역을 둔다.
 *
 * <p><b>순서를 지키는 것이 계약이다.</b> {@code AiRecommendToolImpl} 은 조립 결과를
 * 자리로 맞물려 구간과 짝지으므로, 순서가 흔들리면 곡과 구간이 뒤바뀐다. 대역도 진짜와
 * 같이 넘긴 순서 그대로 돌려준다 — 그래야 그 가정을 시험이 실제로 검사한다.
 */
public final class TestMusicSummaries {

    private TestMusicSummaries() {
    }

    /** 넘긴 곡을 그대로 옮기는 조립기 대역. 전부 재생 가능한 곡이다. */
    public static MusicSummaryAssembler assembler() {
        return assembler(Set.of());
    }

    /**
     * {@code notPlayable} 에 든 곡 번호만 재생 불가로 내려보내는 조립기 대역.
     *
     * <p>완료된 스트림이 없는 곡이다. 추천 툴이 그런 곡을 **내려보내기 전에** 걷어내는지
     * 보는 데 쓴다 (S15P21A204-426).
     */
    public static MusicSummaryAssembler assembler(Set<Integer> notPlayable) {
        MusicSummaryAssembler assembler = mock(MusicSummaryAssembler.class);
        when(assembler.assemble(anyList())).thenAnswer(invocation -> {
            List<Music> musics = invocation.getArgument(0);
            return musics.stream()
                .map(music -> summaryOf(music, !notPlayable.contains(music.getMusicId())))
                .toList();
        });
        return assembler;
    }

    /** 엔티티 한 곡 → 목록 표현. 시험이 단언하는 값(제목·아티스트·앨범)만 진짜를 쓴다. */
    public static MusicSummary summaryOf(Music music) {
        return summaryOf(music, true);
    }

    /** 재생 가능 여부를 지정해 옮긴다. 재생 불가면 길이도 없다(스트림이 없으니까). */
    public static MusicSummary summaryOf(Music music, boolean playable) {
        Artist artist = music.getArtist();
        Album album = music.getAlbum();
        return new MusicSummary(
            music.getMusicId(),
            music.getTitle(),
            music.getReleaseDate(),
            // 길이는 `streams` 에서 오므로 엔티티에 없다. 재생 불가면 완료된 스트림이
            // 없다는 뜻이라 길이도 함께 비운다 — 진짜 조립기가 그렇게 만든다.
            playable ? 180_000 : null,
            playable,
            music.getPlayCount() == null ? 0 : music.getPlayCount(),
            music.getLikeCount() == null ? 0 : music.getLikeCount(),
            null,
            new MusicSummary.ArtistSummary(
                artist == null ? 0 : artist.getArtistId(),
                artist == null ? "" : artist.getName(),
                null),
            new MusicSummary.AlbumSummary(
                album == null ? 0 : album.getAlbumId(),
                album == null ? "" : album.getTitle(),
                null,
                null),
            List.of(),
            List.of());
    }
}
