package com.osundosun.mupid.api.report.response;

import java.time.LocalDate;
import java.util.List;

/**
 * 여러 날의 취향 리포트를 한 번에 내려주는 응답 — 주간 달력이 쓴다.
 *
 * <h2>왜 하루치({@link ReportResponse})를 그대로 배열로 묶지 않는가</h2>
 *
 * 하루치는 트리맵 타일 40곡과 키워드 6개를 담는다. 주간 달력이 그 날짜에서 실제로
 * 쓰는 것은 <b>유형 코드·이름·캐릭터 그림과 그날 들은 시간</b>뿐이다
 * ({@code frontend/components/mupid/listening-calendar.tsx} 의 {@code summarize}).
 * 일곱 날을 묶으면 40곡 목록이 일곱 벌 중복돼 응답만 커지고 화면은 아무것도 더
 * 쓰지 않는다. 그래서 <b>달력이 읽는 필드만</b> 담는다.
 *
 * <h2>그런데 값은 하루치와 같아야 한다</h2>
 *
 * 두 경로가 각자 계산하면 같은 날의 유형이 달력과 본문에서 달라질 수 있다. 그래서
 * {@link Day#from} 은 {@link ReportResponse} 하나만 받아 줄인다 — 계산은
 * {@code ReportServiceImpl.getDailyReport} 한 곳에서만 일어난다.
 *
 * <h2>{@code time_zone} 을 싣는 이유</h2>
 *
 * {@code date} 는 {@code Asia/Seoul} 기준 날짜다. 하루의 경계가 어디인지는 클라이언트가
 * 되짚을 수 없고, 기기 시간대로 해석하면 자정 근처의 기록이 하루씩 어긋난다.
 * 응답이 스스로 자기 기준을 말하게 둔다.
 */
public record ReportDailyRangeResponse(

    /** 요청한 시작일. 포함. */
    LocalDate from,

    /** 요청한 종료일. 포함. */
    LocalDate to,

    /** {@code from}·{@code to}·{@code days[].date} 가 어느 시간대의 날짜인가. 지금은 항상 `Asia/Seoul`. */
    String timeZone,

    /** 날짜 오름차순. 요청한 모든 날이 들어 있다 — 기록이 없는 날도 빠지지 않는다. */
    List<Day> days

) {

    /**
     * 하루의 요약. 달력 카드 한 장이 이만큼 읽는다.
     *
     * <h2>기록 없는 날이 빠지지 않는 이유</h2>
     *
     * 달력은 요일 카드 일곱 장을 <b>항상</b> 그린다. 빈 날짜를 빼면 화면이 그 자리를
     * "아직 불러오는 중"으로 그려, 기록이 없는 것과 로딩 중인 것을 구분할 수 없다.
     * 그래서 빈 날은 {@code total_listening_ms = 0} · {@code mubti = null} ·
     * {@code sample.sufficient = false} 로 채워 보낸다. 하루치 조회가 기록 없는 날에
     * 빈 리포트를 주는 것과 같은 판단이다.
     */
    public record Day(

        LocalDate date,

        /** 그날 실제로 들은 시간. 기록이 없으면 0. */
        long totalListeningMs,

        /**
         * 그날의 유형. 표본이 모자라면(5분·3곡 미만) {@code null} 이다 —
         * {@link ReportResponse#mubti()} 와 같은 규칙이다.
         */
        Mubti mubti,

        /** 판정 근거가 있었는가. 달력이 "들었지만 유형이 열리지 않았다"를 그리는 근거. */
        Sample sample

    ) {

        /**
         * 하루치 리포트를 달력이 읽는 만큼으로 줄인다.
         *
         * {@link ReportResponse} 를 그대로 받는 것이 요점이다. 새로 계산하지 않으므로
         * 본문 화면과 달력이 <b>같은 값</b>을 보여 준다는 것이 타입으로 보장된다.
         */
        public static Day from(ReportResponse report) {
            ReportResponse.Mubti mubti = report.mubti();
            return new Day(
                report.date(),
                report.totalListeningMs(),
                mubti == null ? null
                    : new Mubti(mubti.code(), mubti.title(), mubti.imageUrl()),
                new Sample(report.sample().sufficient()));
        }

    }

    /** 달력 카드가 그리는 유형. 축 점수·설명은 넣지 않는다 — 그날 하나를 볼 때만 필요하다. */
    public record Mubti(

        /** `VTFC` 같은 네 글자. */
        String code,

        /** 유형명 — `보컬 인싸형` */
        String title,

        /** 그 유형의 캐릭터. 없으면 null. */
        String imageUrl

    ) {

    }

    public record Sample(

        /** 그날 재생 횟수·곡 수·청취 시간이 판정 하한을 넘겼는가. */
        boolean sufficient

    ) {

    }

}
