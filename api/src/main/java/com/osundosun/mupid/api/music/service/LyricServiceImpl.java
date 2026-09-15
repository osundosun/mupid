package com.osundosun.mupid.api.music.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.dto.LyricLine;
import com.osundosun.mupid.api.music.entity.Lyric;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.LyricErrorCode;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.repository.LyricRepository;
import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.music.request.LyricAddRequest;
import com.osundosun.mupid.api.music.request.LyricBulkRequest;
import com.osundosun.mupid.api.music.request.LyricUpdateRequest;
import com.osundosun.mupid.api.music.response.LyricBulkResponse;
import com.osundosun.mupid.api.music.response.LyricResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional
public class LyricServiceImpl implements LyricService {

    private final MusicService musicService;
    private final MusicRepository musicRepository;
    private final LyricRepository repository;

    @Override
    @Transactional(readOnly = true)
    public LyricResponse getLyrics(int musicId) {
        if (!musicRepository.existsByMusicIdAndIsDeletedFalse(musicId)) {
            throw new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND);
        }
        // 가사가 없는 곡이 많다. 빈 목록으로 돌려준다 — 곡은 있는데 가사만 없는
        // 것은 오류가 아니다.
        return new LyricResponse(musicId, repository
            .findByMusic_MusicIdOrderByTimeMsAscLyricIdAsc(musicId)
            .stream()
            .map(lyric -> new LyricLine(lyric.getLyricId(), lyric.getTimeMs(), lyric.getText()))
            .toList());
    }

    @Override
    public int addLyric(LyricAddRequest request) {
        Music music = musicService.getMusic(request.musicId())
            .orElseThrow(() -> new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND));
        Lyric lyric = Lyric.builder()
            .music(music)
            .text(request.text())
            .timeMs(request.timeMs())
            .build();
        return repository.save(lyric).getLyricId();
    }

    @Override
    public LyricBulkResponse replaceLyrics(int musicId, LyricBulkRequest request) {
        Music music = musicService.getMusic(musicId)
            .orElseThrow(() -> new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND));

        // 검증 애너테이션이 먼저 걸러 주지만, 서비스를 직접 부르는 길(시험·배치
        // 코드)에서도 상한이 지켜져야 한다.
        if (request.lines().size() > LyricBulkRequest.MAX_LINES) {
            throw new BusinessException(LyricErrorCode.LYRIC_BULK_TOO_MANY);
        }

        // 지운 뒤 넣으므로 먼저 내보낸다. 안 그러면 같은 트랜잭션 안에서 INSERT 가
        // DELETE 보다 앞서 나갈 수 있다 (`OnboardingServiceImpl` 과 같은 이유).
        int deleted = repository.deleteByMusic_MusicId(musicId);
        repository.flush();

        // 받은 순서 그대로 넣는다. 정렬하지 않는다 — 같은 `time_ms` 인 줄이
        // 있을 때, 조회가 `time_ms`·`lyric_id` 순이라 넣은 순서가 곧 보이는
        // 순서다. 여기서 정렬하면 그 동률이 뒤집힌다.
        List<Lyric> rows = request.lines().stream()
            .map(line -> Lyric.builder()
                .music(music)
                .text(line.text().strip())
                .timeMs(line.timeMs())
                .build())
            .toList();
        repository.saveAll(rows);

        return new LyricBulkResponse(musicId, deleted, rows.size());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Lyric> getLyric(int lyricId) {
        return repository.findById(lyricId);
    }

    @Override
    public void updateLyric(int lyricId, LyricUpdateRequest request) {
        Lyric lyric = getLyric(lyricId)
            .orElseThrow(() -> new BusinessException(LyricErrorCode.LYRIC_NOT_FOUND));
        if (request.musicId() != null) {
            Music music = musicService.getMusic(request.musicId())
                .orElseThrow(() -> new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND));
            lyric.setMusic(music);
        }
        if (request.text() != null && !request.text().isBlank()) {
            lyric.setText(request.text());
        }
        if (request.timeMs() != null) {
            Integer timeMs = request.timeMs();
            if (timeMs < 0) {
                throw new BusinessException(LyricErrorCode.LYRIC_INVALID_TIME_MS);
            }
            lyric.setTimeMs(timeMs);
        }
    }

    @Override
    public void deleteLyric(int lyricId) {
        Lyric lyric = getLyric(lyricId)
            .orElseThrow(() -> new BusinessException(LyricErrorCode.LYRIC_NOT_FOUND));
        repository.delete(lyric);
    }
    
}
