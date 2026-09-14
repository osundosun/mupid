package com.osundosun.mupid.api.stream.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        return streamRepository.save(newStream).getStreamId();
    }

    @Override
    @Transactional(readOnly = true)
    public Stream getStream(int streamId) {
        Stream stream = streamRepository.findById(streamId)
            .orElseThrow(() -> new BusinessException(StreamErrorCode.STREAM_NOT_FOUND));
        if (stream.getMusic().getIsDeleted()) {
            throw new BusinessException(StreamErrorCode.STREAM_NOT_FOUND);
        }
        return stream;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Stream> getStreamByMusicId(int musicId) {
        if (!musicService.isExists(musicId)) {
            throw new BusinessException(MusicErrorCode.MUSIC_NOT_FOUND);
        }
        return streamRepository.findAllByMusicId(musicId)
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
    }
    
}
