package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.request.MusicSectionBulkRequest;
import com.osundosun.mupid.api.music.response.MusicSectionBulkResponse;
import com.osundosun.mupid.api.music.service.MusicSectionService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 곡 단위로 구간 분석 결과를 다루는 API.
 *
 * AI 가 곡을 분석해 만든 구간을 `music_section_analysis` 에 넣는다. **이 표가
 * 채워져야 숫자 `music_section_id` 가 생기고, 그래야 추천 응답이 구간을 가리킬 수
 * 있다.** 비어 있는 동안 추천 서버의 `/internal/ready` 는 503 이고 곡 추천은 0건이다.
 *
 * 구간 조회는 `GET /api/v1/music/{musicId}/analysis` 가 이미 하고 있어 여기에는
 * 적재만 둔다. 적재는 배치(`ROLE_SYSTEM`)만 한다.
 */
@RestController
@RequestMapping("/api/v1/music/{musicId}/section")
@RequiredArgsConstructor
public class MusicSectionByMusicController {

    private final MusicSectionService musicSectionService;

    /**
     * 한 곡의 구간을 통째로 갈아 끼운다. **기존 구간은 지워진다.**
     *
     * `PUT` 인 이유: 같은 본문을 두 번 보내면 결과가 같다. 한 곡의 구간은 분석 한
     * 번의 결과로 함께 정해지므로, 구간마다 `POST` 를 부르면 재분석 중간에 옛
     * 구간과 새 구간이 섞이고 그 상태로도 조회가 정상으로 보인다.
     *
     * 응답의 `sections[].musicSectionAnalysisId` 는 AI 가 `id_map` 을 만드는 근거다.
     */
    @SystemRole
    @PutMapping
    public ResponseEntity<BaseResponse<MusicSectionBulkResponse>> replaceSections(
        @PathVariable int musicId,
        @Valid @RequestBody MusicSectionBulkRequest request
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "구간 분석 결과가 등록되었습니다.",
            musicSectionService.replaceSections(musicId, request)
        ));
    }

}
