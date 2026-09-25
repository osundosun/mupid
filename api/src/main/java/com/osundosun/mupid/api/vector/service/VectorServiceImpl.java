package com.osundosun.mupid.api.vector.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.osundosun.mupid.api.analytics.sync.entity.AnalyticsSyncPending;
import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;
import com.osundosun.mupid.api.analytics.sync.service.VectorSync;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.repository.MusicSectionAnalysisRepository;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.vector.client.VectorPointStore;
import com.osundosun.mupid.api.vector.client.VectorPointStore.Point;
import com.osundosun.mupid.api.vector.error.VectorErrorCode;
import com.osundosun.mupid.api.vector.properties.QdrantProperties;
import com.osundosun.mupid.api.vector.repository.VectorSectionRepository;
import com.osundosun.mupid.api.vector.request.MusicVectorLine;
import com.osundosun.mupid.api.vector.request.MusicVectorsRequest;
import com.osundosun.mupid.api.vector.request.Stage1VectorsRequest;
import com.osundosun.mupid.api.vector.response.MusicVectorsResponse;
import com.osundosun.mupid.api.vector.response.Stage1StatusResponse;
import com.osundosun.mupid.api.vector.response.Stage1VectorsResponse;
import com.osundosun.mupid.api.vector.response.VectorPendingResponse;
import com.osundosun.mupid.api.vector.schema.QdrantSchema;

import lombok.extern.slf4j.Slf4j;

/**
 * 벡터 등록 (S15P21A204-457, 설계 §6.6·§7.1·§7.3).
 *
 * <h2>쓰는 모양은 AI 가 읽는 모양과 같다</h2>
 *
 * <p>정의는 {@link QdrantSchema}(= {@code ai_server/app/qdrant_collection.json})이고, AI 의 읽기는
 * {@code ai_server/app/vector_store.py} {@code QdrantVectorStore} 다. 이 서비스가 지키는 것:
 *
 * <ul>
 *   <li>포인트 id = {@code UUIDv5(NS, external_id)} — AI 가 후보 id 로 바로 조회한다.</li>
 *   <li>{@code mert} = 중심화 방향 {@code u}, payload {@code r} — AI 가 {@code μ + r·u} 로 원본을 되돌린다.
 *       μ 는 배치가 동결 μ(설계 §6.3)로 계산해 온다. <b>Spring 은 μ 를 모른다</b> — 단위 벡터인지만 본다.</li>
 *   <li>payload {@code source} — AI 는 검색마다 {@code source ∈ POOL} 로 건다. 빠지면 그 곡은 영영 안 나온다.</li>
 *   <li>구간 지문({@code analysis_version}·{@code seg_start_ms}·{@code seg_end_ms})과 {@code track_id} 는
 *       <b>MySQL 에서</b> 싣는다 — 배치가 보낸 값이 아니다. 구간의 정체성을 정하는 것이 Spring 이라서다(§7.1).</li>
 * </ul>
 *
 * <h2>순서 — 검증(MySQL, 짧은 읽기 트랜잭션) → 쓰기(Qdrant, 전용 풀) → 미반영 지우기</h2>
 *
 * <p>Qdrant 를 기다리는 동안 MySQL 연결을 잡지 않는다. 검증 때 미반영 revision 을 읽어 두고, 쓴 뒤에는
 * 그 revision 일 때만 지운다 — 그 사이 구간이 다시 바뀌었으면 행이 남아 배치가 다시 보낸다.
 */
@Slf4j
@Service
public class VectorServiceImpl implements VectorService {

    static final String MODEL_NAME = "stage1";
    private static final int PENDING_PAGE_MAX = 1_000;

    private final QdrantProperties properties;
    private final QdrantSchema schema;
    private final VectorPointStore store;
    private final VectorWriteExecutor executor;
    private final MusicService musicService;
    private final MusicSectionAnalysisRepository sectionRepository;
    private final VectorSectionRepository vectorSections;
    private final AnalyticsSyncPendingRepository pendingRepository;
    private final VectorSync vectorSync;
    private final TransactionTemplate readOnly;

