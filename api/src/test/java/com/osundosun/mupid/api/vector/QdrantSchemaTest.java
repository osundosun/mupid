package com.osundosun.mupid.api.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.osundosun.mupid.api.vector.schema.QdrantSchema;

/**
 * 쓰는 쪽(Spring)과 읽는 쪽(ai_server)이 같은 정의를 쓰는가 (S15P21A204-457).
 */
class QdrantSchemaTest {

    private final QdrantSchema schema = QdrantSchema.fromResource();

    @Test
    @DisplayName("포인트 id 는 파이썬 uuid.uuid5 와 같다 — ai_server/tests/test_qdrant_store.py 의 대조값")
    void uuid5MatchesPython() {
        assertThat(schema.pointId("T000001#3"))
            .isEqualTo(UUID.fromString("08155de7-3e4e-58ff-8625-f9a049bc76d0"));
    }

    @Test
    @DisplayName("차원·이름·모델 벡터는 정의 파일에서 온다")
    void dimensionsFromDefinition() {
        assertThat(schema.collection()).isEqualTo("moments__mert-v1-95m");
        assertThat(schema.embeddingVersion()).isEqualTo("mert-v1-95m");
        assertThat(schema.dim("mert")).isEqualTo(1536);
        assertThat(schema.dim("feat_v1")).isEqualTo(285);
        assertThat(schema.dim("clap")).isEqualTo(512);
        assertThat(schema.dim("feat_v2")).isEqualTo(-1);
        assertThat(schema.modelDim()).isEqualTo(128);
        assertThat(schema.modelVector("v3")).isEqualTo("stage1_v3");
        assertThat(schema.modelVectorParams()).isEqualTo(Map.of("size", 128, "distance", "Cosine"));
        assertThatThrownBy(() -> schema.modelVector("3")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("리소스는 ai_server/app/qdrant_collection.json 의 바이트 사본이다 (저장소에서 돌 때)")
    void resourceIsByteCopyOfAiDefinition() throws IOException {
        Path source = Path.of("..", "ai_server", "app", "qdrant_collection.json");
        if (!Files.exists(source)) {
            return;                      // api/ 만 있는 빌드 문맥. CI 에서는 ai_server 시험이 같은 것을 본다
        }
        byte[] copy = new ClassPathResource(QdrantSchema.RESOURCE).getContentAsByteArray();
        assertThat(copy).isEqualTo(Files.readAllBytes(source));
    }

}
