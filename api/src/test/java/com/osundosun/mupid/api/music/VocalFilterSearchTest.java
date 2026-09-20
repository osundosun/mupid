package com.osundosun.mupid.api.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicAnalysis;
import com.osundosun.mupid.api.music.enumerate.VocalFilter;
import com.osundosun.mupid.api.music.enumerate.VocalGender;
import com.osundosun.mupid.api.music.repository.AlbumRepository;
import com.osundosun.mupid.api.music.repository.ArtistRepository;
import com.osundosun.mupid.api.music.repository.MusicAnalysisRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.repository.ObjectStorageFileRepository;

/**
 * 탐색 화면의 보컬 필터 검증.
 *
 * <h2>이 테스트가 지키는 것</h2>
 *
 * 세 값(여성·남성·인스트루멘탈)이 <b>서로 겹치지 않고 각자 의도한 곡만</b> 남기는가.
 * 검색어와 필터가 <b>동시에</b> 걸릴 때도 그런가(전문 인덱스 경로와 prefix 경로
 * 양쪽). 필터를 안 걸면 <b>예전과 완전히 같은 목록</b>이 나오는가(하위 호환).
 *
 * <h2>왜 픽스처를 커밋하는가 — 이 테스트의 가장 중요한 결정</h2>
 *
 * 두 글자 이상 검색은 MySQL ngram FULLTEXT 를 탄다. 그런데 <b>InnoDB 전문 인덱스는
 * 같은 트랜잭션에서 넣은 행을 보지 못한다.</b> 직접 확인한 결과다 — 롤백 대역에서
 * `music` 행을 넣고 `MATCH` 를 걸면 0 건이었다. 커밋한 뒤에는 1 건이었다.
 *
 * 그래서 이 클래스는 {@code @Transactional} 로 롤백하지 않는다. 곡을 <b>커밋</b>해야
 * 전문 인덱스가 그것을 찾고, 그래야 "검색어 + 필터" 를 실제로 검증할 수 있다.
 * 분석 행도 함께 커밋한다(같은 이유 — `JOIN` 이 봐야 한다).
 *
 * <h2>그래서 뒷정리가 이 테스트의 일부다</h2>
 *
 * 커밋했으니 {@link #cleanUp()} 이 지운다. {@code music_analysis} 를 먼저 지운다 —
 * `music` 을 참조하는 외래 키가 있어 순서를 바꾸면 삭제가 막힌다.
 *
 * <h2>픽스처를 남기지 않는다 — 세 겹의 방어</h2>
 *
 * 픽스처를 커밋하는 이상 "실패하면 남는다" 를 그냥 받아들일 수 없다. 남은 행은 다음
 * 실행의 단언을 깨뜨리고(픽스처는 고유 표식이라 목록에 그대로 나타난다), 그 실패가 또
 * 행을 남기는 <b>순환</b>을 만든다. 실제로 티켓 S15P21A204-403 에서 36행이 누적되어
 * 실패 목록을 왜곡했고, 그 탓에 없는 회귀를 좇았다. 그래서 정리를 <b>세 곳</b>에 둔다.
 *
 * <ol>
 *   <li><b>시작할 때 청소</b> ({@link #purgeStaleFixtures()} , {@code @BeforeAll} 첫 줄).
 *       표식이 무엇이든 <b>이전 실행이 남긴</b> {@code Vocalprobe%} 를 먼저 지운다.
 *       누적 자체를 막는 근본 방어다.</li>
 *   <li><b>표식이 아니라 규칙으로 지운다</b> ({@link #cleanUp()} , {@code @AfterAll}).
 *       지울 대상을 {@code MARK} 가 아니라 {@code title LIKE 'Vocalprobe%'} 로 찾으므로
 *       <b>메모리 상태에 의존하지 않는다.</b> 이것이 예전 구조의 급소였다 —</li>
 *   <li><b>그래도 커밋을 지연한다</b> — {@link Fixture} 를 필드에 담기 <b>전</b>에는
 *       커밋이 없으므로, 그 전에 죽으면 지울 것도 없다.</li>
 * </ol>
 *
 * <h2>예전 구조가 왜 새던가 — 원인은 "정리 코드가 없어서" 가 아니다</h2>
 *
 * 예전 {@code cleanUp()} 은 <b>실행됐다.</b> 다만 지울 곡 번호를 {@code fixture} 필드에서
 * 읽었는데, {@code seed()} 가 그 대입에 이르기 전에 죽으면 필드가 null 이라
 * {@code NullPointerException} 으로 <b>아무것도 지우지 못하고</b> 끝났다. 실제 스택이다:
 *
 * <pre>
 * Suppressed: java.lang.NullPointerException: Cannot invoke
 *     "VocalFilterSearchTest$Fixture.female()" because "this.fixture" is null
 *     at ...VocalFilterSearchTest.cleanUp(VocalFilterSearchTest.java:173)
 * </pre>
 *
 * 그래서 여기서는 <b>{@code try/finally} 로 {@code seed()} 를 감싸지 않았다.</b>
 * 커밋을 필드 대입 뒤로 미루면 {@code @BeforeAll} 이 어디서 죽든 남는 행이 없어
 * 같은 보증을 더 단순하게 얻는다. 남은 정리 의무는 {@code @AfterAll} 의 몫이다 —
 * JUnit 은 한 번이라도 성공한 {@code @BeforeAll} 뒤에는 {@code @AfterAll} 을 돌린다.
 * 게다가 그 정리도 <b>표식이 아니라 {@code title} 접두사</b>로 대상을 찾으므로,
 * 필드가 null 이어도 지운다. 이 두 성질을 403 에서 실제로 확인했다(주입한 실패,
 * 실행 전 1065행 → 실행 후 1065행).
 *
 * <h2>왜 제목을 직접 만드는가</h2>
 *
 * 이 개발 DB 의 카탈로그는 다른 세션이 바꿀 수 있다. '제목에 Blue 가 든 곡이 이미
 * 있다' 같은 사실에 기대면 어느 날 조용히 깨진다. 검색어도 픽스처와 함께 만든다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VocalFilterSearchTest {

    /** 픽스처 제목·아티스트명·앨범명이 공통으로 쓰는 접두사. 청소가 이걸로 대상을 찾는다. */
    private static final String MARK_PREFIX = "Vocalprobe";

    private static final String MARK_SUFFIX = UUID.randomUUID().toString().substring(0, 8);

    /**
     * 다른 실행·다른 테스트의 곡과 섞이지 않게 하는 고유 표식.
     *
     * <b>한 글자 이상, 두 글자 이상이어야 한다</b> — 앞은 prefix 경로, 뒤는 FULLTEXT
     * 경로를 타야 하기 때문이다. ngram 기본 토큰 크기가 2 라 한 글자로는 전문 인덱스가
     * 걸리지 않는다.
     */
    private static final String MARK = MARK_PREFIX + MARK_SUFFIX;

    /**
     * 픽스처 앨범 제목·아티스트명의 접두사. {@link #seed()} 가 이걸로 이름을 만들고
     * {@link #purgeStaleFixtures()} 가 같은 상수로 찾는다 — 두 곳에 문자열을 각각 적으면
     * 한쪽만 바뀌었을 때 청소가 조용히 대상을 놓친다(실제로 그렇게 놓쳤다).
     */
    private static final String PROBE_ALBUM_TITLE = "보컬검증 앨범 " + MARK_PREFIX;

    private static final String PROBE_ARTIST_NAME = "보컬검증 아티스트 " + MARK_PREFIX;

    /** 픽스처 표지 파일의 이름. {@code object_storage_files} 에서 이 이름으로 찾아 지운다. */
    private static final String PROBE_IMAGE_NAME = "vocal-probe.jpg";

    /**
     * {@code music} 을 참조하는 자식 테이블 — {@link #purgeStaleFixtures()} 가 곡보다 먼저 비운다.
     *
     * <p>목록은 개발 DB 의 외래 키에서 뽑았다({@code music} 을 참조하는 12개). 테스트가
     * 만드는 것은 {@code music_analysis} 뿐이지만, <b>청소는 "이 테스트가 넣은 것" 이 아니라
     * "남아 있는 것" 을 대상으로</b> 하므로 나머지도 함께 비운다. 402 사고에서는
     * {@code music_section_analysis}·{@code music_genres}·{@code music_moods} 가 곡을
     * 붙들고 있었다.
     */
    private static final List<String> MUSIC_CHILD_TABLES = List.of(
        "music_analysis",
        "music_section_analysis",
        "music_genres",
        "music_moods",
        "music_reactions",
        "music_comments",
        "lyrics",
        "playlist_tracks",
        "play_histories",
        "listening",
        "streams",
        "daily_recommendation_tracks");

    @Autowired
    private MusicService musicService;

    @Autowired
    private MusicRepository musicRepository;

    @Autowired
    private MusicAnalysisRepository analysisRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ObjectStorageFileRepository objectStorageFileRepository;

    /**
     * 픽스처 청소 전용. JPA 로는 하기 어려운 두 가지를 여기서 한다 —
     * <b>제목 접두사로 찾기</b>와 <b>여러 자식 테이블을 한 번에 지우기</b>.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    /** 검증에 쓸 곡 네 개의 번호. 커밋됐으므로 클래스 전체가 같은 값을 본다. */
    private record Fixture(int female, int male, int instrumental, int etc) {}

    /**
     * 픽스처가 커밋됐을 때만 채워진다. 커밋은 {@link #seed()} 의 <b>마지막 문장</b>이다 —
     * 이 대입에 이르기 전에 죽으면 DB 에는 아무것도 남지 않는다.
     */
    private Fixture fixture;

    @BeforeAll
    void seed() {
        /*
         * 남은 청소를 여기서 먼저 한다. 자세한 이유는 purgeStaleFixtures() 참고.
         */
        purgeStaleFixtures();

        /*
         * 아티스트·앨범은 이미지가 **필수**다(DB 의 image_file_id 가 NOT NULL, api/AGENTS.md).
         * 곡(image_file_id)만 선택이다. 그래서 여기서 파일 행 하나를 만들어 둘 다에 붙인다.
         * 실제 바이트는 올리지 않는다 — 이 테스트가 보는 것은 보컬 필터지 이미지가 아니다.
         */
        ObjectStorageFile image = objectStorageFileRepository.saveAndFlush(
            ObjectStorageFile.builder()
                .fileType(FileType.IMG)
                .category(FileCategory.ALBUM)
                .originalFileName("vocal-probe.jpg")
                .storageObjectKey("test/%s.jpg".formatted(UUID.randomUUID()))
                .fileSizeBytes(1024)
                .mime("image/jpeg")
                .build());

        Artist artist = artistRepository.save(Artist.builder()
            .name(PROBE_ARTIST_NAME + MARK_SUFFIX)
            .imageFile(image)
            .build());
        Album album = albumRepository.save(Album.builder()
            .artist(artist)
            .title(PROBE_ALBUM_TITLE + MARK_SUFFIX)
            .imageFile(image)
            .releaseDate(LocalDateTime.now())
            .build());

        int female = persist(album, artist, MARK + " 여성 보컬");
        int male = persist(album, artist, MARK + " 남성 보컬");
        int instrumental = persist(album, artist, MARK + " 인스트루멘탈");
        int etc = persist(album, artist, MARK + " 성별미상 보컬");

        /*
         * 분석 행은 **여기서 한꺼번에** 쓴다. 행마다 쓰지 않는 이유는 실패 지점을 하나로
         * 모으기 위해서다 — 네 줄에 흩어 두면 두 번째에서 죽었을 때 곡 4개가 분석 1개와
         * 함께 남는다. 한 번의 문장이면 "곡은 있는데 분석은 없다" 라는 중간 상태가 없다.
         *
         * <b>{@code musicId} 를 직접 넣지 않는다.</b> {@code MusicAnalysis} 는 {@code @MapsId}
         * 라 식별자가 {@code music} 연관에서 파생된다. 둘 다 넣으면 Hibernate 가 병합
         * 경로로 빠져 `null identifier` 로 터진다(직접 겪었다). 연관만 주면 저장 시점에
         * 곡 번호가 그대로 복사된다.
         */
        List<MusicAnalysis> analyses = List.of(
            MusicAnalysis.builder()
                .music(musicRepository.getReferenceById(female))
                .hasVocal(true).vocalGender(VocalGender.F)
                .build(),
            MusicAnalysis.builder()
                .music(musicRepository.getReferenceById(male))
                .hasVocal(true).vocalGender(VocalGender.M)
                .build(),
            MusicAnalysis.builder()
                .music(musicRepository.getReferenceById(instrumental))
                .hasVocal(false).vocalGender(null)
                .build(),
            MusicAnalysis.builder()
                .music(musicRepository.getReferenceById(etc))
                .hasVocal(true).vocalGender(VocalGender.MIX)
                .build());
        analysisRepository.saveAllAndFlush(analyses);

        /*
         * 여기까지 왔을 때만 "커밋된 픽스처가 있다" 가 참이 된다. 위 어느 줄에서 죽어도
         * DB 에는 곡·앨범·아티스트·이미지 파일이 남지 않는다(@AfterAll 이 title 로 지운다).
         */
        imageFileId = image.getFileId();
        fixture = new Fixture(female, male, instrumental, etc);
    }

    /**
     * 이전 실행이 남긴 픽스처를 지운다. {@code @BeforeAll} 의 <b>첫 문장</b>으로 돈다.
     *
     * <h2>왜 필요한가 — 자기 표식만 보면 누적을 못 막는다</h2>
     *
     * 실패를 처리하는 나머지 두 겹({@link #cleanUp()} 과 커밋 지연)이 있으면 새 누수는
     * 생기지 않는다. 그래도 이 방어가 필요한 이유는 <b>이미 새어 있는 행</b> 때문이다 —
     * 옛 버전이 남긴 행은 표식이 {@code MARK} 와 다르므로 이 실행이 만들지도, 지우지도
     * 않는다. 그대로 두면 이 테스트의 목록 단언(픽스처 곡만 남아야 한다)이 계속 깨지고,
     * 그 실패가 <b>없는 회귀</b>로 읽힌다. 실제로 S15P21A204-403 에서 36행이 그렇게
     * 누적되어 develop 과 작업 브랜치의 실패 목록을 똑같이 오염시켰다.
     *
     * <h2>무엇을 지우는가 — 표식이 아니라 규칙</h2>
     *
     * 이 클래스가 만든 곡은 <b>예외 없이</b> {@code title} 이 {@link #MARK_PREFIX} 로
     * 시작한다. 그래서 접두사로 찾는다. 표식 문자열을 저장해 둘 필요가 없고, 이전 실행이
     * 남긴 것도 같은 규칙에 걸린다.
     *
     * <p>다른 테스트가 같은 접두사를 쓸 수 있으므로, 픽스처가 쓸 수 있는 이름 모양
     * ({@code Vocalprobe<8자>})과 정확히 맞는 것만 <b>자바에서</b> 걸러 낸다. SQL 의
     * {@code LIKE} 만 쓰면 실수로 넓은 {@code Vocalprobe%} 를 다른 제목에 대고 지울 수 있다.
     *
     * <h2>지우는 순서</h2>
     *
     * {@code music} 을 참조하는 자식 12개(테이블 목록 참고) → 곡 → 자기 앨범·아티스트 →
     * 자기 이미지 파일. 자식을 건너뛰면 외래 키(1451)에 막힌다.
     *
     * <p><b>{@code music_section_analysis} 를 반드시 포함한다.</b> 개발 DB 에 10007행이
     * 있는 유일한 자식이라, 여기를 빠뜨리면 개발 DB 를 상대로는 이 정리가 통째로 실패한다
     * (테스트 DB 에서는 비어 있어 드러나지 않는다 — 직접 겪었다).
     */
    private void purgeStaleFixtures() {
        List<Integer> staleIds = staleTrackIds();
        if (!staleIds.isEmpty()) {
            for (String child : MUSIC_CHILD_TABLES) {
                jdbcTemplate.update(
                    "DELETE FROM " + child + " WHERE music_id IN (" + placeholders(staleIds.size()) + ")",
                    staleIds.toArray());
            }
            jdbcTemplate.update(
                "DELETE FROM music WHERE music_id IN (" + placeholders(staleIds.size()) + ")",
                staleIds.toArray());
        }

        /*
         * 곡·앨범·아티스트·표지 파일은 **각각 따로** 지운다. 곡이 이미 지워졌다고 여기서
         * 빠져나가면 안 된다 — seed() 가 곡을 만들기 전(앨범·아티스트·파일만 만든 뒤)에
         * 죽으면 곡은 없는데 셋은 남기 때문이다. 실제로 그 경우를 겪었다.
         *
         * 그리고 앨범 제목·아티스트명은 MARK_PREFIX 로 **시작하지 않는다**
         * (`보컬검증 앨범 Vocalprobe…`). 그래서 `%Vocalprobe%` 로 찾는다. 이걸
         * `MARK_PREFIX + " 앨범 %"` 로 썼다가 아무것도 못 지웠다.
         */
        jdbcTemplate.update("DELETE FROM albums WHERE title LIKE ?", "%" + MARK_PREFIX + "%");
        jdbcTemplate.update("DELETE FROM artists WHERE name LIKE ?", "%" + MARK_PREFIX + "%");
        jdbcTemplate.update(
            "DELETE FROM object_storage_files WHERE original_file_name = ?", PROBE_IMAGE_NAME);
    }

    /** {@link #MARK_PREFIX} 로 시작하고 모양이 픽스처와 맞는 곡 번호. */
    private List<Integer> staleTrackIds() {
        return jdbcTemplate.queryForList(
            "SELECT music_id FROM music WHERE title LIKE ?", Integer.class, MARK_PREFIX + "%")
            .stream()
            .filter(this::looksLikeOurFixture)
            .collect(Collectors.toList());
    }

    /**
     * 픽스처가 붙이는 제목인가 — {@code Vocalprobe<8자> <설명>}.
     *
     * 표식은 UUID 앞 8자라 소문자 16진수다. 이 모양까지 확인해야 다른 테스트가 만든
     * {@code Vocalprobe...} 를 실수로 지우지 않는다.
     */
    private boolean looksLikeOurFixture(Integer trackId) {
        String title = jdbcTemplate.queryForObject(
            "SELECT title FROM music WHERE music_id = ?", String.class, trackId);
        return title != null
            && title.matches(MARK_PREFIX + "[0-9a-f]{8} .+");
    }

    /** {@code IN (?)} 를 개수만큼 만든다. 자리 수는 값 개수와 반드시 같아야 한다. */
    private static String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }

    /** seed() 가 만든 표지 파일 번호. 정리할 때 지운다. */
    private Integer imageFileId;

    /**
     * 커밋한 픽스처를 지운다.
     *
     * <h2>왜 {@code fixture} 를 쓰지 않는가 — 403 의 핵심</h2>
     *
     * 예전 판은 지울 곡 번호를 {@code fixture} 필드에서 읽었다. 그래서 {@code seed()} 가
     * 그 대입에 이르기 전에 죽으면(402 의 {@code Data truncated} 가 정확히 그랬다)
     * {@code fixture} 가 null 이라 {@code NullPointerException} 으로 <b>아무것도 지우지
     * 못하고</b> 끝났다. 정리 코드는 돌았는데 정리가 안 된 것이다.
     *
     * <p>그래서 여기서는 표식이 아니라 <b>{@link #MARK_PREFIX} 로 대상을 찾는다</b>.
     * 메모리 상태에 의존하지 않으므로 언제 불려도 자기 몫을 지운다. {@code @AfterAll} 은
     * 한 번이라도 성공한 {@code @BeforeAll} 뒤에 반드시 돈다 — 즉 이 테스트가 곡을 하나라도
     * 남길 수 있는 모든 경로가 여기로 수렴한다.
     *
     * <h2>왜 JdbcTemplate 인가</h2>
     *
     * 두 가지가 JPA 로는 번거롭다. 첫째, 제목 <b>접두사</b>로 곡을 찾는 것(파생 쿼리를
     * 새로 만들면 `main` 을 건드려야 한다 — 이 티켓은 테스트 인프라만 고친다). 둘째,
     * 곡에 매달릴 수 있는 자식 12개를 한 번에 비우는 것. 둘 다 한 문장씩이면 끝난다.
     *
     * <p>JPA 를 쓰지 않으므로 1차 캐시와 어긋날 수 있다. 곡 번호를 <b>JPA 로 읽어</b>
     * 그 값으로 지우고, 앨범·아티스트·이미지는 <b>JPA 리포지토리로</b> 지워 캐시도 함께
     * 비운다. 어차피 다음은 JVM 종료다.
     *
     * <h2>지우는 순서</h2>
     *
     * {@code music_analysis} 를 먼저 지운다 — {@code music_analysis.music_id} 가
     * {@code music} 을 참조하므로 순서를 바꾸면 외래 키에 막힌다. 곡을 지운 뒤에야 앨범과
     * 아티스트를 지울 수 있다(같은 이유). 앨범·아티스트 번호는 <b>곡을 지우기 전에</b> 읽어 둔다.
     */
    @AfterAll
    void cleanUp() {
        List<Integer> ids = fixture == null
            ? staleTrackIds()
            : List.of(fixture.female(), fixture.male(), fixture.instrumental(), fixture.etc());

        /*
         * 지울 것이 없으면 여기서 끝낸다. seed() 가 청소를 마친 뒤 첫 곡을 만들기 전에
         * 죽은 경우가 여기로 온다(위의 purgeStaleFixtures() 가 이전 잔여까지 지웠으므로
         * staleTrackIds() 도 빈 목록이다). 아래로 내려가면 ids.get(0) 이
         * IndexOutOfBoundsException 을 던져 @AfterAll 이 실패로 끝난다 — DB 는 이미
         * 깨끗하니 누수는 아니지만, 실패 목록에 이 테스트가 올라가 다음 사람을 헷갈리게 한다.
         */
        if (ids.isEmpty()) {
            return;
        }

        int albumId = musicRepository.findById(ids.get(0))
            .map(music -> music.getAlbum().getAlbumId())
            .orElse(-1);
        int artistId = musicRepository.findById(ids.get(0))
            .map(music -> music.getArtist().getArtistId())
            .orElse(-1);

        analysisRepository.deleteAllById(ids);
        // music_analysis 밖의 자식은 JPA 가 모른다. 남아 있으면 곡 삭제가 외래 키에 막힌다.
        for (String child : MUSIC_CHILD_TABLES) {
            if (!"music_analysis".equals(child)) {
                jdbcTemplate.update(
                    "DELETE FROM " + child + " WHERE music_id IN (" + placeholders(ids.size()) + ")",
                    ids.toArray());
            }
        }
        musicRepository.deleteAllById(ids);
        if (albumId > 0) {
            albumRepository.deleteById(albumId);
        }
        if (artistId > 0) {
            artistRepository.deleteById(artistId);
        }
        if (imageFileId != null) {
            objectStorageFileRepository.deleteById(imageFileId);
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 하위 호환 — 필터가 없으면 예전과 완전히 같다

    @Test
    @DisplayName("필터가 없으면 예전과 완전히 같은 결과다")
    void withoutFilterMatchesLegacyCall() {
        // 예전 시그니처(필터 없음)와 새 시그니처에 빈 목록·null 을 준 셋이 같아야 한다.
        MusicPageResponse legacy = musicService.searchMusic(null, 0, 50);
        MusicPageResponse emptyList = musicService.searchMusic(null, List.of(), 0, 50);
        MusicPageResponse nullFilters = musicService.searchMusic(null, null, 0, 50);

        assertThat(emptyList.items()).extracting(item -> item.musicId())
            .containsExactlyElementsOf(legacy.items().stream().map(item -> item.musicId()).toList());
        assertThat(nullFilters.totalElements()).isEqualTo(legacy.totalElements());
        assertThat(emptyList.totalElements()).isEqualTo(legacy.totalElements());
    }

    @Test
    @DisplayName("분석 행이 없는 곡도 필터가 없으면 그대로 나온다")
    void withoutFilterKeepsUnanalyzedMusic() {
        // 개발 DB 의 대부분 곡에는 분석 행이 없다. INNER JOIN 이었다면 여기서
        // 픽스처 네 곡만 남고 카탈로그가 통째로 사라졌을 것이다.
        MusicPageResponse page = musicService.searchMusic(null, 0, 100);

        assertThat(page.items()).isNotEmpty();
        assertThat(page.totalElements()).isGreaterThan(100L);
        assertThat(page.totalElements())
            .isEqualTo(legacyTotal());
    }

    /** 아무 필터도 안 걸었을 때의 총계. 기준선으로만 쓴다. */
    private long legacyTotal() {
        return musicRepository.findPage(false, false, false, false,
            org.springframework.data.domain.PageRequest.of(0, 1)).getTotalElements();
    }

    // ─────────────────────────────────────────────────────────────
    // 세 값이 각각 의도한 곡만 남긴다 — 전체 목록 경로

    @Test
    @DisplayName("여성·남성·인스트루멘탈이 각자 자기 곡만 남긴다")
    void eachFilterKeepsOnlyItsOwnMusic() {
        assertThat(idsOf(VocalFilter.FEMALE)).containsExactly(fixture.female());
        assertThat(idsOf(VocalFilter.MALE)).containsExactly(fixture.male());
        assertThat(idsOf(VocalFilter.INSTRUMENTAL)).containsExactly(fixture.instrumental());
    }

    @Test
    @DisplayName("혼성 보컬은 혼성 필터로 선택한다")
    void etcIsNotSelectable() {
        assertThat(idsOf(VocalFilter.MIXED)).containsExactly(fixture.etc());
    }

    @Test
    @DisplayName("여러 개를 고르면 합집합이다")
    void multipleSelectionsAreUnion() {
        assertThat(idsOf(VocalFilter.FEMALE, VocalFilter.INSTRUMENTAL))
            .containsExactlyInAnyOrder(fixture.female(), fixture.instrumental());
        assertThat(idsOf(VocalFilter.FEMALE, VocalFilter.MALE))
            .containsExactlyInAnyOrder(fixture.female(), fixture.male());
    }

    // ─────────────────────────────────────────────────────────────
    // 검색어 + 필터 — 전문 인덱스 경로와 prefix 경로

    @Test
    @DisplayName("검색어와 필터를 같이 주면 둘 다 걸린다 (전문 인덱스 경로)")
    void keywordAndFilterTogether() {
        // 두 글자 이상 -> searchIdsFullText. 검색어가 픽스처 곡만 가리킨다.
        assertThat(idsOfSearch(VocalFilter.FEMALE)).containsExactly(fixture.female());
        assertThat(idsOfSearch(VocalFilter.MALE)).containsExactly(fixture.male());
        assertThat(idsOfSearch(VocalFilter.INSTRUMENTAL)).containsExactly(fixture.instrumental());
    }

    @Test
    @DisplayName("검색어만 주고 필터를 안 주면 픽스처 곡이 전부 나온다")
    void keywordWithoutFilterReturnsEverything() {
        assertThat(idsOf(musicService.searchMusic(MARK, List.of(), 0, 50)))
            .containsExactlyInAnyOrder(
                fixture.female(), fixture.male(), fixture.instrumental(), fixture.etc());
    }

    @Test
    @DisplayName("한 글자 검색어(prefix 경로)에서도 필터가 걸린다")
    void prefixPathAlsoFilters() {
        // 한 글자는 ngram 최소 토큰보다 짧아 searchIdsByPrefix 로 간다.
        String single = MARK.substring(0, 1);

        assertThat(idsOf(musicService.searchMusic(
            single, List.of(VocalFilter.INSTRUMENTAL), 0, 100)))
            .contains(fixture.instrumental())
            .doesNotContain(fixture.female(), fixture.male(), fixture.etc());
    }

    @Test
    @DisplayName("검색어와 필터를 같이 줘도 총계가 실제 결과 수와 맞는다")
    void countQueryStaysInSyncWithContent() {
        // countQuery 에 필터를 빠뜨리면 총계가 픽스처 4곡이 되어 어긋난다.
        // 페이지네이션은 이 값으로 페이지 수를 세므로 여기가 어긋나면 화면이 망가진다.
        MusicPageResponse page = musicService.searchMusic(MARK, List.of(VocalFilter.FEMALE), 0, 50);

        assertThat(page.totalElements()).isEqualTo(page.items().size());
        assertThat(page.totalElements()).isEqualTo(1L);
    }

    @Test
    @DisplayName("필터에 걸리는 곡이 하나도 없으면 빈 목록이고 총계도 0 이다")
    void noMatchIsEmptyNotEverything() {
        // 이 접두사로 시작하는 곡은 없다. 필터를 무시하면 전곡이 나왔을 것이다.
        String absent = "Zzz" + UUID.randomUUID().toString().substring(0, 8);

        MusicPageResponse page = musicService.searchMusic(absent, List.of(VocalFilter.FEMALE), 0, 50);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isZero();
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP 계약 — 파라미터 이름과 응답 모양

    @Test
    @DisplayName("와이어 파라미터 이름은 vocal 이고 snake_case 관례를 따른다")
    void wireParameterIsVocal() throws Exception {
        // 화면이 실제로 부르는 모양 그대로 확인한다. 이름을 바꾸면 여기서 깨진다.
        mockMvc.perform(get("/api/v1/music")
                .param("vocal", "female")
                .param("size", "100")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].music_id").value(fixture.female()));
    }

    @Test
    @DisplayName("쉼표로 이어 보낸 값과 반복 파라미터가 같은 뜻이다")
    void commaAndRepeatedFormsAgree() throws Exception {
        // 둘이 갈라지면 화면(쉼표)과 Bruno(반복)가 다른 결과를 본다.
        // 목록 경로는 music_id 내림차순이라 순서는 단언하지 않는다.
        mockMvc.perform(get("/api/v1/music")
                .param("vocal", "female,male")
                .param("size", "100")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[*].music_id",
                containsInAnyOrder(fixture.female(), fixture.male())));

        mockMvc.perform(get("/api/v1/music")
                .param("vocal", "female")
                .param("vocal", "male")
                .param("size", "100")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[*].music_id",
                containsInAnyOrder(fixture.female(), fixture.male())));
    }

    @Test
    @DisplayName("필터를 안 보내면 예전과 같은 응답이 나온다")
    void httpWithoutFilterIsBackwardCompatible() throws Exception {
        mockMvc.perform(get("/api/v1/music").param("size", "100").with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total_elements").value((int) legacyTotal()))
            .andExpect(jsonPath("$.data.items", hasSize(100)));
    }

    @Test
    @DisplayName("모르는 보컬 값은 조용히 무시되지 않고 400 이다")
    void unknownValueIsRejected() throws Exception {
        // 무시하면 '고르지 않음' 이 되어 사용자가 고른 것과 다른 결과를 보게 된다.
        mockMvc.perform(get("/api/v1/music")
                .param("vocal", "banana")
                .with(authentication(token())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_INVALID_REQUEST_BODY"));
    }

    @Test
    @DisplayName("빈 보컬 값은 '고르지 않음' 이다")
    void blankValueIsNoFilter() throws Exception {
        mockMvc.perform(get("/api/v1/music")
                .param("vocal", "")
                .param("size", "100")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total_elements").value((int) legacyTotal()));
    }

    // ─────────────────────────────────────────────────────────────
    // 도우미

    /** 전체 목록 경로(findPage)에서 걸러 낸 곡 번호. 최신 등록순이라 픽스처가 앞에 온다. */
    private List<Integer> idsOf(VocalFilter... filters) {
        return idsOf(musicService.searchMusic(null, List.of(filters), 0, 100));
    }

    /** 검색어 + 필터(전문 인덱스 경로)에서 걸러 낸 곡 번호. */
    private List<Integer> idsOfSearch(VocalFilter... filters) {
        return idsOf(musicService.searchMusic(MARK, List.of(filters), 0, 100));
    }

    private static List<Integer> idsOf(MusicPageResponse page) {
        return page.items().stream().map(item -> item.musicId()).toList();
    }

    /** 로그인한 사용자. 이 API 는 인증이 필요하다(`SecurityConfig` 의 anyRequest). */
    private static UsernamePasswordAuthenticationToken token() {
        return new UsernamePasswordAuthenticationToken("probe@mupid.test", null, List.of());
    }

    private int persist(Album album, Artist artist, String title) {
        Music music = Music.builder()
            .album(album)
            .artist(artist)
            .title(title)
            .releaseDate(LocalDateTime.now())
            .build();
        // save() 가 곧 커밋은 아니다. 아래 flush 로 INSERT 를 내보낸다.
        musicRepository.saveAndFlush(music);
        return music.getMusicId();
    }

}
