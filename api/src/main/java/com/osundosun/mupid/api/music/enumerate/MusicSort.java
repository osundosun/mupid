package com.osundosun.mupid.api.music.enumerate;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.domain.Sort;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 탐색 결과의 정렬 기준.
 *
 * <h2>파라미터가 없으면 '조회순' 이다 (S15P21A204-396 후속)</h2>
 *
 * <b>더 이상 전부 opt-in 이 아니다.</b> {@code sort} 를 보내지 않은 호출은 이제
 * {@link #PLAYS}(조회순)로 정렬된다 — 파라미터 없음의 뜻을
 * {@link com.osundosun.mupid.api.music.request.MusicSortRequest#ABSENT} 가
 * {@link com.osundosun.mupid.api.music.request.MusicSortRequest#defaultSort()} 로
 * 들고 있다. 값을 <b>명시한</b> 호출은 그 축을 그대로 탄다(그 구분이 왜 필요한지는
 * {@code MusicSortRequest} 주석에 있다).
 *
 * <p>기본값을 이 enum 의 상수로 두지 않은 이유가 있다. 이름을 {@code DEFAULT} 로
 * 두면 "기본값" 과 "정렬하지 않음" 이 한 이름에 겹쳐, 파라미터가 없었는지 값이
 * {@code DEFAULT} 였는지를 코드가 구분하지 못하게 된다. 기본값은 '고르지 않음' 에
 * 딸린 해석이므로 {@code MusicSortRequest} 쪽에 있다.
 *
 * <h2>왜 여기 있는 축만 있는가</h2>
 *
 * <b>실제로 값이 채워지는 축</b>만 남겼다. 후보마다 실측한 근거다(개발 DB 기준).
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
 *   <li>{@link #PLAYS} — {@code music.play_count}. {@code PlayCountBatch} 가 매일
 *       ClickHouse 의 {@code PLAY_START} 집계를 옮겨 채운다. 이 배치가 생기기 전까지
 *       이 컬럼은 전 곡 0 이었고, 그래서 이 축을 만들지 않았다(아래 이력).</li>
 * </ul>
 *
 * <h2>이력 — '조회순' 을 만들지 않았던 판단과, 그 전제를 없앤 것</h2>
 *
 * 이 자리에는 원래 <i>"왜 '재생순' 이 없는가 — 가장 중요한 판단"</i> 이라는 절이 있었고,
 * 거기에는 <b>"이 축은 만들지 않는다"</b> 고 적혀 있었다. 지금은 만든다. 그때의 판단이
 * 무엇이었고 무엇이 바뀌었는지를 지우지 않고 남긴다 — 다음 사람이 같은 판단을 다시
 * 하지 않으려면 이력이 필요하다.
 *
 * <p><b>그때(396 본편, S15P21A204-396)의 판단.</b> {@code music.play_count} 는 컬럼은
 * 있지만 <b>그것을 올리는 코드가 저장소 어디에도 없었다.</b> 개발 DB 1077곡이 전부 0 이고
 * 값이 한 종류뿐이라, 이 축으로 정렬하면 '재생수순' 이라고 적어 놓고 실제로는 순서를
 * 가르지 못하는 셈이 된다. 사용자에게는 고른 기준이 조용히 무시된 것처럼 보인다.
 * 게다가 재생수의 실제 원천(ClickHouse {@code listen_events})은 다른 저장소라, 목록 조회가
 * 그걸 함께 보면 <b>요청마다 도는 경로에 두 번째 저장소가 붙는다</b> — ClickHouse 가
 * 죽을 때 곡 목록까지 함께 죽는다. 그래서 축을 만들지 않고, "컬럼을 실제로 올리는 일이
 * 먼저 정해져야 한다" 는 것을 선행 조건으로 남겼다.
 *
 * <p><b>2026-09-20 에 바뀐 것.</b> 그 선행 조건이 채워졌다 — {@code PlayCountBatch} 가
 * 매일 ClickHouse 의 곡별 {@code PLAY_START} 집계를 {@code music.play_count} 로 옮긴다.
 * 값이 카탈로그에 흩어지므로 축이 성립하고, 정렬은 여전히 <b>MySQL 목록 조회 안에서만</b>
 * 끝난다. 두 번째 저장소가 조회 경로에 붙지 않는다는 원래의 성질은 그대로다 —
 * 원천을 <b>미리 옮겨 두는</b> 방식으로 바꾼 것이 이 결정의 요점이다.
 *
 * <p><b>그래서 남은 위험은 하나다.</b> 새 환경처럼 배치가 아직 한 번도 돌지 않아
 * {@code play_count} 가 다시 0 뿐인 상태. 그때도 결과는 결정적이다 — 두 번째 키가
 * 순서를 확정한다(아래 {@code TIE_BREAK_PROPERTY} 참고). 다만 그 순서는 재생수와
 * 무관하므로, 배치를 돌리기 전과 후에 목록 순서가 달라진다. 이건 정상이다.
 */
@Getter
@AllArgsConstructor
public enum MusicSort {

    /** 최신 등록순. {@code created_at} 내림차순. */
    RECENT("최신순", "createdAt", Sort.Direction.DESC, Sort.Direction.DESC),

    /** 좋아요 많은 순. {@code like_count} 내림차순. */
    LIKES("좋아요순", "likeCount", Sort.Direction.DESC, Sort.Direction.DESC),

    /** 제목 오름차순. 한글·영문이 DB 콜레이션 순서로 섞인다. */
    TITLE("제목순", "title", Sort.Direction.ASC, Sort.Direction.ASC),

    /**
     * 조회순. {@code play_count} 내림차순. {@code PlayCountBatch} 가 매일 채운다.
     *
     * <p><b>이 축은 {@link com.osundosun.mupid.api.music.request.MusicSortRequest#ABSENT}
     * 의 기본값이기도 하다</b> — 파라미터가 없으면 이 축으로 정렬된다. 그때는
     * {@code explicitlyRequested} 가 false 라 "고르지 않음" 이라는 사실은 남는다
     * (검색어와 함께 왔을 때 400 으로 되돌리는 판정이 그 값을 본다).
     *
     * <p>표시명이 '재생순' 이 아니라 <b>'조회순'</b> 인 것은 사용자가 화면에서 고르는
     * 말이 그쪽이기 때문이다. 축이 세는 것은 ClickHouse 의 {@code PLAY_START}(재생
     * 시도)라 실체는 재생수다 — 표시명을 정한 자리는 여기 하나뿐이라 둘을 맞춰 둔다.
     */
    PLAYS("조회순", "playCount", Sort.Direction.DESC, Sort.Direction.DESC);

    /**
     * 두 번째 정렬 키. 항상 곡 번호다.
     *
     * <p>{@link #PLAYS} 때문에 이 상수의 존재가 더 중요해졌다. {@code play_count} 는
     * <b>한 번도 재생되지 않은 곡이 대부분인 카탈로그</b>(개발 DB 1069곡 중 재생 기록이
     * 있는 곡은 한 자릿수)에서 동점이 압도적이고, 배치를 아직 안 돌린 환경에서는
     * <b>전부 0</b> 이다. 그때 이 키가 없으면 순서가 DB 실행 계획에 따라 매 요청 달라져
     * 오프셋 페이지네이션에서 곡이 중복되거나 사라진다.
     */
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
