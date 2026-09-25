package com.osundosun.mupid.api.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.osundosun.mupid.api.analytics.sync.repository.AnalyticsSyncPendingRepository;
import com.osundosun.mupid.api.analytics.sync.service.VectorSync;
import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.music.entity.MusicSectionAnalysis;
import com.osundosun.mupid.api.music.enumerate.SectionLabel;
import com.osundosun.mupid.api.music.repository.MusicSectionAnalysisRepository;
import com.osundosun.mupid.api.music.service.MusicService;
import com.osundosun.mupid.api.vector.client.VectorPointStore.Point;
import com.osundosun.mupid.api.vector.error.VectorErrorCode;
import com.osundosun.mupid.api.vector.properties.QdrantProperties;
import com.osundosun.mupid.api.vector.repository.VectorSectionRepository;
import com.osundosun.mupid.api.vector.request.MusicVectorLine;
import com.osundosun.mupid.api.vector.request.MusicVectorsRequest;
import com.osundosun.mupid.api.vector.request.Stage1VectorsRequest;
import com.osundosun.mupid.api.vector.response.MusicVectorsResponse;
import com.osundosun.mupid.api.vector.response.Stage1StatusResponse;
import com.osundosun.mupid.api.vector.schema.QdrantSchema;
import com.osundosun.mupid.api.vector.service.VectorCodec;
import com.osundosun.mupid.api.vector.service.VectorServiceImpl;
import com.osundosun.mupid.api.vector.service.VectorWriteExecutor;

/**
 * 벡터 등록의 판단 (S15P21A204-457). Qdrant 는 메모리 판({@link InMemoryVectorPointStore}), MySQL 은 목이다.
 *
 * <p>확인하는 것: 검증 거절 넷(구간 수·순서, 차원, 분석 버전, NaN)이 <b>Qdrant 에 닿기 전에</b> 멈추는가,
 * 응답이 나갈 때 이미 쓰였는가, 곡 단위 교체가 옛 포인트를 지우는가, 재계산이 다른 벡터를 건드리지 않는가,
 * 관문의 수, 미반영 {@code VECTOR} 를 자기 revision 일 때만 지우는가. REST 본문의 모양은 로컬 Qdrant
 * 왕복으로 따로 봤다(MR 본문).
 */
class VectorServiceImplTest {

    private static final int MUSIC_ID = 501;
    private static final String TRACK = "T000501";

    private final Random random = new Random(7);
    private final QdrantSchema schema = QdrantSchema.fromResource();

    private InMemoryVectorPointStore store;
    private MusicSectionAnalysisRepository sections;
    private VectorSectionRepository vectorSections;
    private AnalyticsSyncPendingRepository pending;
    private VectorServiceImpl service;

    @BeforeEach
    void setUp() {
        QdrantProperties props = new QdrantProperties("http://qdrant.test", "key", null,
            Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 4, Duration.ofSeconds(5));
        store = new InMemoryVectorPointStore();
        sections = mock(MusicSectionAnalysisRepository.class);
        vectorSections = mock(VectorSectionRepository.class);
        pending = mock(AnalyticsSyncPendingRepository.class);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        service = new VectorServiceImpl(props, schema, store, new VectorWriteExecutor(props),
            mock(MusicService.class), sections, vectorSections, pending, new VectorSync(pending, tx), tx);
    }

    // ------------------------------------------------------------------ 만들기

    private void mysqlHas(String version, int count) {
        List<MusicSectionAnalysis> rows = IntStream.range(0, count)
            .mapToObj(i -> MusicSectionAnalysis.builder()
                .musicSectionAnalysisId(9000 + i)
                .index(i)
                .externalId(TRACK + "#" + i)
                .sectionLabel(SectionLabel.CHORUS)
                .startTimeMs(i * 10_000)
                .endTimeMs((i + 1) * 10_000)
                .analysisVersion(version)
                .build())
            .toList();
        when(sections.findByMusic_MusicIdOrderByIndexAsc(MUSIC_ID)).thenReturn(rows);
    }

    private float[] randomVector(int dim, boolean unit) {
        float[] v = new float[dim];
        double sum = 0;
        for (int i = 0; i < dim; i++) {
            v[i] = (float) random.nextGaussian();
            sum += v[i] * v[i];
        }
        if (unit) {
            float norm = (float) Math.sqrt(sum);
            for (int i = 0; i < dim; i++) {
                v[i] /= norm;
            }
        }
        return v;
    }

