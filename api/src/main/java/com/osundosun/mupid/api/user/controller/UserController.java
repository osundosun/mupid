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

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.request.UserCreateRequest;
import com.osundosun.mupid.api.user.request.UserUpdateRequest;
import com.osundosun.mupid.api.user.response.UserGetInfoResponse;
import com.osundosun.mupid.api.user.service.UserService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    
    @PostMapping
    public ResponseEntity<BaseResponse<Void>> createUser(
        @Valid @RequestBody UserCreateRequest request
    ) {
        userService.createUser(request);
        return ResponseEntity.ok(
            BaseResponse.message("회원가입 되었습니다.")
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
            "/api/v1/user/image/%s".formatted(user.getUserId()), /* 프로필 이미지 URL */
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
