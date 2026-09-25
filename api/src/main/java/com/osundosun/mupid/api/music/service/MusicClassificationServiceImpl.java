package com.osundosun.mupid.api.music.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.analytics.sync.service.TrackFeatureSync;
import com.osundosun.mupid.api.code.entity.Code;
import com.osundosun.mupid.api.code.enumerate.CodeType;
import com.osundosun.mupid.api.code.error.CodeErrorCode;
import com.osundosun.mupid.api.code.repository.CodeRepository;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.mapping.entity.MusicGenre;
import com.osundosun.mupid.api.music.mapping.entity.MusicMood;
import com.osundosun.mupid.api.music.mapping.repository.MusicGenreRepository;
import com.osundosun.mupid.api.music.mapping.repository.MusicMoodRepository;
import com.osundosun.mupid.api.music.request.MusicClassificationRequest;
import com.osundosun.mupid.api.music.response.MusicClassificationResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MusicClassificationServiceImpl implements MusicClassificationService {

    private final MusicService musicService;
    private final CodeRepository codeRepository;
    private final MusicGenreRepository musicGenreRepository;
    private final MusicMoodRepository musicMoodRepository;
    private final TrackFeatureSync trackFeatureSync;

    /**
     * 지우고 다시 넣는다. <b>한 트랜잭션에서</b> 한다.
     *
     * <p>나눠 두면 중간에 끊겼을 때 곡의 분류가 사라진 채 남고, 그 상태로도 조회는
     * 정상으로 보인다 — 장르가 빈 곡과 구분이 되지 않는다.
     *
     * <p>코드 번호는 <b>종류까지</b> 본다. 장르 자리에 분위기 번호를 넣어도 표에는
     * 들어간다. 둘 다 `codes` 를 가리키는 숫자라서. 그러면 온보딩 설문과 곡 분류가
     * 서로 다른 것을 보게 되고, 그 어긋남은 조회에서 드러나지 않는다.
     *
     * <p><b>곡을 찾을 때 리포지토리를 직접 쓰지 않는다.</b> 여기서
     * {@code musicRepository.findById} 를 쓰면 <b>소프트 삭제된 곡에도 분류가
     * 쓰인다</b> — 다른 조회·수정 경로는 전부 {@code isDeleted} 를 보는데 이
     * 엔드포인트만 빠져 있었다(`deleteMusic` 은 행을 지우지 않고 플래그만 세운다).
     * 배치가 등록 기록(`registered.json`)의 낡은 곡 번호로 보내면 서버에 없는 곡의
     * 분류가 조용히 바뀐다. {@code getMusic} 은 살아 있는 곡만 돌려준다
     * (`MusicServiceImpl.getMusic`). 같은 이유로 가사({@code LyricServiceImpl})와
     * 구간({@code MusicSectionServiceImpl})도 같은 방식을 쓴다.
     */
    @Override
    @Transactional
    public MusicClassificationResponse replaceClassification(
        int musicId, MusicClassificationRequest request) {

        Music music = musicService.requireAlive(musicId);

        List<Integer> genreIds = distinct(request.genreCodeIds());
        List<Integer> moodIds = distinct(request.moodCodeIds());

        Map<Integer, Code> codes = loadCodes(genreIds, moodIds);
        List<Code> genres = pick(codes, genreIds, CodeType.GENRE);
        List<Code> moods = pick(codes, moodIds, CodeType.MOOD);

        // 지우기를 먼저 내보낸다. 같은 키를 지우고 다시 넣는 순서가 뒤집히면
        // 영속성 컨텍스트가 INSERT 를 먼저 흘려 중복 키로 터진다.
        musicGenreRepository.deleteByMusicGenreId_MusicId(musicId);
        musicMoodRepository.deleteByMusicMoodId_MusicId(musicId);
        musicGenreRepository.flush();
        musicMoodRepository.flush();

        // `@MapsId` 가 복합키를 관계에서 채우므로 빈 키를 넘긴다.
        // `MusicSectionAnalysisMood` 도 같은 모양이다.
        for (Code genre : genres) {
            musicGenreRepository.save(new MusicGenre(
                new MusicGenre.MusicGenreId(), music, genre));
        }
        for (Code mood : moods) {
            musicMoodRepository.save(new MusicMood(
                new MusicMood.MusicMoodId(), music, mood));
        }

        // ClickHouse `track_features` 의 genres·moods (S15P21A204-455).
        trackFeatureSync.markChanged(musicId);

        return new MusicClassificationResponse(
            musicId,
            genres.stream().map(Code::getCodeId).toList(),
            moods.stream().map(Code::getCodeId).toList());
    }

    /** 순서는 보존하고 중복만 걷어 낸다. 같은 코드를 두 번 주면 중복 키로 터진다. */
    private List<Integer> distinct(List<Integer> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }

    private Map<Integer, Code> loadCodes(List<Integer> genreIds, List<Integer> moodIds) {
        List<Integer> all = new ArrayList<>(genreIds);
        all.addAll(moodIds);
        if (all.isEmpty()) {
            return Map.of();
        }
        return codeRepository.findAllByCodeIdIn(all).stream()
            .collect(Collectors.toMap(Code::getCodeId, Function.identity()));
    }

    private List<Code> pick(Map<Integer, Code> codes, List<Integer> ids, CodeType expected) {
        List<Code> picked = new ArrayList<>(ids.size());
        for (Integer id : ids) {
            Code code = codes.get(id);
            if (code == null) {
                throw new BusinessException(CodeErrorCode.CODE_NOT_FOUND);
            }
            if (code.getCodeType() != expected) {
                throw new BusinessException(CodeErrorCode.CODE_TYPE_MISMATCH);
            }
            picked.add(code);
        }
        return picked;
    }

}