    private MusicVectorLine line(int i) {
        return new MusicVectorLine(TRACK + "#" + i, 3.5 + i,
            VectorCodec.encode(randomVector(1536, true)),
            VectorCodec.encode(randomVector(285, false)),
            Map.of("v1", VectorCodec.encode(randomVector(128, false))),
            i == 0 ? VectorCodec.encode(randomVector(512, true)) : null);
    }

    private MusicVectorsRequest request(String version, List<MusicVectorLine> lines) {
        return new MusicVectorsRequest(version, "mert-v1-95m", "v1", "flow", lines);
    }

    private MusicVectorsRequest request(String version, int count) {
        return request(version, IntStream.range(0, count).mapToObj(this::line).toList());
    }

    private static void assertCode(Runnable call, VectorErrorCode code) {
        assertThatThrownBy(call::run)
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getErrorCode())
            .isEqualTo(code);
    }

    // ------------------------------------------------------------------ 검증 거절 넷

    @Test
    @DisplayName("거절 ① 구간 수·순서가 MySQL 과 다르면 409 이고 Qdrant 에 닿지 않는다")
    void rejectsSectionMismatch() {
        mysqlHas("v1", 3);
        assertCode(() -> service.replaceMusicVectors(MUSIC_ID, request("v1", 2)),
            VectorErrorCode.VECTOR_SECTIONS_MISMATCH);
        List<MusicVectorLine> swapped = new ArrayList<>(List.of(line(1), line(0), line(2)));
        assertCode(() -> service.replaceMusicVectors(MUSIC_ID, request("v1", swapped)),
            VectorErrorCode.VECTOR_SECTIONS_MISMATCH);
        assertThat(store.upserts).isZero();
    }

    @Test
    @DisplayName("거절 ② 차원이 컬렉션 정의와 다르면 400 — MySQL 을 보기 전에")
    void rejectsDimension() {
        MusicVectorLine bad = new MusicVectorLine(TRACK + "#0", 1.0,
            VectorCodec.encode(randomVector(1536, true)), VectorCodec.encode(randomVector(284, false)),
            Map.of("v1", VectorCodec.encode(randomVector(128, false))), null);
        assertCode(() -> service.replaceMusicVectors(MUSIC_ID, request("v1", List.of(bad))),
            VectorErrorCode.VECTOR_DIMENSION_MISMATCH);
        verify(sections, never()).findByMusic_MusicIdOrderByIndexAsc(anyInt());
    }

    @Test
    @DisplayName("거절 ③ 분석 버전이 MySQL 의 현재 값과 다르면 409 — 옛 분석의 벡터가 새 구간에 붙지 않는다")
    void rejectsAnalysisVersion() {
        mysqlHas("v2", 2);
        assertCode(() -> service.replaceMusicVectors(MUSIC_ID, request("v1", 2)),
            VectorErrorCode.VECTOR_ANALYSIS_VERSION_MISMATCH);
        assertThat(store.upserts).isZero();
    }

    @Test
    @DisplayName("거절 ④ NaN·무한대가 하나라도 있으면 400")
    void rejectsNonFinite() {
        float[] feat = randomVector(285, false);
        feat[17] = Float.NaN;
        MusicVectorLine nan = new MusicVectorLine(TRACK + "#0", 1.0,
            VectorCodec.encode(randomVector(1536, true)), VectorCodec.encode(feat),
            Map.of("v1", VectorCodec.encode(randomVector(128, false))), null);
        assertCode(() -> service.replaceMusicVectors(MUSIC_ID, request("v1", List.of(nan))),
            VectorErrorCode.VECTOR_NOT_FINITE);
        float[] s1 = randomVector(128, false);
        s1[0] = Float.POSITIVE_INFINITY;
        MusicVectorLine inf = new MusicVectorLine(TRACK + "#0", 1.0,
            VectorCodec.encode(randomVector(1536, true)), VectorCodec.encode(randomVector(285, false)),
            Map.of("v1", VectorCodec.encode(s1)), null);
        assertCode(() -> service.replaceMusicVectors(MUSIC_ID, request("v1", List.of(inf))),
            VectorErrorCode.VECTOR_NOT_FINITE);
    }

    @Test
    @DisplayName("mert 가 단위 벡터가 아니면(원본 Z 를 보냈으면) 거절한다 — AI 는 u 로 읽는다")
    void rejectsRawMert() {
        MusicVectorLine raw = new MusicVectorLine(TRACK + "#0", 1.0,
            VectorCodec.encode(randomVector(1536, false)), VectorCodec.encode(randomVector(285, false)),
            Map.of("v1", VectorCodec.encode(randomVector(128, false))), null);
        assertCode(() -> service.replaceMusicVectors(MUSIC_ID, request("v1", List.of(raw))),
            VectorErrorCode.VECTOR_NOT_NORMALIZED);
    }

    // ------------------------------------------------------------------ 쓰기

    @Test
    @DisplayName("응답이 나갈 때 Qdrant 에 이미 있다 — id 는 UUIDv5, payload 지문은 MySQL 에서")
    void writesBeforeResponding() {
        mysqlHas("v1", 3);
        MusicVectorsResponse got = service.replaceMusicVectors(MUSIC_ID, request("v1", 3));

        assertThat(got.points()).isEqualTo(3);
        assertThat(got.vectors()).containsExactly("mert", "feat_v1", "stage1_v1", "clap");
        assertThat(got.clapSections()).isEqualTo(1);
        Point p = store.points.get(schema.pointId(TRACK + "#1"));
        assertThat(p).isNotNull();
        assertThat(p.vectors()).containsOnlyKeys("mert", "feat_v1", "stage1_v1");
        assertThat(p.payload()).containsEntry("external_id", TRACK + "#1")
            .containsEntry("track_id", MUSIC_ID)
            .containsEntry("r", 4.5f)
            .containsEntry("source", "flow")
            .containsEntry("emb_version", "mert-v1-95m")
            .containsEntry("feat_version", "v1")
            .containsEntry("analysis_version", "v1")
            .containsEntry("seg_start_ms", 10_000)
            .containsEntry("seg_end_ms", 20_000);
        // 새 모델 벡터 이름은 컬렉션에 덧붙었다.
        assertThat(store.vectorNames).contains("stage1_v1");
    }

    @Test
    @DisplayName("곡 단위 교체 — 이번에 없는 그 곡의 옛 포인트는 지우고, 다른 곡은 건드리지 않는다")
    void replacesPerTrack() {
        mysqlHas("v1", 3);
        service.replaceMusicVectors(MUSIC_ID, request("v1", 3));
        UUID other = UUID.randomUUID();
        store.points.put(other, new Point(other, Map.of("mert", new float[1536]),
            Map.of("track_id", 777, "external_id", "T000777#0")));

        mysqlHas("v1", 2);                       // 재분석으로 구간이 하나 줄었다
        service.replaceMusicVectors(MUSIC_ID, request("v1", 2));

        assertThat(store.points).doesNotContainKey(schema.pointId(TRACK + "#2"))
            .containsKeys(schema.pointId(TRACK + "#0"), schema.pointId(TRACK + "#1"), other);
    }

    @Test
    @DisplayName("미반영 VECTOR 는 검증 때 읽은 revision 으로만 지운다 — 쓰기가 실패하면 남기고 사유를 적는다")
    void clearsPendingOnlyAfterWrite() {
        mysqlHas("v1", 2);
        when(pending.findRevision(MUSIC_ID, "VECTOR")).thenReturn(Optional.of(4L));
        service.replaceMusicVectors(MUSIC_ID, request("v1", 2));
        verify(pending).deleteIfRevision(MUSIC_ID, "VECTOR", 4L);

        store.down = true;
        assertCode(() -> service.replaceMusicVectors(MUSIC_ID, request("v1", 2)),
            VectorErrorCode.VECTOR_STORE_UNAVAILABLE);
        verify(pending).markFailed(eq(MUSIC_ID), eq("VECTOR"), anyString());
        verify(pending, org.mockito.Mockito.times(1)).deleteIfRevision(anyInt(), anyString(), anyLong());
    }

    // ------------------------------------------------------------------ 모델 재계산

    @Test
    @DisplayName("재계산은 stage1_v2 만 덧붙인다 — mert·feat·stage1_v1·payload 는 그대로다")
    void stage1AppendsWithoutTouchingOthers() {
        mysqlHas("v1", 2);
        service.replaceMusicVectors(MUSIC_ID, request("v1", 2));
        Point before = store.points.get(schema.pointId(TRACK + "#0"));
        float[] mert = before.vectors().get("mert").clone();
        float[] v1 = before.vectors().get("stage1_v1").clone();
        Map<String, Object> payload = Map.copyOf(before.payload());
        when(vectorSections.knownExternalIds(anyCollection()))
            .thenReturn(Set.of(TRACK + "#0", TRACK + "#1"));

        float[] v2 = randomVector(128, false);
        var got = service.putStage1("v2", new Stage1VectorsRequest(List.of(
            new Stage1VectorsRequest.Line(TRACK + "#0", VectorCodec.encode(v2)),
            new Stage1VectorsRequest.Line(TRACK + "#1", VectorCodec.encode(randomVector(128, false))))));

        assertThat(got.updated()).isEqualTo(2);
        assertThat(got.vectorAdded()).isTrue();
        Point after = store.points.get(schema.pointId(TRACK + "#0"));
        assertThat(after.vectors().get("stage1_v2")).containsExactly(v2);
        assertThat(after.vectors().get("mert")).containsExactly(mert);
        assertThat(after.vectors().get("stage1_v1")).containsExactly(v1);
        assertThat(after.payload()).isEqualTo(payload);
    }

    @Test
    @DisplayName("재계산 — Qdrant 에 없는 구간이 섞이면 409, MySQL 에 없는 구간이면 404, 1,000 넘으면 400")
    void stage1Rejections() {
        when(vectorSections.knownExternalIds(anyCollection())).thenReturn(Set.of(TRACK + "#0"));
        Stage1VectorsRequest one = new Stage1VectorsRequest(List.of(
            new Stage1VectorsRequest.Line(TRACK + "#0", VectorCodec.encode(randomVector(128, false)))));
        assertCode(() -> service.putStage1("v2", one), VectorErrorCode.VECTOR_POINT_MISSING);

        when(vectorSections.knownExternalIds(anyCollection())).thenReturn(Set.of());
        assertCode(() -> service.putStage1("v2", one), VectorErrorCode.VECTOR_SECTION_UNKNOWN);

        List<Stage1VectorsRequest.Line> many = IntStream.range(0, 1_001)
            .mapToObj(i -> new Stage1VectorsRequest.Line("x#" + i, "AAAA")).toList();
        assertCode(() -> service.putStage1("v2", new Stage1VectorsRequest(many)),
            VectorErrorCode.VECTOR_BATCH_TOO_LARGE);
        assertCode(() -> service.putStage1("2", one), VectorErrorCode.VECTOR_MODEL_VERSION_INVALID);
    }

    // ------------------------------------------------------------------ 관문

    @Test
    @DisplayName("관문 — 채워진 수가 MySQL 구간 수와 Qdrant mert 수 둘 다에 닿아야 complete")
    void gateCounts() {
        mysqlHas("v1", 3);
        service.replaceMusicVectors(MUSIC_ID, request("v1", 3));
        when(vectorSections.countAliveSections()).thenReturn(3L);

        Stage1StatusResponse none = service.stage1Status("v2");
        assertThat(none.vectorExists()).isFalse();
        assertThat(none.complete()).isFalse();
        assertThat(none.points()).isEqualTo(3);

        when(vectorSections.knownExternalIds(anyCollection()))
            .thenReturn(new HashSet<>(List.of(TRACK + "#0", TRACK + "#1", TRACK + "#2")));
        service.putStage1("v2", new Stage1VectorsRequest(List.of(
            new Stage1VectorsRequest.Line(TRACK + "#0", VectorCodec.encode(randomVector(128, false))),
            new Stage1VectorsRequest.Line(TRACK + "#1", VectorCodec.encode(randomVector(128, false))))));
        Stage1StatusResponse partial = service.stage1Status("v2");
        assertThat(partial.filled()).isEqualTo(2);
        assertThat(partial.expectedSections()).isEqualTo(3);
        assertThat(partial.complete()).isFalse();

        service.putStage1("v2", new Stage1VectorsRequest(List.of(
            new Stage1VectorsRequest.Line(TRACK + "#2", VectorCodec.encode(randomVector(128, false))))));
        Stage1StatusResponse done = service.stage1Status("v2");
        assertThat(done.filled()).isEqualTo(3);
        assertThat(done.complete()).isTrue();
        assertThat(done.vector()).isEqualTo("stage1_v2");

        // MySQL 구간은 다 찼어도 Qdrant 에 stage1_v2 없는 포인트가 남아 있으면 AI 준비 판정이 멈춘다.
        UUID stale = UUID.randomUUID();
        store.points.put(stale, new Point(stale, Map.of("mert", new float[1536]), Map.of("track_id", 9)));
        assertThat(service.stage1Status("v2").complete()).isFalse();
    }

}
