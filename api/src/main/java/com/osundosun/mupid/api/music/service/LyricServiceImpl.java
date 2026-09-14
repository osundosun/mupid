package com.osundosun.mupid.api.music.service;

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
import com.osundosun.mupid.api.music.request.LyricUpdateRequest;
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
