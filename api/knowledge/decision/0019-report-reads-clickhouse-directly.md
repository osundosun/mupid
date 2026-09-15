# 0019. 취향 리포트는 ClickHouse 를 직접 읽고, 결론만 MySQL 에 남긴다

**상태** 채택 · 구현 완료

## 맥락

'내 취향' 화면은 세 가지를 보여준다 — 곡별 청취량 트리맵 · 음악 키워드 · MuBTI
판정. 셋 다 청취 이벤트를 집계해야 나오는데, 그 이벤트는 ClickHouse 에 있고
**API 서버에는 ClickHouse 조회 계층이 없었다.** 설정(`ClickHouseConfig`)과
`clickHouseJdbcTemplate` 빈은 있는데 읽는 코드가 한 줄도 없었다.

[api-todo](../api-todo.md) 13번이 이걸 "⛔ 막힘 — 팀이 정할 것" 으로 남겨 두었고,
[0018](0018-history-from-event-stream.md) 도 "최근 감상" 을 결정할 때 같은 갈림길을
만나 **ClickHouse 조회를 피하는 쪽**(MySQL 사본)을 골랐다. "화면 한 줄 그리자고
분석 클러스터를 서비스 경로에 끌어들이는 것도 과하다" 가 그때의 판단이었다.

리포트는 사정이 다르다. **집계 자체가 화면의 내용**이라 사본으로 대신할 수 없다.

## 후보

| | 방법 | 문제 |
|---|---|---|
| A | 리포트도 MySQL 사본만 읽는다 | 수집기가 `PLAY_PROGRESS` 하트비트까지 MySQL 에 남겨야 한다. 5초마다 들어오는 이벤트다 — [0018](0018-history-from-event-stream.md) 이 명시적으로 막은 것 |
| B | 배치가 매일 MySQL 에 집계를 채우고 화면은 그것만 읽는다 | 오늘치가 없다. "지금 들은 것이 반영되는" 화면이 목적인데 하루 뒤에야 보인다 |
| C | **조회할 때마다 ClickHouse 를 직접 읽는다** | 서비스 경로가 분석 클러스터에 의존한다 |

## 결정

**C — 리포트는 ClickHouse 를 직접 읽는다. 다만 의존을 이 화면 하나에 가둔다.**

### 가두는 방법

1. **`ClickHouseConfig` 가 헬스 컨트리뷰터를 떼어 놓은 상태를 유지한다.** 분석
   클러스터가 죽어도 `/api/actuator/health` 는 UP 이고, nginx 의
   `depends_on: service_healthy` 가 걸리지 않는다. 인증·회원·재생은 ClickHouse 와
   무관하다.
2. **집계 실패는 `REPORT_ANALYTICS_UNAVAILABLE`(503) 하나로 끝난다.** 다른 화면은
   아무 영향이 없다.
3. **쿼리는 한 사용자의 하루치 슬라이스만 읽는다.** `ORDER BY (user_id,
   occurred_at, event_id)` 의 접두사라 인덱스가 듣는다. 클러스터 전체를 훑는
   쿼리는 두지 않는다.
4. **비싼 조각은 수명을 나눠 캐시한다.** 90일을 훑는 "전에 들어본 곡" 은 그날 안에
   변하지 않으므로 6시간, 카탈로그 기준선은 1시간이다. 매번 도는 것은 하루치를
   읽는 두 쿼리뿐이다.

### MySQL 에는 결론만 남긴다

`reports`(그날의 유형) · `listening`(곡별 청취량)만 쓴다. **축 점수는 저장하지
않는다** — [0015](0015-derive-interpretation-at-query-time.md) 가 정한 대로
해석은 조회 시점에 계산해야 기준을 고쳤을 때 과거까지 소급된다. 저장하는 것은
"무슨 일이 있었나"(청취량)와 "그래서 뭐라고 불렀나"(유형)이지 "왜 그렇게
판정했나"(점수)가 아니다.

적재 시점은 셋이다.

| 시점 | 동작 | 왜 |
|---|---|---|
| 오늘치 조회 | 5분 throttle 스냅샷 | 화면이 30초마다 폴링한다. 조회마다 쓰면 읽기 경로에 쓰기 폭풍이 붙는다 |
| 지난 날짜 조회 | 없으면 계산해 저장 | 한 번 확정되면 다시 계산할 이유가 없다 |
| 00:05 KST 배치 | 전날 확정 저장 | "아무도 안 열어본 날" 을 채운다 |

## 따라온 것

- **저장소의 첫 `@EnableScheduling` 이 들어왔다.** 스케줄러는 인스턴스마다 돌기
  때문에 배치 진입에 Redis 선점(SETNX)을 걸었다. 지금 배포가 한 벌이어도 그렇게
  둔다 — 인스턴스가 늘어나는 순간 조용히 깨지는 종류의 문제다.
- **구간 기반 신호를 하나도 쓰지 못했다.** `play_section_coverage` ·
  `user_section_signals` · `v_track_skips` 는 전부 `track_sections` 에 의존하는데
  그 표를 채우는 파이프라인이 저장소에 없다. MuBTI A축(보컬/멜로디)이 가장 약한
  축이 된 이유가 이것이다 — `has_vocal` 이라는 **곡의 속성**으로 판정하고 있고,
  듣는 방식을 보지 못한다.
- **`track_id` = `music_id` 를 전제로 굳혔다.** [open-questions C5](../open-questions.md)
  는 여전히 열려 있다. 대신 맞지 않는 청취량을 `unmatched_ms` 로 응답에 실어,
  전제가 깨지면 조용히 사라지지 않고 드러나게 했다.
- **`Listening` 엔티티의 매핑 결함 두 개를 고쳤다.** no-arg 생성자가 없어 Hibernate
  가 인스턴스를 만들지 못했고, `@EmbeddedId` 가 `@MapsId` 없이
  `insertable = false` 라 PK 가 빈 채로 INSERT 될 수 있었다. ERD 변경은 아니다.

## 다시 볼 것

**폴링이 언제 부담이 되는가.** 사용자당 분당 2회이고 대부분 하루치 슬라이스라
지금은 여유롭다. 동시 사용자가 늘면 오늘치 리포트 전체를 짧은 TTL 로 캐시하는
단계가 남아 있다 — 지금 넣지 않은 것은 응답을 직렬화해 Redis 에 넣는 비용이
쿼리보다 싸다는 근거가 없어서다.

**`track_sections` 가 채워지면 A축을 다시 만든다.** 곡의 어느 구간에 머물렀는지를
보면 "후렴만 되감아 듣는가 / 인트로·솔로에 머무는가" 라는 진짜 행동 신호가 생긴다.
계산식은 [domain/mubti-report.md](../domain/mubti-report.md) 에 적어 두었다.

## 근거

`api/src/main/java/com/osundosun/mupid/api/analytics/repository/ListenAnalyticsRepository.java`,
`api/src/main/java/com/osundosun/mupid/api/report/`,
[domain/mubti-report.md](../domain/mubti-report.md),
[decision/0015](0015-derive-interpretation-at-query-time.md),
[decision/0018](0018-history-from-event-stream.md)
