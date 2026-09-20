package com.osundosun.mupid.api.music.request;

import com.osundosun.mupid.api.music.enumerate.MusicSort;

/**
 * 곡 목록 조회의 정렬 파라미터를 푼 결과. <b>null 일 수 있다</b> — 그게 '정렬하지 않음' 이다.
 *
 * <h2>왜 enum 하나로는 부족한가 — 하위 호환이 걸려 있다</h2>
 *
 * {@code sort} 파라미터가 <b>없는</b> 호출과 값을 <b>명시한</b> 호출은 뜻이 다르다.
 *
 * <ul>
 *   <li>파라미터가 없다 → {@link #ABSENT}. 지금까지 나가던 쿼리와 <b>완전히 같아야</b>
 *       하므로 정렬 절을 아예 붙이지 않는다(현행 {@code ORDER BY} 없음 →
 *       {@code music_id} 오름차순).</li>
 *   <li>값을 줬다 → {@code ORDER BY} 를 명시적으로 붙인다. 이 호출은 앞으로 기본값이
 *       바뀌어도 흔들리지 않는다.</li>
 * </ul>
 *
 * <p>이 구분이 없으면 두 가지가 동시에 무너진다. (1) 예전 클라이언트의 순서가 조용히
 * 바뀌거나 — 기본값을 붙이는 순간 그 호출도 새 정렬을 타게 된다 — (2) 반대로 정렬을
 * 고른 사람에게 정렬이 안 걸린다.
 *
 * <p>{@code sort} 가 null 인 상태(=정렬 없음)를 표현해야 해서 {@link MusicSort} 에
 * 기본값 상수를 두지 않았다. 그 상수를 두면 "파라미터 없음 = 그 상수" 가 되어, 실제
 * 동작(정렬 없음 → 등록순 오름차순)과 어긋나는 이름이 붙는다. 특히 '최신순' 을 기본으로
 * 적어 두면 현행과 <b>정반대 방향</b>이라 이름이 거짓말이 된다.
 *
 * @param sort                쓸 정렬 기준. null 이면 정렬하지 않는다
 * @param explicitlyRequested 클라이언트가 기준을 명시했는가
 */
public record MusicSortRequest(MusicSort sort, boolean explicitlyRequested) {

    /**
     * 파라미터가 없을 때의 값. 정렬하지 않고 현행 쿼리를 그대로 탄다.
     *
     * <p>이것이 이 티켓이 정한 <b>기본 정렬</b>이다 — "기존과 동일하게 유지".
     */
    public static final MusicSortRequest ABSENT = new MusicSortRequest(null, false);

    /** 파싱된 기준을 담는다. null 이면 {@link #ABSENT} 다. */
    public static MusicSortRequest of(MusicSort sort) {
        return sort == null ? ABSENT : new MusicSortRequest(sort, true);
    }

    /**
     * null 을 {@link #ABSENT} 로 바꾼다.
     *
     * <p>정렬 파라미터를 모르는 호출부(옛 시그니처·테스트)도 같은 코드를 타게 하려는
     * 것이다.
     */
    public static MusicSortRequest orDefault(MusicSortRequest request) {
        return request == null ? ABSENT : request;
    }

    /** 실제로 {@code ORDER BY} 를 붙여야 하는가. */
    public boolean isRequested() {
        return explicitlyRequested && sort != null;
    }

}
