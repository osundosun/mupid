# 곡과 구간

추천의 대상이 되는 단위. **곡(track)이 아니라 곡 안의 구간(section)이 기본 단위다** → [decision/0006](../decision/0006-section-as-recommendation-unit.md)

> **구현 상태**: 표도 조회 경로도 채우는 쪽도 다 있다. 전처리 배치가 `PUT /api/v1/music/{musicId}/section` 을 부르면 스프링이 MySQL 과 ClickHouse 양쪽에 넣는다 (S15P21A204-435).

## 곡

곡 자체를 담는 테이블은 이 저장소에 없다. `track_id`(`UInt32`)로만 참조하며, 실제 곡 메타데이터는 외부 카탈로그에 있다. **값은 MySQL `music.music_id` 다** — ClickHouse 에는 FK 제약이 없으므로 이름만 다르고 숫자는 같다.

## `track_sections`

MySQL `music_section_analysis` 한 행을 그대로 옮긴 것이다. 이름이 다른 것은 셋뿐이다.

```sql
track_id, music_section_analysis_id, analysis_version, section_index,
section_uid, fn, start_ms, end_ms, energy, instruments, created_at
```

| 컬럼 | MySQL | 의미 |
|---|---|---|
| `track_id` | `music_id` | 곡 번호 |
| `music_section_analysis_id` | `music_section_analysis_id` | 구간 번호. **분석 결과를 MySQL 구간 행에 되짚는 열쇠** |
| `section_uid` | `external_id` | 구간의 고유 키. **신호·커버리지·추천이 전부 이 키로 연결된다** |
| `section_index` | `index` | 곡 안에서의 순서 |
| `start_ms` / `end_ms` | `start_time_ms` / `end_time_ms` | 구간 범위. 재생 위치를 구간에 매핑하는 근거 |
| `fn` | `section_label` | 구간의 역할 분류 |
| `analysis_version` | `analysis_version` | 구간 분석 버전. 버전이 다르면 구간 정의가 다르다 (MySQL 쪽은 451 에서 생겼다) |
| `energy` | `energy` | 구간 RMS(0~1). 서빙 특징 조회가 요청 본문 대신 읽는다 (455). 같은 행의 컬럼이라 따로 표를 두지 않았다 |
| `instruments` | `music_analysis_instruments` → `codes.code_name` | 구간 악기의 **코드 이름**, 코드 번호순(`Array(String)`, 없으면 `[]`). 추천 서버가 악기 조건을 여기서 건다 (458, 계약 v3). `other`·`그 외 악기` 도 그대로 싣고 거르는 것은 읽는 쪽 몫이다. 다른 표지만 같은 요청(`PUT …/section`)이 같이 바꾸므로 구간과 같은 교체·대조를 탄다 |
| `created_at` | — | `ReplacingMergeTree` 의 버전 컬럼 |

`peak_ms` 는 2026-09-22 에 없앴다. MySQL 에 대응이 없고, 구간 안 정점을 산출한 적이 없어 줄곧 구간 시작점이 그대로 들어가 있었다.

**누가 채우는가**

**Spring 하나다.** 원천은 언제나 MySQL `music_section_analysis` 다 (S15P21A204-451).

| 길 | 언제 | 무엇이 |
|---|---|---|
| 커밋 뒤 즉시 쓰기 | `PUT /api/v1/music/{musicId}/section` 이 커밋된 직후 | 방금 넣은 구간을 `TrackSectionRepository` 가 넣는다 |
| 재시도 | 1분마다, 미반영 기록이 남은 곡 | MySQL 의 **지금** 구간으로 곡 단위 교체 |
| 대조 | 매일 04:40 | 레플리카마다 구간 수·체크섬을 MySQL 과 비교해 어긋난 곡을 미반영에 넣는다 |

쓰기가 실패해도 **MySQL 적재는 되돌리지 않는다** — 분석 DB 가 죽었다고 곡 등록이 멈추면 배치가 곡마다 선다. 대신 구간 교체와 같은 트랜잭션에 `analytics_sync_pending` 행이 남아 재시도가 메운다 → [architecture/analytics-sync.md](../architecture/analytics-sync.md). 예전의 `ai_server/tools/push_sections.py`(색인을 ClickHouse 에 직접 쓰던 복구 도구)는 지웠다.

`analysis_version` 은 이제 MySQL `music_section_analysis.analysis_version` 에도 있고 두 저장소가 같은 값을 쓴다.

**저장 설계**

