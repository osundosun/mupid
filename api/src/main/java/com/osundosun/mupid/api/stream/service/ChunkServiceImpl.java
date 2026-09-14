package com.osundosun.mupid.api.stream.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.storage.dto.StorageObjectStat;
import com.osundosun.mupid.api.storage.entity.ObjectStorageFile;
import com.osundosun.mupid.api.storage.enumerate.FileCategory;
import com.osundosun.mupid.api.storage.enumerate.FileType;
import com.osundosun.mupid.api.storage.properties.StorageProperties;
import com.osundosun.mupid.api.storage.service.StorageService;
import com.osundosun.mupid.api.stream.dto.ChunkStorageUrl;
import com.osundosun.mupid.api.stream.entity.Chunk;
import com.osundosun.mupid.api.stream.entity.Chunk.ChunkId;
import com.osundosun.mupid.api.stream.entity.Stream;
import com.osundosun.mupid.api.stream.enumerate.StreamStatus;
import com.osundosun.mupid.api.stream.error.ChunkErrorCode;
import com.osundosun.mupid.api.stream.error.StreamErrorCode;
import com.osundosun.mupid.api.stream.repository.ChunkRepository;
import com.osundosun.mupid.api.stream.request.ChunkCommitRequest;

import lombok.RequiredArgsConstructor;

@Service
@Transactional
@RequiredArgsConstructor
public class ChunkServiceImpl implements ChunkService {

    /**
     * 청크 하나의 상한. 6초 Opus 128k 는 100KB 남짓이라 충분히 여유 있는 값이다.
     *
     * 바이트가 스프링을 거치지 않으므로 이 검사는 업로드 허가 단계에서
     * Content-Length 로만 한다. nginx 쪽 `client_max_body_size` 가 최종 방어선이다.
     */
    public static final long CHUNK_FILE_MAX_SIZE = 1024 * 1024 * 10; // 10 MB

    /**
     * 청크 파일의 mime. 컨테이너 자체는 자체 포맷(RTPK)이고 페이로드가 Opus 다.
     * object_storage_files.mime 이 varchar(15) 라 짧게 유지해야 한다.
     */
    public static final String CHUNK_MIME = "audio/opus";

    private final ChunkRepository repository;
    private final StreamService streamService;
    private final StorageService storageService;
    private final StorageProperties storageProps;

