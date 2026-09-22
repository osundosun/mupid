package com.osundosun.mupid.api.ai.response;

import java.util.List;

/**
 * 곡 하나와 그 구간들. <b>{@code ROLE_SYSTEM} 전용이다.</b>
 *
 * <p>추천 서버가 제 색인 안에 두고 있던 곡명·장르·무드·보컬·악기 사본을 없애고
 * 이 응답을 정본으로 쓰기 위한 계약이다. 그래서 <b>추천이 실제로 읽는 필드만</b>
 * 싣는다 — 앨범·아티스트·표지·발매일·재생수는 화면이 쓰는 값이고 추천 계산에
 * 들어가지 않으므로 여기 없다.
 *
 * <p>구간을 곡 밑에 넣고 곡 단위로 페이지를 나눈다. 구간을 평평하게 펴면 제목·장르·
 * 보컬이 구간 수만큼 되풀이되고, 한 곡의 구간이 페이지 경계에 걸려 <b>반쪽짜리
 * 곡</b>이 생긴다 — 받는 쪽이 그 상태를 정상과 구분할 방법이 없다.
 *
 * <p>{@code hasVocal} 이 {@code null} 이면 "모른다" 다. 연주곡({@code false})과
 * 다르다.
 */
public record AiCatalogTrackResponse(
    Integer musicId,
    String title,
    Boolean hasVocal,
    String vocalGender,
    List<String> genres,
    List<String> moods,
    List<AiCatalogSectionResponse> sections
) {
}
