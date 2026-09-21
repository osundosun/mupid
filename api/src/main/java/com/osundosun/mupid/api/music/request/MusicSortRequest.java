package com.osundosun.mupid.api.music.request;

import org.springframework.data.domain.Sort;

import com.osundosun.mupid.api.music.enumerate.MusicSort;

/**
 * 곡 목록 조회의 정렬 파라미터를 푼 결과.
 *
 * <h2>파라미터가 없는 호출과 빈 값을 준 호출은 다르다</h2>
 *
 * <b>S15P21A204-396 후속에서 이 파일의 뜻이 하나 바뀌었다.</b> 무엇이 바뀌었는지와
 * 왜 그렇게 했는지를 먼저 적는다.
 *
 * <ul>
 *   <li>{@code sort} 파라미터가 <b>없다</b> → {@link #ABSENT}.
 *       <b>이제 기본 정렬(조회순 · {@code play_count DESC})을 탄다.</b>
 *       이것이 현재의 기본 정렬 계약이다.</li>
 *   <li>{@code sort=}(빈 값) → {@link #EXPLICIT_NONE}. 클라이언트가 <b>"아무 기준도
 *       고르지 않았다" 를 직접 말한 것</b>이라 정렬 절을 붙이지 않는다
 *       ({@code ORDER BY} 없음 → {@code music_id} 오름차순 = 등록순).</li>
 *   <li>값을 줬다 → {@code ORDER BY} 를 명시적으로 붙인다.</li>
 * </ul>
 *
 * <h2>이력 — 왜 이 구분이 필요했고, 무엇이 바뀌었나</h2>
 *
 * <p><b>396 본편까지.</b> 세 경우 중 뒤의 둘이 <b>같은 뜻</b>이었다(둘 다 정렬 없음).
 * 그때는 파라미터 없음이 곧 정렬 없음이었고, 그래서 상수 하나({@code ABSENT})로 충분했다.
 * 이 구분을 둔 이유는 하위 호환이었다 — 예전 클라이언트는 {@code sort} 를 보내지 않으므로
 * 그 호출의 순서가 조용히 바뀌면 안 됐다.
 *
 * <p><b>지금(2026-09-20, 사용자 결정).</b> <i>"파라미터가 없으면 조회순"</i> 이 새 계약이
 * 되면서 그 둘이 <b>갈렸다.</b> 파라미터가 없는 호출은 이제 재생수 내림차순으로 나가고,
 * 예전 순서(등록순 오름차순)를 그대로 받고 싶은 클라이언트는 {@code sort=} 를 보내면 된다.
 * 즉 '빈 값' 이 <b>옛 동작을 부르는 유일한 이름</b>이 되었다 — 그 뜻을 담으려고
 * {@link #EXPLICIT_NONE} 을 따로 두었다.
 *
 * <p><b>이 변경은 공개 계약 변경이다.</b> {@code sort} 를 보내지 않던 기존 호출은
 * 전부 다른 순서를 보게 된다. 정렬 <i>기준</i>을 바꾼 것이지 페이지네이션이나 필터를
 * 건드린 것은 아니지만, 목록형 화면은 순서가 곧 내용이라 영향이 크다. 소비자를 조사한
 * 결과는 MR·작업 보고에 있다(frontend 의 {@code api.music.search} 호출부는 {@code sort}
 * 를 아예 보내지 않는다 — 즉 홈·탐색·아티스트 곡 목록이 <b>모두</b> 새 기본값을 탄다).
 *
 * <h2>명시한 호출은 흔들리지 않는다</h2>
 *
 * <p>{@code explicitlyRequested} 를 지우지 않고 남긴 이유가 있다. 기본값이 이제
 * <i>실제로</i> 정렬을 걸기 때문에, "클라이언트가 골랐는가" 와 "정렬이 걸리는가" 가
 * 더 이상 같은 질문이 아니다. 검색어({@code q})와 함께 온 정렬을 400 으로 되돌리는
 * 판정({@code MusicServiceImpl})이 바로 이 값을 본다 — 기본값 때문에 그 판정이
 * 오작동하면 평범한 검색이 전부 400 이 된다.
 *
 * <p><b>그래서 이 값의 뜻은 "정렬 기준을 이름으로 지정했는가" 다.</b> 빈 값은
 * {@code false} 다 — '고르지 않음' 을 고른 것이지 기준을 고른 것이 아니기 때문이다.
 * 여기서 {@code true} 로 두면 {@code q} 와 함께 온 {@code sort=} 가 400 이 되어,
 * <b>396 본편이 정한 동작이 조용히 뒤집힌다</b>(그 동작에는 테스트가 붙어 있다 —
 * {@code ExploreSortTest#blankSortWithKeywordIsAccepted}).
 *
 * <p>그리고 값을 명시한 호출은 <b>앞으로 기본값이 또 바뀌어도 흔들리지 않는다.</b>
 * 기본값은 {@link #ABSENT} 한 곳에만 있고, 명시한 호출은 자기 축을 그대로 탄다.
 *
 * <h2>기본값이 왜 enum 상수가 아닌가</h2>
 *
 * <p>{@link MusicSort} 에 {@code DEFAULT} 상수를 두지 않았다. 두면 "파라미터 없음" 과
 * "값이 {@code DEFAULT} 였음" 이 같은 이름이 되어, 파라미터가 실제로 왔는지를 코드가
 * 구분하지 못한다. 기본값은 '고르지 않음' 에 <b>딸린 해석</b>이라 여기 있다.
 *
 * @param sort                정렬에 쓸 기준. <b>null 이면 정렬 절을 붙이지 않는다</b>
 * @param explicitlyRequested 클라이언트가 정렬 기준을 <b>이름으로</b> 지정했는가.
 *                            파라미터 없음도 빈 값도 false 다
 */
