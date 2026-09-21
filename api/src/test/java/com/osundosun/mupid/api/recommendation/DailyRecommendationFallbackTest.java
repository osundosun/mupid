package com.osundosun.mupid.api.recommendation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
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

import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.mapping.entity.MusicGenre;
import com.osundosun.mupid.api.music.mapping.entity.MusicMood;
import com.osundosun.mupid.api.onboarding.entity.OnboardingSurvey;
import com.osundosun.mupid.api.recommendation.dto.RecommendedTrack;
import com.osundosun.mupid.api.recommendation.entity.DailyRecommendation;
import com.osundosun.mupid.api.recommendation.entity.DailyRecommendationTrack;
import com.osundosun.mupid.api.recommendation.response.DailyRecommendationGroup;
import com.osundosun.mupid.api.recommendation.response.DailyRecommendationResponse;
import com.osundosun.mupid.api.recommendation.service.DailyRecommendationService;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 「오늘의 모먼트」 조회(`GET /api/v1/recommendation/daily`)의 폴백 검증.
 *
 * <p>배치가 아직 돌지 않은 사용자에게 빈 홈이 가던 구멍을 메운 변경이다
 * (S15P21A204-408). 여기서 보는 것은 셋이다.
 *
 * <ul>
 *   <li><b>저장된 것이 있으면 손대지 않는다</b> — 배치 산출물을 읽는 기존 경로와
 *       날짜 되돌림이 그대로여야 한다(회귀 방지)
 *   <li>저장된 것이 하나도 없고 온보딩을 마쳤으면 그 장르·분위기의 조회수 상위
 *       네 곡을 받는다
 *   <li>온보딩이 없거나 조건에 맞는 곡이 모자라도 <b>예외 없이</b> 정해진 대로 나간다
 * </ul>
 *
 * <p>카탈로그 전체에서 고르는 갈래까지 확인해야 해서, 시험용 곡의 조회수를
 * 실제 카탈로그가 닿을 수 없는 값으로 둔다. 그래야 "카탈로그 인기순" 의 결과가
 * 로컬 DB 에 무엇이 들어 있든 같다.
 *
 * <p>로컬 MySQL 이 떠 있어야 하고, 트랜잭션은 테스트마다 롤백된다.
 */
@SpringBootTest
@Transactional
class DailyRecommendationFallbackTest {

    /** 실제 카탈로그가 닿을 수 없는 조회수. 인기순 갈래를 결정적으로 만든다. */
    private static final int HUGE_PLAY_COUNT = 9_000_000;

    private static final String FALLBACK_TITLE = "첫 모먼트";
    private static final String TASTE_GROUP_REASON = "처음 고른 취향에 맞는 곡들이에요.";
    private static final String TASTE_TRACK_REASON = "처음 고른 취향에 맞는 곡이에요.";
    private static final String POPULAR_GROUP_REASON = "많이 듣는 곡부터 골랐어요.";
    private static final String POPULAR_TRACK_REASON = "많이 듣는 곡이에요.";

    @Autowired
    private DailyRecommendationService recommendationService;

    @PersistenceContext
    private EntityManager em;

    private User user;

    /** 조회수 내림차순으로 다섯 곡. {@code songs.get(0)} 이 가장 많이 들린 곡이다. */
    private List<Music> songs;

    private Code wideGenre;
    private Code wideMood;
    private Code narrowGenre;
    private Code narrowMood;
    private Code unusedGenre;
    private Code unusedMood;

    @BeforeEach
    void setUp() {
        user = persistUser();

        String tag = UUID.randomUUID().toString().substring(0, 8);
        wideGenre = persistCode(CodeType.GENRE, "wide-" + tag);
        wideMood = persistCode(CodeType.MOOD, "wide-" + tag);
        narrowGenre = persistCode(CodeType.GENRE, "narrow-" + tag);
        narrowMood = persistCode(CodeType.MOOD, "narrow-" + tag);
        unusedGenre = persistCode(CodeType.GENRE, "unused-" + tag);
        unusedMood = persistCode(CodeType.MOOD, "unused-" + tag);

        ObjectStorageFile image = persistImage();
        Artist artist = Artist.builder().name("LUME " + tag).imageFile(image).build();
        em.persist(artist);
        Album album = Album.builder()
            .artist(artist)
            .title("Blue Static " + tag)
            .imageFile(image)
            .releaseDate(LocalDateTime.now())
            .build();
        em.persist(album);

        songs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Music music = Music.builder()
                .album(album)
                .artist(artist)
                .title("곡 %d (%s)".formatted(i, tag))
                .releaseDate(LocalDateTime.now())
                /* 많이 들린 순서가 곧 songs 의 순서가 되게 둔다. */
                .playCount(HUGE_PLAY_COUNT + 5 - i)
                .build();
            em.persist(music);
            songs.add(music);
            /* 다섯 곡 모두 넓은 태그를 단다. */
            tagGenre(music, wideGenre);
            tagMood(music, wideMood);
        }
        /* 좁은 태그는 앞의 두 곡에만. "조건에 맞는 곡이 4곡 미만" 을 만든다. */
        tagGenre(songs.get(0), narrowGenre);
        tagMood(songs.get(0), narrowMood);
        tagGenre(songs.get(1), narrowGenre);
        tagMood(songs.get(1), narrowMood);

