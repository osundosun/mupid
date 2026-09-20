package com.osundosun.mupid.api.report.dto;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import com.osundosun.mupid.api.common.exception.BusinessException;
import com.osundosun.mupid.api.report.error.ReportErrorCode;

/**
 * 일별 리포트 조회의 기간 — 검증된 `from ~ to`(양끝 포함)와 그 안의 날짜 목록.
 *
 * <h2>왜 값 객체로 떼어 냈는가</h2>
 *
 * {@code MubtiScorer} · {@code CatalogBaseline} 과 같은 이유다. 기간 규칙은
 * <b>날짜 산술과 경계 판단</b>뿐이라 리포지토리도 ClickHouse 도 필요 없다. 서비스에
 * 두면 규칙을 확인하려고 컨텍스트를 띄워야 하고, 그러면 경계값(하루 차이·상한 ±1)을
 * 검사하는 테스트가 인프라에 묶인다.
 *
 * <h2>시간대</h2>
 *
 * 여기서 시간대를 다루지 않는다. {@code LocalDate} 는 이미 날짜고, 어느 시간대의
 * 날짜인지는 호출부가 정한다 — 지금은 언제나 {@code Asia/Seoul}
 * ({@code ReportServiceImpl.ZONE}). 하루의 경계가 ClickHouse
 * {@code DateTime64(3, 'Asia/Seoul')} 과 같아야 하므로 그 값을 여기서 바꿔 끼우지 않는다.
 *
 * <h2>왜 잘라 내지 않고 오류로 끝내는가</h2>
 *
 * 페이지 크기({@code size})는 범위 안으로 당겨도(`Math.clamp`) 뜻이 흐려지지 않는다 —
 * "그만큼만 보여 준다"가 그 파라미터의 뜻이기 때문이다. 기간은 다르다. 1년을 요청했는데
 * 31일치를 조용히 돌려주면 <b>클라이언트는 받은 것을 요청한 창으로 읽는다.</b> 화면에는
 * "이 기간에는 기록이 없다"로 보이는데 실제로는 잘린 것이라, 조용한 절단은 틀린 답을
 * 맞는 답처럼 보이게 만든다. 그래서 {@code REPORT_RANGE_TOO_LONG} 으로 거절한다.
 */
public record ReportRange(

    /** 시작일. 포함. */
    LocalDate from,

    /** 종료일. 포함. */
    LocalDate to,

    /** `from` ~ `to` 를 하루씩 늘어놓은 것. 오름차순이며 화면이 왼쪽→오른쪽으로 그린다. */
    List<LocalDate> days

) {

    /**
     * 한 번에 조회할 수 있는 최대 일수.
     *
     * 31일인 근거는 둘이다.
     *
     * <ol>
     *   <li><b>화면이 실제로 쓰는 창은 7일이다.</b> 주간 MUBTI 대화상자가 일요일부터
     *       토요일까지 일곱 장을 그린다 ({@code frontend/components/mupid/listening-calendar.tsx}).
     *       31일은 그 네 배가 넘는 여유다.</li>
     *   <li><b>한 요청이 nginx 의 읽기 타임아웃 안에 들어와야 한다.</b> 스냅샷이 없는
     *       날짜는 조회 시점에 ClickHouse 에서 다시 집계한다
     *       (decision/0019 의 backfill-on-read). 하루에 집계 질의 3개가 붙으므로
     *       창이 길어질수록 요청 시간이 날짜 수에 비례해 늘어난다. 운영 nginx 의
     *       {@code /api/} 는 {@code proxy_read_timeout 60s} 이고
     *       ({@code application.yaml} 의 {@code clickhouse.console-timeout} 주석이
     *       같은 값을 근거로 든다), 31일은 그 안에 들어오는 크기다.</li>
     * </ol>
     *
     * 31일보다 긴 창은 <b>일별 목록이 아니라 주·월 집계</b>로 답하는 것이 맞다. 그것은
     * {@code reports} 위에서 만드는 별도 작업이고 이 티켓의 범위가 아니다
     * ({@code knowledge/domain/mubti-report.md} 의 "남은 것").
     */
    public static final int MAX_DAYS = 31;

    /**
     * 기간을 검증하고 만든다.
     *
     * 검사 순서에 뜻이 있다. <b>값의 유무 → 순서 → 미래 → 길이</b> 순으로 본다.
     * {@code to} 가 미래이면서 상한도 넘는 요청은 <b>날짜 오류</b>로 답한다 — 무엇이
     * 잘못됐는지 더 근본적인 쪽을 먼저 말하는 편이 고치기 쉽다.
     *
     * @param today 오늘. {@code Asia/Seoul} 기준이며 호출부가 넘긴다. 테스트가 경계를
     *              고정할 수 있도록 인자로 받는다 — 안에서 {@code LocalDate.now()} 를
     *              부르면 자정 근처에서 테스트가 흔들린다.
     * @throws BusinessException {@code REPORT_INVALID_DATE} 값이 없거나, 뒤집혔거나,
     *                           미래를 포함할 때. {@code REPORT_RANGE_TOO_LONG}
     *                           {@link #MAX_DAYS} 를 넘을 때.
     */
    public static ReportRange of(LocalDate from, LocalDate to, LocalDate today) {
        if (from == null || to == null) {
            throw new BusinessException(ReportErrorCode.REPORT_INVALID_DATE);
        }
        if (from.isAfter(to)) {
            throw new BusinessException(ReportErrorCode.REPORT_INVALID_DATE);
        }
        if (to.isAfter(today)) {
            throw new BusinessException(ReportErrorCode.REPORT_INVALID_DATE);
        }

        // 양끝을 포함하므로 하루짜리 기간의 길이는 1 이다.
        long length = ChronoUnit.DAYS.between(from, to) + 1;
        if (length > MAX_DAYS) {
            throw new BusinessException(ReportErrorCode.REPORT_RANGE_TOO_LONG);
        }

        List<LocalDate> days = new ArrayList<>((int)length);
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            days.add(date);
        }
        return new ReportRange(from, to, List.copyOf(days));
    }

}
