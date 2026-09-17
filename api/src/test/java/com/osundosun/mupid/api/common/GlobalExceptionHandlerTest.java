package com.osundosun.mupid.api.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 경로는 맞는데 요청 방식이 다를 때 <b>500 이 아니라 405</b> 가 나가는지 못박는다.
 *
 * <h2>왜 이 테스트가 필요한가</h2>
 *
 * {@code HttpRequestMethodNotSupportedException} 을 잡는 핸들러가 없으면 맨 아래
 * {@code Exception} 핸들러가 그것을 삼켜 <b>500</b> 으로 바꾼다. 보낸 쪽은 서버 장애로
 * 읽고 5xx 를 재시도 대상으로 잡아 쓸데없이 다시 보낸다 — 다시 보내도 같은 답인데도.
 *
 * <p>이 결함은 <b>인증이 필요 없는 경로에서만 눈에 보인다.</b> 인증이 필요한 경로는
 * 필터가 먼저 401 을 내보내 핸들러까지 오지 않기 때문이다. 그래서 {@code SecurityConfig}
 * 가 {@code GET} 만 열어 둔 이미지 경로로 확인한다 — 그 경로는 {@code POST}/{@code DELETE}
 * 로만 매핑돼 있어 {@code GET} 이 정확히 "방식이 다른" 요청이 된다.
 *
 * <p>404 와 구분되는 것도 함께 본다. 둘 다 "잘못 부른 주소" 지만 되돌아오는 코드가
 * 달라야 보낸 쪽이 "주소가 없다" 와 "방식이 틀렸다" 를 가릴 수 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * 이미지 조회는 번호 주소({@code /api/v1/storage/image/{fileId}})라
     * {@code GET /{카테고리}/{키}} 는 매핑이 없다. 업로드·삭제만 그 모양이다.
     */
    @Test
    @DisplayName("경로는 맞는데 방식이 다르면 500 이 아니라 405")
    void methodNotSupportedBecomes405() throws Exception {
        mockMvc.perform(get("/api/v1/storage/image/ALBUM/1"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("X-Mupid-Error", "MUPID_METHOD_NOT_ALLOWED"))
            .andExpect(jsonPath("$.code").value("MUPID_METHOD_NOT_ALLOWED"));
    }

    /** 405 와 404 가 뒤섞이면 "방식이 틀렸다" 와 "주소가 없다" 를 가릴 수 없다. */
    @Test
    @DisplayName("없는 주소는 여전히 404 — 405 와 구분된다")
    void missingPathStays404() throws Exception {
        mockMvc.perform(get("/api/v1/storage/image/999999/1/2/3"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("MUPID_NOT_FOUND"));
    }

    /**
     * 인증이 필요한 경로는 필터가 먼저 끊으므로 핸들러까지 오지 않는다.
     * 이 순서가 뒤집히면(401 이 405 로 바뀌면) 로그인 안 한 사용자에게
     * "방식이 틀렸다" 고 답하게 되어 원인을 잘못 짚게 만든다.
     */
    @Test
    @DisplayName("인증이 필요한 경로는 405 가 아니라 401 이 먼저다")
    void authenticatedPathStillAnswers401() throws Exception {
        mockMvc.perform(post("/api/v1/report"))
            .andExpect(status().isUnauthorized());
    }
}
