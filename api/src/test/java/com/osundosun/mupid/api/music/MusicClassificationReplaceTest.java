package com.osundosun.mupid.api.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;
import com.osundosun.mupid.api.code.error.CodeErrorCode;
import com.osundosun.mupid.api.code.request.CodeCreateRequest;
import com.osundosun.mupid.api.code.response.CodeResponse;
import com.osundosun.mupid.api.code.service.CodeService;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.mapping.entity.MusicGenre;
import com.osundosun.mupid.api.music.mapping.entity.MusicMood;
import com.osundosun.mupid.api.music.mapping.repository.MusicGenreRepository;
import com.osundosun.mupid.api.music.mapping.repository.MusicMoodRepository;
import com.osundosun.mupid.api.music.request.MusicClassificationRequest;
import com.osundosun.mupid.api.music.response.MusicClassificationResponse;
import com.osundosun.mupid.api.music.service.MusicClassificationService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 곡 분류 적재(`PUT /api/v1/music/{musicId}/classification`)와 코드 추가
 * (`POST /api/v1/code`)의 서비스 계층 검증.
 *
 * <p>전처리 배치가 분석 결과를 올리는 경로다. 관리자 API 를 쓰지 않는 이유는
 * 컨트롤러 주석에 적었다.
 *
 * <p><b>`music_genres`·`music_moods` 는 이 변경 전까지 0행이었다.</b> 두 표 모두
 * 관계와 `@EmbeddedId` 가 같은 컬럼을 각자 매핑하고 있어서, 쓰는 순간
 * `No value specified for parameter` 로 터지는 상태였다. 쓴 적이 없어 드러나지
 * 않았을 뿐이다 — 그래서 여기서 <b>실제로 INSERT 가 되는지</b>를 본다.
 *
 * 로컬 MySQL 의 실제 스키마를 그대로 쓴다. 트랜잭션은 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class MusicClassificationReplaceTest {

    @Autowired
    private MusicClassificationService classificationService;

    @Autowired
    private CodeService codeService;

    @Autowired
    private MusicGenreRepository genreRepository;

    @Autowired
    private MusicMoodRepository moodRepository;

    @PersistenceContext
    private EntityManager em;

    private Music music;
    private Code rock;
    private Code jazz;
    private Code calm;

    @BeforeEach
    void setUp() {
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

        // 부트스트랩이 심는 이름과 겹치지 않게 임의 이름을 쓴다.
        String tag = UUID.randomUUID().toString().substring(0, 8);
        rock = persistCode(CodeType.GENRE, "rock-" + tag);
        jazz = persistCode(CodeType.GENRE, "jazz-" + tag);
        calm = persistCode(CodeType.MOOD, "calm-" + tag);
        em.flush();
    }

    @Test
    @DisplayName("빈 곡에 분류를 넣으면 실제로 저장된다 — 두 표가 처음으로 쓰이는 경로다")
    void writesGenresAndMoods() {
        MusicClassificationResponse result = replace(
            List.of(rock.getCodeId(), jazz.getCodeId()), List.of(calm.getCodeId()));

        assertEquals(music.getMusicId(), result.musicId());
        assertEquals(List.of(rock.getCodeId(), jazz.getCodeId()), result.genreCodeIds());
        assertEquals(List.of(calm.getCodeId()), result.moodCodeIds());

        assertEquals(2, storedGenres().size());
        assertEquals(1, storedMoods().size());
    }

    @Test
    @DisplayName("다시 넣으면 갈아 끼운다 — 두 벌이 겹치지 않는다")
    void replacesInsteadOfAppending() {
        replace(List.of(rock.getCodeId(), jazz.getCodeId()), List.of(calm.getCodeId()));

        MusicClassificationResponse result = replace(List.of(jazz.getCodeId()), List.of());

        assertEquals(List.of(jazz.getCodeId()), result.genreCodeIds());
        assertEquals(List.of(), result.moodCodeIds());
        assertEquals(1, storedGenres().size());
        assertEquals(0, storedMoods().size());
    }

    @Test
    @DisplayName("빈 배열을 보내면 지운다 — 옛 분류가 최신인 척 남으면 안 된다")
    void emptyListsClearTheClassification() {
        replace(List.of(rock.getCodeId()), List.of(calm.getCodeId()));

        replace(List.of(), List.of());

        assertEquals(0, storedGenres().size());
        assertEquals(0, storedMoods().size());
    }

    @Test
    @DisplayName("같은 코드를 두 번 주면 한 번만 저장한다 — 중복 키로 터지지 않는다")
    void deduplicatesRepeatedCodes() {
        MusicClassificationResponse result = replace(
            List.of(rock.getCodeId(), rock.getCodeId()), List.of());

        assertEquals(List.of(rock.getCodeId()), result.genreCodeIds());
        assertEquals(1, storedGenres().size());
    }

    @Test
    @DisplayName("장르 자리에 분위기 코드를 넣으면 막는다")
    void rejectsCodeOfTheWrongType() {
        BusinessException thrown = assertThrows(BusinessException.class,
            () -> replace(List.of(calm.getCodeId()), List.of()));

        assertEquals(CodeErrorCode.CODE_TYPE_MISMATCH, thrown.getErrorCode());
    }

    @Test
    @DisplayName("없는 코드 번호를 주면 막는다 — 조용히 빈 분류로 넘어가지 않는다")
    void rejectsUnknownCode() {
        BusinessException thrown = assertThrows(BusinessException.class,
            () -> replace(List.of(Integer.MAX_VALUE), List.of()));

        assertEquals(CodeErrorCode.CODE_NOT_FOUND, thrown.getErrorCode());
    }

    @Test
    @DisplayName("없는 곡이면 막는다")
    void rejectsUnknownMusic() {
        BusinessException thrown = assertThrows(BusinessException.class,
            () -> classificationService.replaceClassification(Integer.MAX_VALUE,
                new MusicClassificationRequest(List.of(), List.of())));

        assertEquals(MusicErrorCode.MUSIC_NOT_FOUND, thrown.getErrorCode());
    }

    @Test
    @DisplayName("코드 추가는 멱등이다 — 배치가 같은 장르를 수백 번 보낸다")
    void addingTheSameCodeTwiceReturnsTheSameRow() {
        String name = "synthwave-" + UUID.randomUUID().toString().substring(0, 8);

        CodeResponse first = codeService.addCode(new CodeCreateRequest(CodeType.GENRE, name, null));
        CodeResponse second = codeService.addCode(new CodeCreateRequest(CodeType.GENRE, name, null));

        assertTrue(first.created(), "처음에는 만들어야 한다");
        assertEquals(false, second.created(), "두 번째는 만들지 않는다");
        assertEquals(first.codeId(), second.codeId());
    }

    @Test
    @DisplayName("같은 이름이라도 종류가 다르면 다른 코드다")
    void theSameNameInAnotherTypeIsAnotherCode() {
        String name = "bright-" + UUID.randomUUID().toString().substring(0, 8);

        CodeResponse genre = codeService.addCode(new CodeCreateRequest(CodeType.GENRE, name, null));
        CodeResponse mood = codeService.addCode(new CodeCreateRequest(CodeType.MOOD, name, null));

        assertTrue(genre.created());
        assertTrue(mood.created());
        assertTrue(!genre.codeId().equals(mood.codeId()));
    }

    // ------------------------------------------------------------------ 도우미

    private MusicClassificationResponse replace(List<Integer> genreIds, List<Integer> moodIds) {
        MusicClassificationResponse result = classificationService.replaceClassification(
            music.getMusicId(), new MusicClassificationRequest(genreIds, moodIds));
        em.flush();
        em.clear();
        return result;
    }

    private List<MusicGenre> storedGenres() {
        return genreRepository.findByMusicGenreId_MusicIdIn(List.of(music.getMusicId()));
    }

    private List<MusicMood> storedMoods() {
        return moodRepository.findByMusicMoodId_MusicIdIn(List.of(music.getMusicId()));
    }

    private Code persistCode(CodeType type, String name) {
        Code code = Code.builder().codeType(type).codeName(name).displayName(name).build();
        em.persist(code);
        return code;
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
