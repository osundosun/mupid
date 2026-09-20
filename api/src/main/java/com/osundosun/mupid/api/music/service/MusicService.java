package com.osundosun.mupid.api.music.service;

import java.util.Optional;

import java.util.Collection;
import java.util.List;

import com.osundosun.mupid.api.music.dto.MusicSummary;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.enumerate.VocalFilter;
import com.osundosun.mupid.api.music.request.MusicSortRequest;
import com.osundosun.mupid.api.music.response.MusicAnalysisResponse;
import com.osundosun.mupid.api.music.response.MusicPageResponse;
import com.osundosun.mupid.api.music.request.MusicAddRequest;
import com.osundosun.mupid.api.music.request.MusicUpdateRequest;

public interface MusicService {

    int addMusic(MusicAddRequest request);

    Optional<Music> getMusic(int musicId);

    /**
     * 곡을 찾되 <b>왜 없는지</b>까지 가른다. 없으면 `MUSIC_NOT_FOUND`(404),
     * 삭제됐으면 `MUSIC_GONE`(410).
     *
     * <p>곡을 참조하는 쓰기·조회 경로가 전부 이걸 쓴다. `getMusic` 은 둘 다
     * `Optional.empty()` 라 구분할 수 없어서, 그대로 쓰면 삭제된 곡이 "없는 곡" 으로
     * 보고된다. 한 곳(`MusicServiceImpl.requireAlive`)에 판정을 모아 두는 이유는
     * 서비스마다 베끼면 한쪽만 고쳐졌을 때 조용히 갈리기 때문이다.
     */
    Music requireAlive(int musicId);

    /**
     * 곡 목록. 검색어가 비면 전체다.
     * 화면이 어떤 곡을 재생할지 고르는 유일한 경로다.
     *
     * @param vocalFilters 탐색 화면의 보컬 필터. 비우면 걸지 않는다(하위 호환)
     */
    MusicPageResponse searchMusic(
        String keyword, Collection<VocalFilter> vocalFilters, int page, int size);

    MusicPageResponse searchMusic(
        String keyword,
        Collection<VocalFilter> vocalFilters,
        Collection<String> moodCodeNames,
        Collection<String> genreCodeNames,
        int page,
        int size);

    /**
     * 위와 같되 <b>정렬 기준을 함께 받는다.</b>
     *
     * <p>정렬을 붙이면서 시그니처를 늘리지 않고 고친 이유는, 기존 호출부(테스트 포함)가
     * "정렬을 모르는 호출" 이라는 사실 자체를 그대로 보존하기 위해서다. 그쪽은
     * {@code sortRequest} 가 null 로 들어와 {@link MusicSortRequest#ABSENT} 를 탄다.
     *
     * <p><b>{@code null} 은 이제 '정렬 없음' 이 아니라 '기본 정렬' 이다</b>
     * (S15P21A204-396 후속 — 기본값이 '조회순' 으로 바뀌었다). 예전 순서(등록순
     * 오름차순)를 그대로 받고 싶은 호출부는 {@link MusicSortRequest#EXPLICIT_NONE} 을
     * 넘겨 {@code ORDER BY} 를 붙이지 않게 한다.
     *
     * @param sortRequest 정렬 기준. null 이면 '파라미터 없음' 과 같아 기본 정렬을 탄다
     */
    MusicPageResponse searchMusic(
        String keyword,
        Collection<VocalFilter> vocalFilters,
        Collection<String> moodCodeNames,
        Collection<String> genreCodeNames,
        MusicSortRequest sortRequest,
        int page,
        int size);

    /**
     * 필터 없는 곡 목록. {@code searchMusic(keyword, null, page, size)} 와 같다.
     *
     * 남겨 두는 이유: 보컬 필터를 모르는 호출부(추천·관리자 등)가 있고, 테스트도
     * 이 계약이 "예전과 완전히 같다" 는 것을 이 시그니처로 증명한다.
     */
    default MusicPageResponse searchMusic(String keyword, int page, int size) {
        return searchMusic(keyword, null, page, size);
    }

    /** 곡 상세. 목록과 같은 표현을 쓴다. */
    MusicSummary getMusicSummary(int musicId);

    /**
     * 곡 분석 결과. 분석 전이면 `analyzed = false` 로 온다 — 404 가 아니다.
     */
    MusicAnalysisResponse getAnalysis(int musicId);

    boolean isExists(int musicId);

    void updateMusic(int musicId, MusicUpdateRequest request);

    void deleteMusic(int musicId);

    /**
     * 한 곡의 누적 재생수를 ClickHouse 집계값으로 덮어쓴다. {@code PlayCountBatch} 전용이다.
     *
     * <h2>왜 서비스에 있는가</h2>
     *
     * <p><b>트랜잭션 경계를 프록시 너머에 두기 위해서다.</b> 배치가 곡마다 이 메서드를
     * 부르는데, 같은 클래스 안의 메서드를 {@code this} 로 부르면 스프링 프록시를 타지 않아
     * {@code REQUIRES_NEW} 가 조용히 무시된다. 그러면 곡 전체가 한 트랜잭션으로 묶여
     * 마지막 한 곡의 실패가 앞의 수천 곡 갱신을 함께 되돌린다 — "한 건 실패해도 다음으로
     * 넘어간다" 가 거짓이 된다. 그래서 경계를 여기 둔다.
     *
     * <h2>왜 반환값이 boolean 인가</h2>
     *
     * <p>갱신된 행이 0 이면 <b>그런 곡이 없다</b>는 뜻이다. 곡이 지워져도 재생 로그에는
     * 남으므로 이 경우는 정상이고, 배치가 에러가 아니라 별도 카운터로 센다.
     *
     * @param plays ClickHouse 집계값. 음수는 0 으로, {@code int} 를 넘으면 상한으로 자른다
     * @return 실제로 갱신된 곡이 있었는가
     */
    boolean updatePlayCount(int musicId, long plays);

}
