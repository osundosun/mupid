package com.osundosun.mupid.api.recommendation.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import java.util.Map;
import java.util.OptionalInt;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.recommendation.service.DailyRecommendationBatch;
import com.osundosun.mupid.api.recommendation.response.DailyRecommendationResponse;
import com.osundosun.mupid.api.recommendation.service.DailyRecommendationService;
import com.osundosun.mupid.api.user.entity.User;

import lombok.RequiredArgsConstructor;

/**
 * 오늘의 추천. 홈 화면의 "오늘의 Mupid Mix" 가 쓴다.
 *
 * 추천을 **만드는** 경로는 없다. 파이프라인이 표를 채우면 여기서 읽힌다.
 */
@RestController
@RequestMapping("/api/v1/recommendation")
@RequiredArgsConstructor
public class DailyRecommendationController {

    private final DailyRecommendationService recommendationService;
    private final DailyRecommendationBatch recommendationBatch;

    /**
     * @param date 기준일(`YYYY-MM-DD`). 비우면 오늘. 그날 추천이 없으면 가장
     *             최근 추천이 있는 날로 되돌아가고, 응답의 `date` 가 그 날짜다.
     *             저장된 추천이 하나도 없으면 온보딩 취향으로 그 자리에서 고른
     *             묶음을 돌려준다 — 저장하지 않으므로 `recommendation_id` 는 0 이다
     */
    @GetMapping("/daily")
    public ResponseEntity<BaseResponse<DailyRecommendationResponse>> getDaily(
        Authentication auth,
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            recommendationService.getDaily((User)auth.getPrincipal(), date)
        ));
    }

    /**
     * 오늘치를 <b>시작만</b> 시킨다. 데이터 배치(`run_batch.sh`)가 분석·색인을 마친 뒤 부른다.
     *
     * <p>생성은 서버 안에서 따로 돌고 이 요청은 바로 202 로 끝난다 — 회원 수만큼 추천 서버를
     * 부르는 동안 연결을 붙잡지 않는다. 이미 누가 돌리고 있으면 `started=false` 이고 아무것도
     * 하지 않으므로, 배치가 다시 보내도 같은 날이 두 번 돌지 않는다. 결과는 로그에 남는다.
     */
    @SystemRole
    @PostMapping("/daily/start")
    public ResponseEntity<BaseResponse<Map<String, Object>>> startDaily() {
        boolean started = recommendationBatch.startToday();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(BaseResponse.of(
            started ? "오늘의 모먼트 생성을 시작했습니다." : "오늘의 모먼트 생성이 이미 진행 중입니다.",
            Map.of("date", LocalDate.now().toString(), "started", started)));
    }

    /**
     * 그날치를 지금 만든다. **운영용이다.**
     *
     * <p>배치는 매일 정오에 돌지만, 그날 추천 서버가 흔들려 실패하면 다음 날까지 아무도
     * 오늘의 모먼트를 못 본다. 다시 돌릴 길이 없으면 배포를 해야 한다.
     *
     * @param date  기준일. 비우면 오늘
     * @param force 이미 만들어 둔 사용자도 다시 만든다
     */
    @SystemRole
    @PostMapping("/daily/generate")
    public ResponseEntity<BaseResponse<Map<String, Object>>> generateDaily(
        @RequestParam(required = false)
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
        @RequestParam(defaultValue = "false") boolean force
    ) {
        LocalDate target = date != null ? date : LocalDate.now();
        // 데이터 배치의 시작 요청·정오 예비와 같은 날짜 선점을 잡는다. 이미 돌고 있으면 409 다.
        OptionalInt made = recommendationBatch.runClaimed(target, force);
        if (made.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(BaseResponse.of(
                "그날의 오늘의 모먼트를 이미 만들고 있습니다. 끝난 뒤 다시 시도해 주세요.",
                Map.of("date", target.toString(), "generated", 0)));
        }
        return ResponseEntity.ok(BaseResponse.of(
            "오늘의 모먼트를 만들었습니다.",
            Map.of("date", target.toString(), "generated", made.getAsInt())));
    }

}
