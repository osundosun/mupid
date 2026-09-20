package com.osundosun.mupid.api.recommendation.service;

import java.time.Duration;

import org.springframework.stereotype.Service;

import com.osundosun.mupid.api.cache.enumerate.CacheKind;
import com.osundosun.mupid.api.cache.service.CacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 추천 사유를 Redis 에 들고 있다가 재생이 들어올 때 되짚는다.
 *
 * <p>키는 {@code recommendation:reason:{추천 번호}:{곡 번호}} 다. 추천 하나가 같은 곡을 두 줄로
 * 줄 수 있어(<b>순위가 다르면 다른 추천</b>) 곡까지 키에 넣는다. 사유가 다른 구간을 가리키므로
 * 추천 번호만으로 묶으면 나중 것이 앞 것을 덮어쓴다.
 *
 * <p>{@code CacheService} 를 그대로 쓴다. 큐(`PlayQueueRepository`)처럼 CAS 가 필요한 값이
 * 아니라 넣고 빼는 것으로 끝나는 부가 정보라 범용 KV 도우미로 충분하다.
 *
 * <p><b>TTL 을 둔다.</b> Redis 는 {@code maxmemory-policy} 가 설정돼 있지 않아 가득 차면 키가
 * 밀려나는 것이 아니라 컨테이너가 죽는다(`PlayQueueRepository` 와 같은 지적). 재생 기록은
 * 나중에 들어올 수 있으므로(오프라인 일괄 전송) 짧게 잡으면 사유를 놓치지만, 그 사유는
 * 이벤트가 받아 주는 과거 한도(`event.occurred-at-max-past`, 기본 72시간)보다 길게만
 * 살아 있으면 충분하다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationReasonCacheImpl implements RecommendationReasonCache {

    /**
     * 며칠 전에 들은 재생의 이벤트가 뒤늦게 도착해도 사유를 찾을 수 있어야 한다.
     * 수집기가 받아 주는 과거 한도(기본 72시간)보다 넉넉하게 둔다.
     */
    private static final Duration TTL = Duration.ofDays(7);

    private final CacheService cacheService;

    @Override
    public void remember(long recommendationId, int musicId, String reason) {
        if (recommendationId <= 0 || musicId <= 0 || reason == null || reason.isBlank()) {
            return;
        }
        try {
            cacheService.set(key(recommendationId, musicId), reason, CacheKind.RecommendationReason, TTL);
        } catch (RuntimeException e) {
            // 사유를 못 적어 두는 것으로 추천 응답이 실패하면 안 된다.
            log.warn("추천 사유를 기억하지 못했다 rec_id={} music_id={}", recommendationId, musicId, e);
        }
    }

    /**
     * 사유를 찾는다. <b>어떤 실패도 올려보내지 않는다.</b>
     *
     * <p>이 값은 기록에 곁들이는 부가 정보라, Redis 가 없거나 값이 깨졌다고 해서 재생 기록
     * 자체를 잃으면 안 된다. 진입 경로만으로 선곡 주체는 이미 정해져 있다.
     */
    @Override
    public String find(String recommendationId, int musicId) {
        Long id = parse(recommendationId);
        if (id == null || id <= 0 || musicId <= 0) {
            return null;
        }
        try {
            return cacheService.get(key(id, musicId), CacheKind.RecommendationReason);
        } catch (RuntimeException e) {
            log.warn("추천 사유를 읽지 못했다 rec_id={} music_id={}", recommendationId, musicId, e);
            return null;
        }
    }

    /**
     * `rec_id` 는 화면이 되돌려주는 문자열이라 숫자가 아닐 수 있다.
     * {@code NextTrackService` 의 번호는 프로세스 안에서 채번한 값이라 인스턴스가 여러 벌이면
     * 겹칠 수 있지만, 겹쳐도 사용자가 다르면 갈리므로 손해가 없다(그쪽 주석과 같은 판단).
     */
    private Long parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String key(long recommendationId, int musicId) {
        return "%d:%d".formatted(recommendationId, musicId);
    }

}
