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
 * <p>축별로 <b>실제 순서가 의도대로 나오는가</b>. 파라미터가 없으면 <b>기본 정렬
 * (조회순)이 걸리는가</b> — 여기가 S15P21A204-396 후속에서 뒤집힌 부분이다. 검색어와
 * 함께 온 정렬은 <b>조용히 무시되지 않고 400</b> 인가. 모르는 값과 빈 값이 갈리는가.
 *
 * <h2>이 파일이 뒤집힌 자리 — 기본 정렬이 '없음' 에서 '조회순' 으로</h2>
 *
 * <p>396 본편에서 이 테스트는 {@code withoutSortDoesNotSort()} 로 <b>"파라미터가 없으면
 * 정렬 절이 붙지 않는다"</b> 를 못 박고 있었다. 그게 하위 호환의 전부였기 때문이다.
 * 후속에서 사용자가 기본 정렬을 '조회순' 으로 정했으므로 그 단언은 <b>거짓이 됐고</b>,
 * 지금은 반대로 <b>"파라미터가 없으면 조회순이 걸린다"</b> 를 못 박는다.
 *
 * <p>대신 <b>예전 순서로 돌아가는 이름</b>이 생겼다 — 빈 값({@code sort=})은 '고르지
 * 않음' 이라 정렬 절을 붙이지 않는다. 그쪽 단언({@link #noSortRequestKeepsRegistrationOrder()})
 * 이 "등록순 오름차순" 을 그대로 지킨다. 이 둘이 함께 있어야 기본값이 실제로 바뀌었고
 * 탈출구도 살아 있다는 것이 한 번에 드러난다.
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
 * 만들고, 단언은 <b>픽스처 네 곡의 상대 순서</b>로만 한다 — '카탈로그의 첫 곡이 무엇'
 * 같은 사실에 기대면 어느 날 조용히 깨진다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExploreSortTest {

    /** 다른 실행과 섞이지 않게 하는 고유 표식. */
    private static final String MARK = "Sortprobe" + UUID.randomUUID().toString().substring(0, 8);

    /**
     * {@link Fixture#plays} 에 넣는 재생수.
     *
     * <p>카탈로그 어디에도 이보다 큰 값이 없도록 크게 잡는다. 배치가 실제로 돌아
     * {@code play_count} 가 채워진 개발 DB 에서도 이 픽스처가 조회순 1위라는 사실이
     * 흔들리지 않아야 한다 — 그래야 "기본 정렬이 조회순이다" 를 카탈로그 사정에 기대지
     * 않고 단언할 수 있다.
     */
    private static final int HUGE_PLAY_COUNT = 999_999;

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
     * 픽스처 네 곡. 정렬 키가 서로 확실히 갈리도록 값을 정한다.
     *
     * <p>곡 번호는 <b>만든 순서</b>대로 올라간다 — 두 번째 키(tie-break)의 방향을
     * 확인하려면 그 순서가 곧 기대 순서가 되므로, 아래 {@code seed} 에서 만드는 순서가
     * {@code titleFirst → likes → plays → recent} 로 고정돼 있다.
     *
     * <ul>
     *   <li>{@code recent} — {@code created_at} 이 가장 최근 · 곡 번호가 가장 큼</li>
     *   <li>{@code likes} — 좋아요가 가장 많다</li>
     *   <li>{@code titleFirst} — 제목이 사전순으로 가장 앞선다 · 곡 번호가 가장 작음</li>
     *   <li>{@code plays} — 재생수가 가장 많다(조회순 · 기본 정렬 1위)</li>
     * </ul>
     */
    private record Fixture(int recent, int likes, int titleFirst, int plays) {}

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

        /*
         * 만드는 순서가 곧 곡 번호 순서다(tie-break 기대값이 여기서 나온다).
         * 제목도 표식 뒤 첫 글자를 A/M/N/Z 로 벌려 사전순을 확정한다.
         */
        int titleFirst = persist(album, artist, MARK + " A 첫 번째 제목", 0, 0);
        int likes = persist(album, artist, MARK + " M 좋아요 많은 곡", 5, 0);
        int plays = persist(album, artist, MARK + " N 조회수 많은 곡", 0, HUGE_PLAY_COUNT);
        int recent = persist(album, artist, MARK + " Z 가장 최근 곡", 0, 0);

        backdate(titleFirst, 300);
        backdate(likes, 200);
        backdate(plays, 150);
        backdate(recent, 100);

        imageFileId = image.getFileId();
        fixture = new Fixture(recent, likes, titleFirst, plays);
    }

    /**
     * 등록 시각을 과거로 밀어 넷을 확실히 벌린다.
     *
     * <p><b>엔티티 setter 로는 안 된다.</b> {@code created_at} 은 감사 필드라
     * {@code @Column(updatable = false)} 이고, 그래서 Hibernate 가 UPDATE 문에서 이
     * 컬럼을 아예 빼 버린다 — 값을 넣어도 조용히 무시된다. 정렬 검증에는 서로 다른
     * 시각이 필요하므로 SQL 로 직접 고친다.
     */
    private void backdate(int musicId, int daysAgo) {
        mysql.update(
            "UPDATE music SET created_at = ? WHERE music_id = ?",
            java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(daysAgo)),
            musicId);
    }

    @AfterAll
    void cleanUp() {
        List<Integer> ids = List.of(
            fixture.recent(), fixture.likes(), fixture.titleFirst(), fixture.plays());

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
            fixture.recent(), fixture.plays(), fixture.likes(), fixture.titleFirst());
    }

    @Test
    @DisplayName("좋아요순은 좋아요가 많은 곡을 앞에 둔다")
    void likesPutsMostLikedFirst() {
        // 개발 DB 는 like_count 가 전부 0 이지만, 이 픽스처가 값을 넣어 축이 살아 있음을
        // 증명한다. 축 자체의 근거는 increaseLikeCount 가 그 값을 올린다는 사실이다.
        //
        // 나머지 셋은 like_count 가 0 으로 동점이라 <b>두 번째 키(곡 번호)</b>가 순서를
        // 정하고, 그 방향은 첫 키와 같은 <b>DESC</b> 다. 그래서 동점 구간은 곡 번호
        // 내림차순(최신 → 조회수 → 제목첫번째)으로 나온다 — 실호출로 확인한 순서다.
        assertThat(relativeOrderOf(idsSortedBy(MusicSort.LIKES))).containsExactly(
            fixture.likes(), fixture.recent(), fixture.plays(), fixture.titleFirst());

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
            fixture.titleFirst(), fixture.likes(), fixture.plays(), fixture.recent());
    }

    @Test
    @DisplayName("조회순은 재생수가 많은 곡을 앞에 둔다")
    void playsPutsMostPlayedFirst() {
        // 이 축이 S15P21A204-396 후속에서 새로 열렸다. 값은 픽스처가 넣은 것이고,
        // 실제 카탈로그의 값을 채우는 것은 PlayCountBatch 다(아래 PlayCountBatchTest).
        List<Integer> ids = idsSortedBy(MusicSort.PLAYS);

        assertThat(ids.get(0)).isEqualTo(fixture.plays());
        // 나머지 셋은 재생수가 0 으로 동점이라 두 번째 키(곡 번호)가 DESC 로 순서를 정한다.
        // 좋아요순과 같은 규칙이고, 실호출로 확인한 순서다.
        assertThat(relativeOrderOf(ids)).containsExactly(
            fixture.plays(), fixture.recent(), fixture.likes(), fixture.titleFirst());
    }

    @Test
    @DisplayName("정렬 키가 동점이어도 순서가 확정된다 (두 번째 키)")
    void tieBreakKeepsOrderStable() {
        // 좋아요순에서 나머지 셋은 like_count 가 0 으로 동점이다. 조회순에서는 넷 중 셋이
        // 동점이다 — 두 번째 키(musicId)가 없으면 페이지마다 순서가 달라질 수 있다.
        assertThat(idsSortedBy(MusicSort.LIKES)).isEqualTo(idsSortedBy(MusicSort.LIKES));
        assertThat(idsSortedBy(MusicSort.PLAYS)).isEqualTo(idsSortedBy(MusicSort.PLAYS));
    }

    // ─────────────────────────────────────────────────────────────
    // 기본 정렬 — 파라미터가 없으면 '조회순' 이다 (S15P21A204-396 후속)

    @Test
    @DisplayName("정렬 파라미터가 없으면 기본 정렬(조회순)이 걸린다")
    void defaultSortIsPlays() {
        List<Integer> byDefault = idsDefault();

        /*
         * 기대 순서를 <b>MySQL 에서 직접</b> 만든다.
         *
         * 두 서비스 호출을 서로 비교하지 않는 이유: 그 비교는 1000여 곡을 페이지로 나눠
         * 훑는 순회를 양쪽에서 한 번씩 하므로, 어긋났을 때 "정렬이 틀렸다" 와 "순회가
         * 틀렸다" 가 구분되지 않는다. DB 에 같은 ORDER BY 를 걸어 오라클로 쓰면 검증하는
         * 것이 '조회순 규칙' 하나로 좁혀진다.
         */
        assertThat(byDefault).isEqualTo(idsByPlayCountDesc());

        // 재생수가 가장 많은 곡이 맨 앞이다. 픽스처가 카탈로그 최대값보다 큰 값을 넣어
        // 두었으므로, 배치가 실제로 돈 개발 DB 에서도 이 사실이 흔들리지 않는다.
        assertThat(byDefault.get(0)).isEqualTo(fixture.plays());

        // 그리고 예전 기본값(정렬 없음)과는 <b>달라야</b> 한다. 같다면 기본값이 바뀌지
        // 않은 것이고, 이 티켓의 변경이 조용히 사라진 것이다.
        assertThat(relativeOrderOf(byDefault))
            .isNotEqualTo(relativeOrderOf(idsNoSortRequest()));
    }

    @Test
    @DisplayName("정렬을 모르는 옛 시그니처(null)도 기본 정렬을 탄다")
    void legacySignatureFollowsDefault() {
        // `sortRequest` 를 안 받는 예전 시그니처는 null 을 넘겨 ABSENT 를 탄다.
        // 후속에서 ABSENT 의 뜻이 바뀌었으므로 <b>그 호출부도 함께 움직인다</b> —
        // 이것이 "기존 클라이언트의 순서가 바뀐다" 는 사실의 코드 쪽 증거다.
        List<Integer> legacy = musicService
            .searchMusic(null, null, List.of(), List.of(), 0, 100)
            .items().stream().map(item -> item.musicId()).toList();

        assertThat(legacy).isEqualTo(idsDefault().subList(0, legacy.size()));
    }

    @Test
    @DisplayName("빈 값(sort=)은 '고르지 않음' 이라 등록순으로 나온다")
    void noSortRequestKeepsRegistrationOrder() {
        // 기본값을 '조회순' 으로 바꾸면서, 예전 순서를 그대로 받을 이름이 필요해졌다.
        // 정렬 절이 아예 붙지 않는다 — 즉 <b>예전 쿼리 그대로</b>다.
        //
        // 그때의 순서는 이 프로젝트의 개발 DB 에서 실측하면 곡 번호 <b>오름차순</b>이다
        // (396 본편의 ExploreSortTest 가 'recent → likes → titleFirst' 로 적어 둔 것과
        // 같은 사실). 그 순서를 계약으로 못 박지는 않되, <b>조회순과 확실히 다르다</b> 는
        // 것은 여기서 지킨다 — 기본값이 실제로 바뀌었다는 증거가 그 차이다.
        List<Integer> noSort = idsNoSortRequest();

        // 픽스처 넷의 <b>상대 순서</b>만 본다 — '카탈로그의 첫 곡이 무엇' 같은 사실에
        // 기대면 안 된다(정렬이 없으니 첫 곡은 카탈로그 사정이 정한다).
        assertThat(relativeOrderOf(noSort)).containsExactly(
            fixture.titleFirst(), fixture.likes(), fixture.plays(), fixture.recent());

        // 조회순이면 재생수 1위가 앞이므로, 이 둘이 같은 순서면 기본값이 안 바뀐 것이다.
        assertThat(relativeOrderOf(noSort))
            .isNotEqualTo(relativeOrderOf(idsSortedBy(MusicSort.PLAYS)));
    }

    @Test
    @DisplayName("조회순과 등록순은 실제로 다른 순서다 — 기본값이 바뀐 증거")
    void playsDiffersFromRegistrationOrder() {
        assertThat(relativeOrderOf(idsDefault()))
            .isNotEqualTo(relativeOrderOf(idsNoSortRequest()));
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
            .andExpect(jsonPath("$.data.total_elements").value(4));
    }

    @Test
    @DisplayName("검색어 없이 온 정렬은 검색어 경로로 새지 않는다 (기본 정렬도 마찬가지)")
    void defaultSortWithKeywordIsAccepted() throws Exception {
        // 기본값이 생기면서 '정렬이 걸리는 호출' 이 흔해졌다. 그 기본값 때문에
        // 평범한 검색이 400 이 되면 안 된다 — explicitlyRequested 가 그 판정을 지킨다.
        mockMvc.perform(get("/api/v1/music")
                .param("q", MARK)
                .param("size", "100")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total_elements").value(4));
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
    @DisplayName("sort 를 안 보내면 조회순으로 나온다 (새 기본값)")
    void httpWithoutSortUsesDefault() throws Exception {
        mockMvc.perform(get("/api/v1/music")
                .param("size", "1")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].music_id").value(fixture.plays()));
    }

    @Test
    @DisplayName("모르는 정렬 값은 조용히 무시되지 않고 400 이다")
    void unknownSortIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/music")
                .param("sort", "playz")
                .with(authentication(token())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("MUPID_INVALID_REQUEST_BODY"));
    }

    @Test
    @DisplayName("빈 정렬 값은 '고르지 않음' 이다 — 등록순으로 나온다")
    void blankSortIsNoSort() throws Exception {
        mockMvc.perform(get("/api/v1/music")
                .param("sort", "")
                .param("size", "1")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].music_id").value(idsNoSortRequest().get(0)));
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
    @DisplayName("축 이름 넷이 모두 응답을 만든다")
    void allAxesWork() throws Exception {
        for (String sort : List.of("recent", "title", "likes", "plays")) {
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

    /** 정렬 파라미터가 <b>없는</b> 조회 — 기본 정렬(조회순)을 탄다. */
    private List<Integer> idsDefault() {
        return idsOf(MusicSortRequest.ABSENT);
    }

    /** 빈 값({@code sort=})을 준 조회 — 정렬 절이 붙지 않아 등록순 오름차순이다. */
    private List<Integer> idsNoSortRequest() {
        return idsOf(MusicSortRequest.EXPLICIT_NONE);
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

    /**
     * 조회수 내림차순 곡 번호를 MySQL 에서 직접 만든다. 기본 정렬의 오라클이다.
     *
     * <p>서비스와 같은 규칙({@code play_count DESC, music_id DESC})을 같은 DB 에 걸어
     * 순서를 받는다. 삭제된 곡은 서비스와 같이 뺀다({@code MusicRepository.findPageFiltered}
     * 의 {@code isDeleted = false}).
     */
    private List<Integer> idsByPlayCountDesc() {
        return mysql.queryForList(
            "SELECT music_id FROM music WHERE is_deleted = false "
                + "ORDER BY play_count DESC, music_id DESC",
            Integer.class);
    }

    /** 한 페이지만 받는다. '앞 n 개' 만 보면 되는 단언에 쓴다. */
    private List<Integer> idsPageOf(MusicSortRequest sort, int page, int size) {
        return musicService.searchMusic(null, null, List.of(), List.of(), sort, page, size)
            .items().stream().map(item -> item.musicId()).toList();
    }

    /** 카탈로그 전체에서 픽스처 네 곡만 상대 순서로 추린다. */
    private List<Integer> relativeOrderOf(List<Integer> ids) {
        List<Integer> probes = List.of(
            fixture.recent(), fixture.likes(), fixture.titleFirst(), fixture.plays());
        return ids.stream().filter(probes::contains).toList();
    }

    private String titleOf(int musicId) {
        return musicRepository.findById(musicId).map(Music::getTitle).orElse("");
    }

    /** 로그인한 사용자. 이 API 는 인증이 필요하다(SecurityConfig 의 anyRequest). */
    private static UsernamePasswordAuthenticationToken token() {
        return new UsernamePasswordAuthenticationToken("probe@mupid.test", null, List.of());
    }

    private int persist(Album album, Artist artist, String title, int likeCount, int playCount) {
        Music music = Music.builder()
            .album(album)
            .artist(artist)
            .title(title)
            .releaseDate(LocalDateTime.now())
            .likeCount(likeCount)
            .playCount(playCount)
            .build();
        musicRepository.saveAndFlush(music);
        return music.getMusicId();
    }

}
