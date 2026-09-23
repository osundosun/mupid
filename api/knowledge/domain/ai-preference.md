# AI 선호도

**AI 가 추천한 곡을 사람들이 실제로 듣는가.** 관리자 화면의 *AI 선호도* 가 그리는 값의
정의·집계·저장 (S15P21A204-427).

[← 도메인](README.md) · [지식 저장소](../README.md)

---

## 한눈에

| | |
|---|---|
| 원본 | ClickHouse `listen_events` (재생 이벤트) |
| 집계 | `analytics/service/AiPreferenceBatch` — 매시 5분, 직전 6시간을 다시 센다 |
| 저장 | ClickHouse `ai_preference_hourly` (`mupid_all`, ReplacingMergeTree) — 한 시간 × 구분 5 |
| 조회 | `GET /api/v1/admin/ai-preference` · 다시 집계 `POST /api/v1/admin/ai-preference/aggregate` |
| 화면 | 관리자 콘솔 왼쪽 목록 **AI 선호도** — 시간별 선 그래프 |
| DDL | `clickhouse/init/01-schema.sql` 끝 · 이미 떠 있는 클러스터는 `clickhouse/migrations/04-ai-preference-hourly.sql` 을 **손으로** 적용 |

MySQL 은 쓰지 않는다. 새 표가 ERD 밖(ClickHouse)이라 팀 합의가 필요한 스키마 변경이 없다.

## 추천 기록은 어디에 남는가

먼저 확인한 것. **"추천을 보여 줬다" 는 기록(노출)은 쓸 만한 곳이 없다.**

| 자리 | 남는 것 | 선호도에 쓸 수 있나 |
|---|---|---|
| ClickHouse `rec_impressions` | 노출 한 건마다 한 줄 — 스키마만 있고 **쓰는 코드가 없다** | ❌ 늘 비어 있다 |
| MySQL `daily_recommendations(_tracks)` | 오늘의 모먼트 묶음(사용자 × 날짜) | ⚠ 만들어 둔 것이지 **본 것이 아니다**. 홈을 안 연 사람도 행이 있다 |
| MySQL `ai_chat_messages` | 챗봇 대화와 추천 곡 | ⚠ 챗봇 한 자리뿐. 구간 추천(`/recommendation/next`)은 어디에도 안 남는다 |
| ClickHouse `listen_events` 의 `PLAY_START` | `entry_path` · `rec_id` · `rec_type` · `rec_rank` — **추천에서 튼 재생**에 실린다 | ✅ 모든 추천 자리에서 온다 |

그래서 **"노출 대비 재생률(CTR)" 은 지금 계산할 수 없다.** 분모(노출)가 없기 때문이다.
대신 **"AI 가 고른 곡을 틀었을 때 끝까지 / 충분히 듣는가"** 를 재고, 같은 값을 AI 가
고르지 않은 재생과 나란히 놓아 비교한다. 노출 기반 지표는 `rec_impressions` 적재가 생긴
뒤의 일이다 → [recommendation.md](recommendation.md) *아직 없는 것* 4.

## 지표 정의

### 세는 단위: 재생

`play_id` 하나가 재생 하나다. 재생은 **`PLAY_START` 가 일어난 시각의 시간 칸**에 속한다.
10:59 에 틀어 11:03 에 끝난 곡은 10시 칸이다. 그 재생의 하트비트·스킵·종료가 다음 시간에
들어와도 따라와서 10시 칸에 반영된다(아래 *늦게 오는 것*).

`play_id` 가 영(zero) UUID 인 이벤트(검색 등 재생 밖의 행동)는 세지 않는다.

### 구분: 이 재생은 AI 가 골랐나

`PLAY_START` 의 추천 표식으로 가른다. 표식은 `PLAY_START` 에만 실린다.

