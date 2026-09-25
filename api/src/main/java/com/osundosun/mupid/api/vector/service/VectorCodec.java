package com.osundosun.mupid.api.vector.service;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.vector.error.VectorErrorCode;

/**
 * 벡터 본문 풀기 — float32 리틀엔디언 배열의 base64 (S15P21A204-457, 설계 §11 미결 5).
 *
 * <p>numpy 로는 {@code base64.b64encode(np.asarray(v, "<f4").tobytes())} 이다. 바이트 길이가
 * {@code 차원 × 4} 가 아니면 차원이 틀린 것이고, 값 하나라도 NaN·무한대면 거절한다 — Qdrant 는 NaN 을
 * 받아 두고 검색에서 조용히 이상한 점수를 낸다.
 */
public final class VectorCodec {

    /** {@code ‖u‖} 가 1 에서 이만큼 벗어나면 중심화 방향이 아니라고 본다. float32 반올림은 1e-6 수준이다. */
    static final double UNIT_TOLERANCE = 1e-3;

    private VectorCodec() {
    }

    public static float[] decode(String base64, int dim) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(VectorErrorCode.VECTOR_ENCODING_INVALID);
        }
        if (bytes.length % Float.BYTES != 0) {
            throw new BusinessException(VectorErrorCode.VECTOR_ENCODING_INVALID);
        }
        if (bytes.length != dim * Float.BYTES) {
            throw new BusinessException(VectorErrorCode.VECTOR_DIMENSION_MISMATCH);
        }
        float[] out = new float[dim];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out);
        for (float value : out) {
            if (!Float.isFinite(value)) {
                throw new BusinessException(VectorErrorCode.VECTOR_NOT_FINITE);
            }
        }
        return out;
    }

    public static String encode(float[] values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(values);
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    static boolean isUnit(float[] values) {
        double sum = 0;
        for (float value : values) {
            sum += (double) value * value;
        }
        return Math.abs(Math.sqrt(sum) - 1.0) <= UNIT_TOLERANCE;
    }

}
