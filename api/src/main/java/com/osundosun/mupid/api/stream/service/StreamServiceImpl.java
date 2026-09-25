package com.osundosun.mupid.api.stream.service;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.analytics.sync.service.TrackFeatureSync;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.Music;
import com.osundosun.mupid.api.music.error.MusicErrorCode;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.stream.entity.Chunk;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.enumerate.StreamStatus;
import com.osundosun.mupid.api.stream.error.StreamErrorCode;
import com.osundosun.mupid.api.stream.repository.ChunkRepository;
import com.osundosun.mupid.api.stream.repository.StreamRepository;
import com.osundosun.mupid.api.stream.request.StreamCreateRequest;
import com.osundosun.mupid.api.storage.service.StorageService;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor 
public class StreamServiceImpl implements StreamService {

    private final MusicService musicService;
    private final StorageService storageService;
    private final StreamRepository streamRepository;
    private final ChunkRepository chunkRepository;
    private final TrackFeatureSync trackFeatureSync;

    @Override
    public int createStream(int musicId, StreamCreateRequest request) {
        Music music = musicService.getMusic(musicId)
            .orElseThrow(() -> new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND));

        // 같은 코덱·비트레이트를 두 번 만들면 어느 쪽을 재생해야 할지 알 수 없다.
        // 다시 올리려면 기존 스트림을 먼저 지운다.
        if (streamRepository.existsByMusicIdAndCodecAndBitrateKbps(
                musicId, request.codec(), request.bitrateKbps())) {
            throw new BusinessException(StreamErrorCode.DUPLICATE_STREAM);
        }

        Stream newStream = Stream.builder()
            .music(music)
            .bitrateKbps(request.bitrateKbps())
            .codec(request.codec())
            .durationMs(request.durationMs())
            .chunkCount(request.chunkCount())
            .status(StreamStatus.PENDING)
            .build();
        int streamId = streamRepository.save(newStream).getStreamId();
        // PENDING 이라 재생 가능 여부는 그대로지만, 곡 특징은 곡 단위로 통째로 다시 쓰므로 무엇이
        // 바뀌었는지 가리지 않는다 — 스트림을 바꾸는 입구는 모두 올린다 (S15P21A204-455).
        trackFeatureSync.markChanged(musicId);
        return streamId;
    }

    @Override
    @Transactional(readOnly = true)
    public Stream getStream(int streamId) {
        Stream stream = streamRepository.findById(streamId)
            .orElseThrow(() -> new BusinessException(StreamErrorCode.STREAM_NOT_FOUND));
        // 부모 곡이 삭제됐으면 스트림도 Gone 이다. "그런 스트림이 없다" 와
        // "딸린 곡이 지워졌다" 는 보낸 쪽이 할 일이 다르다.
        if (stream.getMusic().getIsDeleted()) {
            throw new BusinessException(MusicErrorCode.MUSIC_GONE);
        }
        return stream;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Stream> getStreamByMusicId(int musicId) {
        // 곡이 없으면 404, 삭제됐으면 410 이다. `isExists` 는 false 하나로만 답한다.
        musicService.requireAlive(musicId);
        return streamRepository.findAllByMusicId(musicId)
                .stream()
                .filter(it -> it.getStatus().equals(StreamStatus.DONE))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Stream> getStreamByMusicIds(Collection<Integer> musicIds) {
        if (musicIds.isEmpty()) {
            return List.of();
        }
        /* 곡의 존재 여부는 확인하지 않는다. 호출부가 이미 손에 쥔 곡들이다. */
        return streamRepository.findAllByMusicIdIn(musicIds)
                .stream()
                .filter(it -> it.getStatus().equals(StreamStatus.DONE))
                .toList();
    }

    @Override
    public void updateStatus(int streamId, StreamStatus status) {
        Stream stream = getStream(streamId);

        // DONE 은 "이 스트림은 이제 재생해도 된다"는 선언이다. 청크가 비면 재생 중 끊긴다.
        if (status.equals(StreamStatus.DONE)) {
            long uploaded = chunkRepository.countByChunkId_StreamId(streamId);
            if (uploaded != stream.getChunkCount()) {
                throw new BusinessException(StreamErrorCode.STREAM_CHUNK_INCOMPLETE);
            }
        }

        stream.setStatus(status);
        // DONE 이 되거나 풀리면 `track_features.playable`·`duration_ms` 가 바뀐다 (S15P21A204-455).
        trackFeatureSync.markChanged(stream.getMusic().getMusicId());
    }

    @Override
    public void deleteStream(int streamId) {
        Stream stream = streamRepository.findById(streamId)
            .orElseThrow(() -> new BusinessException(StreamErrorCode.STREAM_NOT_FOUND));

        // 청크 행을 먼저 지워야 object_storage_files 를 건드릴 수 있다 (FK).
        List<Chunk> chunks = chunkRepository.findByChunkId_StreamId(streamId);
        List<Integer> fileIds = chunks.stream()
            .map(it -> it.getFile().getFileId())
            .toList();

        chunkRepository.deleteAll(chunks);
        chunkRepository.flush();

        for (int fileId : fileIds) {
            storageService.deleteFile(fileId);
        }
        streamRepository.delete(stream);
        trackFeatureSync.markChanged(stream.getMusic().getMusicId());
    }
    
}
