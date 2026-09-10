# 곡과 구간

추천의 대상이 되는 단위. **곡(track)이 아니라 곡 안의 구간(section)이 기본 단위다** → [decision/0006](../decision/0006-section-as-recommendation-unit.md)

> **구현 상태**: `track_sections` 테이블과 조회 경로는 구현돼 있다. 이 테이블을 **채우는 분석 파이프라인은 저장소에 없다.**

## 곡

곡 자체를 담는 테이블은 이 저장소에 없다. `track_id`(`UInt32`)로만 참조하며, 실제 곡 메타데이터는 외부 카탈로그에 있다.

## `track_sections`

```sql
track_id, analysis_version, section_index, section_uid,
fn, start_ms, end_ms, peak_ms, created_at
```

| 컬럼 | 의미 |
|---|---|
| `section_uid` | 구간의 고유 키. **신호·커버리지·추천이 전부 이 키로 연결된다** |
| `analysis_version` | 구간 분석 버전. 버전이 다르면 구간 정의가 다르다 |
| `section_index` | 곡 안에서의 순서 |
| `start_ms` / `end_ms` | 구간 범위. 재생 위치를 구간에 매핑하는 근거 |
| `peak_ms` | 구간 내 정점 위치 |
| `fn` | 구간의 역할 분류 |

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
