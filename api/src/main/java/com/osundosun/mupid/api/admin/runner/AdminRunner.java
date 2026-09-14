package com.osundosun.mupid.api.admin.runner;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserGender;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;
import com.osundosun.mupid.api.user.repository.UserRepository;
import com.osundosun.mupid.api.user.service.UserService;
import com.osundosun.mupid.api.utils.PasswordGenerateUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AdminRunner implements ApplicationRunner {

    private final UserService userService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        // 관리자 계정이 하나도 없다면...
        if (!userService.isAdminUserExists()) {
            // 새로운 관리자 계정을 생성한다.
            String email = "admin@ssafy";
            String password = PasswordGenerateUtils.generatePassword(20);

            userRepository.save(User.builder()
                .email(email)
                .password(passwordEncoder.encode(password))
                .nickname("관리자")
                .birthYear(2026)
                .gender(UserGender.ETC)
                .role(UserRole.ROLE_ADMIN)
                .status(UserStatus.ACTIVE)
                .build());
            
            log.info("관리자 계정이 존재하지 않으므로 기본 관리자 계정이 자동 생성되었습니다.");
            System.out.println();
            System.out.printf("+----------------------------------------------------+\n");
            System.out.printf("  [ADMIN ACCOUNT]\n");
            System.out.printf("  EMAIL    : %s\n", email);
            System.out.printf("  PASSWORD : %s\n", password);
            System.out.printf("+----------------------------------------------------+\n");
            System.out.println();
        }
    }
    
}
