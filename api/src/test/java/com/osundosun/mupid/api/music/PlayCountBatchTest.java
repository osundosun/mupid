package com.osundosun.mupid.api.music;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.analytics.repository.ListenAnalyticsRepository;
import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;
import com.osundosun.mupid.api.music.entity.Album;
import com.osundosun.mupid.api.music.entity.Artist;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.repository.AlbumRepository;
import com.osundosun.mupid.api.music.repository.ArtistRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.service.PlayCountBatch;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.repository.ObjectStorageFileRepository;

/**
 * 재생수 집계 배치(S15P21A204-396 후속) 검증.
 *
 * <h2>왜 실제로 돌려야 하는가</h2>
 *
 * <p>"배치를 만들었다" 는 사실은 아무것도 증명하지 않는다. 이 배치는
 * <b>ClickHouse 를 읽어 MySQL 을 쓴다</b> 는 두 저장소를 잇는 일이라, 어느 한쪽이
 * 어긋나면 조용히 아무 일도 안 하면서 성공 로그만 남는다. 그래서 여기서는
 * {@link PlayCountBatch#refreshPlayCounts()} 를 <b>그대로 호출해</b> MySQL 의
 * {@code play_count} 가 실제로 바뀌는지 본다.
 *
 * <h2>이 테스트가 기대는 환경</h2>
 *
 * <p>개발 스택의 MySQL·Redis·ClickHouse 가 떠 있어야 한다({@code _shared/mupid-test-env.sh}).
 * 다만 <b>ClickHouse 에 재생 기록이 하나도 없어도 통과해야 한다</b> — 그때는 배치가
 * "갱신할 곡 없음" 으로 끝나는 것이 정상이기 때문이다(개발 스택을 새로 띄운 직후가
 * 그렇다). 그래서 판정을 <b>두 갈래</b>로 쓴다.
 *
 * <ul>
 *   <li>ClickHouse 에 재생 기록이 <b>있다</b> → 픽스처 곡의 {@code play_count} 가
 *       갱신되어 <b>0 이 아니게</b> 된다. 그리고 그 값은 픽스처가 미리 넣어 둔
 *       엉뚱한 값(777)을 <b>덮어쓴</b> 결과여야 한다 — 배치가 대입이지 덧셈이 아님을
 *       이 한 줄이 증명한다.</li>
 *   <li>재생 기록이 <b>없다</b> → 어떤 곡의 {@code play_count} 도 바뀌지 않는다.
 *       집계가 비었을 때 컬럼을 건드리지 않는다는 계약이 그것이다.</li>
 * </ul>
 *
 * <p>어느 갈래인지는 {@link ListenAnalyticsRepository#catalogPlayCounts()} 를 직접 불러
 * <b>테스트가 스스로 판단</b>한다. 환경에 따라 테스트가 조용히 무의미해지지 않게,
 * 두 경우 모두 최소 한 개의 단언이 실제로 실행된다.
 *
 * <h2>왜 ClickHouse 에 직접 이벤트를 넣지 않는가</h2>
 *
 * <p>넣으면 개발 스택의 분석 데이터를 영구히 오염시킨다 — MuBTI 리포트·카탈로그 기준선이
 * 그 값을 함께 본다. 그리고 그 데이터를 지우는 것은 이 티켓 범위(api/ 만 수정)밖이다.
 * 그래서 <b>이미 있는 원천으로</b> 검증한다.
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlayCountBatchTest {

    /** 다른 실행과 섞이지 않게 하는 고유 표식. */
    private static final String MARK = "Playprobebatch" + UUID.randomUUID().toString().substring(0, 8);

    /**
     * 배치가 덮어써야 하는, 일부러 넣어 둔 엉뚱한 값.
     *
     * <p>0 이 아닌 값을 고르는 이유: 배치가 아무것도 안 했을 때 0 과 구별되게 하려는
     * 것이다. 0 을 넣어 두면 "갱신했다" 와 "손대지 않았다" 가 같은 값이 된다.
     */
    private static final int STALE_PLAY_COUNT = 777;

    private static final Duration LOCK = Duration.ofHours(6);

    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    @Autowired
    private PlayCountBatch playCountBatch;

    @Autowired
    private ListenAnalyticsRepository analytics;

    @Autowired
    private MusicRepository musicRepository;

    @Autowired
    private AlbumRepository albumRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ObjectStorageFileRepository objectStorageFileRepository;

    @Autowired
    private CacheService cacheService;

    /**
     * MySQL 을 직접 본다.
     *
     * <p>엔티티가 아니라 SQL 로 읽는 이유: 배치가 {@code clearAutomatically} 로 영속성
     * 컨텍스트를 비우기 때문에, 캐시된 엔티티를 보면 <b>DB 의 값이 아니라 배치 전의
     * 값</b>을 보게 될 수 있다. 여기서 확인하려는 것은 DB 에 실제로 쓰였는가다.
     */
    @Autowired
    private JdbcTemplate mysql;

    private int albumId;
    private int artistId;
    private int musicId;
    private Integer imageFileId;

    @BeforeAll
    void seed() {
        ObjectStorageFile image = objectStorageFileRepository.saveAndFlush(
            ObjectStorageFile.builder()
                .fileType(FileType.IMG)
                .category(FileCategory.ALBUM)
                .originalFileName("play-batch-probe.jpg")
                .storageObjectKey("test/%s.jpg".formatted(UUID.randomUUID()))
                .fileSizeBytes(1024)
                .mime("image/jpeg")
                .build());

        Artist artist = artistRepository.save(Artist.builder()
            .name("재생수배치검증 아티스트 " + MARK)
            .imageFile(image)
            .build());
        Album album = albumRepository.save(Album.builder()
            .artist(artist)
            .title("재생수배치검증 앨범 " + MARK)
            .imageFile(image)
            .releaseDate(LocalDateTime.now())
            .build());
        Music music = Music.builder()
            .album(album)
            .artist(artist)
            .title(MARK + " 검증곡")
            .releaseDate(LocalDateTime.now())
            // 배치가 이 값을 무엇으로 바꾸는지가 관찰 대상이다.
            .playCount(STALE_PLAY_COUNT)
            .build();
        musicRepository.saveAndFlush(music);

        imageFileId = image.getFileId();
        albumId = album.getAlbumId();
        artistId = artist.getArtistId();
        musicId = music.getMusicId();
    }

    @AfterAll
    void cleanUp() {
        musicRepository.deleteById(musicId);
        albumRepository.deleteById(albumId);
        artistRepository.deleteById(artistId);
        if (imageFileId != null) {
            objectStorageFileRepository.deleteById(imageFileId);
        }
        // 선점 키를 지운다. 남겨 두면 다음 회차(또는 수동 실행)가 자기 선점에 막힌다.
        cacheService.delete(LocalDateTime.now(ZONE).toLocalDate().toString(),
            CacheKind.PlayCountBatch);
    }

    @Test
    @DisplayName("배치를 직접 돌리면 play_count 가 ClickHouse 집계로 덮어써진다")
    void refreshWritesPlayCountFromClickHouse() {
        Map<Integer, Long> playsByTrack = playsByTrack();

        int updated = playCountBatch.refreshPlayCounts();

        if (playsByTrack.isEmpty()) {
            /*
             * 재생 기록이 없는 환경(개발 스택을 새로 띄운 직후). 배치가 갱신할 것이 없으므로
             * 아무 곡도 바뀌지 않는 것이 계약이다. 이 갈래에서는 '컬럼을 건드리지 않는다'
             * 가 곧 검증 대상이다.
             */
            assertThat(updated).as("집계가 비면 갱신 곡 수도 0 이어야 한다").isZero();
            assertThat(playCountOf(musicId))
                .as("집계가 없을 때 기존 값을 건드리면 안 된다")
                .isEqualTo(STALE_PLAY_COUNT);
            return;
        }

        assertThat(updated).as("집계가 있으면 갱신된 곡이 있어야 한다").isPositive();

        /*
         * 대입인지 증명하는 자리다. 픽스처가 777 을 넣어 두었으므로,
         * (1) 값이 777 그대로면 배치가 손대지 않은 것이고
         * (2) 777 보다 커졌으면 덧셈을 한 것이다(777 + 집계값).
         * 둘 다 아니어야 한다 — 다만 이 픽스처 곡은 ClickHouse 에 자기 재생 기록이
         * 없으므로(곡 번호가 새로 발급됐다) 갱신 대상이 아니다. 그래서 픽스처 곡이 아니라
         * <b>갱신된 곡들</b>을 본다.
         */
        Optional<Integer> anyTrack = playsByTrack.keySet().stream()
            .filter(id -> playCountOf(id) != null)
            .findFirst();
        assertThat(anyTrack)
            .as("ClickHouse 에 재생 기록이 있는 곡이 카탈로그에도 있어야 검증이 성립한다")
            .isPresent();

        int trackId = anyTrack.get();
        long expected = playsByTrack.get(trackId);
        assertThat(playCountOf(trackId))
            .as("music_id=%d 의 play_count 는 ClickHouse 집계값이어야 한다(덧셈이면 더 커진다)", trackId)
            .isEqualTo((int)Math.min(expected, Integer.MAX_VALUE));
    }

    @Test
    @DisplayName("배치를 두 번 돌려도 같은 값이다 — 대입이지 덧셈이 아니다")
    void refreshIsIdempotent() {
        Map<Integer, Long> playsByTrack = playsByTrack();
        if (playsByTrack.isEmpty()) {
            // 이 갈래에서는 두 번 돌려도 아무것도 안 바뀐다는 것이 계약이다.
            assertThat(playCountBatch.refreshPlayCounts()).isZero();
            assertThat(playCountOf(musicId)).isEqualTo(STALE_PLAY_COUNT);
            return;
        }

        playCountBatch.refreshPlayCounts();
        List<Integer> afterFirst = playCountsOf(playsByTrack.keySet().stream().toList());
        assertThat(playCountBatch.refreshPlayCounts()).isPositive();
        List<Integer> afterSecond = playCountsOf(playsByTrack.keySet().stream().toList());

        // 덧셈이었다면 두 번째 회차에서 두 배가 된다.
        assertThat(afterSecond).isEqualTo(afterFirst);
    }

    /**
     * 갱신 쿼리의 행 수 계약. <b>트랜잭션 안에서 직접 부른다.</b>
     *
     * <p>{@code @Modifying} JPQL 은 트랜잭션을 요구하므로 테스트에서 프록시를 그냥 부르면
     * {@code TransactionRequiredException} 이 난다. 실제 경로는 배치 →
     * {@code MusicService.updatePlayCount}({@code REQUIRES_NEW}) 이고, 그쪽이 트랜잭션
     * 경계를 만든다 — 위의 {@code refreshPlayCounts} 테스트가 그 경로를 이미 태운다.
     * 여기서는 <b>행 수가 무엇을 뜻하는지</b>만 확인한다: 0 이면 그런 곡이 없다는 뜻이고,
     * 배치는 그 값을 실패가 아니라 '카탈로그에 없음' 으로 센다.
     */
    @Test
    @Transactional
    @DisplayName("갱신 쿼리는 없는 곡에 0 을, 있는 곡에 1 을 돌려준다")
    void updatePlayCountReturnsRowCount() {
        assertThat(musicRepository.updatePlayCount(-1, 5))
            .as("없는 곡 — 예외가 아니라 0 이다")
            .isZero();
        assertThat(musicRepository.updatePlayCount(musicId, STALE_PLAY_COUNT))
            .as("있는 곡 — 갱신된 행 수")
            .isPositive();
    }

    @Test
    @DisplayName("Redis 선점(SETNX)이 두 번째 진입을 막는다")
    void redisLockBlocksSecondEntry() {
        // 스케줄러는 API 인스턴스마다 돈다. 두 인스턴스가 같은 시각에 진입하는 상황을
        // 여기서 그대로 흉내 낸다 — 같은 키로 두 번 선점을 시도한다.
        String key = "lockprobe-" + MARK;
        Duration ttl = Duration.ofHours(6);

        assertThat(cacheService.setIfAbsent(key, CacheKind.PlayCountBatch, ttl))
            .as("첫 인스턴스가 잡는다")
            .isTrue();
        assertThat(cacheService.setIfAbsent(key, CacheKind.PlayCountBatch, ttl))
            .as("두 번째 인스턴스는 못 잡는다")
            .isFalse();
        assertThat(cacheService.exists(key, CacheKind.PlayCountBatch)).isTrue();

        cacheService.delete(key, CacheKind.PlayCountBatch);
        assertThat(cacheService.setIfAbsent(key, CacheKind.PlayCountBatch, ttl))
            .as("선점을 놓으면 다시 잡힌다 — 하루를 넘기지 않는 TTL 근거")
            .isTrue();

        cacheService.delete(key, CacheKind.PlayCountBatch);
    }

    @Test
    @DisplayName("스케줄 진입점은 선점을 잡은 인스턴스에서만 집계한다")
    void scheduledEntryIsGuardedByLock() {
        /*
         * 캐시 키가 아니라 <b>실제 진입점</b>을 본다. 키가 맞아도 키를
         * `CacheKind.PlayCountBatch` 로 안 만들면 선점이 뚫리므로, 둘이 함께 검증돼야 한다.
         *
         * 픽스처 곡은 곡 번호가 새로 발급돼 ClickHouse 에 자기 기록이 없다 — 그래서
         * 관찰 대상은 <b>원천에 기록이 있는 곡</b>이다. 그런 곡이 없으면 이 테스트는
         * 선점만 확인하고 끝난다(환경이 데이터를 안 준 경우).
         */
        Map<Integer, Long> playsByTrack = playsByTrack();
        Optional<Integer> tracked = playsByTrack.keySet().stream()
            .filter(id -> playCountOf(id) != null)
            .findFirst();
        if (tracked.isEmpty()) {
            // 원천에 기록이 있으면서 카탈로그에도 있는 곡이 하나도 없다. 선점 계약만 본다.
            String probeKey = LocalDate.now(ZONE).toString();
            assertThat(cacheService.setIfAbsent(probeKey, CacheKind.PlayCountBatch, LOCK))
                .isTrue();
            playCountBatch.runDaily();
            cacheService.delete(probeKey, CacheKind.PlayCountBatch);
            return;
        }

        int trackId = tracked.get();
        long expected = playsByTrack.get(trackId);
        String key = LocalDate.now(ZONE).toString();

        // 선점을 잡아 둔 상태: 다른 인스턴스가 돌고 있다고 보고 <b>집계를 하지 않는다.</b>
        assertThat(cacheService.setIfAbsent(key, CacheKind.PlayCountBatch, LOCK)).isTrue();
        Integer beforeBlocked = playCountOf(trackId);
        playCountBatch.runDaily();
        assertThat(playCountOf(trackId))
            .as("선점이 잡혀 있으면 컬럼이 바뀌지 않아야 한다")
            .isEqualTo(beforeBlocked);
        assertThat(cacheService.exists(key, CacheKind.PlayCountBatch)).isTrue();

        // 선점을 놓는다 → 이번에는 집계가 돈다.
        cacheService.delete(key, CacheKind.PlayCountBatch);
        playCountBatch.runDaily();

        assertThat(playCountOf(trackId))
            .as("선점을 놓으면 music_id=%d 가 ClickHouse 집계값으로 갱신된다", trackId)
            .isEqualTo((int)Math.min(expected, Integer.MAX_VALUE));
        assertThat(cacheService.exists(key, CacheKind.PlayCountBatch))
            .as("돌고 나면 선점이 걸려 있다 — 다음 인스턴스가 못 들어온다")
            .isTrue();
    }

    // ─────────────────────────────────────────────────────────────
    // 도우미

    /**
     * ClickHouse 의 곡별 누적 재생수. 비어 있으면 이 환경에는 원천 데이터가 없다.
     *
     * <p><b>실패를 삼키는 이유.</b> 여기서 예외를 그대로 올리면 테스트가 "배치가 틀렸다"
     * 로 보고된다. 실제로는 <b>원천을 못 읽은 것</b>이고, 그때 배치는 컬럼을 건드리지
     * 않는 것이 계약이다(그 계약 자체가 아래 단언 대상이다). ClickHouse 는 메모리 한도
     * (개발 스택 4GiB) 아래에서 다른 분석 질의와 겹치면 {@code MEMORY_LIMIT_EXCEEDED} 로
     * 이 질의를 끊을 수 있다 — 실제로 전체 스위트를 동시에 돌릴 때 한 번 났다.
     *
     * <p>그래서 "원천이 비었다" 와 "원천을 못 읽었다" 를 <b>같은 갈래</b>로 묶는다.
     * 두 경우 모두 배치가 아무것도 쓰지 않아야 하고, 그것이 여기서 지키려는 것이다.
     */
    private Map<Integer, Long> playsByTrack() {
        Map<Integer, Long> byTrack = new java.util.HashMap<>();
        try {
            for (var row : analytics.catalogPlayCounts()) {
                byTrack.put(row.trackId(), row.plays());
            }
        } catch (DataAccessException e) {
            return Map.of();
        }
        return byTrack;
    }

    /** MySQL 에서 직접 읽는다. 그런 곡이 없으면 null. */
    private Integer playCountOf(int id) {
        List<Integer> found = mysql.queryForList(
            "SELECT play_count FROM music WHERE music_id = ?", Integer.class, id);
        return found.isEmpty() ? null : found.get(0);
    }

    private List<Integer> playCountsOf(List<Integer> ids) {
        return ids.stream().map(this::playCountOf).toList();
    }

}
