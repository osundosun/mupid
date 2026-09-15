package com.osundosun.mupid.api.report.dto;

import java.util.List;

import com.osundosun.mupid.api.report.enumerate.MubtiClass;

/**
 * MuBTI 판정 결과. `MubtiScorer` 가 만들고 `ReportService` 가 `mubti` 표와 맞춰
 * 이름·문구를 붙인다.
 *
 * **점수를 저장하지 않는다.** 판정 기준은 서비스가 굴러가면서 계속 바뀔 값이라,
 * 계산 결과를 테이블에 넣으면 기준을 고쳐도 과거 데이터가 따라오지 않는다 —
 * decision/0015 가 `v_track_skips` 를 일반 VIEW 로 둔 것과 같은 이유다. 저장하는
 * 것은 `reports.mubti_id`(그날의 결론)뿐이고, 점수는 응답에만 실린다.
 */
public record MubtiVerdict(

    MubtiClass.A classA,

    MubtiClass.B classB,

    MubtiClass.C classC,

    MubtiClass.D classD,

    /** 네 축의 원점수와 확신도. 화면이 막대로 그린다. */
    List<AxisScore> axes

) {

    /** `VTFC` 같은 네 글자. `mubti` 표를 찾는 키다. */
    public String code() {
        return "%s%s%s%s".formatted(classA.name(), classB.name(), classC.name(), classD.name());
    }

    /**
     * 축 하나.
     *
     * @param axis        `A` `B` `C` `D`
     * @param score       −1 ~ +1. 부호가 글자를 정한다
     * @param confidence  0 ~ 1. 판정에 쓸 근거가 얼마나 있었는가.
     *                    0 이면 근거가 없어 기본값으로 둔 것이다
     */
    public record AxisScore(
        String axis,
        MubtiClass.Axis picked,
        MubtiClass.Axis opposite,
        double score,
        double confidence
    ) {

    }

}
