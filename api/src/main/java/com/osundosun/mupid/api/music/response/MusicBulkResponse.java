package com.osundosun.mupid.api.music.response;

import java.util.List;

import com.osundosun.mupid.api.music.dto.MusicSummary;

/**
 * 곡 일괄 조회 결과 (S15P21A204-445).
 *
 * <p>{@code items} 는 <b>보낸 번호 순서</b>를 지킨다. 없거나 삭제된 곡은 빠지므로
 * 보낸 것보다 짧을 수 있다 — 화면은 빠진 번호를 "없는 곡" 으로 읽는다.
 * 곡 모양은 목록·검색과 같은 {@link MusicSummary} 다.
 */
public record MusicBulkResponse(List<MusicSummary> items) {
}
