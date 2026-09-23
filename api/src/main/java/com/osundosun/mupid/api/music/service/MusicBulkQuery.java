package com.osundosun.mupid.api.music.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.osundosun.mupid.api.common.error.GlobalErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;

/**
 * 곡 일괄 조회({@code GET /api/v1/music/bulk})로 온 곡 번호를 푼다 (S15P21A204-445).
 *
 * <h2>왜 따로 두는가</h2>
 *
 * 화면은 곡 번호만 들고 있는 목록(좋아요·큐·플레이리스트)을 곡 정보로 바꿔야 그린다.
 * 한 곡씩 {@code GET /api/v1/music/{id}} 로 받으면 곡 수만큼 요청이 나가서 한 번에 받게
 * 했다. 푸는 규칙은 DB 없이 시험할 수 있게 여기 모았다.
 *
 * <h2>규칙</h2>
 *
 * <ul>
 *   <li>반복 파라미터({@code ids=1&ids=2})와 쉼표 목록({@code ids=1,2})을 같은 뜻으로 받는다.
 *       보컬 필터({@code MusicQueryController.parseVocalFilters})와 같다.</li>
 *   <li>빈 값은 무시한다. 아무것도 없으면 빈 목록이다 — 400 이 아니다.</li>
 *   <li>숫자가 아니거나 1 보다 작으면 400 이다. 조용히 버리면 화면은 그 곡이 지워진
 *       것으로 읽는다.</li>
 *   <li>중복은 처음 나온 자리만 남긴다. 순서는 보낸 그대로다.</li>
 *   <li>{@value #MAX_SIZE} 곡을 넘으면 400 이다. 잘라서 돌려주면 화면은 뒤쪽 곡이
 *       없는 곡인지 잘린 곡인지 알 수 없다. 페이지 크기 상한과 같은 값이다.</li>
 * </ul>
 */
public final class MusicBulkQuery {

    public static final int MAX_SIZE = MusicServiceImpl.MAX_PAGE_SIZE;

    private MusicBulkQuery() {
    }

    public static List<Integer> parse(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        Set<Integer> ids = new LinkedHashSet<>();
        for (String value : raw) {
            if (value == null) continue;
            for (String part : value.split(",")) {
                String token = part.trim();
                if (token.isEmpty()) continue;
                ids.add(toMusicId(token));
            }
        }
        if (ids.size() > MAX_SIZE) {
            throw new BusinessException(GlobalErrorCode.INVALID_REQUEST_BODY);
        }
        return List.copyOf(ids);
    }

    private static int toMusicId(String token) {
        try {
            int id = Integer.parseInt(token);
            if (id < 1) throw new BusinessException(GlobalErrorCode.INVALID_REQUEST_BODY);
            return id;
        } catch (NumberFormatException e) {
            throw new BusinessException(GlobalErrorCode.INVALID_REQUEST_BODY);
        }
    }
}
