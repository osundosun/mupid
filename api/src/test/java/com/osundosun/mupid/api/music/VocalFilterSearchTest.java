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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
 * `music` 을 참조하는 외래 키가 있어 순서를 바꾸면 삭제가 막힌다. 표식은 클래스마다
 * 새로 만드는 {@link #MARK} 라, 이 테스트가 비정상 종료해 남긴 행이 있어도 다음
 * 실행은 자기 것만 본다.
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

    /**
     * 다른 실행·다른 테스트의 곡과 섞이지 않게 하는 고유 표식.
     *
     * <b>한 글자 이상, 두 글자 이상이어야 한다</b> — 앞은 prefix 경로, 뒤는 FULLTEXT
     * 경로를 타야 하기 때문이다. ngram 기본 토큰 크기가 2 라 한 글자로는 전문 인덱스가
     * 걸리지 않는다.
     */
    private static final String MARK = "Vocalprobe" + UUID.randomUUID().toString().substring(0, 8);

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

    @Autowired
    private MockMvc mockMvc;

    private Fixture fixture;

    /** 검증에 쓸 곡 네 개의 번호. 커밋됐으므로 클래스 전체가 같은 값을 본다. */
    private record Fixture(int female, int male, int instrumental, int etc) {}

    @BeforeAll
    void seed() {
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
            .name("보컬검증 아티스트 " + MARK)
            .imageFile(image)
            .build());
        Album album = albumRepository.save(Album.builder()
            .artist(artist)
            .title("보컬검증 앨범 " + MARK)
            .imageFile(image)
            .releaseDate(LocalDateTime.now())
            .build());

        int female = persist(album, artist, MARK + " 여성 보컬");
        int male = persist(album, artist, MARK + " 남성 보컬");
        int instrumental = persist(album, artist, MARK + " 인스트루멘탈");
        int etc = persist(album, artist, MARK + " 성별미상 보컬");

        analyze(female, true, VocalGender.F);
        analyze(male, true, VocalGender.M);
        analyze(instrumental, false, null);
        analyze(etc, true, VocalGender.MIX);

        imageFileId = image.getFileId();
        fixture = new Fixture(female, male, instrumental, etc);
    }

    /** seed() 가 만든 표지 파일 번호. 정리할 때 지운다. */
    private Integer imageFileId;

    /**
     * 커밋한 픽스처를 지운다.
     *
     * 분석 행을 먼저 지운다 — {@code music_analysis.music_id} 가 {@code music} 을
     * 참조하므로 순서를 바꾸면 외래 키에 막힌다. 곡을 지운 뒤에야 앨범과 아티스트를
     * 지울 수 있다(같은 이유). 앨범·아티스트 번호는 <b>곡을 지우기 전에</b> 읽어 둔다.
     */
    @AfterAll
    void cleanUp() {
        List<Integer> ids = List.of(
            fixture.female(), fixture.male(), fixture.instrumental(), fixture.etc());

        int albumId = musicRepository.findById(fixture.female())
            .map(music -> music.getAlbum().getAlbumId())
            .orElse(-1);
        int artistId = musicRepository.findById(fixture.female())
            .map(music -> music.getArtist().getArtistId())
            .orElse(-1);

        analysisRepository.deleteAllById(ids);
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

    /**
     * 분석 행을 붙인다.
     *
     * <b>{@code musicId} 를 직접 넣지 않는다.</b> {@code MusicAnalysis} 는 {@code @MapsId}
     * 라 식별자가 {@code music} 연관에서 파생된다. 둘 다 넣으면 Hibernate 가 병합
     * 경로로 빠져 `null identifier` 로 터진다(직접 겪었다). 연관만 주면 저장 시점에
     * 곡 번호가 그대로 복사된다.
     */
    private void analyze(int musicId, boolean hasVocal, VocalGender gender) {
        analysisRepository.saveAndFlush(MusicAnalysis.builder()
            .music(musicRepository.getReferenceById(musicId))
            .hasVocal(hasVocal)
            .vocalGender(gender)
            .build());
    }

}
