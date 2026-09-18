package com.osundosun.mupid.api.music.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.music.enumerate.VocalFilter;

/**
 * 보컬 필터를 리포지토리가 받는 boolean 네 개로 옮기는 규칙.
 *
 * <p>DB 도 Spring 컨텍스트도 필요 없다 — 순수한 변환이다. 그래서
 * {@code MusicSearchQueryTest} 와 같이 인프라 없이 돈다
 * (convention/testing.md).
 *
 * <p>여기서 못 박는 것은 <b>"고른 것이 없다"와 "골랐는데 아무것도 안 맞는다"가
 * 다르다</b>는 점이다. 이 구분이 무너지면 '초기화' 를 눌렀을 때 전곡이 나오는 대신
 * 결과가 비거나, 반대로 필터가 조용히 무시된다.
 */
class VocalFilterQueryTest {

    @Test
    @DisplayName("고른 것이 없으면 필터를 걸지 않는다")
    void emptyMeansNoFilter() {
        assertThat(VocalFilterQuery.of(null)).isEqualTo(VocalFilterQuery.NONE);
        assertThat(VocalFilterQuery.of(List.of())).isEqualTo(VocalFilterQuery.NONE);

        // filtered 가 false 라야 쿼리의 첫 항이 참이 되어 예전 경로를 탄다.
        assertThat(VocalFilterQuery.of(List.of()).filtered()).isFalse();
    }

    @Test
    @DisplayName("값마다 자기 자리만 켠다")
    void eachValueSetsItsOwnFlag() {
        VocalFilterQuery female = VocalFilterQuery.of(List.of(VocalFilter.FEMALE));
        assertThat(female.filtered()).isTrue();
        assertThat(female.wantFemale()).isTrue();
        assertThat(female.wantMale()).isFalse();
        assertThat(female.wantInstrumental()).isFalse();

        VocalFilterQuery instrumental = VocalFilterQuery.of(List.of(VocalFilter.INSTRUMENTAL));
        assertThat(instrumental.wantInstrumental()).isTrue();
        assertThat(instrumental.wantFemale()).isFalse();
        assertThat(instrumental.wantMale()).isFalse();
    }

    @Test
    @DisplayName("여러 개를 고르면 각 자리가 모두 켜진다")
    void multipleValuesAreUnion() {
        VocalFilterQuery query =
            VocalFilterQuery.of(List.of(VocalFilter.FEMALE, VocalFilter.MALE, VocalFilter.INSTRUMENTAL));

        assertThat(query.filtered()).isTrue();
        assertThat(query.wantFemale()).isTrue();
        assertThat(query.wantMale()).isTrue();
        assertThat(query.wantInstrumental()).isTrue();
    }

    @Test
    @DisplayName("같은 값을 두 번 보내도 한 번과 같다")
    void duplicatesCollapse() {
        assertThat(VocalFilterQuery.of(List.of(VocalFilter.FEMALE, VocalFilter.FEMALE)))
            .isEqualTo(VocalFilterQuery.of(List.of(VocalFilter.FEMALE)));
    }

}