| 구분 | 조건 | 무엇 |
|---|---|---|
| `HOME` | `entry_path='RECOMMEND'` ∧ `rec_id≠''` ∧ `rec_type='HOME'` | 오늘의 모먼트 |
| `SECTION` | 〃 ∧ `rec_type='SECTION'` | 홈 "취향에 이어지는 음악" + 챗봇 추천 곡 |
| `CUSTOM` | 〃 ∧ `rec_type='CUSTOM'` | 맞춤 추천 (지금 싣는 화면 없음) |
| `UNTYPED` | 〃 ∧ `rec_type` 이 그 밖 | 표식은 있는데 유형을 모름 — 0 이 아니면 프론트가 새 값을 보내기 시작한 것 |
| `NON_AI` | 그 밖 전부 | 검색·재생 목록·큐·최근 들은 곡·아티스트·폴백 목록. **비교 기준선** |

**`entry_path='RECOMMEND'` 만으로 AI 로 치지 않는다.** 그 값은 화면으로 갈음한 추정이라
추천 서버가 죽었을 때 카탈로그로 채운 홈 대체 목록, 개인화 없는 닮은 구간, 저장되지 않은
폴백 모먼트(`recommendation_id=0`)가 섞인다. `rec_id` 가 있어야 추천 응답이 실제로 만든
재생이다 ([listening-events.md](listening-events.md) *추천 자리마다 다르다*).

> 구간 추천과 챗봇은 **가를 수 없다.** 둘 다 `rec_type='SECTION'` 이고 `rec_id` 도 같은
> 모양(밀리초로 시작하는 순번)이다. 가르려면 프론트가 챗봇 재생에 다른 `rec_type` 을 실어야
> 하는데, 그러면 `ai_server` 의 `SURFACE` 매핑도 함께 바뀌어야 해서 이 작업에서 손대지 않았다.

### 재생 하나에서 보는 것

| 값 | 정의 |
|---|---|
| 들은 시간 | 그 재생의 `PLAY_PROGRESS` 마다 `max(position_ms − from_ms, 0)` 의 합. `UInt32` 뺄셈 언더플로를 막으려 `toInt64` 로 올려 뺀다 |
| 끝까지 들음 | `PLAY_END` 가 있다 |
| 넘김 | `TRACK_SKIP` 이 있다 |
| 좋아요 | 그 재생 안에서 `LIKE` 가 있다 (재생 밖에서 누른 좋아요는 `play_id` 가 없어 안 들어온다) |
| **선호 재생** | **들은 시간 ≥ 30초 ∨ 끝까지 들음** |
| 조기 스킵 | 넘김 ∧ 들은 시간 < 30초 |

30초는 스트리밍 업계가 "재생 한 번" 으로 세는 관행적인 문턱이다. 완주만 세면 마음에 들어
절반쯤 듣다 다음 곡으로 넘긴 재생이 전부 실패로 잡힌다 — 우리 곡은 거의 전부 2분을 넘는다.
`AiPreferenceRepository.ENGAGED_MS` 한 곳에 있다.

### 시간 칸 × 구분의 비율

| 지표 | 식 | 읽는 법 |
|---|---|---|
| **선호율** | 선호 재생 / 재생 | **머리 지표.** AI 가 고른 곡을 틀면 충분히 듣는가 |
| 완주율 | 끝까지 들음 / 재생 | |
| 스킵률 | 넘김 / 재생 | |
| 조기 스킵률 | 조기 스킵 / 재생 | "틀자마자 싫었다" 에 가깝다 |
| 좋아요율 | 좋아요 / 재생 | |
| 재생 비중 | 이 구분의 재생 / 같은 시간 전체 재생 | 사람들이 듣는 것 가운데 AI 가 고른 몫 |

- **분모가 0 이면 비율은 `null`** 이다. 0% 로 두면 "들었는데 아무도 안 좋아했다" 로 읽힌다.
- 여러 칸·여러 구분을 합칠 때는 **건수를 먼저 더한 뒤 나눈다**(AI 합계 선, 기간 합계).
  칸의 비율을 평균 내면 재생 1회짜리 새벽 칸이 재생 백 회짜리 저녁 칸과 같은 무게가 된다.