        em.flush();
    }

    // ───────────────────────────────────────────────── (a) 기존 경로 회귀 방지

    @Test
    @DisplayName("저장된 추천이 있으면 폴백이 끼어들지 않고 그대로 나간다")
    void keepsStoredRecommendationUntouched() {
        LocalDate today = LocalDate.now();
        DailyRecommendation stored = persistStored(today, songs.get(4));
        em.flush();
        em.clear();

        DailyRecommendationResponse response = recommendationService.getDaily(user, today);

        assertEquals(today, response.date());
        assertEquals(1, response.groups().size());
        DailyRecommendationGroup group = response.groups().getFirst();
        assertEquals(stored.getRecommendationId(), group.recommendationId());
        assertEquals("오늘의 모먼트", group.title());
        assertEquals("배치가 적어 둔 사유", group.reason());
        assertEquals(wideMood.getCodeName(), group.mood());
        assertEquals(1, group.musicCount());
        assertEquals(List.of(songs.get(4).getMusicId()), musicIds(group));
        assertEquals("배치가 적어 둔 곡 사유", group.tracks().getFirst().reason());
    }

    @Test
    @DisplayName("그날 것이 없어도 지난 추천이 있으면 그 날짜로 되돌아간다 — 폴백보다 앞선다")
    void prefersTheEarlierDateOverTheFallback() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        persistStored(yesterday, songs.get(4));
        em.flush();
        em.clear();

        DailyRecommendationResponse response = recommendationService.getDaily(user, LocalDate.now());

        assertEquals(yesterday, response.date());
        assertEquals("오늘의 모먼트", response.groups().getFirst().title());
    }

    // ───────────────────────────────────── (b) 온보딩을 마친 사용자의 콜드스타트

    @Test
    @DisplayName("저장된 것이 없고 온보딩을 마쳤으면 그 장르·분위기의 조회수 상위 4곡이 온다")
    void picksTopPlayedTracksOfTheChosenTaste() {
        submitOnboarding(wideMood, wideGenre);
        em.flush();
        em.clear();

        LocalDate today = LocalDate.now();
        DailyRecommendationResponse response = recommendationService.getDaily(user, today);

        assertEquals(today, response.date());
        DailyRecommendationGroup group = onlyGroup(response);

        /* 저장된 묶음이 아니라 가리킬 번호가 없다. */
        assertEquals(0, group.recommendationId());
        assertEquals(FALLBACK_TITLE, group.title());
        assertEquals(TASTE_GROUP_REASON, group.reason());
        /* 구간 분위기가 없는 곡들이라 사용자가 직접 고른 분위기로 물러난다. */
        assertEquals(wideMood.getCodeName(), group.mood());
        assertEquals(4, group.musicCount());

        /* 다섯 곡 중 조회수 상위 네 곡이 그 순서대로. */
        assertEquals(musicIdsOf(songs.subList(0, 4)), musicIds(group));
        assertEquals(List.of(1, 2, 3, 4),
            group.tracks().stream().map(RecommendedTrack::rank).toList());
        assertTrue(group.tracks().stream()
            .allMatch(track -> TASTE_TRACK_REASON.equals(track.reason())));
        assertDescendingPlayCount(group);
    }

    @Test
    @DisplayName("조건에 맞는 곡이 4곡보다 적으면 넓히지 않고 모자란 대로 내린다")
    void shipsFewerTracksRatherThanPaddingWithUnrelatedSongs() {
        submitOnboarding(narrowMood, narrowGenre);
        em.flush();
        em.clear();

        DailyRecommendationGroup group = onlyGroup(
            recommendationService.getDaily(user, LocalDate.now()));

        assertEquals(2, group.musicCount());
        assertEquals(2, group.tracks().size());
        assertEquals(musicIdsOf(songs.subList(0, 2)), musicIds(group));
        /* 취향으로 좁힌 결과이므로 사유도 그대로다 — 인기순으로 갈아타지 않았다. */
        assertEquals(TASTE_GROUP_REASON, group.reason());
    }

    @Test
    @DisplayName("고른 취향에 맞는 곡이 하나도 없으면 인기순으로 넓히고 사유도 그렇게 말한다")
    void relaxesToPopularWhenNothingMatchesTheChosenTaste() {
        submitOnboarding(unusedMood, unusedGenre);
        em.flush();
        em.clear();

        DailyRecommendationGroup group = onlyGroup(
            recommendationService.getDaily(user, LocalDate.now()));

        assertEquals(4, group.musicCount());
        assertEquals(musicIdsOf(songs.subList(0, 4)), musicIds(group));
        assertEquals(FALLBACK_TITLE, group.title());
        /* 취향으로 고른 것이 아니라고 말한다. 꾸미지 않는다. */
        assertEquals(POPULAR_GROUP_REASON, group.reason());
        assertTrue(group.tracks().stream()
            .allMatch(track -> POPULAR_TRACK_REASON.equals(track.reason())));
        assertDescendingPlayCount(group);
    }

    // ─────────────────────────────────────────── (c) 온보딩을 아직 안 한 사용자

    @Test
    @DisplayName("온보딩을 안 했어도 예외 없이 카탈로그 인기순 4곡이 온다")
    void fallsBackToPopularWithoutOnboarding() {
        em.flush();
        em.clear();

        DailyRecommendationGroup group = onlyGroup(
            recommendationService.getDaily(user, LocalDate.now()));

        assertEquals(4, group.musicCount());
        assertEquals(musicIdsOf(songs.subList(0, 4)), musicIds(group));
        assertEquals(POPULAR_GROUP_REASON, group.reason());
        /* 분위기를 정할 근거가 없으면 지어내지 않고 빈 값이다. */
        assertEquals("", group.mood());
    }

    @Test
    @DisplayName("폴백은 `daily_recommendations` 에 아무것도 남기지 않는다")
    void neverWritesTheFallbackDown() {
        em.flush();
        em.clear();

        assertFalse(onlyGroup(recommendationService.getDaily(user, LocalDate.now()))
            .tracks().isEmpty());

        Long stored = em.createQuery(
                "SELECT COUNT(r) FROM DailyRecommendation r WHERE r.user.userId = :userId",
                Long.class)
            .setParameter("userId", user.getUserId())
            .getSingleResult();
        assertEquals(0L, stored);
    }

    // ──────────────────────────────────────────────────────────────── 도우미

    private DailyRecommendationGroup onlyGroup(DailyRecommendationResponse response) {
        assertEquals(1, response.groups().size());
        return response.groups().getFirst();
    }

    private List<Integer> musicIds(DailyRecommendationGroup group) {
        return group.tracks().stream().map(track -> track.music().musicId()).toList();
    }

    private List<Integer> musicIdsOf(List<Music> expected) {
        return expected.stream().map(Music::getMusicId).toList();
    }

    private void assertDescendingPlayCount(DailyRecommendationGroup group) {
        List<Integer> plays = group.tracks().stream()
            .map(track -> track.music().playCount())
            .toList();
        for (int i = 1; i < plays.size(); i++) {
            assertTrue(plays.get(i - 1) >= plays.get(i),
                "조회수가 내림차순이어야 한다: " + plays);
        }
    }

    /** 배치가 만들어 둔 묶음 한 벌. 곡 하나만 담는다. */
    private DailyRecommendation persistStored(LocalDate date, Music music) {
        DailyRecommendation group = DailyRecommendation.builder()
            .user(user)
            .title("오늘의 모먼트")
            .musicCount(1)
            .reason("배치가 적어 둔 사유")
            .moodCode(wideMood)
            .recommendationDate(date)
            .build();
        em.persist(group);
        em.flush();
        em.persist(DailyRecommendationTrack.builder()
            .dailyRecommendationTrackId(
                new DailyRecommendationTrack.DailyRecommendationTrackId(null, 1))
            .dailyRecommendation(group)
            .music(music)
            .reason("배치가 적어 둔 곡 사유")
            .build());
        return group;
    }

    /** 온보딩은 분위기 × 장르 조합으로 깔린다 ({@code OnboardingServiceImpl}). */
    private void submitOnboarding(Code mood, Code genre) {
        em.persist(OnboardingSurvey.builder()
            .user(user)
            .moodCode(mood)
            .genreCode(genre)
            .build());
    }

    private void tagGenre(Music music, Code genre) {
        em.persist(new MusicGenre(new MusicGenre.MusicGenreId(), music, genre));
    }

    private void tagMood(Music music, Code mood) {
        em.persist(new MusicMood(new MusicMood.MusicMoodId(), music, mood));
    }

    private User persistUser() {
        User newUser = User.builder()
            /* 이메일이 유니크라 테스트끼리 부딪히지 않게 매번 새로 만든다 */
            .email("daily-%s@mupid.test".formatted(UUID.randomUUID()))
            .password("$2a$10$abcdefghijklmnopqrstuv")
            .nickname("테스터")
            .birthYear(1999)
            .gender(UserGender.ETC)
            .role(UserRole.ROLE_USER)
            .status(UserStatus.ACTIVE)
            .build();
        em.persist(newUser);
        return newUser;
    }

    /** `codes` 는 (code_type, code_name) 이 유니크라 부트스트랩 값과 겹치지 않게 꼬리를 붙인다. */
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

}
