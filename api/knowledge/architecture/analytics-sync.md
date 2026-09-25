# MySQL → ClickHouse 사본 동기화 (`track_sections`)

MySQL `music_section_analysis` 가 **정본**이고 ClickHouse `track_sections_local` 은 그 **사본**이다.
사본이 비거나 어긋나면 재생 이벤트가 어느 구간인지 판정되지 못해 신호가 조용히 버려진다
([domain/track-sections.md](../domain/track-sections.md)). 이 문서는 그 사본을 맞추는 장치와,
정본을 바꾸는 **쓰기 입구 전수 목록**을 둔다 (S15P21A204-451, `infra/VECTOR-STORE-DESIGN.md` §8·§11 미결 1).

> **불변식**: 커밋된 MySQL 구간 변경은 유한한 시간 안에 ClickHouse 에 같은 내용으로 반영된다.

## 장치

| # | 장치 | 코드 | 하는 일 |
|---|---|---|---|
| 1 | 미반영 기록 | `TrackSectionSync.markPending` | 구간을 바꾸는 **트랜잭션 안에서** `analytics_sync_pending(music_id, kind='CLICKHOUSE')` 를 올린다. 곡당 한 행, 다시 오면 `revision` +1 |
| 2 | 커밋 뒤 즉시 쓰기 | `TrackSectionSync.pushAfterCommit` | `afterCommit` 에서 ClickHouse 에 쓴다. 성공하면 **자기 revision 일 때만** 행을 지운다. 실패하면 `attempts`·`last_error` 를 적고 행을 남긴다 |
| 3 | 재시도 | `TrackSectionRetryJob` (1분, fixedDelay) | 남은 곡을 `FOR UPDATE SKIP LOCKED` 로 하나씩 가져가 **MySQL 의 지금 구간**으로 교체한다. 멱등. 한 곡이 실패하면 그 회차를 멈춘다 |
| 4 | 대조 | `TrackSectionReconciler` (매일 04:40 KST, Redis 선점) | 곡·버전별 구간 수와 체크섬을 MySQL 과 **레플리카마다** 비교해, 어긋난 곡을 1 에 넣는다 |
| 5 | 같은 기준 | `music_section_analysis.analysis_version` | 요청이 싣는 버전을 MySQL 에도 둔다. 재시도·대조가 MySQL 만 보고 같은 값을 쓴다 |
| 6 | 지표 | `AnalyticsSyncMonitor` | 관리자 대시보드 "연결 상태" 의 **ClickHouse 구간 동기화** 한 줄 + Micrometer 게이지 |

ClickHouse 쓰기(`TrackSectionRepository.replaceSections`)를 부르는 곳은 `TrackSectionSync` 하나다.
ClickHouse 가 죽어도 MySQL 적재는 성공한다 — 그 틈을 미반영 기록이 들고 있다.

### 왜 revision 인가

같은 곡의 두 요청 A·B 가 겹치면 A 의 커밋 뒤 쓰기가 성공하면서 B 의 미반영을 지울 수 있다.
그 뒤 B 의 쓰기가 실패하면 그 곡은 기록 없이 어긋난 채 남는다. 행을 곡 단위가 아니라
`revision` 으로 지우면 A 는 B 가 올린 행을 못 지운다. 남은 행은 재시도가 **지금 구간(B)** 으로 덮는다.

A 의 스냅숏이 B 보다 늦게 ClickHouse 에 닿는 순서 역전은 이것으로 막지 못한다 (B 가 먼저 성공해
행을 지운 경우). 드물고, 다음 대조가 잡는다.

### 왜 재시도는 Redis 선점이 아닌가

스케줄러는 인스턴스마다 돈다(`ApiApplication`). 다른 배치는 Redis 로 한 곳만 돌게 하지만, 재시도는
곡 하나를 가져가는 것이 행 잠금(`SKIP LOCKED`)이라 여러 벌이 **일을 나눠 가진다.** 잠금은 그 곡의
ClickHouse 쓰기 한 번 동안이고, 그 사이 같은 곡의 구간 교체가 오면 그 요청의 `upsert` 가 기다렸다가
새 행을 넣는다 — 미반영이 빠지지 않는다. 대조는 전 곡 집계라 Redis 로 선점한다.

### 레플리카를 각각 본다

Spring 은 헤드리스 서비스 `clickhouse:8123` 하나에 붙는다. 대조는
`clusterAllReplicas('mupid_all', …, 'track_sections_local') FINAL` 을 `hostName()` 으로 묶어 두 레플리카를
따로 센다. 레플리카 목록은 `clusterAllReplicas('mupid_all', system.one)` 로 데이터와 무관하게 얻는다 —
통째로 빈 레플리카도 전 곡 불일치로 잡힌다. 한 레플리카가 안 닿으면 대조 자체가 실패로 남는다.

체크섬은 `구간번호|순번|구간UID|라벨|시작ms|끝ms` 문자열 CRC32 의 합이다. MySQL `CRC32(CONCAT_WS('|',…))` 와
ClickHouse `CRC32(concat(…))` 가 같은 값을 낸다(2026-09-25 dev 에서 한 행씩 확인).
두 문장은 `SectionDigestRepository`·`TrackSectionRepository.DIGESTS_BY_REPLICA` 에 있고 **같이 고쳐야 한다.**

대조가 세기만 하고 넣지 않는 것:

- **ClickHouse 에만 있는 곡** — MySQL 에 구간이 없는 곡. 교체는 빈 목록을 쓰지 않는다. 곡 삭제 전파(455)의 몫.
- **옛 버전 잔여** — 곡이 MySQL 에 다른 버전으로 있다. 교체는 같은 버전 안에서만 옛 순번을 지운다. 딕셔너리는 현재 버전만 본다.