- `users`(서로 다른 사람 수)는 한 구분의 한 시간 칸에만 있다. 더하면 같은 사람을 두 번
  세므로 합계에서는 비운다.

**AI 선호율은 기준선(`NON_AI`) 선호율과 견줘서 읽는다.** 선호율 40% 가 좋은지 나쁜지는
사람들이 원래 곡을 얼마나 끝까지 듣는지에 달렸다. 화면이 두 선을 한 그래프에 겹쳐
그리는 이유다(점선이 기준선).

## 배치

`AiPreferenceBatch.runHourly()` — `@Scheduled(cron = "0 5 * * * *", zone = "Asia/Seoul")`.

1. Redis 선점 `analytics:ai-preference:batch:<회차 시각>` (50분). 여러 인스턴스 중 하나만 돈다.
2. `[지금 정시 − 6시간, 지금 정시)` 에 **시작한** 재생을 원본에서 다시 센다. 이벤트는 창의
   끝보다 6시간(`TRAILING`) 더 읽는다 — 시간을 넘겨 이어진 재생의 꼬리를 줍기 위해서다.
3. 창의 **모든 시간 × 모든 구분**을 늘어놓고 센 값이 없으면 0 으로 채운다.
4. 한 번의 INSERT 로 로컬 표에 넣는다. `computed_at` 은 회차마다 같은 값.
5. 실패하면(ClickHouse 가 안 닿음) **아무것도 안 쓰고** 로그만 남긴다. 다음 회차가 같은 창을 다시 센다.

5분인 이유: Kafka 엔진이 15초까지 모아서 쓰므로(`kafka_flush_interval_ms`) 정각에 세면
59분대 이벤트가 빠진다. `ReportDailyBatch` 와 같은 판단이다.

### 멱등 — 덧셈이 아니라 대입

- 매 회차가 창을 **통째로 다시 세어 덮어쓴다.** 결과 표가 `ReplacingMergeTree(computed_at)`
  이고 정렬 키가 `(hour, segment)` 라 같은 칸은 마지막에 쓴 한 줄만 남는다.
- 같은 회차를 두 번, 두 인스턴스가 동시에, 관리자 다시 집계와 겹쳐 돌아도 값이 같다.
  (로컬 dev 에서 같은 8일치를 두 번 넣어 읽은 결과가 한 행도 다르지 않음을 확인했다.)
- **빈 칸도 0 으로 쓴다.** 안 쓰면 한 번 값이 있던 칸이 재계산에서 사라졌을 때 옛 값이
  남고, 화면이 "0회" 와 "계산 안 함" 을 가를 수 없다.
- 병합 전에는 옛 행이 함께 남아 있으므로 **읽을 때 `FINAL`** 을 붙인다.

증분을 더하지 않는 이유는 `PlayCountBatch` 와 같다 — 늦은 도착·병합 전 중복·TTL 때문에
"어디까지 더했나" 를 상태로 들고 있어야 하고, 그 상태를 잃으면 값이 조용히 어긋난다.

### 늦게 오는 것

한 칸은 그 시간이 지난 뒤 **여섯 번** 다시 계산된다(매시 직전 6시간). 그래서

- 이어 들은 재생의 꼬리, 늦게 도착한 이벤트가 몇 시간 안에 반영된다.
- 서버가 6시간 넘게 멈췄던 구간은 저절로 메워지지 않는다 → 다시 집계.
- 재생 하나가 6시간 넘게 이어지면 그 뒤는 잘린다(일시정지한 채 두고 간 재생). 들은 것이 아니므로 잘려도 된다.

### 다시 집계 (backfill)

