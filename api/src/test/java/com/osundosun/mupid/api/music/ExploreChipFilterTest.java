package com.osundosun.mupid.api.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.ArrayList;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 탐색 화면 왼쪽 사이드바의 칩 → 검색 결과까지 한 줄로 검증한다.
 *
 * <h2>이 테스트가 지키는 것</h2>
 *
 * <ol>
 *   <li><b>칩이 뜬다.</b> {@code GET /api/v1/music/filter-option} 이 분위기·장르·보컬
 *       선택지를 준다. 화면은 {@code display_name} 을 글자로 그리고
 *       {@code name} 을 검색 파라미터로 되돌려 준다
 *       ({@code frontend/components/mupid/explore-categories.tsx}).</li>
 *   <li><b>칩 글자가 한국어다.</b> 분위기 {@code code_name} 은 영문 내부 코드
 *       ({@code melancholic}), {@code display_name} 이 화면용 한국어({@code 애상적인})다.
 *       2026-09-21 코드표 정리로 장르와 방향이 같아졌다.</li>
 *   <li><b>모든 칩이 결과를 낸다.</b> 눌러도 0건인 칩이 있으면 사용자는 검색이 고장 난
 *       것으로 읽는다. 곡이 달리지 않은 코드는 선택지에서 빠져야 한다
 *       ({@code CodeRepository.findMoodsInUse}).</li>
 *   <li><b>고른 칩으로 실제로 걸러진다.</b> 돌아온 곡이 전부 그 분위기·장르를 갖고 있고,
 *       전체 목록보다 적다.</li>
 * </ol>
 *
 * <h2>왜 개수를 소스에 적지 않는가</h2>
 *
 * 카탈로그는 배치가 바꾼다. '분위기 9종' 같은 수를 적어 두면 곡이 늘 때 조용히 깨진다.
 * 그래서 기대값을 DB 에서 그때그때 세어 비교한다 — 이 테스트는 <b>데이터의 양이 아니라
 * 관계</b>를 지킨다. 카탈로그가 통째로 비어 있으면 단언할 것이 없어 건너뛴다.
 *
 * <h2>픽스처를 만들지 않는다</h2>
 *
 * 읽기만 한다. 곡을 새로 넣으면 같은 DB 를 보는 다른 테스트의 총계가 흔들린다
 * ({@code ExploreSortTest} · {@code VocalFilterSearchTest} 가 총계를 단언한다).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExploreChipFilterTest {

    /** 한 번에 받아 올 곡 수. 검색 응답의 상한(100)과 같다. */
    private static final int PAGE_SIZE = 100;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 응답 본문을 읽기만 한다. 주입받지 않는 것은 이 프로젝트의 빈이 Jackson 3
     * ({@code tools.jackson}) 이고 {@code com.fasterxml} 쪽 빈은 없기 때문이다 —
     * 읽기 전용이라 설정을 맞출 필요도 없다.
     */
    private final JsonMapper json = JsonMapper.builder().build();

    // ─────────────────────────────────────────────────────────────
    // 1. 칩이 뜬다

    @Test
    @DisplayName("사이드바 선택지가 분위기·장르·보컬 세 축을 모두 준다")
    void filterOptionCarriesEveryAxis() throws Exception {
        JsonNode options = filterOptions();

        assertThat(options.get("vocals")).as("보컬 선택지").isNotEmpty();
        // 분위기·장르는 카탈로그가 비면 빌 수 있다. 빈 것 자체는 오류가 아니다.
        assertThat(options.has("moods")).as("분위기 축이 응답에 있다").isTrue();
        assertThat(options.has("genres")).as("장르 축이 응답에 있다").isTrue();
        if (catalogIsEmpty()) {
            return;
        }
        assertThat(options.get("moods")).as("곡이 있는데 분위기 칩이 없다").isNotEmpty();
        assertThat(options.get("genres")).as("곡이 있는데 장르 칩이 없다").isNotEmpty();
    }

    @Test
    @DisplayName("칩은 글자로 쓸 한국어와 검색에 쓸 코드 이름을 함께 싣는다")
    void everyChipCarriesBothNames() throws Exception {
        JsonNode options = filterOptions();

        for (JsonNode chip : options.get("moods")) {
            String name = chip.get("name").asString();
            String shown = chip.get("display_name").asString();
            assertThat(name).as("분위기 코드 이름").isNotBlank();
            assertThat(shown).as("%s 의 화면 글자", name).isNotBlank();
            // 화면이 `display_name || name` 을 그린다. 코드 이름이 한국어면 두 값이
            // 뒤집힌 것이고, 그때 검색 파라미터로 한국어가 나가 결과가 0건이 된다.
            assertThat(name).as("%s 의 코드 이름이 영문이어야 한다", name).matches("[a-z_]+");
            assertThat(shown).as("%s 의 화면 글자가 한국어여야 한다", name).matches("[가-힣]+");
        }
        for (JsonNode chip : options.get("genres")) {
            assertThat(chip.get("name").asString()).isNotBlank();
            assertThat(chip.get("display_name").asString()).isNotBlank();
        }
    }

    @Test
    @DisplayName("같은 글자가 두 칩으로 뜨지 않는다")
    void chipLabelsAreDistinct() throws Exception {
        // 운영에서 같은 무드가 두 번호로 갈라져 `강렬한` 칩이 둘이었다 (2026-09-21 정리).
        // 사용자는 어느 쪽을 눌러야 하는지 알 수 없다.
        for (String axis : List.of("moods", "genres")) {
            List<String> shown = labelsOf(filterOptions().get(axis));

            assertThat(shown).as("%s 칩 글자가 겹친다", axis).doesNotHaveDuplicates();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 2. 누르면 결과가 나온다

    @Test
    @DisplayName("분위기 칩은 하나도 빠짐없이 결과를 낸다 — 0건인 칩이 섞이면 안 된다")
    void everyMoodChipReturnsSongs() throws Exception {
        if (catalogIsEmpty()) {
            return;
        }
        for (JsonNode chip : filterOptions().get("moods")) {
            String name = chip.get("name").asString();

            assertThat(totalOf(search("mood", name)))
                .as("'%s'(%s) 칩을 눌렀는데 결과가 0건이다", chip.get("display_name").asString(), name)
                .isPositive();
        }
    }

    @Test
    @DisplayName("장르 칩도 하나도 빠짐없이 결과를 낸다")
    void everyGenreChipReturnsSongs() throws Exception {
        if (catalogIsEmpty()) {
            return;
        }
        for (JsonNode chip : filterOptions().get("genres")) {
            String name = chip.get("name").asString();

            assertThat(totalOf(search("genre", name)))
                .as("'%s'(%s) 칩을 눌렀는데 결과가 0건이다", chip.get("display_name").asString(), name)
                .isPositive();
        }
    }

    // ─────────────────────────────────────────────────────────────
    // 3. 고른 칩으로 실제로 걸러진다

    @Test
    @DisplayName("분위기를 고르면 그 분위기의 곡만 남는다")
    void moodChipNarrowsToThatMood() throws Exception {
        JsonNode chip = firstChip("moods");
        if (chip == null) {
            return;
        }
        String name = chip.get("name").asString();

        JsonNode page = search("mood", name);
        long total = totalOf(page);

        assertThat(total).as("'%s' 결과 수가 DB 와 다르다", name).isEqualTo(songsWithMood(name));
        assertThat(total).as("필터가 아무것도 좁히지 못했다").isLessThan(liveSongs());
        for (int musicId : musicIdsOf(page)) {
            assertThat(moodNamesOf(musicId)).as("결과에 '%s' 가 아닌 곡이 있다", name).contains(name);
        }
    }

    @Test
    @DisplayName("장르를 고르면 그 장르의 곡만 남는다")
    void genreChipNarrowsToThatGenre() throws Exception {
        JsonNode chip = firstChip("genres");
        if (chip == null) {
            return;
        }
        String name = chip.get("name").asString();

        JsonNode page = search("genre", name);

        assertThat(totalOf(page)).as("'%s' 결과 수가 DB 와 다르다", name).isEqualTo(songsWithGenre(name));
        assertThat(totalOf(page)).as("필터가 아무것도 좁히지 못했다").isLessThan(liveSongs());
    }

    @Test
    @DisplayName("분위기를 여러 개 고르면 합집합이다 — 화면은 쉼표로 이어 보낸다")
    void severalMoodChipsAreUnion() throws Exception {
        List<JsonNode> chips = twoChips("moods");
        if (chips.isEmpty()) {
            return;
        }
        String first = chips.get(0).get("name").asString();
        String second = chips.get(1).get("name").asString();

        // 화면이 실제로 보내는 모양(`mood=a,b`) 그대로 확인한다.
        long both = totalOf(search("mood", first + "," + second));

        assertThat(both).as("합집합이 한쪽보다 작다").isGreaterThanOrEqualTo(
            Math.max(totalOf(search("mood", first)), totalOf(search("mood", second))));
        assertThat(both).as("합집합이 DB 와 다르다").isEqualTo(songsWithAnyMood(first, second));
    }

    @Test
    @DisplayName("분위기와 장르를 같이 고르면 둘 다 걸린다 (축이 다르면 AND)")
    void moodAndGenreApplyTogether() throws Exception {
        JsonNode mood = firstChip("moods");
        JsonNode genre = firstChip("genres");
        if (mood == null || genre == null) {
            return;
        }
        String moodName = mood.get("name").asString();
        String genreName = genre.get("name").asString();

        long both = totalOf(mockSearch(request -> request
            .param("mood", moodName)
            .param("genre", genreName)));

        // 교집합이라 어느 쪽보다도 크지 않다. 0 일 수 있다 — 겹치는 곡이 없을 뿐이다.
        assertThat(both).isLessThanOrEqualTo(totalOf(search("mood", moodName)));
        assertThat(both).isLessThanOrEqualTo(totalOf(search("genre", genreName)));
        assertThat(both).as("교집합이 DB 와 다르다").isEqualTo(songsWithMoodAndGenre(moodName, genreName));
    }

    @Test
    @DisplayName("화면 글자를 그대로 보내면 걸리지 않는다 — 칩이 코드 이름을 보내야 하는 이유")
    void displayNameIsNotTheSearchKey() throws Exception {
        JsonNode chip = firstChip("moods");
        if (chip == null) {
            return;
        }
        // 검색 축은 `code_name` 이다(`MusicRepository.TAXONOMY_FILTER`). 화면이
        // `display_name` 을 보내기 시작하면 오류 없이 결과만 0건이 된다 — 그래서
        // `explore-categories.tsx` 가 키를 `mood:{name}` 으로 만든다.
        assertThat(totalOf(search("mood", chip.get("display_name").asString())))
            .as("한국어가 검색 축에 걸렸다 — 두 값의 역할이 뒤집혔는지 확인한다")
            .isZero();
    }

    // ─────────────────────────────────────────────────────────────
    // 거들기

    private JsonNode filterOptions() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/music/filter-option")
                .with(authentication(token())))
            .andExpect(status().isOk())
            .andReturn();
        return body(result).get("data");
    }

    private JsonNode search(String axis, String value) throws Exception {
        return mockSearch(request -> request.param(axis, value));
    }

    private JsonNode mockSearch(RequestTweak tweak) throws Exception {
        var request = get("/api/v1/music").param("size", String.valueOf(PAGE_SIZE));
        MvcResult result = mockMvc.perform(tweak.apply(request).with(authentication(token())))
            .andExpect(status().isOk())
            .andReturn();
        return body(result).get("data");
    }

    @FunctionalInterface
    private interface RequestTweak {
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder apply(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request);
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static long totalOf(JsonNode page) {
        return page.get("total_elements").asLong();
    }

    private static List<Integer> musicIdsOf(JsonNode page) {
        List<Integer> ids = new ArrayList<>();
        for (JsonNode item : page.get("items")) {
            ids.add(item.get("music_id").asInt());
        }
        return ids;
    }

    private static List<String> labelsOf(JsonNode chips) {
        List<String> shown = new ArrayList<>();
        for (JsonNode chip : chips) {
            shown.add(chip.get("display_name").asString());
        }
        return shown;
    }

    /** 선택지의 첫 칩. 카탈로그가 비어 칩이 없으면 {@code null} 이다. */
    private JsonNode firstChip(String axis) throws Exception {
        JsonNode chips = filterOptions().get(axis);
        return chips.isEmpty() ? null : chips.get(0);
    }

    /** 앞의 두 칩. 하나뿐이면 빈 목록 — 합집합을 볼 수 없다. */
    private List<JsonNode> twoChips(String axis) throws Exception {
        JsonNode chips = filterOptions().get(axis);
        return chips.size() < 2 ? List.of() : List.of(chips.get(0), chips.get(1));
    }

    private static UsernamePasswordAuthenticationToken token() {
        return new UsernamePasswordAuthenticationToken("chipprobe@mupid.test", null, List.of());
    }

    // ── 기대값은 DB 에서 센다. 검색 쿼리와 같은 조건(`is_deleted = 0`)을 쓴다.

    private boolean catalogIsEmpty() {
        return liveSongs() == 0;
    }

    private long liveSongs() {
        return count("SELECT COUNT(*) FROM music WHERE is_deleted = 0");
    }

    private long songsWithMood(String codeName) {
        return count("""
            SELECT COUNT(DISTINCT m.music_id) FROM music m
            JOIN music_moods mm ON mm.music_id = m.music_id
            JOIN codes c ON c.code_id = mm.mood_code_id AND c.code_type = 'MOOD'
            WHERE m.is_deleted = 0 AND c.code_name = ?
            """, codeName);
    }

    private long songsWithAnyMood(String first, String second) {
        return count("""
            SELECT COUNT(DISTINCT m.music_id) FROM music m
            JOIN music_moods mm ON mm.music_id = m.music_id
            JOIN codes c ON c.code_id = mm.mood_code_id AND c.code_type = 'MOOD'
            WHERE m.is_deleted = 0 AND c.code_name IN (?, ?)
            """, first, second);
    }

    private long songsWithGenre(String codeName) {
        return count("""
            SELECT COUNT(DISTINCT m.music_id) FROM music m
            JOIN music_genres mg ON mg.music_id = m.music_id
            JOIN codes c ON c.code_id = mg.genre_code_id AND c.code_type = 'GENRE'
            WHERE m.is_deleted = 0 AND c.code_name = ?
            """, codeName);
    }

    private long songsWithMoodAndGenre(String moodName, String genreName) {
        return count("""
            SELECT COUNT(DISTINCT m.music_id) FROM music m
            JOIN music_moods mm ON mm.music_id = m.music_id
            JOIN codes mc ON mc.code_id = mm.mood_code_id AND mc.code_type = 'MOOD'
            JOIN music_genres mg ON mg.music_id = m.music_id
            JOIN codes gc ON gc.code_id = mg.genre_code_id AND gc.code_type = 'GENRE'
            WHERE m.is_deleted = 0 AND mc.code_name = ? AND gc.code_name = ?
            """, moodName, genreName);
    }

    /** 곡에 달린 분위기 코드 이름. 결과 한 줄이 정말 그 분위기인지 되짚는다. */
    private List<String> moodNamesOf(int musicId) {
        return jdbcTemplate.queryForList("""
            SELECT c.code_name FROM music_moods mm
            JOIN codes c ON c.code_id = mm.mood_code_id AND c.code_type = 'MOOD'
            WHERE mm.music_id = ?
            """, String.class, musicId);
    }

    private long count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }
}
