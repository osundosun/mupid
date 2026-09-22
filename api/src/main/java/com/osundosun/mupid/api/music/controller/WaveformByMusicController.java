package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.request.WaveformReplaceRequest;
import com.osundosun.mupid.api.music.response.WaveformReplaceResponse;
import com.osundosun.mupid.api.music.response.WaveformResponse;
import com.osundosun.mupid.api.music.service.WaveformService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 곡 단위로 파형을 다루는 API.
 *
 * <p>곡 하나에 매달린 것이라 `/api/v1/music/{musicId}/waveform` 이다. 가사
 * (`LyricByMusicController`)와 같은 모양이고, `@SystemRole` 을 클래스가 아니라
 * <b>메서드에</b> 붙인 것도 같은 이유다 — 조회는 로그인한 누구나 하고, 교체는
 * 배치(`ROLE_SYSTEM`)만 한다.
 *
 * <h2>왜 청크처럼 nginx 로 우회하지 않는가</h2>
 *
 * 청크는 곡당 100KB 짜리가 수십 개라 바이트가 JVM 을 지나가면 안 된다. 그래서
 * 업로드는 `auth_request`, 다운로드는 `X-Accel-Redirect` 로 nginx 가 스토리지와
 * 직접 주고받는다. <b>파형은 곡당 1~2KB 다.</b> 같은 길을 깔려면 개발·운영 nginx
 * 설정 양쪽에 location 을 더하고 배포까지 해야 하는데, 얻는 것이 없다.
 */
@RestController
@RequestMapping("/api/v1/music/{musicId}/waveform")
@RequiredArgsConstructor
public class WaveformByMusicController {

    private final WaveformService waveformService;

    /**
     * 한 곡의 파형. 시간 오름차순 0~1 값이다.
     *
     * <p><b>파형이 없으면 404 다</b> — 가사처럼 빈 배열을 주지 않는다. 화면이
     * "없으니 내가 계산한다" 로 갈아타야 하는데, 빈 배열로는 그 분기를 만들 수 없다.
     */
    @GetMapping
    public ResponseEntity<BaseResponse<WaveformResponse>> getWaveform(
        @PathVariable int musicId
    ) {
        return ResponseEntity.ok(BaseResponse.of(waveformService.getWaveform(musicId)));
    }

    /**
     * 한 곡의 파형을 통째로 갈아 끼운다.
     *
     * <p>`PUT` 인 이유는 가사와 같다 — 같은 본문을 두 번 보내면 결과가 같다.
     * 배치가 하는 일이 정확히 "이 곡의 파형은 이 값들이다" 이고, 중간에 끊긴
     * 배치를 그대로 다시 돌릴 수 있어야 한다.
     */
    @SystemRole
    @PutMapping
    public ResponseEntity<BaseResponse<WaveformReplaceResponse>> replaceWaveform(
        @PathVariable int musicId,
        @Valid @RequestBody WaveformReplaceRequest request
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "파형이 등록되었습니다.",
            waveformService.replaceWaveform(musicId, request)
        ));
    }

}
