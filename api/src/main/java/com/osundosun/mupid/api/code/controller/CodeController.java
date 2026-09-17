package com.osundosun.mupid.api.code.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.code.request.CodeCreateRequest;
import com.osundosun.mupid.api.code.response.CodeResponse;
import com.osundosun.mupid.api.code.service.CodeService;
import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 공통 코드 — 장르 · 분위기 · 악기.
 *
 * <p><b>왜 관리자 API 가 아니라 여기인가.</b> 전처리 배치가 곡을 분석하면서 새 장르와
 * 분위기를 만난다. 그렇다고 배치에 관리자 권한을 주면 그 계정으로 회원·결제까지
 * 닿게 되고, 반대로 `/api/v1/admin/**` 을 배치에 열어 주면 관리자 API 가 관리자
 * 전용이라는 말이 무의미해진다. 배치가 실제로 필요한 것만 여기에 둔다.
 *
 * <p>부트스트랩이 심는 기본 16개(장르 8 · 분위기 8)는 그대로 둔다. 서비스 초기에
 * 분석 결과가 없어도 온보딩 설문이 보여 줄 것이 있어야 하기 때문이다. 배치는 그
 * 위에 덧붙인다.
 *
 * <p>목록 조회는 온보딩(`/api/v1/onboarding/options`)이 이미 하고 있어 여기에는
 * 추가만 둔다.
 */
@RestController
@RequestMapping("/api/v1/code")
@RequiredArgsConstructor
public class CodeController {

    private final CodeService codeService;

    /**
     * 코드를 추가한다. <b>이미 있으면 그대로 돌려준다.</b>
     *
     * <p>`POST` 인데 멱등이다. 배치가 곡마다 같은 장르를 수백 번 보내므로, 두 번째
     * 부터 409 를 돌려주면 호출하는 쪽이 "이미 있음" 과 "진짜 실패" 를 가려야 한다.
     * 그 분기를 빠뜨리면 배치가 통째로 멈춘다.
     *
     * <p>응답의 `created` 로 이번에 만들었는지 알 수 있다 — 배치가 "몇 개가 새로
     * 들어왔는가" 를 세는 근거다.
     */
    @SystemRole
    @PostMapping
    public ResponseEntity<BaseResponse<CodeResponse>> addCode(
        @Valid @RequestBody CodeCreateRequest request
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "코드가 등록되었습니다.",
            codeService.addCode(request)
        ));
    }

}
