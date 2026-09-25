package com.osundosun.mupid.api.user.controller;

import java.time.Duration;
import java.time.Instant;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.auth.provider.JwtProvider;
import com.osundosun.mupid.api.auth.service.TokenRevocationService;
import com.osundosun.mupid.api.common.annotation.AdminRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.onboarding.service.OnboardingService;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.dto.SystemUserCreateResult;
import com.osundosun.mupid.api.user.request.SystemUserCreateRequest;
import com.osundosun.mupid.api.user.request.SystemUserTokenRequest;
import com.osundosun.mupid.api.user.request.UserCreateRequest;
import com.osundosun.mupid.api.user.request.UserUpdateRequest;
import com.osundosun.mupid.api.user.response.SystemUserCreateResponse;
import com.osundosun.mupid.api.user.response.SystemUserTokenResponse;
import com.osundosun.mupid.api.user.response.UserGetInfoResponse;
import com.osundosun.mupid.api.user.service.UserImageService;
import com.osundosun.mupid.api.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserImageService userImageService;
    private final JwtProvider jwtProvider;
    private final TokenRevocationService tokenRevocationService;
    /*
     * 온보딩 완료 여부를 실어 주려고 컨트롤러에서 직접 부른다. `UserServiceImpl`
     * 에 넣으면 OnboardingServiceImpl → UserService → OnboardingService 로
     * 순환 참조가 된다.
     */
    private final OnboardingService onboardingService;
    
    @PostMapping
    public ResponseEntity<BaseResponse<Void>> createUser(
        @Valid @RequestBody UserCreateRequest request
    ) {
        userService.createUser(request);
        return ResponseEntity.ok(
            BaseResponse.message("회원가입 되었습니다.")
        );
    }

    /**
     * 배치 프로그램용 ROLE_SYSTEM 계정을 발급한다.
     *
     * 비밀번호와 리프레시 토큰은 **이 응답에서만** 평문으로 나온다.
     * 배치는 리프레시 토큰만 보관하고, 액세스 토큰은 `GET /api/v1/auth` 로 그때그때 받는다.
     */
    @AdminRole
    @PostMapping("/system")
    public ResponseEntity<BaseResponse<SystemUserCreateResponse>> createSystemUser(
        @Valid @RequestBody SystemUserCreateRequest request
    ) {
        SystemUserCreateResult result = userService.createSystemUser(request);
        User user = result.user();
        Duration validity = jwtProvider.resolveRefreshTokenValidity(user, request.refreshTokenValidityDays());
        return ResponseEntity.ok(BaseResponse.of(
            "시스템 계정이 생성되었습니다. 비밀번호와 리프레시 토큰은 다시 확인할 수 없습니다.",
            new SystemUserCreateResponse(
                user.getUserId(),
                user.getEmail(),
                result.password(),
                jwtProvider.issueRefreshToken(user, validity),
                Instant.now().plus(validity)
            )
        ));
    }

    /**
     * 시스템 계정의 리프레시 토큰을 새로 발급한다 (S15P21A204-454).
     *
     * <p>ROLE_SYSTEM 의 토큰은 회전하지 않으니 만료 전에 사람이 갈아 끼운다. 비밀번호로 로그인해도
     * 되지만, 비밀번호는 금고에만 두고 꺼내지 않는 편이 낫다 — 관리자 토큰으로 받는다.
     * 옛 토큰은 그대로 살아 있다. 끊으려면 {@link #revokeSystemUserTokens} 를 <b>먼저</b> 부른다
     * (폐기는 그 시각 이전에 발급된 것을 모두 막으므로 발급 뒤에 부르면 새 토큰도 막힌다).
     */
    @AdminRole
    @PostMapping("/system/{userId}/token")
    public ResponseEntity<BaseResponse<SystemUserTokenResponse>> issueSystemUserToken(
        @PathVariable int userId,
        @Valid @RequestBody(required = false) SystemUserTokenRequest request
    ) {
        User user = userService.getSystemUser(userId);
        Integer days = request == null ? null : request.refreshTokenValidityDays();
        Duration validity = jwtProvider.resolveRefreshTokenValidity(user, days);
        return ResponseEntity.ok(BaseResponse.of(
            "리프레시 토큰이 발급되었습니다. 다시 확인할 수 없습니다.",
            new SystemUserTokenResponse(
                user.getUserId(),
                user.getEmail(),
                jwtProvider.issueRefreshToken(user, validity),
                Instant.now().plus(validity)
            )
        ));
    }

    /**
     * 시스템 계정의 토큰을 모두 무효로 한다 — 일괄 로그아웃 (S15P21A204-454).
     *
     * <p>회전하지 않는 6개월짜리 토큰이 새었을 때 끊는 길이다. 이 시각 이전에 발급된 그 계정의
     * 리프레시 토큰과 액세스 토큰이 모두 401 이 된다.
     */
    @AdminRole
    @DeleteMapping("/system/{userId}/token")
    public ResponseEntity<BaseResponse<Void>> revokeSystemUserTokens(
        @PathVariable int userId
    ) {
        User user = userService.getSystemUser(userId);
        tokenRevocationService.revokeAll(user.getUserId());
        return ResponseEntity.ok(
            BaseResponse.message("시스템 계정의 토큰을 모두 폐기했습니다.")
        );
    }

    @GetMapping
    public ResponseEntity<BaseResponse<UserGetInfoResponse>> getUserInfo(
        Authentication auth
    ) {
        User user = (User)auth.getPrincipal();
        return ResponseEntity.ok(BaseResponse.of(new UserGetInfoResponse(
            user.getUserId(), /* 유저 번호 */
            user.getEmail(), /* 이메일 */
            user.getNickname(), /* 닉네임 */
            userImageService.getProfileImageUrl(user), /* 프로필 이미지 URL */
            user.getBirthYear(), /* 출생 연도 */
            user.getGender(), /* 성별 */
            user.getRole(), /* 권한 */
            user.getStatus(), /* 유저 상태 */
            onboardingService.hasSubmitted(user.getUserId()) /* 온보딩 설문을 마쳤는가 */
        )));
    }

    @PatchMapping
    public ResponseEntity<BaseResponse<Void>> updateUser(
        Authentication auth,
        @Valid @RequestBody UserUpdateRequest request
    ) {
        User user = (User)auth.getPrincipal();
        userService.updateUser(user.getUserId(), request);
        return ResponseEntity.ok(
            BaseResponse.message("회원정보가 수정 되었습니다.")
        );
    }

    @DeleteMapping
    public ResponseEntity<BaseResponse<Void>> deleteUser(
        Authentication auth
    ) {
        User user = (User)auth.getPrincipal();
        userService.deleteUser(user.getUserId());
        return ResponseEntity.ok(
            BaseResponse.message("회원탈퇴 처리 되었습니다.")
        );
    }

}
