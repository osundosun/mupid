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
 * <p><b>세 가지가 서로 다르다</b> — S15P21A204-396 후속에서 '파라미터 없음' 의 뜻이
 * 바뀌면서 셋으로 갈렸다. 이 구분이 무너지면 두 방향으로 조용히 깨진다.
 *
 * <ul>
 *   <li>{@link MusicSortRequest#ABSENT} — 파라미터가 없었다. <b>기본 정렬(조회순)</b>을 탄다.</li>
 *   <li>{@link MusicSortRequest#EXPLICIT_NONE} — 빈 값으로 "고르지 않음" 을 말했다.
 *       정렬 절을 붙이지 않아 <b>예전 그대로 등록순</b>이다.</li>
 *   <li>값을 줬다 — 그 축으로 정렬한다.</li>
 * </ul>
 *
 * <p>둘 중 하나라도 다른 하나에 뭉치면, 기본값을 되돌리려는 클라이언트가 쓸 이름이
 * 사라지거나(첫째와 둘째가 합쳐질 때) 평범한 조회가 400 이 된다(둘째가 '값' 으로
 * 취급될 때 — 검색어와 함께 오면 정렬 동반으로 판정된다).
 *
 * <p>방향(<b>DESC</b>)도 여기서 못 박는다. {@link MusicSort#PLAYS} 를 기본으로 만들면서
 * 부호를 잘못 잡으면 목록이 정반대로 나오는데, 그건 DB 를 띄워야 알 수 있는 종류의
 * 실수라 여기서 미리 막는다.
 */
class MusicSortParseTest {

    @Test
    @DisplayName("값이 없거나 비면 parse 는 빈 Optional 이다")
    void absentValueParsesEmpty() {
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
        // S15P21A204-396 후속에서 열린 축이다. 예전에는 이 값이 모르는 값이었다.
        assertThat(MusicSort.parse("plays")).contains(MusicSort.PLAYS);
        assertThat(MusicSort.parse("PLAYS")).contains(MusicSort.PLAYS);
    }

    @Test
    @DisplayName("모르는 값은 조용히 정렬 없음으로 흘리지 않는다")
    void unknownValueIsNotSwallowed() {
        // 흘리면 사용자가 고른 기준과 다른 순서를 보게 된다.
        // 컨트롤러는 이 empty 를 400 으로 되돌린다(vocal=banana 와 같은 결).
        assertThat(MusicSort.parse("recentt")).isEmpty();
        assertThat(MusicSort.parse("최신순")).isEmpty();
        // '조회순' 의 오타도 모르는 값이다 — 표시명을 그대로 보내도 안 된다.
        assertThat(MusicSort.parse("조회순")).isEmpty();
    }

    @Test
    @DisplayName("기본 정렬은 '조회순' 이다 — 파라미터가 없으면 조회순을 탄다")
    void defaultSortIsPlays() {
        // S15P21A204-396 후속에서 바뀐 계약이다. 예전에는 여기서 정렬을 붙이지
        // 않았고, 그래서 이 테스트의 이름도 '기본 정렬은 없다' 였다.
        assertThat(MusicSortRequest.ABSENT.sort()).isEqualTo(MusicSort.PLAYS);
        assertThat(MusicSortRequest.defaultSort()).isEqualTo(MusicSort.PLAYS);

        // '고르지 않았다' 는 사실은 남는다 — 검색어 동반 판정이 이 값을 본다.
        assertThat(MusicSortRequest.ABSENT.explicitlyRequested()).isFalse();
    }

    @Test
    @DisplayName("파라미터가 없으면 정렬 절이 붙는다 — 예전과 달라진 점")
    void absentDoesApplySort() {
        // 기본값이 실제로 정렬을 건다. 이 단언이 깨지면 기본 정렬이 조용히 사라진다.
        assertThat(MusicSortRequest.ABSENT.toSort())
            .isEqualTo(MusicSort.PLAYS.toSort());
        assertThat(MusicSortRequest.ABSENT.toSort()
            .getOrderFor("playCount").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("빈 값은 '고르지 않음' 이라 정렬 절이 붙지 않는다 — 예전 동작의 탈출구")
    void explicitNoneDoesNotSort() {
        // 기본값이 바뀌었으니, 예전 순서(등록순 오름차순)를 그대로 받을 이름이 필요하다.
        assertThat(MusicSortRequest.EXPLICIT_NONE.sort()).isNull();
        assertThat(MusicSortRequest.EXPLICIT_NONE.toSort()).isEqualTo(Sort.unsorted());

        // '기준을 이름으로 골랐는가' 는 false 다 — 검색어와 함께 와도 400 이 아니어야
        // 396 본편의 동작이 그대로 유지된다.
        assertThat(MusicSortRequest.EXPLICIT_NONE.explicitlyRequested()).isFalse();

        // ABSENT 와 절대 같은 값이면 안 된다 — 그러면 구분 자체가 사라진다.
        assertThat(MusicSortRequest.EXPLICIT_NONE).isNotEqualTo(MusicSortRequest.ABSENT);
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

        assertThat(request.explicitlyRequested()).isTrue();
        assertThat(request.toSort()).isEqualTo(MusicSort.TITLE.toSort());
        // null 을 주면 파라미터가 없었던 것과 같다.
        assertThat(MusicSortRequest.of(null)).isEqualTo(MusicSortRequest.ABSENT);
    }

    @Test
    @DisplayName("두 번째 정렬 키가 항상 곡 번호로 붙는다")
    void everySortHasMusicIdTieBreak() {
        // created_at 은 초 단위, title 은 중복 가능, like_count 는 동점이 흔하다.
        // play_count 는 더하다 — 재생 기록이 없는 곡이 대부분이라 사실상 전부 동점이다.
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
        // 조회순은 '많이 들은 곡이 앞' 이다. ASC 로 뒤집히면 목록이 반대로 나온다.
        assertThat(MusicSort.PLAYS.toSort().getOrderFor("playCount").getDirection())
            .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("축 넷의 표시명과 허용 목록이 와이어 값과 맞는다")
    void allowedNamesCoverEveryAxis() {
        assertThat(MusicSort.PLAYS.getDisplayName()).isEqualTo("조회순");
        assertThat(MusicSort.allowedNames())
            .isEqualTo("RECENT, LIKES, TITLE, PLAYS");
    }

}
