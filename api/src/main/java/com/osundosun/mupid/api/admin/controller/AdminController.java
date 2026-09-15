package com.osundosun.mupid.api.admin.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 관리자 화면의 HTML 골격.
 *
 * **왜 `/admin` 이 아니라 `/api/admin` 인가.** 운영 nginx(`infra/deploy/nginx.conf`)가
 * 스프링으로 넘기는 경로는 `/api/` 뿐이다. 나머지는 정적 프런트엔드 루트로 떨어지므로
 * `/admin` 은 404 가 된다. 액추에이터를 `management.endpoints.web.base-path` 로
 * `/api/actuator` 에 옮겨 둔 것과 같은 이유다. 이 규칙을 어기면 로컬에서만 열리고
 * 배포하면 사라진다.
 *
 * **이 두 경로는 인증 없이 열린다** (`SecurityConfig`). 브라우저 주소창으로 들어오는
 * 요청에는 `Authorization` 헤더를 붙일 수 없고, 이 프로젝트의 인증은 헤더의 JWT 뿐이라
 * (`AuthFilter` 는 헤더만 본다) 화면을 닫아 두면 아무도 들어올 수 없다. 대신 이 HTML 에는
 * 데이터가 한 줄도 들어 있지 않다 — 화면이 `fetch` 로 부르는
 * `/api/v1/admin/**`(`AdminApiController`) 이 `@AdminRole` 로 막혀 있고, 실제 방어선은
 * 거기 하나다.
 */
@Controller
@RequestMapping("/api/admin")
public class AdminController {

    @GetMapping
    public String indexPage() {
        return "admin/index";
    }

    @GetMapping("/login")
    public String loginPage() {
        return "admin/login";
    }

}
