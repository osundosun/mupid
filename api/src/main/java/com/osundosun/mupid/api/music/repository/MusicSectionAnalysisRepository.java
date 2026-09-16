package com.osundosun.mupid.api.music.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;

public interface MusicSectionAnalysisRepository extends JpaRepository<
    MusicSectionAnalysis, Integer
> {

    /** 한 곡의 구간. 순서대로 준다. */
    List<MusicSectionAnalysis> findByMusic_MusicIdOrderByIndexAsc(Integer musicId);

    /**
     * 한 곡의 구간을 통째로 지운다. 지운 개수를 돌려준다.
     *
     * 일괄 적재가 "지우고 다시 쓰기" 라서 필요하다 (`LyricRepository` 와 같은 이유).
     */
    int deleteByMusic_MusicId(Integer musicId);

    /**
     * `external_id` 로 이미 자리 잡은 구간들.
     *
     * 유니크 제약이 DB 에 있지만, 그것만 믿으면 위반이 `DataIntegrityViolationException`
     * 으로 올라와 **어느 값이 겹쳤는지 알 수 없다.** 넣기 전에 물어보고 값을 집어
     * 알려 주기 위한 것이다.
     */
    List<MusicSectionAnalysis> findByExternalIdIn(Collection<String> externalIds);

}
