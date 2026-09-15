package com.osundosun.mupid.api.user.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.auth.provider.JwtProvider;
import com.osundosun.mupid.api.common.annotation.AdminRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.dto.SystemUserCreateResult;
import com.osundosun.mupid.api.user.request.SystemUserCreateRequest;
import com.osundosun.mupid.api.user.request.UserCreateRequest;
import com.osundosun.mupid.api.user.request.UserUpdateRequest;
import com.osundosun.mupid.api.user.response.SystemUserCreateResponse;
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
        return ResponseEntity.ok(BaseResponse.of(
            "시스템 계정이 생성되었습니다. 비밀번호와 리프레시 토큰은 다시 확인할 수 없습니다.",
            new SystemUserCreateResponse(
                user.getUserId(),
                user.getEmail(),
                result.password(),
                jwtProvider.issueRefreshToken(user)
            )
        ));
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
            user.getStatus() /* 유저 상태 */
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
