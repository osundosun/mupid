package com.osundosun.mupid.api.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.dto.LyricLine;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.LyricErrorCode;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.request.LyricBulkLine;
import com.osundosun.mupid.api.music.request.LyricBulkRequest;
import com.osundosun.mupid.api.music.response.LyricBulkResponse;
import com.osundosun.mupid.api.music.service.LyricService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 가사 일괄 교체(`PUT /api/v1/music/{musicId}/lyric`)의 서비스 계층 검증.
 *
 * 로컬 MySQL 의 실제 스키마를 그대로 쓴다 — `lyrics.time_ms` 가 `INT UNSIGNED`
 * 라서 음수 처리는 인메모리 DB 로 검증되지 않는다. 트랜잭션은 테스트마다
 * 롤백되므로 데이터는 남지 않는다.
 *
 * 애너테이션 검증(`@NotEmpty`·`@Size`)은 컨트롤러 계층에서 걸리므로 여기서는
 * 서비스가 직접 막는 것(상한)과 교체 의미만 본다.
 */
@SpringBootTest
@Transactional
class LyricReplaceTest {

    @Autowired
    private LyricService lyricService;

    @PersistenceContext
    private EntityManager em;

    private Music music;
    private Music otherMusic;

    @BeforeEach
    void setUp() {
        /* albums·artists 는 이미지 파일이 필수다 (image_file_id NOT NULL) */
        ObjectStorageFile image = persistImage();

        Artist artist = Artist.builder().name("LUME").imageFile(image).build();
        em.persist(artist);

        Album album = Album.builder()
            .artist(artist)
            .title("Blue Static")
            .imageFile(image)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(album);

        music = persistMusic(album, artist, "푸른 오후");
        otherMusic = persistMusic(album, artist, "새벽 드라이브");
        em.flush();
    }

    @Test
    @DisplayName("빈 곡에 가사를 넣으면 전부 들어가고 지운 것은 없다")
    void writesAllLinesToEmptyMusic() {
        LyricBulkResponse result = replace(music, line(0, "첫 줄"), line(1500, "둘째 줄"));

        assertEquals(music.getMusicId(), result.musicId());
        assertEquals(0, result.deleted());
        assertEquals(2, result.created());
        assertEquals(List.of("첫 줄", "둘째 줄"), texts(music));
    }

    @Test
    @DisplayName("다시 넣으면 기존 가사를 지우고 갈아 끼운다 — 두 벌이 겹치지 않는다")
    void replacesInsteadOfAppending() {
        replace(music, line(0, "옛 줄 1"), line(1000, "옛 줄 2"), line(2000, "옛 줄 3"));

        LyricBulkResponse result = replace(music, line(0, "새 줄"));

        assertEquals(3, result.deleted());
        assertEquals(1, result.created());
        assertEquals(List.of("새 줄"), texts(music));
    }

    @Test
    @DisplayName("같은 본문을 두 번 보내면 결과가 같다")
    void isIdempotent() {
        LyricBulkLine[] lines = {line(0, "첫 줄"), line(1500, "둘째 줄")};

        replace(music, lines);
        List<String> first = texts(music);
        replace(music, lines);

        assertEquals(first, texts(music));
        assertEquals(2, texts(music).size());
    }

    @Test
    @DisplayName("다른 곡의 가사는 건드리지 않는다")
    void leavesOtherMusicAlone() {
        replace(otherMusic, line(0, "남의 곡 가사"));
        replace(music, line(0, "내 곡 가사 1"), line(900, "내 곡 가사 2"));

        assertEquals(List.of("남의 곡 가사"), texts(otherMusic));
    }

    @Test
    @DisplayName("시각이 같은 줄은 보낸 순서 그대로 보인다")
    void keepsInsertionOrderForEqualTimes() {
        /* 후렴에서 겹쳐 나오는 줄이 실제로 이렇다 (원본 데이터에 164줄) */
        replace(music, line(5000, "본 가사"), line(5000, "(따라 부르기)"), line(5000, "뒤 줄"));

        assertEquals(List.of("본 가사", "(따라 부르기)", "뒤 줄"), texts(music));
    }

