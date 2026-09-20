package com.osundosun.mupid.api.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

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

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.enumerate.MusicSort;
import com.osundosun.mupid.api.music.repository.AlbumRepository;
import com.osundosun.mupid.api.music.repository.ArtistRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.request.MusicSortRequest;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.repository.ObjectStorageFileRepository;

/**
 * 탐색 결과 정렬(S15P21A204-396) 검증.
 *
 * <h2>이 테스트가 지키는 것</h2>
 *
 * 축별로 <b>실제 순서가 의도대로 나오는가</b>. 파라미터가 없으면 <b>정렬이 걸리지
 * 않는가</b> — 하위 호환의 핵심이다. 검색어와 함께 온 정렬은 <b>조용히 무시되지 않고
 * 400</b> 인가. 모르는 값과 빈 값이 갈리는가.
 *
 * <h2>왜 '기본 정렬 없음' 을 굳이 단언하는가</h2>
 *
 * {@link MusicSort#RECENT} 는 {@code created_at DESC} 라, 현행 무정렬
 * (등록순 오름차순)과 <b>방향이 반대</b>다. 누군가 "기본값은 최신순" 이라는 이유로
 * 파라미터가 없을 때 {@code RECENT} 를 끼워 넣으면 <b>기존 클라이언트의 목록이 통째로
 * 뒤집힌다.</b> {@link #withoutSortDoesNotSort()} 와
 * {@link #explicitRecentDiffersFromDefault()} 가 그 실수를 막는다.
 *
 * <h2>왜 픽스처를 커밋하는가</h2>
 *
 * {@code VocalFilterSearchTest} 와 같은 이유다 — 전문 인덱스가 같은 트랜잭션의 행을
 * 보지 못해서 롤백 대역에서는 검색어 경로를 검증할 수 없다. 그래서 커밋하고
 * {@link #cleanUp()} 이 지운다.
 *
 * <h2>왜 값을 직접 만드는가</h2>
 *
 * 개발 DB 카탈로그는 다른 세션이 바꿀 수 있다. 픽스처끼리 정렬 키가 확실히 갈리도록
 * 만들고, 단언은 <b>픽스처 세 곡의 상대 순서</b>로만 한다 — '카탈로그의 첫 곡이 무엇'
 * 같은 사실에 기대면 어느 날 조용히 깨진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExploreSortTest {

    /** 다른 실행과 섞이지 않게 하는 고유 표식. */
    private static final String MARK = "Sortprobe" + UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    private MusicService musicService;

    @Autowired
    private MusicRepository musicRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ObjectStorageFileRepository objectStorageFileRepository;

    @Autowired
    private MockMvc mockMvc;

    /**
     * 픽스처의 {@code created_at} 을 고치는 데만 쓴다.
     *
     * <p>MySQL 쪽은 {@code @Primary} 라 그냥 주입된다(ClickHouse 쪽은
     * {@code @Qualifier("clickHouseJdbcTemplate")} 을 요구한다).
     */
    @Autowired
    private JdbcTemplate mysql;

    private Fixture fixture;
    private Integer imageFileId;

    /**
     * 픽스처 세 곡. 정렬 키가 서로 확실히 갈리도록 값을 정한다.
     *
     * <ul>
     *   <li>{@code recent} — {@code created_at} 이 가장 최근</li>
     *   <li>{@code likes} — 좋아요가 가장 많다</li>
     *   <li>{@code titleFirst} — 제목이 사전순으로 가장 앞선다</li>
     * </ul>
     */
    private record Fixture(int recent, int likes, int titleFirst) {}

    @BeforeAll
    void seed() {
        /*
         * 아티스트·앨범은 이미지가 필수다(DB 의 image_file_id 가 NOT NULL).
         * 곡은 선택이라 붙이지 않는다 — 이 테스트가 보는 것은 정렬이다.
         */
        ObjectStorageFile image = objectStorageFileRepository.saveAndFlush(
            ObjectStorageFile.builder()
                .fileType(FileType.IMG)
                .category(FileCategory.ALBUM)
                .originalFileName("sort-probe.jpg")
                .storageObjectKey("test/%s.jpg".formatted(UUID.randomUUID()))
                .fileSizeBytes(1024)
                .mime("image/jpeg")
                .build());

        Artist artist = artistRepository.save(Artist.builder()
            .name("정렬검증 아티스트 " + MARK)
            .imageFile(image)
            .build());
        Album album = albumRepository.save(Album.builder()
            .artist(artist)
            .title("정렬검증 앨범 " + MARK)
            .imageFile(image)
            .releaseDate(LocalDateTime.now())
            .build());

        // 제목: 표식 뒤 첫 글자를 A/M/Z 로 벌려 사전순을 확정한다.
        int titleFirst = persist(album, artist, MARK + " A 첫 번째 제목", 0);
        int likes = persist(album, artist, MARK + " M 좋아요 많은 곡", 5);
        int recent = persist(album, artist, MARK + " Z 가장 최근 곡", 0);

        backdate(titleFirst, 300);
        backdate(likes, 200);
        backdate(recent, 100);

        imageFileId = image.getFileId();
        fixture = new Fixture(recent, likes, titleFirst);
    }

    /**
     * 등록 시각을 과거로 밀어 셋을 확실히 벌린다.
     *
     * <p><b>엔티티 setter 로는 안 된다.</b> {@code created_at} 은 감사 필드라
     * {@code @Column(updatable = false)} 이고, 그래서 Hibernate 가 UPDATE 문에서 이
     * 컬럼을 아예 빼 버린다 — 값을 넣어도 조용히 무시된다. 정렬 검증에는 서로 다른
     * 시각이 필요하므로 SQL 로 직접 고친다.
     *
     * <p>카탈로그의 곡들은 최근에 등록됐으므로, 픽스처를 멀찍이 과거로 보내면
     * '최신순' 목록에서 픽스처가 서로 붙어 나온다.
     */
    private void backdate(int musicId, int daysAgo) {
        mysql.update(
            "UPDATE music SET created_at = ? WHERE music_id = ?",
            java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(daysAgo)),
            musicId);
    }

    @AfterAll
    void cleanUp() {
        List<Integer> ids =
            List.of(fixture.recent(), fixture.likes(), fixture.titleFirst());

        // 앨범·아티스트 번호는 곡을 지우기 전에 읽어 둔다(외래 키).
        int albumId = musicRepository.findById(fixture.recent())
            .map(music -> music.getAlbum().getAlbumId()).orElse(-1);
        int artistId = musicRepository.findById(fixture.recent())
            .map(music -> music.getArtist().getArtistId()).orElse(-1);

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
    // 축별 순서

    @Test
    @DisplayName("최신순은 등록이 늦은 곡을 앞에 둔다")
    void recentPutsNewestFirst() {
        assertThat(relativeOrderOf(idsSortedBy(MusicSort.RECENT))).containsExactly(
            fixture.recent(), fixture.likes(), fixture.titleFirst());
    }

    @Test
    @DisplayName("좋아요순은 좋아요가 많은 곡을 앞에 둔다")
    void likesPutsMostLikedFirst() {
        // 개발 DB 는 like_count 가 전부 0 이지만, 이 픽스처가 값을 넣어 축이 살아 있음을
        // 증명한다. 축 자체의 근거는 increaseLikeCount 가 그 값을 올린다는 사실이다.
        assertThat(relativeOrderOf(idsSortedBy(MusicSort.LIKES))).containsExactly(
            fixture.likes(), fixture.recent(), fixture.titleFirst());

        assertThat(idsSortedBy(MusicSort.LIKES).get(0)).isEqualTo(fixture.likes());
    }

    @Test
    @DisplayName("제목순은 사전순으로 앞선 제목을 먼저 둔다")
    void titlePutsAlphabeticallyFirst() {
        // 표식으로 시작하는 곡만 추린다 — 다른 곡의 제목 순서에 기대지 않기 위함이다.
        List<Integer> probes = idsSortedBy(MusicSort.TITLE).stream()
            .filter(id -> titleOf(id).startsWith(MARK))
            .toList();

        assertThat(probes).containsExactly(
            fixture.titleFirst(), fixture.likes(), fixture.recent());
    }

    @Test
    @DisplayName("정렬 키가 동점이어도 순서가 확정된다 (두 번째 키)")
    void tieBreakKeepsOrderStable() {
        // 좋아요순에서 나머지 둘은 like_count 가 0 으로 동점이다. 두 번째 키(musicId)가
        // 없으면 페이지마다 순서가 달라질 수 있다.
        assertThat(idsSortedBy(MusicSort.LIKES)).isEqualTo(idsSortedBy(MusicSort.LIKES));
    }

    // ─────────────────────────────────────────────────────────────
    // 하위 호환 — 파라미터가 없으면 정렬하지 않는다

    @Test
    @DisplayName("정렬 파라미터가 없으면 현행과 완전히 같은 순서다")
    void withoutSortDoesNotSort() {
        List<Integer> unsorted = idsUnsorted();

        // 정렬을 모르는 옛 시그니처(파라미터 4개)와 <b>앞 100곡이 같아야</b> 한다.
        // 전곡을 비교하지 않는 이유는 `size` 상한(100) 때문이다 — 여기서 보려는 것은
        // "정렬 파라미터가 없다는 사실이 쿼리를 바꾸지 않는다" 이고, 그건 첫 페이지
        // 비교로 충분히 드러난다.
        List<Integer> legacy = musicService.searchMusic(null, null, List.of(), List.of(), null, 0, 100)
            .items().stream().map(item -> item.musicId()).toList();

        assertThat(unsorted.subList(0, legacy.size())).isEqualTo(legacy);

        // 픽스처의 상대 순서는 곡 번호 오름차순(= 등록순)이다.
        assertThat(relativeOrderOf(unsorted)).containsExactly(
            fixture.titleFirst(), fixture.likes(), fixture.recent());
    }

    @Test
    @DisplayName("최신순을 명시하면 기본 순서와 달라진다 — 기본값을 바꾸지 않았다는 증거")
    void explicitRecentDiffersFromDefault() {
        // RECENT 는 created_at DESC 라 등록순 오름차순과 방향이 반대다.
        // 이것이 '기본값은 현행 유지' 라는 결정의 근거다.
        assertThat(relativeOrderOf(idsSortedBy(MusicSort.RECENT)))
            .isNotEqualTo(relativeOrderOf(idsUnsorted()));
    }

    // ─────────────────────────────────────────────────────────────
    // 검색어 경로

    @Test
    @DisplayName("검색어와 함께 온 정렬은 조용히 무시되지 않고 400 이다")
    void sortWithKeywordIsRejected() throws Exception {
        // 관련도순이 결과 자체를 만드는 규칙이라 정렬로 덮을 수 없다. 무시하면
        // 사용자는 '최신순' 을 골랐는데 관련도순을 보게 된다.
        mockMvc.perform(get("/api/v1/music")
                .param("q", MARK)
                .param("sort", "recent")
                .with(authentication(token())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_INVALID_REQUEST_BODY"));
    }

    @Test
    @DisplayName("검색어 경로에서도 sort 가 비면 관련도순 그대로 돈다")
    void blankSortWithKeywordIsAccepted() throws Exception {
        mockMvc.perform(get("/api/v1/music")
                .param("q", MARK)
                .param("sort", "")
                .param("size", "100")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total_elements").value(3));
    }

    @Test
    @DisplayName("서비스도 같은 규칙으로 400 을 낸다")
    void serviceRejectsSortWithKeyword() {
        assertThatThrownBy(() -> musicService.searchMusic(
            MARK, null, List.of(), List.of(), MusicSortRequest.of(MusicSort.RECENT), 0, 10))
            .isInstanceOf(BusinessException.class);
    }

    // ─────────────────────────────────────────────────────────────
    // HTTP 계약 — 파라미터 이름과 오류

    @Test
    @DisplayName("와이어 파라미터 이름은 sort 이고 값을 주면 순서가 바뀐다")
    void wireParameterIsSort() throws Exception {
        mockMvc.perform(get("/api/v1/music")
                .param("sort", "likes")
                .param("page", "0")
                .param("size", "1")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].music_id").value(fixture.likes()));
    }

    @Test
    @DisplayName("sort 를 안 보내면 정렬이 걸리지 않는다 (하위 호환)")
    void httpWithoutSortIsBackwardCompatible() throws Exception {
        mockMvc.perform(get("/api/v1/music")
                .param("size", "1")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].music_id").value(idsUnsorted().get(0)));
    }

    @Test
    @DisplayName("모르는 정렬 값은 조용히 무시되지 않고 400 이다")
    void unknownSortIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/music")
                .param("sort", "plays")
                .with(authentication(token())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_INVALID_REQUEST_BODY"));
    }

    @Test
    @DisplayName("빈 정렬 값은 '고르지 않음' 이다")
    void blankSortIsNoSort() throws Exception {
        mockMvc.perform(get("/api/v1/music")
                .param("sort", "")
                .param("size", "1")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].music_id").value(idsUnsorted().get(0)));
    }

    @Test
    @DisplayName("대소문자를 가리지 않는다")
    void caseInsensitiveOnWire() throws Exception {
        mockMvc.perform(get("/api/v1/music")
                .param("sort", "LIKES")
                .param("page", "0")
                .param("size", "1")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].music_id").value(fixture.likes()));
    }

    @Test
    @DisplayName("축 이름 셋이 모두 응답을 만든다")
    void allAxesWork() throws Exception {
        for (String sort : List.of("recent", "title", "likes")) {
            mockMvc.perform(get("/api/v1/music")
                    .param("sort", sort)
                    .param("size", "10")
                    .with(authentication(token())))
                .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("정렬과 보컬 필터를 같이 줘도 둘 다 걸린다")
    void sortAndVocalFilterCombine() throws Exception {
        // 필터와 정렬은 서로 다른 축이라 함께 동작해야 한다. 결과가 비어도(분석 행이
        // 없어도) 200 인지만 본다 — 500 이면 조합이 깨진 것이다.
        mockMvc.perform(get("/api/v1/music")
                .param("sort", "title")
                .param("vocal", "instrumental")
                .param("size", "10")
                .with(authentication(token())))
            .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────
    // 도우미

    /** 정렬을 명시한 조회. */
    private List<Integer> idsSortedBy(MusicSort sort) {
        return idsOf(MusicSortRequest.of(sort));
    }

    /** 정렬 파라미터가 없는 조회 — 파라미터를 아예 안 보낸 것과 같다. */
    private List<Integer> idsUnsorted() {
        return idsOf(MusicSortRequest.ABSENT);
    }

    private List<Integer> idsOf(MusicSortRequest sort) {
        /*
         * 한 페이지로는 부족하다 — `size` 가 100 으로 잘리고 카탈로그는 1000 곡이 넘는다.
         * 픽스처를 과거로 밀어 두었으므로 '최신순' 에서는 뒤쪽에 나온다. 끝까지 넘겨야
         * 상대 순서를 볼 수 있다.
         */
        List<Integer> ids = new java.util.ArrayList<>();
        int page = 0;
        while (true) {
            MusicPageResponse found = musicService.searchMusic(
                null, null, List.of(), List.of(), sort, page, 100);
            found.items().forEach(item -> ids.add(item.musicId()));
            if (found.items().isEmpty() || page + 1 >= found.totalPages()) {
                return ids;
            }
            page++;
        }
    }

    /** 카탈로그 전체에서 픽스처 세 곡만 상대 순서로 추린다. */
    private List<Integer> relativeOrderOf(List<Integer> ids) {
        List<Integer> probes =
            List.of(fixture.recent(), fixture.likes(), fixture.titleFirst());
        return ids.stream().filter(probes::contains).toList();
    }

    private String titleOf(int musicId) {
        return musicRepository.findById(musicId).map(Music::getTitle).orElse("");
    }

    /** 로그인한 사용자. 이 API 는 인증이 필요하다(SecurityConfig 의 anyRequest). */
    private static UsernamePasswordAuthenticationToken token() {
        return new UsernamePasswordAuthenticationToken("probe@mupid.test", null, List.of());
    }

    private int persist(Album album, Artist artist, String title, int likeCount) {
        Music music = Music.builder()
            .album(album)
            .artist(artist)
            .title(title)
            .releaseDate(LocalDateTime.now())
            .likeCount(likeCount)
            .build();
        musicRepository.saveAndFlush(music);
        return music.getMusicId();
    }

}
