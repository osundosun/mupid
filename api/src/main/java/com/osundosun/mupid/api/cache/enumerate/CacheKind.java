package com.osundosun.mupid.api.cache.enumerate;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum CacheKind {

    BlockToken("block:token"),

    /**
     * 이미 받은 청취 이벤트. 재시도로 같은 event_id 가 다시 와도 Kafka 에 두 번 넣지 않는다.
     */
    EventId("event:id"),

    /**
     * 추천 묶음 번호 → 그 추천이 화면에 건 사유 문장.
     *
     * **재생 이벤트에는 사유가 실리지 않는다.** 진입 경로(`entry_path`)만 실리고, 사유는
     * 사용자가 곡을 누르기 **전에** 본 추천 응답에만 있다
     * (`knowledge/domain/listening-events.md` 의 `PLAY_START` 고유 필드). 그래서 응답을
     * 내보내는 자리에서 여기 적어 두고, 재생이 들어올 때 `rec_id` 로 되짚는다.
     *
     * 상관 키가 있어 가능한 일이다 — `NextTrackService` 가 화면에 `recommendation_id` 를
     * 주고 화면이 `rec_id` 로 되돌려 준다. 그 왕복이 곧 조회 키다.
     */
    RecommendationReason("recommendation:reason"),

    /**
     * 관리자 ClickHouse 콘솔의 열람권. 값은 연 사람의 이메일이다.
     *
     * JWT 로 만들지 않는다. iframe 은 로그아웃했다고 알려 주지 않으므로, 서버가
     * 지워서 바로 끊을 수 있어야 한다.
     */
    ClickHouseConsole("admin:clickhouse:console"),

    /**
     * 그날 이전에 들어본 곡 번호 (취향 리포트 D축).
     *
     * 90일치를 훑는 리포트에서 가장 무거운 질의인데 **그날 안에는 값이 변하지 않는다.**
     * 오늘치 집계와 수명을 나누려고 따로 둔다.
     */
    ReportHeardTracks("report:heard"),

    /**
     * 직전 MuBTI 판정 네 글자.
     *
     * 점수가 0 근처일 때 조회할 때마다 글자가 뒤집히는 것을 막는다 (이력 현상).
     * 저장하는 것은 결론뿐이고 점수는 매번 다시 계산한다 — decision/0015.
     */
    ReportVerdict("report:verdict"),

    /**
     * 오늘치 리포트를 MySQL 에 스냅샷한 표시. 있으면 이번 조회에서는 쓰지 않는다.
     * 조회마다 쓰면 읽기 경로에 쓰기 폭풍이 붙는다.
     */
    ReportSnapshot("report:snapshot"),

    /**
     * 자정 배치 선점. API 를 여러 벌 띄워도 하루치 확정 저장은 한 번만 돈다.
     */
    ReportBatch("report:batch"),

    /** 오늘의 모먼트 생성 선점. 데이터 배치의 시작 요청과 정오 예비 스케줄이 함께 잡는다. */
    DailyRecommendationBatch("recommendation:daily:batch"),

    /**
     * 재생수 집계 배치 선점. 스케줄러는 인스턴스마다 돈다.
     *
     * <p>이 배치가 {@code music.play_count} 를 채우고, 그 값이 목록 조회의 <b>기본 정렬</b>
     * (조회순)이 된다. 즉 선점이 뚫려 여러 인스턴스가 동시에 돌면 같은 곡의 값을 서로
     * 다른 시점의 집계로 덮어써 <b>화면 순서가 흔들린다</b> — 다른 배치들과 달리
     * 중복 실행의 결과가 "더 오래 걸린다" 로 끝나지 않는다.
     */
    PlayCountBatch("music:playcount:batch"),

    /**
     * AI 선호도 시간별 집계 배치 선점. 키는 그 회차가 맡은 시각(`yyyy-MM-ddTHH`)이다.
     *
     * <p>선점이 뚫려도 값은 틀리지 않는다 — 결과 표가 ReplacingMergeTree 라 같은 칸을 두 번
     * 넣어도 한 줄만 남는다. ClickHouse 를 인스턴스 수만큼 때리지 않으려는 것뿐이다.
     */
    AiPreferenceBatch("analytics:ai-preference:batch"),
    /**
     * 사용자별 재생 큐. `queue:user:{userId}` 한 자리에 큐 한 벌이 통째로 들어간다.
     *
     * **이 Redis 에서 유일하게 오래 사는(90일) 키다.** 나머지는 잃어도 다시 만들 수
     * 있는 값이지만 큐는 사용자가 직접 담은 것이라 복구할 길이 없다 — decision/0022.
     */
    PlayQueue("queue:user");

    private final String redisKey;
    
}
