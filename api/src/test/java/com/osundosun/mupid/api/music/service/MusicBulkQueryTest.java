package com.osundosun.mupid.api.music.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.osundosun.mupid.api.common.exception.BusinessException;

/**
 * 곡 일괄 조회의 번호 규칙 (S15P21A204-445).
 *
 * <p>{@code MusicSortParseTest} 와 같은 이유로 DB 없이 돈다 — 확인할 것은 문자열을
 * 번호로 옮기는 규칙뿐이다. 없는 곡·삭제된 곡을 빼는 것은 조립기
 * ({@code MusicSummaryAssembler.byIds})가 이미 다른 경로(좋아요·최근 감상)에서 쓰는 규칙이다.
 */
class MusicBulkQueryTest {

    @Test
    @DisplayName("쉼표 목록과 반복 파라미터를 같은 뜻으로 받고, 보낸 순서를 지킨다")
    void commaAndRepeatedFormsMeanTheSame() {
        assertThat(MusicBulkQuery.parse(List.of("30,10", "20"))).containsExactly(30, 10, 20);
        assertThat(MusicBulkQuery.parse(List.of("30", "10", "20"))).containsExactly(30, 10, 20);
    }

    @Test
    @DisplayName("중복은 처음 자리만 남기고, 빈 값은 무시한다")
    void duplicatesAndBlanks() {
        assertThat(MusicBulkQuery.parse(List.of(" 5 , ,7", "5", ""))).containsExactly(5, 7);
    }

    @Test
    @DisplayName("아무것도 없으면 빈 목록이다 — 400 이 아니다")
    void emptyIsNotAnError() {
        assertThat(MusicBulkQuery.parse(null)).isEmpty();
        assertThat(MusicBulkQuery.parse(List.of())).isEmpty();
        assertThat(MusicBulkQuery.parse(List.of(""))).isEmpty();
    }

    @Test
    @DisplayName("숫자가 아니거나 1 보다 작으면 조용히 버리지 않고 400 이다")
    void invalidIdsAreRejected() {
        assertThatThrownBy(() -> MusicBulkQuery.parse(List.of("1,abc"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> MusicBulkQuery.parse(List.of("0"))).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> MusicBulkQuery.parse(List.of("-3"))).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("상한을 넘으면 잘라서 돌려주지 않고 400 이다. 상한까지는 받는다")
    void sizeLimit() {
        String atLimit = String.join(",", IntStream.rangeClosed(1, MusicBulkQuery.MAX_SIZE).mapToObj(String::valueOf).toList());
        assertThat(MusicBulkQuery.parse(List.of(atLimit))).hasSize(MusicBulkQuery.MAX_SIZE);
        assertThatThrownBy(() -> MusicBulkQuery.parse(List.of(atLimit + "," + (MusicBulkQuery.MAX_SIZE + 1))))
            .isInstanceOf(BusinessException.class);
    }
}
