# 0014. 청취 이벤트 수집기를 API 안에 둔다

**상태** 채택 · 구현 완료 (`rec_impressions` 기록만 남음)

## 맥락

[0005](0005-kafka-clickhouse-for-listen-events.md) 는 적재 경로(Kafka → ClickHouse)만 정했고 **이벤트를 발행하는 주체는 정하지 않았다.** `infra/deploy/compose.yml` 주석이 "서버 B(FastAPI)" 를 언급하지만 그런 서비스는 저장소에 없다. 프론트에서 곡을 들을 때마다 22종 행동 이벤트가 자동으로 쌓이게 하려면 이것부터 정해야 했다 (이전 [open-questions](../open-questions.md) 의 "청취 이벤트 프로듀서는 누가 만드는가").

수집 계층의 참고 대상으로 Google Analytics 4 를 봤다. GA4 의 설치 코드는 원격 `gtag.js` 를 `<script async>` 로 불러오고, **인라인 3줄이 `dataLayer` 큐에 밀어넣는 스텁을 먼저 정의**한다. 스크립트가 늦게 오거나 차단돼도 호출부가 깨지지 않는다. 이벤트는 내부 큐에 최대 5초 모였다가 `navigator.sendBeacon` 으로 한 번에 나가고, 각 이벤트는 **직전 이벤트 이후의 참여 시간 델타**(`_et` / `engagement_time_msec`)를 실어 서버가 합산하게 한다.

## 결정

GA4 의 **수집 계층 공학만** 가져오고 **데이터 모델은 가져오지 않는다.**

| GA4 | 우리 |
| --- | --- |
| 단일 `gtag('event', …)` + 전역 큐 | 단일 `track(type, params)` + 모듈 스코프 큐 |
| 배치 후 `sendBeacon` 으로 이탈 시 flush | 동일 (20건 / 5초 / `visibilitychange` / `pagehide`) |
| `_et` — 직전 이벤트 이후 델타를 서버가 합산 | `PLAY_PROGRESS` 가 `[from_ms, position_ms)` 오디오 구간을 나르고 ClickHouse 가 합산 |
| 원격 `<script async>` + 인라인 스텁 | **npm 모듈.** 번들이 하나뿐이라 외부 스크립트를 로드할 이유가 없다 |
| `_ga` 쿠키의 익명 `cid` | **JWT.** 비로그인 청취를 막으므로 `user_id` 는 서버가 확정한다 |
| schemaless `ep.` / `epn.` 파라미터 | 고정 컬럼 + `ext` Map. `dictGet` 하려면 `position_ms` 가 실컬럼이어야 한다 |
| 수신 시각 기준 (MP 만 `timestamp_micros`, 72시간 제한) | **발생 즉시 클라이언트가 `occurred_at` 각인**, 서버는 시계 오차만 보정 |
| 30분 무활동 세션 | **`play_id`** — 재생 1회가 곧 세션. 시간 기반 만료 없음 |
| 중복 허용 | **`event_id` 로 Kafka 진입 전 멱등 처리** (아래) |

경로는 세 갈래가 있었다.

| | A. FE → API → Kafka | B. FE → Kafka 직결 | C. FE → ClickHouse HTTP |
| --- | --- | --- | --- |
| `user_id` 신뢰 | JWT 필터에서 확정 | 브로커에 인증을 따로 붙여야 함 | 불가 |
| 브로커 노출 | 내부망만 | 외부 노출 필요 | — |
| 검증·정규화 | 원본 테이블에 닿기 전에 차단 | 없음 | 없음 |
| 멱등 처리 | Redis (이미 있다) | 없음 | 없음 |
| 비용 | API 트래픽 증가 | 인증·네트워크 재설계 | 소량 INSERT 로 파트 폭증 |

**A 를 택한다.** 인증(`AuthFilter`)·Redis(`CacheService`)·nginx 가 이미 있어 새로 만들 것이 프로듀서 하나뿐이다. GA4 도 실무에서 서버사이드 태깅(sGTM)을 붙일 때 같은 모양이 된다.

