package com.osundosun.mupid.api.music.request;

import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 한 곡의 파형 전체.
 *
 * <p><b>추가가 아니라 교체다.</b> 이 요청이 그 곡의 파형을 통째로 대신한다.
 * 같은 본문을 두 번 보내면 결과가 같다 — 배치가 하는 일이 정확히 "이 곡의 파형은
 * 이 값들이다" 이고, 중간에 끊겨도 반쪽짜리 파형이 남지 않아야 한다.
 *
 * <p>값은 칸마다의 RMS 를 곡 전체의 최댓값으로 나눈 <b>0~1</b> 이다. 칸 수는
 * 화면이 그리는 막대 수와 달라도 된다 — 프런트가 다시 표본한다
 * (`frontend/lib/waveform-display.ts`). 지금 배치는 160칸을 보낸다
 * (`WAVEFORM_SAMPLES`).
 */
public record WaveformReplaceRequest(

    @NotEmpty(message = "파형이 입력되지 않았습니다.")
    @Size(min = MIN_SAMPLES, max = MAX_SAMPLES,
        message = "파형은 16칸 이상 4096칸 이하여야 합니다.")
    List<
        @NotNull(message = "파형에 빈 값이 있습니다.")
        @DecimalMin(value = "0.0", message = "파형 값은 0 이상이어야 합니다.")
        @DecimalMax(value = "1.0", message = "파형 값은 1 이하여야 합니다.")
        Double
    > values

) {

    /**
     * 칸 수의 하한.
     *
     * 이보다 적으면 포락선이라 부를 만한 모양이 남지 않는다. 계산이 반쯤 실패한
     * 배치가 몇 칸만 보내는 것을 막는다.
     */
    public static final int MIN_SAMPLES = 16;

    /**
     * 칸 수의 상한.
     *
     * 파일 크기가 아니라 <b>요청 본문</b>의 상한이다. 4096칸이면 JSON 으로 30KB
     * 남짓이고, 화면이 그리는 막대는 많아야 160개라 그보다 잘게 저장할 이유가 없다.
     */
    public static final int MAX_SAMPLES = 4096;

}
