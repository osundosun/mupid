package com.osundosun.mupid.api.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.user.dto.SystemUserCreateResult;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.request.SystemUserCreateRequest;
import com.osundosun.mupid.api.user.service.UserService;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * ROLE_SYSTEM 계정은 쓰는 곳마다 따로 둔다 (저장소 재설계 원칙 6, S15P21A204-453).
 *
 * <p>운영에서 만들 이름이 {@code system-preprocessing}·{@code system-ci} 다. 닉네임을 비우면
 * 이메일 앞부분을 쓰는데, {@code users.nickname} 이 VARCHAR(10) 이라 20자짜리 앞부분은 INSERT
 * 에서 {@code Data too long} 으로 깨졌다. 실제 DB 에 넣어 봐야 드러나는 것이라 여기서 본다.
 */
@SpringBootTest
@Transactional
class SystemUserCreateTest {

    @Autowired
    private UserService userService;

    @PersistenceContext
    private EntityManager em;

    @Test
    @DisplayName("쓰는 곳마다 계정을 하나씩 만들 수 있고, 긴 이메일 앞부분은 닉네임 상한에서 자른다")
    void createsOneAccountPerConsumer() {
        String domain = UUID.randomUUID().toString().substring(0, 8) + ".mupid.test";

        SystemUserCreateResult preprocessing = userService.createSystemUser(
            new SystemUserCreateRequest("system-preprocessing@" + domain, null));
        SystemUserCreateResult ci = userService.createSystemUser(
            new SystemUserCreateRequest("system-ci@" + domain, null));
        em.flush();

        assertEquals(UserRole.ROLE_SYSTEM, preprocessing.user().getRole());
        assertEquals(UserRole.ROLE_SYSTEM, ci.user().getRole());
        assertNotEquals(preprocessing.user().getUserId(), ci.user().getUserId());
        assertEquals("system-pre", preprocessing.user().getNickname());
        assertEquals("system-ci", ci.user().getNickname());
    }

}