수집기가 지는 책임 네 가지.

1. **`user_id` 는 요청 본문이 아니라 `Authentication` principal 에서 뽑는다.** 본문에 실려 온 값은 무시한다.
2. **`event_id` 로 멱등 처리한다.** Redis `SETNX` 로 이미 본 `event_id` 면 버린다.
3. **`occurred_at` 은 클라이언트 값을 쓰되 시계 오차만 클램프한다.** 발생 시각이 곧 선호도 계산의 기준이라 서버 수신 시각으로 대체하면 안 된다.
4. **값 집합을 검증한다.** `entry_path` ∈ {`RECOMMEND`, `SEARCH`, `PLAYLIST`, `QUEUE`, `RECENT`, `ARTIST`}, `skip_trigger` ∈ {`NEXT_BUTTON`, `OTHER_TRACK`}, `target` ∈ {`TRACK`, `SECTION`}, `rec_type` ∈ {`HOME`, `SECTION`, `CUSTOM`}. ClickHouse 쪽 `CONSTRAINT` 로 막지 않는다 — 한 건이 어긋나면 블록 전체가 거부돼 Kafka 소비가 멈춘다.

   **모르는 값은 거절하지 않고 빈 문자열로 떨어진다.** `normalize()` 가 `parse()` 로 읽어 실패하면 `""` 를 넣는다 — 값 하나 때문에 배치 전체를 400 으로 떨어뜨리지 않으려는 선택이다(아래 "왜 enum 으로 받지 않는가"). 대가는 **조용함**이다. 그래서 프론트가 새 경로를 보내기 시작하면 `EntryPath` enum 을 **먼저** 늘려야 하고, 배포 순서도 API 가 앞이다. 늦으면 그 사이 재생은 경로 없이 되돌릴 수 없는 원본 테이블에 박힌다.

`AUTOPLAY` 는 `entry_path` 값 집합에서 뺀다. 인플레이어 다음곡 추천이 없으므로, 검색 결과에서 이어 재생되는 것도 `SEARCH` 로 남겨야 나중에 검색 노출 순서를 조정하는 근거가 된다. 재생 목록이 알아서 넘긴 재생은 `AUTOPLAY` 가 아니라 `QUEUE` 다 — 그 순서를 만든 것도 사용자이므로 추천이 한 선택으로 셀 수 없다.

### `RECOMMEND` 는 화면의 기본값이 아니다 (2026-09-22 보강)

처음에는 `entry_path` 를 **보고 있는 화면**으로만 정했다(검색이면 `SEARCH`, 보관함이면 `PLAYLIST`, 그 외 전부 `RECOMMEND`). 정확한 출처를 들고 다니지 않아 갈음한 것인데, 그러면 `RECOMMEND` 가 측정값이 아니라 **떨어지는 자리**가 된다. 추천 성과의 분모에 추천과 무관한 재생이 섞이고, 지표는 실제보다 좋게 나온다.

그래서 **어느 화면에서 눌러도 뜻이 같은 세 자리**는 화면으로 갈음하지 않고 누른 쪽이 값을 직접 넘긴다.

| 값 | 무엇 | 왜 가르는가 |
|---|---|---|
| `QUEUE` | 재생 목록에서 꺼냄 · 다음/이전 버튼 · 자동 다음곡 · 한곡 반복 | 이미 담아 둔 곡을 되꺼낸 것. 자동 전환까지 묶어야 한 목록을 듣는 동안 경로가 갈리지 않는다 |
| `RECENT` | '최근 들은 음악' (홈 · 프로필) | 이미 아는 곡의 되듣기. 완주율이 구조적으로 높아 추천과 섞으면 편향이 쌓인다 |
| `ARTIST` | 아티스트 패널에서 그 아티스트의 다른 곡 | 아티스트 선호를 직접 드러내는 탐색. 패널이 모든 곡 줄에서 열려 화면으로 갈음하면 세 값으로 흩어졌다 |

