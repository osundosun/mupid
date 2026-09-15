package com.osundosun.mupid.api.report.dto;

import java.util.Map;
import java.util.Set;

import com.osundosun.mupid.api.analytics.dto.DailyPlaySummary;

/**
 * `MubtiScorer` 가 판정에 쓰는 값 전부. 여기 없는 것은 판정에 쓰이지 않는다.
 *
 * 스코어러가 리포지토리를 직접 부르지 않게 하려고 입력을 한 덩어리로 모았다. 그래야
 * 판정 규칙을 DB 없이 단위 테스트할 수 있다 — 기준(계수·임계)을 자주 만질 코드라
 * 그게 중요하다.
 */
public record MubtiInput(

    /** 곡 번호 → 그날 청취 ms. MySQL 에 없는 곡은 이미 걸러진 뒤다. */
    Map<Integer, Long> listeningMsByMusic,

    /** 곡 번호 → 아티스트 번호. B축의 집중도를 여기서 만든다. */
    Map<Integer, Integer> artistByMusic,

    /**
     * 곡 번호 → 보컬 유무. `music_analysis` 행이 없는 곡은 **키 자체가 없다.**
     * `false` 와 "모른다" 를 섞으면 분석이 안 된 카탈로그에서 모두가 멜로디파가 된다.
     */
    Map<Integer, Boolean> hasVocalByMusic,

    /** 곡 번호 → 카탈로그 안에서의 재생수 백분위(0~1). */
    Map<Integer, Double> popularityPercentileByMusic,

    /** 그날 기준 90일 이내에 발매된 곡. */
    Set<Integer> freshMusicIds,

    /** 카탈로그 전체에서 보컬곡이 차지하는 비중(0~1). A축의 기준선이다. */
    double catalogVocalShare,

    DailyPlaySummary playSummary,

    /**
     * 곡 번호 → 그날 그 곡을 튼 횟수(`PLAY_START`). <b>스킵으로 끝난 재생도 들어 있다.</b>
     *
     * `listeningMsByMusic` 과 키 집합이 다르다. 5초 하트비트 전에 넘긴 곡은 청취 시간이
     * 0 이라 저쪽에는 없고 여기에만 있다. D축(국밥/개척)이 "빠르게 넘기며 디깅하는"
     * 행동을 보려면 이쪽을 봐야 한다.
     */
    Map<Integer, Long> playsByMusic,

    /** 그날 이전 며칠 안에 들어본 곡. D축이 "처음 만지는 곡" 을 가려내는 데 쓴다. */
    Set<Integer> heardBeforeMusicIds,

    /**
     * 보관함에 넣어 둔 곡 — 좋아요 + 플레이리스트 수록곡.
     *
     * 국밥형의 정의가 "검증된 최애곡을 반복해 듣는다" 이므로, 최근에 들었는지와 별개로
     * <b>직접 담아 둔 곡</b>은 익숙한 곡으로 본다.
     */
    Set<Integer> libraryMusicIds,

    /**
     * 직전 판정 네 글자(`VTFC`). 있으면 이력 현상(hysteresis)이 걸려 점수가 0 근처에서
     * 깜빡이지 않는다. 없으면(그날 첫 조회) 점수 부호를 그대로 따른다.
     */
    String previousCode

) {

}
