package com.osundosun.mupid.api.music.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.entity.MusicAnalysis;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.repository.MusicAnalysisRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.request.MusicAnalysisRequest;
import com.osundosun.mupid.api.music.response.MusicAnalysisBulkResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MusicAnalysisServiceImpl implements MusicAnalysisService {

    private final MusicRepository musicRepository;
    private final MusicAnalysisRepository musicAnalysisRepository;

    /**
     * 곡당 한 행이라 <b>지우고 다시 넣지 않는다.</b>
     *
     * <p>구간({@code MusicSectionServiceImpl})은 자식 표가 여럿이라 지우고 넣지만,
     * 여기는 대상이 {@code music_analysis} 한 행뿐이고 PK 가 {@code music_id} 다.
     * 지웠다 넣으면 그 사이에 읽는 쪽이 "분석 없음" 을 보게 되고, 트랜잭션이 롤백될
     * 때 사라진 옛 판정이 함께 사라진다. 있는 행을 고치는 편이 짧고 안전하다.
     *
     * <p>{@code vocalGender} 가 {@code null} 이면 <b>비운 채로 둔다.</b>
     * {@code ETC} 로 바꾸지 않는다 — {@code ETC} 는 "기타로 판정했다" 이고
     * {@code null} 은 "판정하지 못했다" 다. 재분석에서 성별을 못 냈다고 옛 판정을
     * {@code ETC} 로 덮으면 화면이 판정 실패를 결과로 말하게 된다.
     */
    @Override
    @Transactional
    public MusicAnalysisBulkResponse replaceAnalysis(int musicId, MusicAnalysisRequest request) {
        Music music = musicRepository.findById(musicId)
            .orElseThrow(() -> new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND));

        MusicAnalysis existing = musicAnalysisRepository.findById(musicId).orElse(null);
        boolean replaced = existing != null;

        if (existing == null) {
            // `@MapsId` 가 PK 를 관계에서 채운다. 빈 키 대신 곡을 넘긴다.
            existing = MusicAnalysis.builder()
                .music(music)
                .hasVocal(request.hasVocal())
                .vocalGender(request.vocalGender())
                .build();
        } else {
            existing.setHasVocal(request.hasVocal());
            existing.setVocalGender(request.vocalGender());
        }
        musicAnalysisRepository.save(existing);

        return new MusicAnalysisBulkResponse(
            musicId,
            existing.getHasVocal(),
            existing.getVocalGender() == null ? null : existing.getVocalGender().name(),
            replaced);
    }

}
