package com.osundosun.mupid.api.vector.response;

import java.util.List;

/**
 * 곡 벡터 등록 결과. 응답이 나갔다면 Qdrant 에 이미 쓰였다({@code wait=true}).
 *
 * @param musicId         곡 번호 (payload {@code track_id})
 * @param analysisVersion 쓴 분석 버전 (MySQL 과 같다)
 * @param collection      쓴 컬렉션
 * @param points          쓴 포인트(구간) 수. 이 곡의 다른 옛 포인트는 지웠다
 * @param vectors         쓴 named vector 이름들
 * @param clapSections    {@code clap} 이 있는 구간 수
 */
public record MusicVectorsResponse(
    int musicId,
    String analysisVersion,
    String collection,
    int points,
    List<String> vectors,
    int clapSections
) {
}
