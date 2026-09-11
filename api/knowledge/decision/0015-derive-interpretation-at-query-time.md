# 0015. 해석(분류·가중치)은 저장하지 않고 쿼리 시점에 계산한다

**상태** 채택 · `v_track_skips` 는 준수, `mv_user_section_signals` 는 미준수

## 맥락

스킵을 어떻게 볼지가 문제였다. 명세는 스킵을 세 가지로 나눈다 — `EARLY_SKIP`(진입 직후), `SKIP_AFTER_HOOK`(전에 들어봐서 싫증), `LATE_SKIP`(거의 다 듣고 넘김). 그런데 **프론트는 이 셋을 판정할 수 없다.** `SKIP_AFTER_HOOK` 은 "이 사용자가 이 구간을 전에 얼마나 들었는가" 를 알아야 하는데, 그건 브라우저에 없는 정보다. `EARLY`/`LATE` 도 구간 경계를 알아야 하고, 구간은 백엔드가 매긴다.

그래서 프론트는 **무엇으로 넘겼는지(`skip_trigger`)와 언제 넘겼는지(`position_ms`)만** 보내고 분류는 나중에 한다. 여기까지는 자연스럽다. 문제는 "나중에" 를 ClickHouse 어디에서 하느냐였다.

[0005](0005-kafka-clickhouse-for-listen-events.md) 는 "파생 집계도 MATERIALIZED VIEW 로 ClickHouse 안에서 만든다" 고 적었다. 그런데 **MV 는 INSERT 시점에 계산해서 결과를 테이블에 넣는다.** 규칙을 바꿔도 이미 저장된 행은 그대로다. 분류 기준(5초 / 20% / 80%)이나 가중치는 서비스가 굴러가면서 계속 바뀔 값이라, MV 에 넣으면 바꿀 때마다 과거 데이터와 새 데이터가 섞인다.

## 결정

파생을 두 종류로 갈라 다르게 만든다.

| | 무엇 | 어떻게 | 왜 |
| --- | --- | --- | --- |
| **해석** | 분류·가중치·점수처럼 "이 행동을 어떻게 볼 것인가" | 일반 `VIEW` (쿼리 시점 계산) | `CREATE OR REPLACE VIEW` 한 번으로 **과거 데이터까지 소급**된다 |
| **재구성** | 원시 신호를 다시 조립한 것. 커버리지 같은 사실 | `MATERIALIZED VIEW` | 매번 계산하기엔 비싸고, 규칙이 바뀔 성질이 아니다 |

그리고 **수집 계층에는 해석을 두지 않는다.** `listen_events` 에 `weight` 나 `skip_type` 컬럼은 없고 프론트도 보내지 않는다. 원본은 "무슨 일이 있었나" 만 담는다.

경계는 이렇게 가른다 — **원본만 있으면 언제든 다시 만들 수 있는가.** 다시 만들 수 있으면 VIEW 로 두고, 다시 만드는 게 너무 비싸면 그때만 MV 를 쓴다.

현재 배치:

- `v_track_skips` — **VIEW.** `skip_type`(EARLY / AFTER_HOOK / LATE / 나머지 MID)과 그 기본 가중치를 쿼리할 때 계산한다.
- `mv_play_section_coverage` → `play_section_coverage` — **MV.** 하트비트가 커버한 오디오 구간을 곡 구간 경계로 쪼개 합산한다. 해석이 아니라 사실이고, 이벤트 전체를 매번 훑으면 비싸다.

## 결과

**얻은 것**

- 분류 기준을 바꾸는 데 마이그레이션이 필요 없다. `01-schema.sql` 의 `CREATE OR REPLACE VIEW` 를 고치고 다시 적용하면 끝이고, 어제 쌓인 이벤트도 새 기준으로 다시 해석된다.
- 모델이 다른 가중치를 쓰고 싶으면 `v_track_skips` 를 무시하고 원본에서 직접 계산하면 된다. 뷰의 `weight` 는 기본선일 뿐 계약이 아니다.

**받아들인 비용**

- **VIEW 는 매번 전체를 훑는다.** `v_track_skips` 는 `play_section_coverage` 를 재생 단위로 GROUP BY 한 결과를 통째로 만든 뒤 JOIN 한다. 인덱스가 도와주지 않는 구조라 데이터가 쌓이면 느려진다. 그때는 **커버리지 집계만 MV 로 한 단계 더 내리고 분류 규칙은 VIEW 에 남기는** 2단 구조로 간다. 규칙을 소급 가능한 자리에 두는 것이 이 결정의 핵심이므로, 분류까지 MV 로 내리면 안 된다.
- **"나중에 재가공한다" 가 "나중에 뭐든 만들 수 있다" 는 뜻은 아니다.** `SKIP_AFTER_HOOK` 은 커버리지가 있어야 하고, 커버리지는 `PLAY_PROGRESS` 하트비트가 **그때 수집돼 있어야** 만들어진다. 해석은 미룰 수 있지만 원시 신호 수집은 미룰 수 없다.
- **`mv_user_section_signals` 가 이 결정을 어긴다.** 가중치를 계산해 `user_section_signals.weight` 에 **저장**한다. 값을 바꿔도 소급되지 않아 과거 신호와 새 신호가 섞인다. 이 결정을 쓰기 전에 만들어진 구조이고, 고칠지 말지는 [open-questions B5](../open-questions.md) 에 있다.

## 근거

`api/clickhouse/init/01-schema.sql` (`v_track_skips`, `mv_play_section_coverage`),
`frontend/lib/analytics/tracker.ts` (`skip()` 이 원시 신호만 남긴다),
[domain/listening-events.md](../domain/listening-events.md),
[decision/0005](0005-kafka-clickhouse-for-listen-events.md),
[decision/0014](0014-event-collector-in-api.md)
