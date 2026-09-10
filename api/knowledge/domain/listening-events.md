# 청취 이벤트

사용자의 모든 청취 행동은 **이벤트 하나의 스트림**으로 들어온다. 이 문서는 그 이벤트가 어떤 모양이고 어떻게 신호로 바뀌는지 다룬다.

구현 위치는 `api/clickhouse/init/01-schema.sql`. 파이프라인 운영은 [architecture/analytics-clickhouse.md](../architecture/analytics-clickhouse.md), 왜 이 구조인지는 [decision/0005](../decision/0005-kafka-clickhouse-for-listen-events.md).

## 흐름

```
클라이언트
  → Kafka 토픽 mupid.listen.events   (JSONEachRow)
  → kafka_listen_events              (Kafka 엔진, 컨슈머 3)
  → mv_kafka_to_events               (MATERIALIZED VIEW)
  → listen_events_local              (원본, 수정하지 않음)
       ├→ mv_user_section_signals → user_section_signals   (가중치 부여)
       └→ v_track_skips                                    (스킵 해석, VIEW)
```

원본은 고치지 않고 쌓기만 한다. 정제 규칙이 바뀌어도 원본부터 다시 계산할 수 있어야 하기 때문이다.

## `listen_events` 주요 필드

| 필드 | 의미 |
|---|---|
| `event_id` UUID | 중복 적재 방지. 네트워크 재시도로 같은 이벤트가 두 번 오는 것을 막는다 |
| `event_type` | 행동 종류 |
| `user_id` UInt32 / `track_id` UInt32 | 대상. MySQL `users.user_id`(INT)에 맞춘 타입이다 |
| `occurred_at` / `ingested_at` | **발생 시각과 적재 시각을 분리한다.** 오프라인 후 일괄 전송 대비 |
| `play_id` UUID | 재생 한 번을 묶는 키 |
| `entry_path` | 이 재생에 어떻게 진입했는가 (`PLAY_START` 에 기록) |
| `rec_id`, `rec_type`, `rec_rank` | 어느 추천에서 나온 반응인지 추적 |
| `position_ms` | **구간 판정의 근거.** 없거나 0 이면 신호가 만들어지지 않는다 |
| `from_ms` | Seek 이전 위치 |
| `start_ms` / `end_ms` | 구간 지정형 이벤트의 범위 |
| `skip_trigger` | 스킵을 무엇으로 했는가 (예: `NEXT_BUTTON`) |
| `ext` Map | 스키마 변경 없이 추가 정보를 넣는 자리 |

**저장 설계**: `ReplicatedReplacingMergeTree`(`ingested_at` 기준 중복 제거) · `PARTITION BY toYYYYMM(occurred_at)` · `ORDER BY (user_id, occurred_at, event_id)` · **TTL 18개월** · `cityHash64(user_id)` 로 샤딩 — 한 사용자의 이벤트가 한 샤드에 모여 사용자별 집계에 셔플이 없다.

## 신호 가중치

`mv_user_section_signals` 가 이벤트를 구간 신호로 바꾼다. **가중치가 스키마에 하드코딩돼 있다.**

| 이벤트 | 가중치 | |
|---|---:|---|
| `SECTION_MARK` | **+1.5** | 구간을 직접 찍음 — 가장 강한 긍정 |
| `LIKE` | +1.0 | |
| `PLAYLIST_ADD` | +0.8 | |
| `COMMENT_ADD` | +0.7 | |
| `SEEK_BACK` | +0.6 | 되감아 다시 들음 — 암묵적 긍정 |
| `UNLIKE` | −0.5 | |
| `PLAYLIST_REMOVE` | −0.8 | |
| `DISLIKE` | −1.0 | |
| `SECTION_UNMARK` | **−1.5** | |

구간 판정은 `dictGet('dict_track_sections', 'section_uid', track_id, position_ms)` — RANGE_HASHED 딕셔너리로 재생 위치를 구간에 매핑한다.

**신호가 만들어지지 않는 조건**: `track_id = 0`, `position_ms = 0`, 또는 구간 매핑 실패(빈 문자열). 이벤트는 남지만 신호는 안 생긴다.

> ⚠ **가중치를 바꾸면 과거 신호와 새 신호가 섞인다.** `user_section_signals` 는 계산된 값을 저장하므로 소급 적용되지 않는다.

## 스킵 해석

`v_track_skips` 는 "스킵 = 부정" 으로 단순화하지 않는다. **얼마나 듣고 스킵했는지**로 나눈다.

| 분류 | 조건 | 가중치 |
|---|---|---:|
| `SKIP_AFTER_HOOK` | 이 구간을 전에 절반 넘게 들은 적 있음 | 0.0 |
| `EARLY_SKIP` | 5초 이내 또는 구간의 20% 이내 | 추천 유입 + `NEXT_BUTTON` 이면 **−1.0**, 그 외 −0.4 |
| `LATE_SKIP` | 구간의 80% 이상 들음 | **+0.3** (긍정) |
| `MID_SKIP` | 나머지 | 0.0 |

읽어 둘 점:

- **끝까지 듣고 넘기는 것은 긍정 신호다** (`LATE_SKIP` +0.3).
- 이미 익숙한 구간을 넘기는 것은 취향과 무관하다고 본다 (`SKIP_AFTER_HOOK` 0.0).
- 부정 판정을 **추천으로 들어온 경우에만** 강하게 준다. 사용자가 직접 검색해 튼 곡을 넘긴 것에 같은 점수를 주면 추천 평가가 왜곡되기 때문이다.

## 커버리지

`play_section_coverage` 는 재생마다 구간을 얼마나 들었는지 기록한다 (`listened_ms` / `section_ms` / `reached`). `v_track_skips` 의 `SKIP_AFTER_HOOK` 판정이 이 테이블에 의존한다.

**미구현:** 이 테이블을 채우는 코드가 저장소에 없다.

## 현재 상태

| | 상태 |
|---|---|
| 스키마·클러스터·Kafka 연동 | ✅ 구현 |
| 이벤트 프로듀서 | ❌ **없음** — 현재 데이터는 `verify.sh` 의 수동 INSERT 뿐 |
| `play_section_coverage` 적재 | ❌ 없음 |
| `rec_impressions` 적재 | ❌ 없음 |

`user_id` 는 MySQL `users.user_id`(`Integer`, INT)에 맞춰 `UInt32` 다. 원래 `UInt64` 로 잘못 선언돼 있었고 수정했다 — **이미 띄운 클러스터에는 반영되지 않으므로** 재생성이 필요하다 → [failure/clickhouse-and-kafka.md](../failure/clickhouse-and-kafka.md)

## 이벤트를 추가하려면

1. `listen_events_local` 과 `kafka_listen_events` **양쪽에** 필드를 추가한다 (또는 `ext` Map 을 쓴다).
2. `mv_kafka_to_events` 의 SELECT 에도 넣는다. **MV 는 만들어질 때의 쿼리로 고정된다** — 테이블만 고치면 새 필드가 안 들어온다.
3. 신호로 쓸 이벤트면 `mv_user_section_signals` 의 `multiIf` 와 `WHERE ... IN (...)` 에 추가한다.
4. `api/clickhouse/verify.sh` 로 검증한다. **CI 는 이 영역을 검사하지 않는다.**
