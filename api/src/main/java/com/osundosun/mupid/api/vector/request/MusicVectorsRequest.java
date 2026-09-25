package com.osundosun.mupid.api.vector.request;

import java.util.List;

import com.osundosun.mupid.api.music.request.MusicSectionBulkRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * 곡 하나의 벡터 전부 — {@code PUT /api/v1/music/{musicId}/vectors} (S15P21A204-457, 설계 §6.6).
 *
 * <p><b>본문 형식(설계 §11 미결 5 의 결정)</b>: JSON 안에 벡터마다 <b>float32 리틀엔디언 바이트의
 * base64</b> 를 싣는다. {@code application/octet-stream} 을 버린 이유 — 한 요청에 종류가 다섯
 * ({@code mert}·{@code r}·{@code feat_v1}·{@code stage1_v{N}}·{@code clap})이고 {@code clap} 은 구간마다
 * 있거나 없어서, 바이트 한 덩어리로 보내면 구간·종류의 경계를 헤더에 따로 적는 틀을 새로 만들어야
 * 한다. JSON 이면 메타(구간 순서·분석 버전·임베딩/특징/모델 버전)와 벡터가 한 문서에 있고 검증이
 * 애너테이션으로 끝난다. base64 는 바이트보다 1/3 크지만(곡당 약 80KB → 107KB) JSON 숫자 배열(약
 * 200KB)보다 작고 파싱이 빠르다. Nginx 일반 요청 한도(20MB)와는 거리가 멀다.
 *
 * <p>구간은 <b>MySQL 에 등록된 순서 그대로</b>(순번 오름차순) 싣는다. Spring 은 수·순서가 다르면
 * 거절한다 — 옛 분석의 벡터가 새 구간에 붙지 않게 {@code analysis_version} 도 대조한다.
 *
 * @param analysisVersion 이 벡터를 만든 분석 버전. MySQL 의 그 곡 구간 버전과 같아야 한다
 * @param embVersion      임베딩 버전. 컬렉션 정의의 {@code embedding_version} 과 같아야 한다
 * @param featVersion     특징 버전({@code v1} → 벡터 {@code feat_v1})
 * @param source          추천 풀 구분({@code flow}). 서빙은 늘 {@code source ∈ POOL} 로 건다
 * @param sections        구간마다 한 줄
 */
public record MusicVectorsRequest(
    @NotBlank @Size(max = 20) String analysisVersion,
    @NotBlank @Size(max = 64) String embVersion,
    @NotBlank @Size(max = 16) String featVersion,
    @NotBlank @Size(max = 32) String source,
    @NotEmpty
    @Size(max = MusicSectionBulkRequest.MAX_SECTIONS, message = "한 곡의 구간은 200개까지입니다.")
    List<@Valid MusicVectorLine> sections
) {
}