- `ENGINE = ReplicatedReplacingMergeTree(..., created_at)` — 같은 키로 다시 넣으면 최신 것이 남는다
- `ORDER BY (analysis_version, track_id, section_index)`
- 클러스터가 **`mupid_all`** 이다. 이벤트 테이블(`mupid`)과 달리 샤딩하지 않고 **모든 노드에 같은 사본**을 둔다 — 이벤트와 조인할 때 네트워크 이동을 없애기 위해서다.
- 이벤트 테이블과 달리 **TTL 이 없다.** 구간 정의는 분석 데이터가 아니라 참조 데이터다.

## `track_features` — 곡 단위 특징 사본 (S15P21A204-455)

추천 요청 본문 `catalog.tracks` 에 싣던 곡 값을 ClickHouse 에 둔 것이다(계약 v3 에서 본문은 없어졌고
추천 서버가 이 표를 읽는다, 458). **모든 곡**이 한 행씩 있고,
본문이 후보를 고르던 조건을 값으로 들고 있다.

| 컬럼 | 원천 (MySQL) |
|---|---|
| `track_id` | `music.music_id` |
| `track` | 첫 구간(시작 시각순) `external_id` 의 `#` 앞. 구간이 없으면 빈 문자열 |
| `duration_ms` | `DONE` 스트림 `duration_ms` 의 최댓값 |
| `bpm`·`bpm_requested_min/max`·`language`·`category`·`has_vocal`·`vocal_gender` | `music_analysis` |
| `genres`·`moods` | `music_genres`·`music_moods` 의 코드 이름, 코드 번호순 |
| `is_deleted` | `music.is_deleted` — **곡 삭제가 ClickHouse 에 닿는 곳** |
| `playable` | `DONE` 스트림이 있는가 |
| `analysis_version` | 첫 구간의 분석 버전 |
| `version` | Spring 이 쓴 시각(epoch ms). ReplacingMergeTree 버전 |

값의 규칙은 `RecommenderCatalogServiceImpl.build` 와 같다(시험 `TrackFeatureMysqlTest`). 쓰는 쪽은
`TrackFeatureSync` 하나이고, 미반영 기록·재시도·대조를 구간과 같은 틀로 탄다 →
[architecture/analytics-sync.md](../architecture/analytics-sync.md). 읽을 때는 `FINAL`.

**곡 삭제와 구간**: 곡을 지워도 구간 사본은 남는다(MySQL 도 구간을 지우지 않는다). 서빙은
`track_features` 와 맞춰 삭제된 곡을 뺀다. 재생 신호 판정(`dict_track_sections`)은 지금처럼 전 곡을 본다 —
지워진 곡의 과거 청취도 취향 신호다.

### 서빙 조회 (AI)

| 객체 | 하는 일 |
|---|---|
| `v_serving_sections` | 곡의 **현재** 분석 버전(`track_features.analysis_version`)인 구간만, `FINAL` 로 |
| `dict_serving_sections` | 위 뷰를 `section_uid` 로 찾는 사전(`COMPLEX_KEY_HASHED_ARRAY`). 1만 곡에 12MB, 원본을 따라오는 데 최대 30초 |

`ai_server/app/feature_store.py` 가 읽는다 — 후보 수백 개의 특징(`lookup`)과 조건 풀기(`solve`).
읽기 전용 서빙 계정 `mupid_serving`(`clickhouse/users/20-serving-user.xml`)으로 같은 노드의
`clickhouse-0` 에 붙는다. 지연은 설계 문서 §11 미결 4.

## 재생 위치 → 구간 판정

```
v_track_sections_current   (VIEW, analysis_version = 'v1' 만)
        ↓
dict_track_sections        (DICTIONARY, RANGE_HASHED, LIFETIME 300~600초)
        ↓
dictGet('dict_track_sections', 'section_uid', track_id, position_ms)
```

`mv_user_section_signals` 가 이벤트를 신호로 바꿀 때 이 딕셔너리를 쓴다. **매핑에 실패하면(빈 문자열) 신호가 만들어지지 않는다** → [listening-events.md](listening-events.md)

## `analysis_version` 을 올릴 때

구간 정의가 바뀌면 과거 신호의 의미도 달라진다. 그래서 버전을 나눠 저장하고, 현재 버전만 뷰로 노출한다.

현재 절차상 걸리는 점:

- `v_track_sections_current` 에 **`'v1'` 이 하드코딩**돼 있다. 버전을 올리려면 뷰를 `CREATE OR REPLACE` 해야 한다.
- 딕셔너리는 뷰를 보므로 `LIFETIME`(300~600초)만큼 지연 후 반영된다.
- 이미 쌓인 `user_section_signals` 는 **소급 갱신되지 않는다.** 옛 `section_uid` 를 그대로 들고 있다.

## 근거

`api/clickhouse/init/01-schema.sql`, [architecture/analytics-clickhouse.md](../architecture/analytics-clickhouse.md)