    @Override
    @Transactional(readOnly = true)
    public Chunk getChunk(int streamId, int index) {
        Chunk chunk = repository.findByChunkId(new ChunkId(streamId, index))
            .orElseThrow(() -> new BusinessException(ChunkErrorCode.CHUNK_NOT_FOUND));
        boolean isDeleted = chunk.getStream().getMusic().getIsDeleted();
        if (isDeleted) {
            throw new BusinessException(ChunkErrorCode.CHUNK_NOT_FOUND);
        }
        return chunk;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Chunk> getChunkAll(int streamId) {
        List<Chunk> chunks = repository.findByChunkId_StreamId(streamId);
        if (chunks.size() > 0) {
            boolean isDeleted = chunks.get(0).getStream().getMusic().getIsDeleted();
            if (!isDeleted) {
                return chunks;
            }
        }
        return List.of();
    }

    @Override
    @Transactional(readOnly = true)
    public ChunkStorageUrl getStorageUrl(int streamId, int index) {
        Chunk chunk = getChunk(streamId, index);
        ObjectStorageFile chunkFile = chunk.getFile();
        return new ChunkStorageUrl(
            getAccelRedirectUri(chunkFile.getStorageObjectKey()),
            chunkFile.getMime());
    }

    @Override
    @Transactional(readOnly = true)
    public String prepareUpload(int streamId, int index, Long contentLength) {
        Stream stream = streamService.getStream(streamId);

        // 완료 선언된 스트림을 갈아끼우면 재생 중인 클라이언트가 깨진다. 지우고 다시 만든다.
        if (stream.getStatus().equals(StreamStatus.DONE)) {
            throw new BusinessException(StreamErrorCode.STREAM_ALREADY_DONE);
        }
        if (index < 0 || index >= stream.getChunkCount()) {
            throw new BusinessException(ChunkErrorCode.INVALID_CHUNK_INDEX);
        }
        if (contentLength != null) {
            if (contentLength <= 0) {
                throw new BusinessException(ChunkErrorCode.EMPTY_CHUNK_FILE);
            }
            if (contentLength > CHUNK_FILE_MAX_SIZE) {
                throw new BusinessException(ChunkErrorCode.CHUNK_FILE_SIZE_EXCEEDED);
            }
        }

        return getStorageObjectPath(getStorageKey(stream, index));
    }

    @Override
    public Chunk commitChunk(int streamId, int index, ChunkCommitRequest request) {
        Stream stream = streamService.getStream(streamId);

        if (stream.getStatus().equals(StreamStatus.DONE)) {
            throw new BusinessException(StreamErrorCode.STREAM_ALREADY_DONE);
        }
        if (index < 0 || index >= stream.getChunkCount()) {
            throw new BusinessException(ChunkErrorCode.INVALID_CHUNK_INDEX);
        }

        // 바이트를 보지 못했으므로 "정말 올라왔는지" 는 스토리지에 물어본다.
        // HEAD 라서 본문은 받지 않는다.
        String storageKey = getStorageKey(stream, index);
        StorageObjectStat stat = storageService.statObject(storageKey)
            .orElseThrow(() -> new BusinessException(ChunkErrorCode.CHUNK_NOT_UPLOADED));
        if (stat.sizeBytes() != request.sizeBytes()) {
            throw new BusinessException(ChunkErrorCode.CHUNK_SIZE_MISMATCH);
        }

        ObjectStorageFile storageFile = storageService.registerFile(
            FileType.MUSIC,
            FileCategory.STREAMING,
            storageKey,
            "chunk_%04d.rtp".formatted(index),
            CHUNK_MIME,
            request.sizeBytes());

        // 같은 순서를 다시 커밋하면 덮어쓴다. 배치가 중간에 죽어도 그대로 다시 돌릴 수 있어야 한다.
        Chunk chunk = repository.findByChunkId(new ChunkId(streamId, index)).orElse(null);
        if (chunk == null) {
            chunk = repository.save(Chunk.builder()
                .chunkId(new ChunkId(streamId, index))
                .stream(stream)
                .durationMs(request.durationMs())
                .file(storageFile)
                .fileHash(request.fileHash())
                .build());
        } else {
            chunk.setDurationMs(request.durationMs());
            chunk.setFile(storageFile);
            chunk.setFileHash(request.fileHash());
        }

        // 첫 청크가 커밋된 순간부터는 "인코딩 진행중" 이다.
        if (stream.getStatus().equals(StreamStatus.PENDING)) {
            stream.setStatus(StreamStatus.IN_PROGRESS);
        }

        return chunk;
    }

    /**
     * 청크 오브젝트 키. 곡·코덱·비트레이트별로 디렉터리가 갈린다.
     * 예) music/12/opus128/chunk_0003.rtp
     */
    private String getStorageKey(Stream stream, int index) {
        return "music/%d/%s%d/chunk_%04d.rtp".formatted(
            stream.getMusic().getMusicId(),
            stream.getCodec().name().toLowerCase(),
            stream.getBitrateKbps(),
            index);
    }

    /**
     * 스토리지 업스트림(SeaweedFS filer) 기준 오브젝트 경로.
     * nginx 가 업로드 본문을 그대로 이 경로로 흘려보낸다.
     */
    private String getStorageObjectPath(String key) {
        return "/buckets/%s/%s".formatted(storageProps.bucketName(), key);
    }

    /**
     * 다운로드용 X-Accel-Redirect URI. nginx 의 internal `/storage/` location 을 거친다.
     */
    private String getAccelRedirectUri(String key) {
        return "/storage" + getStorageObjectPath(key);
    }
    
}