    @Test
    @DisplayName("앞뒤 공백은 잘라서 저장한다")
    void trimsText() {
        replace(music, line(0, "  공백 낀 줄  "));

        assertEquals(List.of("공백 낀 줄"), texts(music));
    }

    @Test
    @DisplayName("상한을 넘기면 하나도 넣지 않는다")
    void rejectsTooManyLines() {
        List<LyricBulkLine> tooMany = new ArrayList<>();
        for (int i = 0; i <= LyricBulkRequest.MAX_LINES; i++) {
            tooMany.add(line(i * 100, "줄 " + i));
        }

        BusinessException e = assertThrows(BusinessException.class,
            () -> lyricService.replaceLyrics(music.getMusicId(), new LyricBulkRequest(tooMany)));

        assertEquals(LyricErrorCode.LYRIC_BULK_TOO_MANY, e.getErrorCode());
        assertTrue(texts(music).isEmpty());
    }

    @Test
    @DisplayName("상한을 넘겨도 이미 있던 가사는 남는다")
    void keepsExistingLinesWhenRejected() {
        replace(music, line(0, "지키고 싶은 줄"));

        List<LyricBulkLine> tooMany = new ArrayList<>();
        for (int i = 0; i <= LyricBulkRequest.MAX_LINES; i++) {
            tooMany.add(line(i * 100, "줄 " + i));
        }
        assertThrows(BusinessException.class,
            () -> lyricService.replaceLyrics(music.getMusicId(), new LyricBulkRequest(tooMany)));

        assertEquals(List.of("지키고 싶은 줄"), texts(music));
    }

    @Test
    @DisplayName("없는 곡이면 404 다")
    void rejectsMissingMusic() {
        BusinessException e = assertThrows(BusinessException.class,
            () -> lyricService.replaceLyrics(-1, new LyricBulkRequest(List.of(line(0, "줄")))));

        assertEquals(MusicErrorCode.MUSIC_NOT_FOUND, e.getErrorCode());
    }

    @Test
    @DisplayName("삭제된 곡에는 가사를 넣을 수 없다 — 404 가 아니라 410 이다")
    void rejectsDeletedMusic() {
        music.setIsDeleted(true);
        em.flush();

        BusinessException e = assertThrows(BusinessException.class,
            () -> replace(music, line(0, "줄")));

        /* 행이 남으므로 "없는 곡" 과 "지워진 곡" 을 가른다 (2026-09-18 변경). */
        assertEquals(MusicErrorCode.MUSIC_GONE, e.getErrorCode());
    }

    // ─────────────────────────────────────────────────────────────

    private LyricBulkResponse replace(Music target, LyricBulkLine... lines) {
        LyricBulkResponse result = lyricService.replaceLyrics(
            target.getMusicId(), new LyricBulkRequest(List.of(lines)));
        /* 조회는 새 트랜잭션이 아니므로, 저장이 실제로 나갔는지 보려면 밀어낸다 */
        em.flush();
        return result;
    }

    private static LyricBulkLine line(int timeMs, String text) {
        return new LyricBulkLine(text, timeMs);
    }

    /** 조회 순서(`time_ms`, `lyric_id`) 그대로의 가사 본문. */
    private List<String> texts(Music target) {
        em.flush();
        return lyricService.getLyrics(target.getMusicId()).lines()
            .stream()
            .map(LyricLine::text)
            .toList();
    }

    private ObjectStorageFile persistImage() {
        ObjectStorageFile image = ObjectStorageFile.builder()
            .fileType(FileType.IMG)
            .category(FileCategory.ALBUM)
            .originalFileName("cover.jpg")
            .storageObjectKey("test/%s.jpg".formatted(UUID.randomUUID()))
            .fileSizeBytes(1024)
            .mime("image/jpeg")
            .build();
        em.persist(image);
        return image;
    }

    private Music persistMusic(Album album, Artist artist, String title) {
        Music newMusic = Music.builder()
            .album(album)
            .artist(artist)
            .title(title)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(newMusic);
        return newMusic;
    }

}
