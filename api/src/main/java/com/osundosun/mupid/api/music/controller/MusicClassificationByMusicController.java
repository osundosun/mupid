package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.request.MusicClassificationRequest;
import com.osundosun.mupid.api.music.response.MusicClassificationResponse;
import com.osundosun.mupid.api.music.service.MusicClassificationService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 곡 단위로 장르·분위기를 다루는 API.
 *
 * <p>전처리 배치가 곡을 분석해 얻은 분류를 여기에 올린다. 조회는
 * `GET /api/v1/music/{musicId}` 가 이미 하고 있어 여기에는 적재만 둔다.
 *
 * <p><b>관리자 API 를 쓰지 않는 이유.</b> 관리자용은 관리자를 위한 것이다. 배치에
 * 관리자 권한을 주면 그 계정으로 회원·결제까지 닿고, 반대로 `/api/v1/admin/**` 을
 * 배치에 열어 주면 관리자 전용이라는 말이 무의미해진다. 배치가 실제로 필요한 일만
 * `@SystemRole` 로 따로 낸다 — 가사(`LyricByMusicController`)와 구간
 * (`MusicSectionByMusicController`)이 이미 같은 모양이다.
 *
 * <p>악기(`CodeType.INSTRUMENT`)는 여기 없다. `music_analysis_instruments` 가
 * <b>곡이 아니라 구간</b>에 매달려 있어서다. 구간 분석이 등록된 뒤에 구간 적재와
 * 함께 다룬다.
 */
@RestController
@RequestMapping("/api/v1/music/{musicId}/classification")
@RequiredArgsConstructor
public class MusicClassificationByMusicController {

    private final MusicClassificationService musicClassificationService;

    /**
     * 한 곡의 장르·분위기를 통째로 갈아 끼운다. <b>기존 분류는 지워진다.</b>
     *
     * <p>`PUT` 인 이유: 같은 본문을 두 번 보내면 결과가 같다. 한 곡의 분류는 분석
     * 한 번의 결과로 함께 정해지므로, 항목마다 `POST` 를 부르면 재분석 중간에
     * 옛 분류와 새 분류가 섞이고 그 상태로도 조회가 정상으로 보인다.
     *
     * <p>빈 배열을 보내면 그 분류를 지운다. 재분석에서 장르가 하나도 안 나왔는데
     * 옛 장르가 남아 있으면 그게 최신인 것처럼 보이기 때문이다.
     */
    @SystemRole
    @PutMapping
    public ResponseEntity<BaseResponse<MusicClassificationResponse>> replaceClassification(
        @PathVariable int musicId,
        @Valid @RequestBody MusicClassificationRequest request
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "분류가 등록되었습니다.",
            musicClassificationService.replaceClassification(musicId, request)
        ));
    }

}
