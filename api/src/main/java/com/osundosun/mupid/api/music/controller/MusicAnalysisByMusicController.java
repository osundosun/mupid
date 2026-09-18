package com.osundosun.mupid.api.music.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.osundosun.mupid.api.common.annotation.SystemRole;
import com.osundosun.mupid.api.common.response.BaseResponse;
import com.osundosun.mupid.api.music.request.MusicAnalysisRequest;
import com.osundosun.mupid.api.music.response.MusicAnalysisBulkResponse;
import com.osundosun.mupid.api.music.service.MusicAnalysisService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 곡 단위로 보컬 분석 결과를 다루는 API.
 *
 * <p>구간(<code>MusicSectionByMusicController</code>)·분류(<code>MusicClassificationByMusicController</code>)
 * 와 같은 모양이다. 조회는 <code>GET /api/v1/music/{musicId}/analysis</code> 가 이미
 * 하고 있어 여기에는 적재만 둔다. 적재는 배치(<code>ROLE_SYSTEM</code>)만 한다.
 *
 * <p><b>왜 필요한가.</b> <code>music_analysis</code> 는 조회 쪽에서 이미 읽히고
 * 있었다 — MuBTI 리포트의 A축(보컬 성향)과 곡 분석 응답의 <code>hasVocal</code> ·
 * <code>vocalGender</code> 가 이 표에서 온다. 그런데 <b>이 표에 넣는 경로가 없었다.</b>
 * 그래서 조회는 언제나 <code>null</code> 을 내보냈고, 그 <code>null</code> 이
 * "보컬 없음" 인지 "판정 전" 인지 화면이 구분할 수 없었다.
 *
 * <p><b>관리자 API 를 쓰지 않는 이유</b>는 분류 컨트롤러와 같다. 배치에 관리자
 * 권한을 주면 그 계정으로 회원·결제까지 닿는다. 배치가 실제로 필요한 일만
 * <code>@SystemRole</code> 로 따로 낸다.
 */
@RestController
@RequestMapping("/api/v1/music/{musicId}/analysis")
@RequiredArgsConstructor
public class MusicAnalysisByMusicController {

    private final MusicAnalysisService musicAnalysisService;

    /**
     * 한 곡의 보컬 판정을 갈아 끼운다. <b>기존 판정은 덮어써진다.</b>
     *
     * <p><code>PUT</code> 인 이유: 같은 본문을 두 번 보내면 결과가 같다(멱등).
     * 한 곡의 판정은 분석 한 번의 결과로 함께 정해지므로, <code>POST</code> 로
     * 쌓으면 재분석 중간에 옛 판정과 새 판정이 섞인다.
     *
     * <p>응답의 <code>replaced</code> 는 이전 판정을 덮어썼는지 알려준다 —
     * 배치가 "처음 넣음" 과 "재분석으로 바뀜" 을 구분하는 근거다.
     */
    @SystemRole
    @PutMapping
    public ResponseEntity<BaseResponse<MusicAnalysisBulkResponse>> replaceAnalysis(
        @PathVariable int musicId,
        @Valid @RequestBody MusicAnalysisRequest request
    ) {
        return ResponseEntity.ok(BaseResponse.of(
            "보컬 분석 결과가 등록되었습니다.",
            musicAnalysisService.replaceAnalysis(musicId, request)
        ));
    }

}