## 지표·알림

| 지표 | 게이지 | 알림 기준 (`AnalyticsSyncMonitor`) |
|---|---|---|
| 미반영 곡 수 | `mupid.analytics.sync.pending{kind}` | — (0 이 정상, 커밋 직후 잠깐 1 은 정상) |
| 가장 오래된 미반영의 나이 | `mupid.analytics.sync.pending.oldest.age{kind}` (초) | `PENDING_AGE_ALERT` = **10분** |
| 마지막 대조의 불일치 곡 수 | `mupid.analytics.reconcile.mismatch{kind}` (대조 전 -1) | `MISMATCH_ALERT` = **1곡** |
| 마지막 대조 시각 | — | `RECONCILE_STALE_ALERT` = 26시간 |

기준을 넘으면 대시보드 줄이 `WARN` 이 된다. 게이지는 재시도 작업이 1분마다 채운 값이고, 액추에이터가
지금 `health` 만 노출하므로 밖에서 긁히지는 않는다(수집기를 붙이는 날 이름이 바뀌지 않게 먼저 정해 둔다).
마지막 대조 결과는 Redis `analytics:reconcile:last:track-sections` 에 있다 — 대조는 한 인스턴스에서만 돌고
대시보드 요청은 아무 인스턴스로나 가기 때문이다.

## 쓰기 입구 전수 목록 (2026-09-25)

`music_section_analysis` 와 ClickHouse 사본에 실리는 컬럼을 바꾸는 길 전부. **새 길을 만들면 여기 적고
`TrackSectionSync.markPending` 을 같은 트랜잭션에서 부른다.** 하나라도 빠지면 불변식이 깨지고, 그 틈은
다음 날 대조가 잡을 때까지 남는다.

| 입구 | 코드 | 무엇을 바꾸나 | 동기화 |
|---|---|---|---|
| `PUT /api/v1/music/{musicId}/section` (분석 배치 `music_preprocessing` ⑥ 등록, `ai_server/tools/register_sections.py`) | `MusicSectionServiceImpl.replaceSections` | 곡의 구간 전체 교체 | 미반영 기록 + 커밋 뒤 즉시 쓰기 |
| 관리자 화면 자원 CRUD `POST·PATCH·DELETE /api/v1/admin/resource/musicSectionAnalysis` | `AdminResourceServiceImpl.create·update·delete` | 구간 한 행 | 미반영 기록만 — 재시도가 1분 안에 곡 전체를 교체한다. 곡을 옮기면 옛 곡도 올린다 |
| 곡 삭제 `DELETE /api/v1/music/{musicId}` · 관리자 `music` 수정 | `MusicServiceImpl` · `AdminResourceServiceImpl` | `music.is_deleted` — 구간은 그대로 | **대상 아님.** ClickHouse 는 곡 삭제를 모른다 — `track_features` 의 `is_deleted` 로 455 에서 |
| 구간 에너지 되채우기 (`music_preprocessing analysis-values` 가 만든 UPDATE 파일) | 사람이 MySQL 에 한 번 | `energy` | 대상 아님 — ClickHouse 사본에 없는 컬럼이다 |
| 구간 분위기·악기 (`music_section_analysis_mood`·`music_analysis_instruments`) | `replaceSections`·관리자 CRUD | 연결 표 | 대상 아님 — 사본에 없다 |
| e2e 시드 `ai_server/tools/seed_e2e.py` | MySQL·ClickHouse 에 직접 | dev 전용 시험 픽스처 | 대상 아님 — 운영에서 돌지 않고 회차가 끝나면 지운다 |
| 부트스트랩·스케줄 배치 (`BootstrapRunner`·`ReportDailyBatch`·`PlayCountBatch`·`DailyRecommendationBatch`·`AiPreferenceBatch`) | — | 구간을 쓰지 않는다 | — |

**지운 우회**: `ai_server/tools/push_sections.py` 가 색인(`moments.parquet`)을 `id_map` 으로 번역해 ClickHouse 에
HTTP 로 직접 썼다. 쓰기 관문을 우회했고, MySQL 이 아닌 색인을 원천으로 삼아 두 저장소가 어긋날 여지를
만들었다. 451 에서 지웠다 — 복구는 대조 → 재시도가 맡는다. `ai_server` 가 `track_sections` 에 쓰지 않는다는
것은 `test_ai_server_never_writes_track_sections` 가 지킨다.

## 운영에서 손으로 할 일

- **한 곡을 당장 다시 쓰고 싶을 때**: `analytics_sync_pending` 에 행을 넣는다(관리자 화면 "사본 미반영" 자원,
  또는 `INSERT INTO analytics_sync_pending (music_id, kind, analysis_version) VALUES (…, 'CLICKHOUSE', 'v1')`).
  1분 안에 재시도가 MySQL 구간으로 교체한다.
- **대조를 당장 돌리고 싶을 때**: 지금은 호출 경로가 없다. 다음 04:40 을 기다리거나, 의심 곡을 위처럼 넣는다.
- **`last_error` 가 계속 같은 곡에 쌓일 때**: 그 곡의 구간 값이 ClickHouse 형(`UInt32` 등)에 안 맞는 경우다.
  MySQL 값을 고치면 다음 회차가 넣는다.

## 근거

`api/src/main/java/com/osundosun/mupid/api/analytics/sync/`, `analytics/repository/TrackSectionRepository.java`,
`knowledge/domain/migrate-analytics-sync-2026-09-25.sql`, 시험 `analytics/sync/**`