`POST /api/v1/admin/ai-preference/aggregate?from=YYYY-MM-DD&to=YYYY-MM-DD` — 관리자 화면의
**다시 집계** 단추. 하루씩 끊어 같은 계산을 한다. 31일까지, 몇 번을 불러도 같은 값.
창의 끝이 오늘이면 직전 정시까지만 센다.

## 표 — `ai_preference_hourly`

```sql
ai_preference_hourly_local ON CLUSTER mupid_all
  (hour DateTime('Asia/Seoul'), segment LowCardinality(String),
   plays, users, engaged_plays, completed_plays, skipped_plays,
   early_skip_plays, liked_plays UInt32, listened_ms UInt64,
   computed_at DateTime64(3, 'Asia/Seoul'))
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/ai_preference_hourly', '{replica}', computed_at)
PARTITION BY toYYYYMM(hour) ORDER BY (hour, segment) TTL hour + INTERVAL 18 MONTH
```

- **비율은 저장하지 않는다.** 합칠 수 없어서다. 나누는 것은 응답 직전
  (`AdminAiPreferenceServiceImpl`).
- `mupid_all`(전 노드 복제)인 이유: 사용자별 행이 아니라 전체 합계라 샤딩할 키가 없고,
  시간당 다섯 줄이라 모든 노드가 다 가져도 가볍다. `track_sections` 와 같은 쓰기 방식 —
  Distributed 가 아니라 **로컬 표에 바로 INSERT** 하고 로컬 표를 읽는다
  ([architecture/analytics-clickhouse.md](../architecture/analytics-clickhouse.md)).
- 원본에서 언제든 다시 만들 수 있는 파생 표다. 지워도 잃는 것이 없다.

### 적용 (수동)

`ddl-auto` 는 ClickHouse 와 무관하고, `init/` 은 **새로 만드는 클러스터**에서만 돈다.
이미 떠 있는 클러스터(운영 k3s·로컬 dev)에는 손으로 한 번 적용한다.

```bash
clickhouse-client --user mupid --password "$MUPID_CLICKHOUSE_PASSWORD" \
    --multiquery < api/clickhouse/migrations/04-ai-preference-hourly.sql
# 확인 — 노드마다 2
SELECT hostName(), count() FROM clusterAllReplicas(mupid_all, system.tables)
WHERE database = 'default' AND name LIKE 'ai_preference_hourly%' GROUP BY 1;
```

전부 `IF NOT EXISTS` 라 두 번 돌려도 안전하고 기존 표에 손대지 않는다. 적용한 뒤 관리자
화면에서 기간을 고르고 **다시 집계** 를 누르면 지난 기간이 채워진다.

**적용 전에 배포되면**: 배치는 매시 경고 로그를 남기고 넘어가고(`AI 선호도 집계를 하지
못해…`), 화면은 503 `MUPID_ANALYTICS_UNAVAILABLE` 을 보인다. 다른 기능에는 영향이 없다.

## 한계

- **노출 대비 재생률이 없다.** `rec_impressions` 를 쓰는 코드가 생기면 분모가 생긴다.
- **구간 추천과 챗봇을 가르지 못한다** (위 *구분*).
- 좋아요는 **재생 중에 누른 것**만 센다. 라이브러리 목록에서 나중에 누른 좋아요는 어느
  재생의 것인지 알 수 없다.
- 지금 진행 중인 시간은 보이지 않는다. 다음 정시 5분에 들어온다.
- 선호도는 구간 단위가 아니라 **재생 단위**다. "추천한 구간을 들었나" 는
  `play_section_coverage` 와 추천 구간을 이어야 하는데, 추천 구간이 어디에도 저장되지 않는다
  (노출 기록이 없는 것과 같은 뿌리).

## 근거

`analytics/repository/AiPreferenceRepository`(쿼리), `analytics/service/AiPreferenceBatch`,
`admin/service/AdminAiPreferenceServiceImpl`, `clickhouse/init/01-schema.sql`.
테스트: `AiPreferenceBatchTest` · `AdminAiPreferenceBuildTest` (인프라 없이 돈다).
