package com.osundosun.mupid.api.recommendation;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

import com.osundosun.mupid.api.recommendation.request.NextTrackRequest;
import com.osundosun.mupid.api.user.entity.User;
import com.osundosun.mupid.api.user.enumerate.UserRole;
import com.osundosun.mupid.api.user.enumerate.UserStatus;

/**
 * 이어 듣기의 뺄 곡 목록에 상한이 있다 (S15P21A204-458, 설계 §9.1).
 *
 * <p>계약 v3 에서 추천 요청 본문의 크기를 정하는 것은 {@code exclude_tracks} 하나다. 상한이 없으면 화면
 * 버그 하나가 요청마다 수만 곡을 실어 보낸다. 받는 입구에서 막히는지 — 서비스를 부르기 전에, 다른
 * 검증 실패와 같은 모양(400 · {@code MUPID_INVALID_REQUEST_BODY})으로 — 본다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NextTrackExcludeLimitTest {

    @Autowired
    private MockMvc mockMvc;

    private static String body(int excludes) {
        String ids = IntStream.rangeClosed(1, excludes).mapToObj(String::valueOf)
            .collect(Collectors.joining(","));
        return "{\"exclude_music_ids\":[" + ids + "],\"limit\":5}";
    }

    @Test
    @DisplayName("뺄 곡이 501곡이면 추천 서버를 부르기 전에 400 이다")
    void rejectsMoreThanTheCap() throws Exception {
        // 검증에서 끝나므로 사용자는 저장하지 않아도 된다 — 서비스까지 가지 않는다.
        User user = User.builder().userId(1).role(UserRole.ROLE_USER).status(UserStatus.ACTIVE).build();

        mockMvc.perform(post("/api/v1/recommendation/next")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(NextTrackRequest.MAX_EXCLUDE + 1))
                .with(authentication(new UsernamePasswordAuthenticationToken(user, null, List.of()))))
            .andExpect(status().isBadRequest())
            .andExpect(header().string("X-Mupid-Error", "MUPID_INVALID_REQUEST_BODY"))
            .andExpect(jsonPath("$.code").value("MUPID_INVALID_REQUEST_BODY"))
            .andExpect(jsonPath("$.error_details.excludeMusicIds").value("뺄 곡은 최대 500곡까지 보낼 수 있습니다."));
    }

}