    public VectorServiceImpl(QdrantProperties properties, QdrantSchema schema, VectorPointStore store,
                             VectorWriteExecutor executor, MusicService musicService,
                             MusicSectionAnalysisRepository sectionRepository,
                             VectorSectionRepository vectorSections,
                             AnalyticsSyncPendingRepository pendingRepository, VectorSync vectorSync,
                             PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.schema = schema;
        this.store = store;
        this.executor = executor;
        this.musicService = musicService;
        this.sectionRepository = sectionRepository;
        this.vectorSections = vectorSections;
        this.pendingRepository = pendingRepository;
        this.vectorSync = vectorSync;
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
    }

    // ------------------------------------------------------------------ 곡 전체

    /** 검증을 통과한 구간 하나. */
    private record Decoded(String externalId, float r, float[] mert, float[] feat,
                           Map<String, float[]> stage1, float[] clap) {
    }

    /** 검증 때 MySQL 에서 읽은 것. */
    private record Snapshot(List<MusicSectionAnalysis> sections, Optional<Long> revision) {
    }

    @Override
    public MusicVectorsResponse replaceMusicVectors(int musicId, MusicVectorsRequest request) {
        requireConfigured();
        // ① 요청 안에서 닫히는 검사 — DB 를 보기 전에. 틀린 본문이 MySQL·Qdrant 를 두드리지 않는다.
        if (!schema.embeddingVersion().equals(request.embVersion())) {
            throw new BusinessException(VectorErrorCode.EMBEDDING_VERSION_MISMATCH);
        }
        String featName = "feat_" + request.featVersion();
        int featDim = schema.dim(featName);
        if (featDim < 0) {
            throw new BusinessException(VectorErrorCode.VECTOR_DIMENSION_MISMATCH);
        }
        List<Decoded> decoded = decode(request.sections(), featDim);
        Set<String> models = modelVersions(decoded);

        // ② MySQL 과 대조. 짧은 읽기 트랜잭션.
        Snapshot snap = readOnly.execute(status -> snapshot(musicId, request));

        // ③ Qdrant — 전용 풀에서. 포인트를 먼저 쓰고 그 곡의 나머지를 지운다. 순서를 뒤집으면 그 사이
        //    그 곡이 통째로 비어 추천에서 빠진다.
        List<Point> points = new ArrayList<>(decoded.size());
        for (int i = 0; i < decoded.size(); i++) {
            points.add(point(musicId, request, featName, decoded.get(i), snap.sections().get(i)));
        }
        List<UUID> keep = points.stream().map(Point::id).toList();
        try {
            executor.run(() -> {
                ensureModelVectors(models);
                store.upsert(points);
                store.deleteTrackPointsExcept(musicId, keep);
            });
        } catch (BusinessException e) {
            vectorSync.recordFailure(musicId, e.getErrorCode().getCode());
            throw e;
        }

        // ④ 쓴 뒤 미반영을 지운다.
        vectorSync.clear(musicId, snap.revision());

        Set<String> names = new LinkedHashSet<>(List.of(QdrantSchema.MERT, featName));
        models.forEach(v -> names.add(schema.modelVector(v)));
        int clap = (int) decoded.stream().filter(d -> d.clap() != null).count();
        if (clap > 0) {
            names.add(QdrantSchema.CLAP);
        }
        log.info("벡터 등록 musicId={} version={} 구간 {} · 벡터 {}", musicId,
            request.analysisVersion(), points.size(), names);
        return new MusicVectorsResponse(musicId, request.analysisVersion(), schema.collection(),
            points.size(), List.copyOf(names), clap);
    }

    private List<Decoded> decode(List<MusicVectorLine> lines, int featDim) {
        int mertDim = schema.dim(QdrantSchema.MERT);
        int clapDim = schema.dim(QdrantSchema.CLAP);
        int modelDim = schema.modelDim();
        Set<String> seen = new HashSet<>();
        List<Decoded> out = new ArrayList<>(lines.size());
        for (MusicVectorLine line : lines) {
            String id = line.externalId().strip();
            if (!seen.add(id)) {
                throw new BusinessException(VectorErrorCode.VECTOR_SECTION_DUPLICATED);
            }
            double r = line.r();
            if (!Double.isFinite(r) || r <= 0) {
                throw new BusinessException(VectorErrorCode.VECTOR_NORM_INVALID);
            }
            float[] mert = VectorCodec.decode(line.mert(), mertDim);
            if (!VectorCodec.isUnit(mert)) {
                throw new BusinessException(VectorErrorCode.VECTOR_NOT_NORMALIZED);
            }
            float[] feat = VectorCodec.decode(line.featV1(), featDim);
            Map<String, float[]> stage1 = new TreeMap<>();
            line.stage1().forEach((version, value) -> {
                if (!QdrantSchema.isModelVersion(version)) {
                    throw new BusinessException(VectorErrorCode.VECTOR_MODEL_VERSION_INVALID);
                }
                stage1.put(version, VectorCodec.decode(value, modelDim));
            });
            float[] clap = line.clap() == null || line.clap().isBlank()
                ? null : VectorCodec.decode(line.clap(), clapDim);
            out.add(new Decoded(id, (float) r, mert, feat, stage1, clap));
        }
        return out;
    }

