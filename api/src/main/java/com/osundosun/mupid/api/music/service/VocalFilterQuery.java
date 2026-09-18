package com.osundosun.mupid.api.music.service;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

import com.osundosun.mupid.api.music.enumerate.VocalFilter;

/**
 * 보컬 필터를 리포지토리가 받는 네 개의 boolean 으로 옮긴 값.
 *
 * <h2>왜 boolean 네 개인가</h2>
 *
 * 네이티브 쿼리(`searchIdsFullText`·`searchIdsByPrefix`)에 JPQL 처럼 컬렉션을 넘길
 * 수 없다. 파라미터는 스칼라여야 해서 {@code IN (:filters)} 대신 <b>값마다 참·거짓
 * 하나</b>를 둔다. 네 값뿐이라 이 편이 오히려 읽기 쉽다.
 *
 * <h2>{@code filtered} 를 따로 두는 이유</h2>
 *
 * "고른 것이 없다" 와 "고른 것이 있는데 그중 아무것도 안 맞았다" 는 완전히 다르다.
 * 전자는 <b>필터를 걸지 않은 것</b>이고(전곡), 후자는 결과 0 이다. 사용자가 '초기화'
 * 를 눌렀을 때가 전자다. 이 값을 쿼리에 그대로 넘겨 한 쿼리로 둘 다 처리한다.
 */
public record VocalFilterQuery(
    boolean filtered,
    boolean wantFemale,
    boolean wantMale,
    boolean wantMixed,
    boolean wantInstrumental
) {

    /** 고른 것이 없으면 필터 없는 조회다. */
    public static final VocalFilterQuery NONE =
        new VocalFilterQuery(false, false, false, false, false);

    /**
     * 화면이 보낸 값들을 boolean 네 개로 옮긴다.
     *
     * 중복은 알아서 사라진다({@link EnumSet}). 모르는 값은 컨트롤러가 이미 걸러낸다.
     */
    public static VocalFilterQuery of(Collection<VocalFilter> filters) {
        if (filters == null || filters.isEmpty()) {
            return NONE;
        }
        Set<VocalFilter> chosen = EnumSet.copyOf(filters);
        return new VocalFilterQuery(
            true,
            chosen.contains(VocalFilter.FEMALE),
            chosen.contains(VocalFilter.MALE),
            chosen.contains(VocalFilter.MIXED),
            chosen.contains(VocalFilter.INSTRUMENTAL));
    }

}
