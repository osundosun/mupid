package com.osundosun.mupid.api.music.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.analytics.sync.service.TrackFeatureSync;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicAnalysis;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.repository.MusicAnalysisRepository;
import com.osundosun.mupid.api.music.request.MusicAnalysisRequest;
import com.osundosun.mupid.api.music.response.MusicAnalysisBulkResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MusicAnalysisServiceImpl implements MusicAnalysisService {

    private final MusicService musicService;
    private final MusicAnalysisRepository musicAnalysisRepository;
    private final TrackFeatureSync trackFeatureSync;

    /**
     * 곡당 한 행이라 <b>지우고 다시 넣지 않는다.</b>
     *
     * <p>구간({@code MusicSectionServiceImpl})은 자식 표가 여럿이라 지우고 넣지만,
     * 여기는 대상이 {@code music_analysis} 한 행뿐이고 PK 가 {@code music_id} 다.
     * 지웠다 넣으면 그 사이에 읽는 쪽이 "분석 없음" 을 보게 되고, 트랜잭션이 롤백될
     * 때 사라진 옛 판정이 함께 사라진다. 있는 행을 고치는 편이 짧고 안전하다.
     *
     * <p>{@code vocalGender} 가 {@code null} 이면 <b>비운 채로 둔다.</b>
     * {@code MIX} 로 바꾸지 않는다 — {@code MIX} 는 "혼성으로 판정했다" 이고
     * {@code null} 은 "판정하지 못했다" 다. 재분석에서 성별을 못 냈다고 옛 판정을
     * {@code MIX} 로 덮으면 화면이 판정 실패를 혼성 보컬로 말하게 된다.
     *
     * <p><b>곡을 찾을 때 리포지토리를 직접 쓰지 않는다.</b>
     * {@code musicRepository.findById} 는 소프트 삭제된 곡도 돌려주므로, 서버에서
     * 지운 곡의 분석 결과가 조용히 다시 붙는다(`deleteMusic` 은 행을 지우지 않는다).
     * {@code requireAlive} 는 살아 있는 곡만 주고, 삭제된 곡이면 404 가 아니라
     * 410 Gone 이다 — 분류({@code MusicClassificationServiceImpl})·가사·구간과
     * 같은 방식이다.
     */
    @Override
    @Transactional
    public MusicAnalysisBulkResponse replaceAnalysis(int musicId, MusicAnalysisRequest request) {
        Music music = musicService.requireAlive(musicId);

        MusicAnalysis existing = musicAnalysisRepository.findById(musicId).orElse(null);
        boolean replaced = existing != null;

        if (existing == null) {
            // `@MapsId` 가 PK 를 관계에서 채운다. 빈 키 대신 곡을 넘긴다.
            existing = MusicAnalysis.builder().music(music).build();
        }
        // **교체다.** 보내지 않은 필드는 비운다(요청 DTO 주석).
        existing.setHasVocal(request.hasVocal());
        existing.setVocalGender(request.vocalGender());
        existing.setBpm(request.bpm());
        existing.setBpmRequestedMin(request.bpmRequestedMin());
        existing.setBpmRequestedMax(request.bpmRequestedMax());
        existing.setLanguage(blankToNull(request.language()));
        existing.setCategory(blankToNull(request.category()));
        musicAnalysisRepository.save(existing);
        // ClickHouse `track_features` 사본. 미반영 기록을 이 트랜잭션 안에서 남기고 커밋 뒤에 쓴다
        // (S15P21A204-455, `TrackFeatureSync`).
        trackFeatureSync.markChanged(musicId);

        return new MusicAnalysisBulkResponse(
            musicId,
            existing.getHasVocal(),
            existing.getVocalGender() == null ? null : existing.getVocalGender().name(),
            existing.getBpm(),
            existing.getBpmRequestedMin(),
            existing.getBpmRequestedMax(),
            existing.getLanguage(),
            existing.getCategory(),
            replaced);
    }

    /** 빈 문자열은 "모른다" 다. 그대로 두면 조건 필터가 빈 값을 어휘로 센다. */
    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

}
