# 추천

**곡이 아니라 구간을 추천한다** → [decision/0006](../decision/0006-section-as-recommendation-unit.md)

> **구현 상태**: 노출 기록 테이블(`rec_impressions`)과 선호 신호 축적(`user_section_signals`)은 스키마가 있다. **추천을 계산하는 코드와 노출을 기록하는 코드는 저장소에 없다.** 이 문서는 스키마가 요구하는 구조만 다룬다.

## 선호 신호

사용자 행동은 `mv_user_section_signals` 를 거쳐 구간 단위 신호로 쌓인다. 가중치는 스키마에 하드코딩돼 있다 → [listening-events.md](listening-events.md)

```sql
user_section_signals(user_id, section_uid, track_id, signal,
                     entry_path, occurred_at, play_id, weight)
```

- `ORDER BY (user_id, section_uid, occurred_at)` — 사용자별·구간별 시계열 집계에 맞춰져 있다.
- `cityHash64(user_id)` 샤딩이라 **사용자별 집계에 셔플이 없다.**
- TTL 18개월.

`weight` 는 계산되어 **저장된** 값이다. 가중치 정의를 바꿔도 과거 행이 소급 갱신되지 않는다.

## 노출 기록 — `rec_impressions`

추천을 보여줄 때마다 남긴다. **이게 없으면 추천이 좋았는지 평가할 수 없다.**

| 필드 | 왜 필요한가 |
|---|---|
| `rec_id` | 이벤트의 `rec_id` 와 이어져 **노출 ↔ 반응**을 연결 |
| `section_uids`, `scores` | 무엇을 어떤 점수로 냈는지 |
| `explore_flags` | 항목별 탐색(explore) 여부 — 선호에만 맞추면 새 음악을 만나지 못한다 |
| `seed_section_uid`, `pref_section_uids` | 무엇을 근거로 추천했는지 |
| `signal_weights` | 그때 사용한 가중치 |
| `model_version`, `embedding_version`, `analysis_version` | **재현 가능성** |

버전 셋을 함께 남기는 이유: 나중에 "이 추천은 어떤 모델이 왜 냈나" 를 되짚을 수 있어야 하고, 모델을 바꿨을 때 이전 결과와 섞이면 안 되기 때문이다.

`ORDER BY (user_id, served_at, rec_id)` · `PARTITION BY toYYYYMM(served_at)` · TTL 18개월.

## 평가

노출 → 재생 → 반응을 `rec_id` 로 연결해 본다.

`v_track_skips` 가 부정 가중치를 **추천 유입(`entry_path = 'RECOMMEND'`)일 때만** 강하게 주는 것도 같은 맥락이다 — 사용자가 직접 검색해 튼 곡의 반응을 추천 성적에 넣으면 평가가 왜곡된다.

스킵 해석 규칙(끝까지 듣고 넘기면 긍정, 익숙한 구간을 넘기면 중립)은 [listening-events.md](listening-events.md) 참고.

## 아직 없는 것

추천이 동작하려면 다음이 필요하다. 순서대로 앞의 것이 뒤의 전제다.

1. **이벤트 프로듀서** — 현재 데이터는 `verify.sh` 의 수동 INSERT 뿐
2. **구간 분석 파이프라인** — `track_sections` 를 채우는 코드
3. **`play_section_coverage` 적재** — `v_track_skips` 의 `SKIP_AFTER_HOOK` 판정이 여기 의존한다
4. **추천 계산 + `rec_impressions` 기록**

가중치를 정교하게 만드는 것보다 **이벤트가 빠짐없이 수집되는지 확인하는 것**이 먼저다.

## 근거

`api/clickhouse/init/01-schema.sql`, [architecture/analytics-clickhouse.md](../architecture/analytics-clickhouse.md)
