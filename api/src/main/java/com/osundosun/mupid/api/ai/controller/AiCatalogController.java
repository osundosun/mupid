package com.osundosun.mupid.api.ai.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.ai.response.AiCatalogPageResponse;
import com.osundosun.mupid.api.ai.service.AiCatalogService;
import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.error.GlobalErrorCode;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.common.response.BaseResponse;

import lombok.RequiredArgsConstructor;

/**
 * 추천 서버(`ai_server`)가 읽는 곡·구간 카탈로그. <b>`ROLE_SYSTEM` 전용이다.</b>
 *
 * <h2>왜 생겼나</h2>
 *
 * 추천 색인이 곡명·장르·무드·보컬·악기·순간 메타데이터를 <b>제 안에 한 벌 더</b>
 * 들고 있었다. 그러면 MySQL 에서 곡을 지우거나 장르를 고쳐도 추천은 옛 값으로 계속
 * 돌고, 색인을 다시 굽기 전까지 아무 오류도 나지 않는다. 기준 데이터는 MySQL 하나이고
 * 추천 서버는 여기로만 읽는다.
 *
 * <p>추천 서버가 MySQL 에 직접 붙지 않는 이유는 같은 표를 두 가지 권한 모델로 읽게
 * 되기 때문이다. 여기로 오면 삭제 여부·코드표 해석이 서버 한 곳에서만 정해진다.
 *
 * <h2>화면 계약이 아니다</h2>
 *
 * 외부 API·FE 가 쓰는 경로는 하나도 바뀌지 않는다. 이 경로는 새로 생긴 것이고
 * `ROLE_SYSTEM` 이 아니면 403 이다.
 *
 * <h2>싣는 것</h2>
 *
 * 추천 계산이 실제로 읽는 필드만 싣는다 — 제목·장르·무드·보컬 유무·보컬 성별,
 * 그리고 구간마다 `external_id`·구간 종류·시각·악기·순간 무드. 앨범·아티스트·표지·
 * 발매일·재생수는 화면이 쓰는 값이라 넣지 않는다.
 */
@RestController
@RequestMapping("/api/v1/internal/ai")
@RequiredArgsConstructor
@SystemRole
public class AiCatalogController {

    /**
     * 한 번에 받을 수 있는 곡 수의 상한.
     *
     * <p>상한을 두는 것은 응답 크기 때문이다. 곡 하나에 구간이 열 개 가까이 붙고
     * 구간마다 악기 목록이 달려서, 곡 수가 늘면 응답이 선형보다 빨리 커진다.
     * 상한 없이 열어 두면 한 번의 호출로 카탈로그 전체를 힙에 올리게 된다.
     */
    private static final int MAX_SIZE = 500;
    private static final int DEFAULT_SIZE = 200;

    private final AiCatalogService aiCatalogService;

    /**
     * 카탈로그 한 페이지.
     *
     * <p>{@code size} 가 상한을 넘으면 <b>조용히 깎지 않고 400 으로 거절한다.</b>
     * 깎으면 부르는 쪽이 1000곡을 요청하고 500곡을 받고도 그것이 전부인 줄 알아,
     * 카탈로그가 절반만 실린 채 추천이 돈다 — 오류가 나지 않아 며칠 뒤에나 드러난다.
     */
    @GetMapping("/catalog")
    public ResponseEntity<BaseResponse<AiCatalogPageResponse>> getCatalog(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size
    ) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new BusinessException(GlobalErrorCode.INVALID_REQUEST_BODY);
        }
        return ResponseEntity.ok(BaseResponse.of(aiCatalogService.getCatalogPage(page, size)));
    }

}
