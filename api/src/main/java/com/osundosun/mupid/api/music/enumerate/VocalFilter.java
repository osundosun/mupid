package com.osundosun.mupid.api.music.enumerate;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 탐색 화면의 '보컬·악기' 필터 값.
 *
 * <h2>왜 {@link VocalGender} 를 그대로 쓰지 않는가</h2>
 *
 * 화면이 고르는 세 값 중 '인스트루멘탈' 은 <b>성별이 아니라 보컬의 유무</b>다.
 * {@code VocalGender}({@code M}·{@code F}·{@code ETC})는 보컬이 <b>있다는 전제</b>
 * 위의 성별 축이라 여기에 '없음' 을 끼워 넣을 자리가 없다. 게다가 그 값은 DB 의
 * {@code music_analysis.vocal_gender ENUM('M','F','ETC')} 로 이미 저장돼 있어
 * 값을 늘리려면 스키마를 바꿔야 한다.
 *
 * 그래서 <b>요청 쪽 어휘를 따로 둔다.</b> 세 값은 {@code music_analysis} 의 두 컬럼에
 * 이렇게 대응한다.
 *
 * <ul>
 *   <li>{@link #FEMALE} → {@code has_vocal = true AND vocal_gender = 'F'}</li>
 *   <li>{@link #MALE} → {@code has_vocal = true AND vocal_gender = 'M'}</li>
 *   <li>{@link #INSTRUMENTAL} → {@code has_vocal = false}</li>
 * </ul>
 *
 * <h2>ETC 는 화면에 없다</h2>
 *
 * {@code has_vocal = true} 이면서 성별이 {@code ETC} 인 곡(판정하지 못한 보컬)은
 * <b>세 값 어디에도 걸리지 않는다.</b> 화면에 대응하는 항목이 없기 때문이다 —
 * 억지로 '남성' 이나 '여성' 에 넣으면 사용자가 고르지 않은 것을 보여 주게 된다.
 * 필터가 없을 때는 다른 곡과 똑같이 목록에 나온다.
 *
 * <h2>여러 개 고르면 합집합이다</h2>
 *
 * '여성 보컬' 과 '인스트루멘탈' 을 같이 고르면 둘 다 나온다(OR). 같은 축의 값을
 * 좁히는 교집합(AND)이 아니다 — 한 곡이 여성이면서 동시에 인스트루멘탈일 수 없다.
 */
@Getter
@AllArgsConstructor
public enum VocalFilter {

    FEMALE("여성 보컬"),
    MALE("남성 보컬"),
    INSTRUMENTAL("인스트루멘탈");

    private final String displayName;

}
