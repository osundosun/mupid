package com.osundosun.mupid.api.report.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.osundosun.mupid.api.music.repository.MusicRepository;
import com.osundosun.mupid.api.report.entity.Listening;
import com.osundosun.mupid.api.report.entity.Mubti;
import com.osundosun.mupid.api.report.entity.Report;
import com.osundosun.mupid.api.report.repository.ListeningRepository;
import com.osundosun.mupid.api.report.repository.ReportRepository;
import com.osundosun.mupid.api.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 계산된 리포트를 MySQL `reports` · `listening` 에 남긴다.
 *
 * <h2>왜 조회 서비스와 분리했는가</h2>
 *
 * 리포트 조회는 읽기 경로다. 거기에 `@Transactional` 쓰기를 섞으면 30초마다 폴링하는
 * 화면이 그대로 MySQL 쓰기 부하가 된다. 쓰기를 따로 떼어 두고 호출부가 <b>언제 쓸지를
 * 정하게</b> 한다 (오늘치는 5분 throttle, 지난 날짜는 없을 때 한 번, 자정 배치는 확정).
 *
 * <h2>실패는 여기서 삼키지 않는다</h2>
 *
 * 적재는 부수 효과이므로 <b>호출부가</b> 실패를 삼켜야 한다. 이 클래스 안에서
 * `try/catch` 로 감싸면 안 된다 — `@Transactional` 안에서 예외를 잡아도 트랜잭션은
 * 이미 rollback-only 로 표시돼 있어서, 커밋 시점에 `UnexpectedRollbackException`
 * 이 프록시 경계에서 다시 튀어나온다. 삼킨 것처럼 보이지만 삼켜지지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportSnapshotWriter {

    /** `listening.listening_ms` 가 INT 다. 하루(86,400,000ms)는 넉넉히 들어가지만 막아 둔다. */
    private static final long LISTENING_MS_MAX = Integer.MAX_VALUE;

    private final ReportRepository reportRepository;
    private final ListeningRepository listeningRepository;
    private final UserRepository userRepository;
    private final MusicRepository musicRepository;

    /**
     * 하루치 스냅샷을 갈아 끼운다.
     *
     * `uq_report_period (user_id, start_date)` 로 하루 한 행이므로, 있으면 유형만
     * 갱신하고 곡별 청취량은 통째로 다시 쓴다. 남겨 두고 덧쓰면 삭제된 곡의 옛 행이
     * 남아 합이 어긋난다.
     */
    @Transactional
    public void store(int userId, LocalDate date, Mubti mubti, Map<Integer, Long> listeningMs) {
        LocalDateTime startDate = date.atStartOfDay();
        Report report = reportRepository.findByUserAndStartDate(userId, startDate)
            .map(existing -> {
                existing.setMubti(mubti);
                return existing;
            })
            .orElseGet(() -> Report.builder()
                .user(userRepository.getReferenceById(userId))
                .mubti(mubti)
                .startDate(startDate)
                // 반열린 구간 [start_date, end_date) 다. 집계 쿼리의 경계와 정확히
                // 같은 값을 넣는다.
                //
                // "그날의 마지막 순간"(23:59:59.999999999)을 넣어 보면 `end_date` 가
                // `DATETIME`(초 정밀도)이라 **다음 날 00:00:00 으로 반올림된다.**
                // 결국 같은 값이 저장되면서 의도만 흐려지므로, 처음부터 열린 끝을
                // 넣고 그렇게 읽는다.
                .endDate(date.plusDays(1).atStartOfDay())
                .build());
        Report saved = reportRepository.save(report);

        listeningRepository.deleteByReport_ReportId(saved.getReportId());
        // 지운 행과 넣을 행이 같은 키를 쓴다. 먼저 내보내지 않으면 Hibernate 가
        // INSERT 를 DELETE 보다 앞세워 유니크 제약에 걸린다.
        listeningRepository.flush();

        List<Listening> rows = new ArrayList<>(listeningMs.size());
        for (Map.Entry<Integer, Long> entry : listeningMs.entrySet()) {
            rows.add(Listening.of(
                saved,
                musicRepository.getReferenceById(entry.getKey()),
                (int)Math.min(entry.getValue(), LISTENING_MS_MAX)));
        }
        listeningRepository.saveAll(rows);
    }

    // 실패를 삼키는 자리는 여기가 아니라 `ReportServiceImpl` 이다. 같은 빈 안에서
    // 감싸는 메서드를 두면 자기 호출이라 프록시를 타지 않아 `@Transactional` 이
    // 통째로 무시된다.

}
