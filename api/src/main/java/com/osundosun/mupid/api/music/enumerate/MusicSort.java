package com.osundosun.mupid.api.music.enumerate;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.domain.Sort;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 탐색 결과의 정렬 기준. <b>전부 opt-in 이다</b> — 파라미터가 없으면 정렬하지 않는다
 * ({@link com.osundosun.mupid.api.music.request.MusicSortRequest#ABSENT}).
 *
 * <h2>왜 여기 있는 축만 있는가</h2>
 *
 * <b>실제로 값이 채워지는 축</b>만 남겼다. 후보마다 실측한 근거다(개발 DB 1077곡).
 *
 * <ul>
 *   <li>{@link #RECENT} — {@code music.created_at}. 값이 51가지로 흩어져 있다. 초
 *       단위({@code DATETIME})라 같은 초에 여러 곡이 들어가므로 {@code music_id} 를
 *       두 번째 키로 반드시 붙여야 순서가 흔들리지 않는다.</li>
 *   <li>{@link #LIKES} — {@code music.like_count}. 반응이 들어올 때
 *       {@code MusicRepository.increaseLikeCount} 가 실제로 올리는 값이라
 *       <b>축으로 성립한다.</b> 다만 개발 DB 는 아직 전 곡이 0 이라, 지금 이 축으로
 *       정렬하면 순서가 등록순과 구별되지 않는다. 데이터가 쌓이면 그대로 살아난다.</li>
 *   <li>{@link #TITLE} — {@code music.title}. 1077곡 중 1024가지로 거의 유일하고
 *       {@code ix_music_title} B-Tree 가 있어 정렬에 쓸 수 있다.</li>
 * </ul>
 *
 * <h2>왜 '재생순' 이 없는가 — 가장 중요한 판단</h2>
 *
 * {@code music.play_count} 는 컬럼은 있지만 <b>그것을 올리는 코드가 저장소 어디에도
 * 없다.</b> 개발 DB 1077곡이 전부 0 이고 값이 한 종류뿐이라, 이 축으로 정렬하면
 * '재생수순' 이라고 적어 놓고 실제로는 순서를 가르지 못하는 셈이 된다. 사용자에게는
 * 고른 기준이 조용히 무시된 것처럼 보인다.
 *
 * 재생수에 해당하는 실제 데이터는 ClickHouse {@code listen_events} 에 있다
 * ({@code PLAY_START} 를 곡별로 세면 된다 — {@code ListenAnalyticsRepository} 가 이미
 * 그렇게 집계한다). 그런데 그 경로를 쓰려면 MySQL 목록 조회가 ClickHouse 를 함께
 * 봐야 한다. 취향 리포트가 먼저 그 결합을 들여왔지만
 * (knowledge/decision/0019 — 리포트가 ClickHouse 를 직접 읽는다), <b>목록 조회는
 * 요청마다 도는 경로라 성격이 다르다.</b> 지금 넣으면 탐색 화면 한 번에 두 저장소를
 * 조인해야 하고, ClickHouse 가 죽었을 때 곡 목록까지 함께 죽는다 — API 컨테이너가
 * unhealthy 가 되면 nginx 까지 내려가는 경로가 이미 문서화돼 있다
 * (knowledge/architecture/analytics-clickhouse.md).
 *
 * 그래서 <b>이 축은 만들지 않는다.</b> 대신 {@code music.play_count} 를 실제로 올리는
 * 일(청취 이벤트 → 주기 집계)이 먼저 정해져야 한다고 보고한다. 컬럼이 채워지기
 * 시작하면 이 enum 에 {@code PLAYS} 를 더하는 것만으로 축이 열린다.
 *
 * <h2>기본 정렬은 없다 — 현행 동작을 그대로 둔다</h2>
 *
 * 파라미터가 없을 때의 순서는 <b>지금 화면이 보고 있는 순서</b>여야 한다. 현행 쿼리는
 * {@code ORDER BY} 가 아예 없어 {@code music_id} 오름차순(=오래된 등록순)으로 나간다
 * ({@code MusicServiceImpl} 이 넘기던 {@code Sort.by(ASC, "musicId")} 도 같은 뜻이다).
 *
 * <p>여기에 '최신순' 을 기본값으로 박으면 <b>순서가 뒤집힌다</b> — {@link #RECENT} 는
 * {@code created_at DESC} 라 현행과 정반대다. 그래서 이 enum 에는 {@code DEFAULT}
 * 상수를 두지 않는다. 기본값 상수를 두는 순간 "파라미터 없음 = 그 상수" 라는 뜻이 되어
 * 실제 동작과 어긋나기 때문이다.
 *
 * <p>기본을 {@code RECENT} 로 바꾸고 싶다면 그건 <b>기존 클라이언트의 결과 순서를
 * 바꾸는 결정</b>이라 이 티켓 범위 밖이다 — 소비자 영향과 함께 따로 승인받아야 한다.
 */
@Getter
@AllArgsConstructor
public enum MusicSort {

    /** 최신 등록순. {@code created_at} 내림차순. */
    RECENT("최신순", "createdAt", Sort.Direction.DESC, Sort.Direction.DESC),

    /** 좋아요 많은 순. {@code like_count} 내림차순. */
    LIKES("좋아요순", "likeCount", Sort.Direction.DESC, Sort.Direction.DESC),

    /** 제목 오름차순. 한글·영문이 DB 콜레이션 순서로 섞인다. */
    TITLE("제목순", "title", Sort.Direction.ASC, Sort.Direction.ASC);

    /** 두 번째 정렬 키. 항상 곡 번호다. */
    private static final String TIE_BREAK_PROPERTY = "musicId";

    /** 화면에 보여줄 이름. {@code VocalFilter} 와 같은 규칙이다. */
    private final String displayName;

    /** 첫 번째 정렬 키. <b>엔티티 필드명</b>이다 — DB 컬럼명이 아니다. */
    private final String primaryProperty;

    private final Sort.Direction primaryDirection;

    /**
     * 동점일 때 순서를 가르는 두 번째 키의 방향. 첫 키와 같은 방향으로 둔다 —
     * 반대로 두면 '최신순' 의 동점 구간만 뒤집혀 보여 직관과 어긋난다.
     */
    private final Sort.Direction tieBreakDirection;

    /**
     * JPA {@code Pageable} 에 넘길 정렬.
     *
     * <p><b>두 번째 키를 빼면 안 된다.</b> {@code created_at} 은 초 단위라 같은 초에
     * 들어온 곡이 여럿이고, {@code title} 도 중복이 있으며 {@code like_count} 는
     * 동점이 흔하다. 정렬 키가 유일하지 않으면 같은 요청이 페이지마다 다른 순서를
     * 돌려줄 수 있어 <b>무한 스크롤에서 곡이 중복되거나 사라진다.</b> 이 프로젝트는
     * 커서가 아니라 오프셋({@code page}) 페이지네이션이라 그 문제가 바로 드러난다.
     * 곡 번호를 마지막 키로 붙여 순서를 확정한다.
     */
    public Sort toSort() {
        return Sort.by(
            new Sort.Order(primaryDirection, primaryProperty),
            new Sort.Order(tieBreakDirection, TIE_BREAK_PROPERTY));
    }

    /**
     * 와이어 값을 enum 으로 옮긴다.
     *
     * <p>대소문자는 가리지 않는다({@code recent}·{@code RECENT} 둘 다). 앞뒤 공백도
     * 무시한다. 모르는 값이면 {@link Optional#empty()} 를 준다 — 조용히 정렬 없음으로
     * 흘리지 않는다. 흘리면 사용자가 고른 기준과 다른 순서를 보게 된다
     * ({@code vocal} 필터가 모르는 값을 400 으로 되돌리는 것과 같은 이유).
     *
     * <p>빈 값({@code sort=})도 empty 다. 이건 '고르지 않음' 이지 잘못된 값이 아니다.
     */
    public static Optional<MusicSort> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
            .filter(sort -> sort.name().toLowerCase(Locale.ROOT).equals(normalized))
            .findFirst();
    }

    /** 문서·오류 안내에 적을 고를 수 있는 값 목록. */
    public static String allowedNames() {
        return String.join(", ", Arrays.stream(values()).map(MusicSort::name).toList());
    }

}