나머지(`SEARCH` · `PLAYLIST` · `RECOMMEND`)는 여전히 화면으로 갈음한다. 갈음이 남아 있는 한 `RECOMMEND` 는 추정이므로, **추천 성과는 `rec_id != ''` 를 함께 걸어 센다** — 그 값은 추천 응답이 실제로 준 사실이다.

### 멱등 처리를 왜 Kafka **앞**에서 하는가

`listen_events_local` 은 `ReplacingMergeTree` 라 같은 `event_id` 가 두 번 들어와도 병합 때 정리된다. **그런데 MV 는 INSERT 시점에 이미 발화한 뒤다.** `user_section_signals` 와 `play_section_coverage` 는 중복 정리 장치가 없는 엔진이라, 중복 `LIKE` 하나가 가중치를 `+1.0` 이 아니라 `+2.0` 으로 만들고 병합으로도 사라지지 않는다. 모바일에서 네트워크 재시도는 일상적이므로 이건 가정이 아니라 예정된 사고다. 따라서 중복은 **원본 테이블에 닿기 전에** 걸러야 한다.

`verify.sh` 를 두 번 돌리면 커버리지가 누적되는 것이 같은 현상이다. 그래서 스크립트가 6단계 앞에서 테스트 사용자의 커버리지를 먼저 지운다.

## 결과

**얻은 것**

- 이벤트 계약이 한 곳(`event` 도메인)에 모인다. 값 집합이 바뀌면 고칠 곳이 하나다.
- `play_section_coverage` 를 채울 수 있게 됐다. `PLAY_PROGRESS` 가 `[from_ms, position_ms)` 를 나르므로 `mv_play_section_coverage` 가 구간 경계로 쪼개 합산한다. 이 테이블이 비어 있는 동안 `v_track_skips` 의 `SKIP_AFTER_HOOK` 은 **한 번도 판정된 적이 없었다** — 모든 스킵이 EARLY/LATE/MID 로만 분류되고 있었다.
- `entry_path` 를 재생의 모든 이벤트에 실으면서 `v_track_skips` 에서 `PLAY_START` 로 되짚는 JOIN 하나가 빠졌다.

**받아들인 비용**

- **API 응답 경로에 트래픽이 붙는다.** 5초 하트비트가 전체 이벤트의 90% 이상을 차지한다. 사용자 1명이 1시간 들으면 `PLAY_PROGRESS` 만 720건이다. 수집 엔드포인트는 202 로 즉시 응답하고 Kafka 발행은 비동기로 돌린다.
- **Redis 가 이벤트 경로의 의존성이 된다.** 지금까지 Redis 는 인증에만 쓰였다. Redis 가 죽으면 멱등 처리를 못 한다 — 그때 이벤트를 버릴지 중복을 감수하고 통과시킬지 구현 시점에 정해야 한다.
- **`spring-kafka` 의존성이 늘어난다.** 현재 `build.gradle` 에 없다.
- **클라이언트 시각을 신뢰한다.** 클램프 범위를 잘못 잡으면 오프라인 일괄 전송이 잘리거나, 조작된 시각이 들어온다.
- **프론트에 상태를 가진 모듈이 생긴다.** 재생 포인터·큐·`play_id` 를 SDK 가 들고 있어야 한다. `frontend/` 는 아직 API 어댑터 자체가 없어(`mupidService = createService('demo')`) transport 를 인터페이스로 분리해야 한다.

**아직 없는 것**

| | |
| --- | --- |
| `event` 도메인 (`POST /api/v1/events` · Kafka 프로듀서) | ✅ 구현 |
| 프론트 SDK (`frontend/lib/analytics/`) | ✅ 구현 (기본 전송은 콘솔 싱크) |
| ClickHouse 스키마 (커버리지 MV 포함) | ✅ 반영됨 |
| `rec_impressions` 기록 | ❌ 추천 서빙 코드가 없다 |

## 근거

`api/clickhouse/init/01-schema.sql`, `api/clickhouse/verify.sh`,
[domain/listening-events.md](../domain/listening-events.md),
[architecture/analytics-clickhouse.md](../architecture/analytics-clickhouse.md),
[decision/0005](0005-kafka-clickhouse-for-listen-events.md)