    /**
     * 모든 구간이 같은 모델 버전 셋을 가져야 한다. 구간마다 다르면 한 곡 안에서 어떤 구간은
     * {@code stage1_v3} 가 있고 어떤 구간은 없다 — AI 준비 판정이 "일부 구간뿐" 으로 멈춘다.
     */
    private static Set<String> modelVersions(List<Decoded> decoded) {
        Set<String> first = decoded.get(0).stage1().keySet();
        if (first.isEmpty() || decoded.stream().anyMatch(d -> !d.stage1().keySet().equals(first))) {
            throw new BusinessException(VectorErrorCode.VECTOR_MODEL_REQUIRED);
        }
        return new LinkedHashSet<>(first);
    }

    private Snapshot snapshot(int musicId, MusicVectorsRequest request) {
        // 삭제된 곡은 벡터를 받지 않는다(410). 대조가 그 곡의 포인트를 지운다(설계 §7.4).
        musicService.requireAlive(musicId);
        List<MusicSectionAnalysis> sections = sectionRepository.findByMusic_MusicIdOrderByIndexAsc(musicId);
        if (sections.isEmpty()) {
            throw new BusinessException(VectorErrorCode.VECTOR_MUSIC_HAS_NO_SECTIONS);
        }
        String version = sections.get(0).getAnalysisVersion();
        if (!version.equals(request.analysisVersion())) {
            log.warn("벡터 분석 버전 불일치 musicId={} MySQL={} 요청={}", musicId, version, request.analysisVersion());
            throw new BusinessException(VectorErrorCode.VECTOR_ANALYSIS_VERSION_MISMATCH);
        }
        List<String> expected = sections.stream().map(MusicSectionAnalysis::getExternalId).toList();
        List<String> got = request.sections().stream().map(line -> line.externalId().strip()).toList();
        if (!expected.equals(got)) {
            log.warn("벡터 구간 불일치 musicId={} MySQL {}구간 · 요청 {}구간", musicId, expected.size(), got.size());
            throw new BusinessException(VectorErrorCode.VECTOR_SECTIONS_MISMATCH);
        }
        return new Snapshot(sections, vectorSync.revision(musicId));
    }

    private Point point(int musicId, MusicVectorsRequest request, String featName, Decoded d,
                        MusicSectionAnalysis section) {
        Map<String, float[]> vectors = new LinkedHashMap<>();
        vectors.put(QdrantSchema.MERT, d.mert());
        vectors.put(featName, d.feat());
        d.stage1().forEach((version, value) -> vectors.put(schema.modelVector(version), value));
        if (d.clap() != null) {
            vectors.put(QdrantSchema.CLAP, d.clap());
        }
        // 필드 이름·형은 정의 파일의 payload 절 그대로다.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("external_id", d.externalId());
        payload.put("track_id", musicId);
        payload.put("r", d.r());
        payload.put("source", request.source());
        payload.put("emb_version", request.embVersion());
        payload.put("feat_version", request.featVersion());
        payload.put("analysis_version", section.getAnalysisVersion());
        payload.put("seg_start_ms", section.getStartTimeMs());
        payload.put("seg_end_ms", section.getEndTimeMs());
        return new Point(schema.pointId(d.externalId()), vectors, payload);
    }

