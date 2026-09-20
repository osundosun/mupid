package com.osundosun.mupid.api.music.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import com.osundosun.mupid.api.music.enumerate.MusicSort;
import com.osundosun.mupid.api.music.request.MusicSortRequest;

/**
 * 정렬 파라미터를 푸는 규칙.
 *
 * <h2>왜 DB 없이 도는가</h2>
 *
 * {@code VocalFilterQueryTest}·{@code MusicSearchQueryTest} 와 같은 이유다 — 여기서
 * 확인할 것은 <b>문자열을 enum 으로 옮기는 규칙</b>뿐이라 인프라가 필요 없다
 * (convention/testing.md — MySQL 이 떠 있어야 도는 테스트를 굳이 늘리지 않는다).
 *
 * <h2>여기서 못 박는 것</h2>
 *
 * <b>'파라미터가 없다' 와 '값을 줬다' 는 다르다.</b> 이 구분이 무너지면 예전
 * 클라이언트의 결과 순서가 조용히 바뀐다 — 실제로 {@link MusicSort#RECENT} 는 현행
 * 무정렬(등록순 오름차순)과 <b>반대 방향</b>이라, 기본값을 실수로 끼워 넣으면 목록이
 * 뒤집힌다. 이 테스트가 그 실수를 막는다.
 */
class MusicSortParseTest {

    @Test
    @DisplayName("값이 없거나 비면 '정렬하지 않음' 이다")
    void absentMeansNoSort() {
        assertThat(MusicSort.parse(null)).isEmpty();
        assertThat(MusicSort.parse("")).isEmpty();
        assertThat(MusicSort.parse("   ")).isEmpty();
    }

    @Test
    @DisplayName("대소문자와 앞뒤 공백을 가리지 않는다")
    void caseAndSpaceInsensitive() {
        assertThat(MusicSort.parse("recent")).contains(MusicSort.RECENT);
        assertThat(MusicSort.parse("RECENT")).contains(MusicSort.RECENT);
        assertThat(MusicSort.parse("Recent")).contains(MusicSort.RECENT);
        assertThat(MusicSort.parse("  title ")).contains(MusicSort.TITLE);
        assertThat(MusicSort.parse("likes")).contains(MusicSort.LIKES);
    }

    @Test
    @DisplayName("모르는 값은 조용히 정렬 없음으로 흘리지 않는다")
    void unknownValueIsNotSwallowed() {
        // 흘리면 사용자가 고른 기준과 다른 순서를 보게 된다.
        // 컨트롤러는 이 empty 를 400 으로 되돌린다(vocal=banana 와 같은 결).
        assertThat(MusicSort.parse("plays")).isEmpty();
        assertThat(MusicSort.parse("recentt")).isEmpty();
        assertThat(MusicSort.parse("최신순")).isEmpty();
    }

    @Test
    @DisplayName("기본 정렬은 없다 — 파라미터가 없으면 정렬을 붙이지 않는다")
    void absentRequestSortsNothing() {
        // 이 한 가지가 하위 호환의 전부다. 여기서 true 가 나오면 옛 클라이언트의
        // 목록 순서가 바뀐다(RECENT 는 현행과 반대 방향).
        assertThat(MusicSortRequest.ABSENT.isRequested()).isFalse();
        assertThat(MusicSortRequest.ABSENT.sort()).isNull();
    }

    @Test
    @DisplayName("null 요청도 '파라미터 없음' 으로 본다")
    void nullRequestFallsBackToAbsent() {
        assertThat(MusicSortRequest.orDefault(null)).isEqualTo(MusicSortRequest.ABSENT);
    }

    @Test
    @DisplayName("값을 주면 그 기준을 쓴다")
    void explicitValueIsRequested() {
        MusicSortRequest request = MusicSortRequest.of(MusicSort.TITLE);

        assertThat(request.isRequested()).isTrue();
        assertThat(request.sort()).isEqualTo(MusicSort.TITLE);
        // null 을 주면 파라미터가 없었던 것과 같다.
        assertThat(MusicSortRequest.of(null)).isEqualTo(MusicSortRequest.ABSENT);
    }

    @Test
    @DisplayName("두 번째 정렬 키가 항상 곡 번호로 붙는다")
    void everySortHasMusicIdTieBreak() {
        // created_at 은 초 단위, title 은 중복 가능, like_count 는 동점이 흔하다.
        // 두 번째 키가 없으면 오프셋 페이지네이션에서 곡이 중복되거나 사라진다.
        for (MusicSort sort : MusicSort.values()) {
            assertThat(sort.toSort().stream().map(Sort.Order::getProperty).toList())
                .as("정렬 %s 의 키 목록", sort)
                .hasSize(2)
                .endsWith("musicId");
        }
    }

    @Test
    @DisplayName("축마다 방향이 의도한 대로다")
    void directionsMatchIntent() {
        assertThat(MusicSort.RECENT.toSort().getOrderFor("createdAt").getDirection())
            .isEqualTo(Sort.Direction.DESC);
        assertThat(MusicSort.TITLE.toSort().getOrderFor("title").getDirection())
            .isEqualTo(Sort.Direction.ASC);
        assertThat(MusicSort.LIKES.toSort().getOrderFor("likeCount").getDirection())
            .isEqualTo(Sort.Direction.DESC);
    }

}
