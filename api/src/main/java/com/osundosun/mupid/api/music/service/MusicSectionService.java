package com.osundosun.mupid.api.music.service;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.request.MusicSectionBulkRequest;
import com.osundosun.mupid.api.music.response.MusicSectionBulkResponse;

public interface MusicSectionService {

    /**
     * 한 곡의 구간 분석 결과를 통째로 갈아 끼우고, 발급된 번호를 돌려준다.
     */
    MusicSectionBulkResponse replaceSections(int musicId, MusicSectionBulkRequest request);

    /**
     * AI 문자열 구간 ID → 구간 행. **추천 응답을 번역할 때 쓴다.**
     *
     * 추천 서버는 숫자 ID 를 모르고 `external_id` 만 돌려준다. 곡 번호·시간·구간 종류는
     * 전부 여기서 나온다. 없는 `external_id` 는 결과에 담기지 않는다 — 부르는 쪽이
     * 세어서 알린다.
     */
    Map<String, MusicSectionAnalysis> findByExternalIds(Collection<String> externalIds);

    /**
     * 곡 번호 → 그 곡의 AI 문자열 ID. **추천 요청을 만들 때 쓴다.**
     *
     * 구간 UID 의 `#` 앞이 곧 곡 ID 다(Q3 — MySQL 에 곡 문자열 ID 를 따로 두지 않는다).
     * 구간 분석이 없는 곡이면 비어 있다.
     */
    Optional<String> trackExternalIdOf(int musicId);

    /**
     * 구간 번호 → 그 구간의 AI 문자열 ID. 추천 요청에 기준 구간을 실을 때 쓴다.
     */
    Optional<String> externalIdOfSection(int musicSectionAnalysisId);

    /**
     * 그 구간이 그 곡의 것인가. 아니면 비어 있다.
     *
     * 남의 곡 구간 번호로 물어보면 404 여야 한다 — 통과시키면 URL 의 곡과 결과가
     * 어긋난 채 화면이 그려진다.
     */
    Optional<MusicSectionAnalysis> findSectionOfMusic(int musicId, int musicSectionAnalysisId);

}