    /**
     * 컬렉션에 모델 벡터가 없으면 덧붙인다(멱등). 컬렉션 자체와 고정 벡터는 만들지 않는다 — 운영
     * 절차(456 도구)가 만들고, 없으면 503 이다. 여기서 만들면 정의를 두 곳에서 해석하게 된다.
     */
    private boolean ensureModelVectors(Set<String> versions) {
        Set<String> have = store.vectorNames();
        if (!have.contains(QdrantSchema.MERT)) {
            throw new BusinessException(VectorErrorCode.VECTOR_COLLECTION_MISSING);
        }
        boolean added = false;
        for (String version : versions) {
            String name = schema.modelVector(version);
            if (!have.contains(name)) {
                store.addVector(name, schema.modelVectorParams());
                log.info("Qdrant {} 에 {} 을 덧붙였다", schema.collection(), name);
                added = true;
            }
        }
        return added;
    }

    // ------------------------------------------------------------------ 모델 재계산

    @Override
    public Stage1VectorsResponse putStage1(String version, Stage1VectorsRequest request) {
        requireConfigured();
        if (!QdrantSchema.isModelVersion(version)) {
            throw new BusinessException(VectorErrorCode.VECTOR_MODEL_VERSION_INVALID);
        }
        if (request.sections().size() > Stage1VectorsRequest.MAX_SECTIONS) {
            throw new BusinessException(VectorErrorCode.VECTOR_BATCH_TOO_LARGE);
        }
        String name = schema.modelVector(version);
        int dim = schema.modelDim();
        Map<String, float[]> byId = new LinkedHashMap<>();
        for (Stage1VectorsRequest.Line line : request.sections()) {
            if (byId.put(line.externalId().strip(), VectorCodec.decode(line.vector(), dim)) != null) {
                throw new BusinessException(VectorErrorCode.VECTOR_SECTION_DUPLICATED);
            }
        }
        // 구간 정체성이 바뀌지 않으므로 MySQL·ClickHouse 에는 쓰지 않는다(§7.3). 있는 구간인지만 본다.
        if (!vectorSections.knownExternalIds(byId.keySet()).containsAll(byId.keySet())) {
            throw new BusinessException(VectorErrorCode.VECTOR_SECTION_UNKNOWN);
        }
        Map<UUID, Map<String, float[]>> updates = new LinkedHashMap<>();
        byId.forEach((id, vec) -> updates.put(schema.pointId(id), Map.of(name, vec)));

        boolean added = executor.call(() -> {
            boolean created = ensureModelVectors(Set.of(version));
            // 없는 포인트에 벡터만 붙일 수는 없다(Qdrant 가 404). 곡 전체 벡터가 먼저다 — 미리 세어 알린다.
            if (store.existing(updates.keySet()).size() != updates.size()) {
                throw new BusinessException(VectorErrorCode.VECTOR_POINT_MISSING);
            }
            store.updateVectors(updates);
            return created;
        });
        log.info("모델 재계산 {} — {}구간", name, updates.size());
        return new Stage1VectorsResponse(version, name, updates.size(), added);
    }

    @Override
    public Stage1StatusResponse stage1Status(String version) {
        requireConfigured();
        if (!QdrantSchema.isModelVersion(version)) {
            throw new BusinessException(VectorErrorCode.VECTOR_MODEL_VERSION_INVALID);
        }
        String name = schema.modelVector(version);
        long expected = vectorSections.countAliveSections();
        boolean exists = store.vectorNames().contains(name);
        long points = store.countWithVector(QdrantSchema.MERT);
        long filled = exists ? store.countWithVector(name) : 0;
        boolean complete = exists && expected > 0 && filled >= expected && filled >= points;
        return new Stage1StatusResponse(MODEL_NAME, version, name, exists, expected, points, filled, complete);
    }

    // ------------------------------------------------------------------ 미반영

    @Override
    public VectorPendingResponse pending(int after, int limit) {
        int size = Math.max(1, Math.min(limit, PENDING_PAGE_MAX));
        List<AnalyticsSyncPending> rows = pendingRepository.findPage(VectorSync.KIND, after, size);
        List<VectorPendingResponse.Item> items = rows.stream()
            .map(row -> new VectorPendingResponse.Item(row.getId().getMusicId(), row.getAnalysisVersion(),
                row.getAttempts(), row.getLastError(), row.getCreatedAt()))
            .toList();
        Integer next = items.size() < size ? null : items.get(items.size() - 1).musicId();
        return new VectorPendingResponse(items, next);
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) {
            throw new BusinessException(VectorErrorCode.VECTOR_STORE_NOT_CONFIGURED);
        }
    }

}
