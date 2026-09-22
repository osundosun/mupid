# 곡과 구간

추천의 대상이 되는 단위. **곡(track)이 아니라 곡 안의 구간(section)이 기본 단위다** → [decision/0006](../decision/0006-section-as-recommendation-unit.md)

> **구현 상태**: 표도 조회 경로도 채우는 쪽도 다 있다. 전처리 배치가 `PUT /api/v1/music/{musicId}/section` 을 부르면 스프링이 MySQL 과 ClickHouse 양쪽에 넣는다 (S15P21A204-435).

## 곡

곡 자체를 담는 테이블은 이 저장소에 없다. `track_id`(`UInt32`)로만 참조하며, 실제 곡 메타데이터는 외부 카탈로그에 있다. **값은 MySQL `music.music_id` 다** — ClickHouse 에는 FK 제약이 없으므로 이름만 다르고 숫자는 같다.

## `track_sections`

MySQL `music_section_analysis` 한 행을 그대로 옮긴 것이다. 이름이 다른 것은 셋뿐이다.

```sql
track_id, music_section_analysis_id, analysis_version, section_index,
section_uid, fn, start_ms, end_ms, created_at
```

| 컬럼 | MySQL | 의미 |
|---|---|---|
| `track_id` | `music_id` | 곡 번호 |
| `music_section_analysis_id` | `music_section_analysis_id` | 구간 번호. **분석 결과를 MySQL 구간 행에 되짚는 열쇠** |
| `section_uid` | `external_id` | 구간의 고유 키. **신호·커버리지·추천이 전부 이 키로 연결된다** |
| `section_index` | `index` | 곡 안에서의 순서 |
| `start_ms` / `end_ms` | `start_time_ms` / `end_time_ms` | 구간 범위. 재생 위치를 구간에 매핑하는 근거 |
| `fn` | `section_label` | 구간의 역할 분류 |
| `analysis_version` | — | 구간 분석 버전. 버전이 다르면 구간 정의가 다르다 |
| `created_at` | — | `ReplacingMergeTree` 의 버전 컬럼 |

`peak_ms` 는 2026-09-22 에 없앴다. MySQL 에 대응이 없고, 구간 안 정점을 산출한 적이 없어 줄곧 구간 시작점이 그대로 들어가 있었다.

**누가 채우는가**

| 길 | 언제 | 무엇이 |
|---|---|---|
| `PUT /api/v1/music/{musicId}/section` | 전처리 배치가 곡을 올릴 때 | `analytics/repository/TrackSectionRepository` 가 MySQL 적재 **커밋 뒤에** 넣는다 |
| `ai_server/tools/push_sections.py` | 일괄 시드·복구(`--full`), 하루 1회 점검(`--audit`) | 색인의 `moments.parquet` 을 `id_map` 으로 번역해 넣는다 |

두 길이 넣는 컬럼은 같고 `ReplacingMergeTree` 라 나중 것이 남는다. 앞의 길이 실패해도 **MySQL 적재는 되돌리지 않는다** — 분석 DB 가 죽었다고 곡 등록이 멈추면 배치가 곡마다 선다. 대신 `ERROR` 로그에 곡 번호가 남고, 밀린 곡은 뒤의 길로 메운다.

**저장 설계**

- `ENGINE = ReplicatedReplacingMergeTree(..., created_at)` — 같은 키로 다시 넣으면 최신 것이 남는다
- `ORDER BY (analysis_version, track_id, section_index)`
- 클러스터가 **`mupid_all`** 이다. 이벤트 테이블(`mupid`)과 달리 샤딩하지 않고 **모든 노드에 같은 사본**을 둔다 — 이벤트와 조인할 때 네트워크 이동을 없애기 위해서다.
- 이벤트 테이블과 달리 **TTL 이 없다.** 구간 정의는 분석 데이터가 아니라 참조 데이터다.

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