public record MusicSortRequest(MusicSort sort, boolean explicitlyRequested) {

    /**
     * 파라미터가 <b>없었을</b> 때의 값. <b>기본 정렬(조회순)을 탄다.</b>
     *
     * <p>396 본편에서 이 상수는 "정렬하지 않는다(= 예전과 완전히 같다)" 는 뜻이었다.
     * 지금은 반대다 — 재생수 내림차순으로 정렬한다. 이 한 줄이 기본 정렬의 <b>정본</b>이고,
     * 여기가 아니라 {@link MusicSort} 나 컨트롤러에 기본값을 적으면 두 곳이 갈린다.
     */
    public static final MusicSortRequest ABSENT = new MusicSortRequest(MusicSort.PLAYS, false);

    /**
     * {@code sort=}(빈 값) — 클라이언트가 <b>아무 기준도 고르지 않았음</b>을 직접 말한 것.
     *
     * <p>파라미터가 없었던 {@link #ABSENT} 와 달리 기본값을 타지 않는다. 정렬 절이 붙지
     * 않으므로 결과는 예전과 같은 <b>등록순 오름차순</b>이다 — 이 티켓이 바꾼 기본 정렬을
     * 원하지 않는 호출의 탈출구다.
     *
     * <p>{@code explicitlyRequested} 는 false 다. '고르지 않음' 을 고른 것이지 <b>기준을
     * 고른 것이 아니기</b> 때문이다. 이 값을 true 로 두면 검색어({@code q})와 함께 온
     * {@code sort=} 가 400 이 되어, 396 본편이 정한 동작이 조용히 뒤집힌다.
     */
    public static final MusicSortRequest EXPLICIT_NONE = new MusicSortRequest(null, false);

    /** 이 호출에 실제로 적용되는 정렬. 정렬 절을 붙이지 않으면 {@link Sort#unsorted()}. */
    public Sort toSort() {
        return sort == null ? Sort.unsorted() : sort.toSort();
    }

    /**
     * 기본 정렬에 쓰는 축. 파라미터가 없었을 때 무엇으로 줄 세우는지를 한 곳에서 답한다.
     *
     * <p>{@link #ABSENT} 를 만든 자리에서 값을 그대로 들고 있으므로, 기본값을 바꾸려면
     * {@link #ABSENT} 하나만 고치면 된다.
     */
    public static MusicSort defaultSort() {
        return ABSENT.sort;
    }

    /** 파싱된 기준을 담는다. null 이면 {@link #ABSENT} 다. */
    public static MusicSortRequest of(MusicSort sort) {
        return sort == null ? ABSENT : new MusicSortRequest(sort, true);
    }

    /**
     * null 을 {@link #ABSENT} 로 바꾼다.
     *
     * <p>정렬 파라미터를 모르는 호출부(옛 시그니처·테스트)도 같은 코드를 타게 하려는
     * 것이다. 그런 호출도 이제 <b>기본 정렬</b>을 탄다 — "정렬을 모른다" 와 "정렬하지
     * 않는다" 는 다르고, HTTP 파라미터가 없는 것도 앞쪽이다.
     */
    public static MusicSortRequest orDefault(MusicSortRequest request) {
        return request == null ? ABSENT : request;
    }

}
