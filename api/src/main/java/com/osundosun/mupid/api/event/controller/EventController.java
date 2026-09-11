package com.osundosun.mupid.api.event.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.event.request.EventCollectRequest;
import com.osundosun.mupid.api.event.response.EventCollectResponse;
import com.osundosun.mupid.api.event.service.EventService;
import com.osundosun.mupid.api.user.entity.User;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    /**
     * 청취 이벤트 배치 수집.
     *
     * `SecurityConfig` 에 permitAll 을 추가하지 않는다. 비로그인 청취를 막기로 했으므로
     * 기본값 `anyRequest().authenticated()` 가 그대로 맞다.
     *
     * 202 로 답한다. Kafka 발행은 응답을 기다리지 않으므로 "받아서 처리 중" 이지
     * "적재 완료" 가 아니다. 200 으로 답하면 클라이언트가 적재를 보장받았다고 오해한다.
     */
    @PostMapping
    public ResponseEntity<BaseResponse<EventCollectResponse>> collectEvents(
        Authentication auth,
        @Valid @RequestBody EventCollectRequest request
    ) {
        User user = (User)auth.getPrincipal();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(
            BaseResponse.of(eventService.collectEvents(user.getUserId(), request))
        );
    }

}
