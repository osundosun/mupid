# 요청 경로 복잡도 점검표

작성 2026-09-25 · `S15P21A204-452` · 설계 `infra/VECTOR-STORE-DESIGN.md` §10.3-1(병목 1위)·§9.3·§13 2단계

설계 규모(10만 곡, 90만 구간)에서는 후보를 좁혀도 **요청 경로에 남은 O(풀) 연산 하나가 전체를
지배한다.** 이 표는 `daily`·`next`·`search`·`similar` 가 지나는 연산을 하나씩 적고, 각각이 무엇에
비례하는지를 적는다. 새 연산을 요청 경로에 넣을 때 이 표에 한 줄을 더한다.

## 기호

| 기호 | 뜻 | 설계 규모 |
|---|---|---|
| **N** | 요청 사본의 후보 풀 구간 수 (기준 풀 스냅샷 ∩ 색인 풀. 458 전에는 본문 `catalog` ∩ 색인 풀) | 약 90만 |
| **T** | 그 풀의 곡 수 | 약 10만 |
| **K** | 점수를 매기는 후보 수. 씨앗당 `DNA_CANDIDATES_PER_SEED`(200) × 씨앗 수(≤7), 콜드스타트는 `COLD_START_CANDIDATES`(300) | ≤ 1,400 |
| **C** | 씨앗 수 (DNA 중심 ≤ 7, 온보딩 씨앗 ≤ 3) | ≤ 7 |
| **H** | 사용자 한 명의 이력 (라벨·행동·제외 곡) | 수십~수백 |
| **D** | 순간 벡터 차원 | 1536 |
| **n** | 돌려줄 개수 | 4~50 |

## 분류

| 분류 | 뜻 | 허용 |
|---|---|---|
| **요청마다 O(K·…)·O(H)** | 후보·사용자 이력에만 비례 | 목표 |
| **갱신마다 한 번 O(N)** | 기준 풀 스냅샷(`pool_snapshot`, 458)을 **갱신 스레드**가 갈아 끼울 때 — 피처 저장소 읽기, 스냅샷, 사본·풀 색인·곡 어휘·조건 파생값(`internal_api.warm`). 내용이 바뀐 갱신만 치르고, 요청 경로 밖이다 | 허용 |
| **사본마다 한 번 O(N)** | 요청 사본을 처음 쓰는 요청 한 번. 기준 사본은 갱신 스레드가 미리 만들어 요청이 치르지 않는다. 조건 검색은 새 조건마다 통과한 구간의 사본을 만든다(`_view(ps, kept)`, LRU 8) — 458 전에 Spring 이 조건마다 다른 본문을 보내던 것과 같은 단위다 | 허용 |
| **조합마다 한 번 O(N)·O(T)** | 게이트·온보딩 답변 조합마다 사본당 한 번. 조합은 많지 않고 LRU(`VIEW_CACHE_MAX`=64)로 묶는다 | 허용 |
| **판마다 한 번** | 색인 판을 올린 뒤 한 번 (기동) | 허용 |
| **벡터 검색 (ANN 대용)** | 로컬 갈래의 브루트포스 내적. Qdrant(456)에서는 ANN 이 된다 | **예외로 허용** — 요청당 이것 하나 |
| ~~본문 (계약 v2)~~ | 본문 `catalog` 자체가 풀 크기였다. **계약 v3(458)에서 사라졌다** — 본문은 수백 B~5KB | — |
| ~~요청마다 O(N)~~ | **없어야 한다** | ✗ |

## 요청 경계 (`internal_api`)

| 연산 | 복잡도 | 분류 | 비고 |
|---|---|---|---|
| 요청 본문 JSON·pydantic 파싱 | O(H) | 요청마다 | 458 전에는 본문 `catalog` 로 O(N)(1만 곡 약 4.4MB). 옛 Spring 이 보내도 받기만 하고 읽지 않는다 |
| `_pool`·`_view` (기준 사본 찾기) | O(1) | 요청마다 | 스냅샷 참조와 dict 조회. 458 전에는 본문 해시 `digest_of`(O(본문))를 요청마다 쟀다 |
| 스냅샷·기준 사본·풀 색인·곡 어휘·조건 파생값 | O(N) | 갱신마다 한 번 | 갱신 스레드(`pool_snapshot.Holder.refresh` → `internal_api.warm`). 곡 어휘(`catalog_facets`)는 곡마다 `.loc` 하던 것을 dict 로 바꿨다(458) |
| 조건 풀기 `query_conditions.apply` | O(T + 값 조합) 벡터화 · 결과는 스냅샷 안에서 재사용 | 조합마다 한 번 | 곡 단위로 판정해 구간에 펼친다(numpy). 458 전에는 Spring `QueryPlanFilter` 가 요청마다 O(N) |
| 조건 통과 사본 `_view(ps, kept)` | O(N) | 조합마다 한 번 | 458 전에도 새 본문마다 같은 일을 했다 |
| `_behavior_events` (ClickHouse) | O(H) | 요청마다 | |
| `_onboarding` → `_genre_catalog` → `catalog_facets()` | O(N) | 사본마다 한 번 | 조건 어휘. 사본 캐시(`_facets_cache`) |

`search` 는 새 조건이면 통과한 구간의 사본을 새로 만든다(O(조건에 맞는 N), 같은 조건은 LRU 로 재사용).
458 전에는 Spring 이 조건마다 다른 본문을 보내 같은 일이 났다. 조건을 ClickHouse 집계로 풀어 사본 없이
허용 목록만 넘기는 것은 기준 풀 스냅샷을 걷어낼 후속이다(설계 §9.3 "458 구현").

## `recommend` (daily · next · search)

| 단계 | 연산 | 복잡도 | 분류 | 예전 |
|---|---|---|---|---|
| DNA | `user_positives` · `labels_for` (ClickHouse) | O(H) | 요청마다 | |
| DNA | 검색일 때 조건 안 선호 구간 찾기 | O(H) | 요청마다 | `c.pool.moment_id.isin(pos)` — **O(N)** |
| DNA | 원본 fetch · 군집(linkage) | O(H·D) · O(H²) | 요청마다 | |
| DNA | `other_positives` + 원본 fetch (질의 측 보정) | O(전체 사용자 L1) | 요청마다 — **풀과 무관, 사용자 수에 비례** | 아래 "남은 것" |
| 온보딩 | `onboarding_seeds` | O(1) 캐시 조회 | 요청마다 | 풀 전체를 조건으로 거르고 원본 평균 — **O(N)** |
| 온보딩 | `compute_onboarding_seeds` (곡 표 거르기 + 곡별 합 더하기) | O(T) + O(고른 곡의 구간) | 답변 조합마다 한 번 | |
| 온보딩 | `seed_sums` (곡 × 구간 종류별 중심화 벡터 합) | O(그 곡들의 구간·D) | 곡마다 한 번 (사본·세대) | 모델 묶음 `onboarding_seeds.npz` 로 내보낼 단위 (`tools/export_onboarding_seeds.py`) |
| 사용자 | `model_user_vec` · `user_behavior` | O(H) | 요청마다 | |
| 사본 | `pool_index()` (`PoolIndex`: id→행, 추천 행, 곡 안 에너지 백분위, 콜드스타트 순서, 곡→행) | O(N) | 사본마다 한 번 | 요청마다 `pool[~isin(hard)]`·`dur_sec` 필터·`rank` — **O(N)** |
| 게이트 | `_gate` (언어·보컬·제외 스타일·구간 유형 → 풀 마스크 + 허용 목록) | O(N) | 조합마다 한 번 | 풀 전체를 점수 매긴 뒤 후보마다 `fac.loc` — **O(N)**, 게이트마다 `set_index` |
| 후보 | **씨앗 → `vector_store().search_many`** | 로컬 O(N·D·C) · Qdrant ANN | **벡터 검색 (ANN 대용)** | 로컬 갈래는 검색 없이 풀 전체를 점수 매겼다 |
| 후보 | 콜드스타트 `_cold_candidates` (사본의 콜드스타트 순서 앞에서) | O(K + H) | 요청마다 | 풀 전체를 점수 매기고, Pinecone 이면 **풀 전체 fetch** (9/24 이그레스 소진) |
| 후보 | 검색 무드 후보 (`PoolIndex.mood_rows`) | O(K) · 색인은 O(N) 사본마다 한 번 | 요청마다 | |
| 후보 | 하드 제외(목록·세션·DISLIKE) | O(K + H) | 요청마다 | `c.pool.track_id.isin(hard)` — **O(N)** |
| 점수 | 원본 fetch·중심화 | O(K·D) | 요청마다 | 풀 전체 fetch — **O(N·D)** |
| 점수 | 규칙 유사도 `V @ C.T` · 학습 점수 · 사용자 평균 질의 | O(K·C·D) · O(K·d) | 요청마다 | O(N·…) |
| 점수 | 결합기 특징 `serve_features` × (1 + C) | O(K·C) | 요청마다 | 곡 안 에너지 백분위를 후보마다 `groupby` — 이제 사본 값(`rms_pct`)을 쓴다 |
| 점수 | CLAP·무드 가점 (`mood_match_ratio`) | O(K) | 요청마다 | 무드는 요청마다 곡 표 `set_index` — **O(T)** |
| 점수 | 행동 가점·최근 재생 감점 | O(K) | 요청마다 | |
| 점수 | 흐름 감점 `_flow_penalty` | O(K) · 기준 에너지 dict 는 사본마다 한 번 | 요청마다 | `c.mom.moment_id == x` — **O(N)** |
| 게이트 | BPM 게이트 · 분위 임계값 | O(K log K) | 요청마다 | |
| 선택 | 묶음별 라운드로빈 · 근접 중복 제거 · 탐색 칸 | O(K·C + n²) | 요청마다 | |
| 순간 | 첫 chorus (`first_chorus_moments`) | O(K + 뽑힌 곡의 구간) | 요청마다 | 후보 밖 chorus 는 `PoolIndex.track_rows` 로 덧붙인다 |
| 응답 | 사유 문장·`track_info` | O(n) | 요청마다 | |

## `similar`

| 연산 | 복잡도 | 분류 | 예전 |
|---|---|---|---|
| 기준 구간 찾기 | O(1) | 요청마다 | `c.pool.moment_id == x` — **O(N)** |
| 원본 fetch · 중심화 | O(D) | 요청마다 | |
| `candidate_search` | 로컬 O(N·D) · Qdrant ANN | **벡터 검색 (ANN 대용)** | 로컬은 요청마다 `center(Z[pool_rows])` — O(N·D) **복사 + 할당** |
| 곡당 하나 거르기 | O(n·`SIMILAR_OVERFETCH`) | 요청마다 | `c.pool.set_index(...)` — **O(N)** |

## 판마다 한 번

| 연산 | 복잡도 | 비고 |
|---|---|---|
| `LocalVectorStore` μ (색인 풀 전체 평균, !285) | O(N_색인·D) | 판을 올린 뒤 첫 사용 |
| `LocalVectorStore.radius` (행마다 ‖z − μ‖) | O(N_색인·D) | 중심화 행렬을 따로 만들지 않고 검색을 푼다 — 90만 구간이면 5GB 를 아낀다 |
| `current_snapshot` (시험의 판 지문 대조 — 요청 경로 밖) | O(N_색인·D) | |

## 남은 것 (이 티켓 밖)

1. ~~**본문 `catalog`** — 파싱·해시가 요청마다 O(N).~~ 계약 v3(458)에서 사라졌다. 대신 기준 풀 스냅샷 갱신이
   풀 전체를 읽는다(요청 경로 밖, 내용이 바뀔 때만 다시 만든다). 풀 전체 값을 ClickHouse 집계로 대체하는 것이 후속이다.
2. **`other_positives`** — 다른 사용자 전원의 L1 을 읽고 원본을 모두 가져와 평균 낸다. 풀이 아니라
   **사용자 수 × 이력**에 비례한다. 평균은 합으로 쪼개지므로 라벨 판(run)마다 전체 합을 한 번 세고
   요청마다 자기 몫만 빼면 O(H) 가 된다 — 라벨 판 세대를 캐시 열쇠로 삼는 일이라 따로 다룬다.
3. **파일 경로(개발·오프라인)** — `labels_for`·`other_positives`·`_file_events`·`load_overrides` 는
   ClickHouse 가 없을 때 파일을 통째로 읽는다. 운영은 ClickHouse 라 요청 경로에 없다.

## 순위가 바뀔 수 있는 자리

후보를 먼저 좁혀서 생기는 차이다. 설계 §9.3("후보 약 300개 → 점수")이 정한 동작이고, qdrant
갈래도 같은 후보 단계를 거친다.

- **씨앗이 있는 경로**: 결합기 특징(규칙 유사도·학습 점수·길이·위치·BPM)이 코사인 상위 K 밖의 구간을
  더 높게 칠 수 있다. 그 구간은 이제 후보가 아니다.
- **분위 임계값·백분위**: `P_TOP_FRAC` 분위와 `pct()` 가 풀이 아니라 후보 안에서 센다.
- **게이트를 먼저 건다**: 게이트 밖 행이 백분위 분모에서 빠진다(순서는 같고 간격이 달라진다).

바뀌지 않게 한 것:

- 콜드스타트 순서(에너지 백분위·위치)는 사본 전체로 센다 — 뽑히는 곡이 풀 전체를 매긴 것과 같다
  (`test_vector_store.py::test_cold_start_picks_the_same_tracks_as_scoring_the_whole_pool`).
- 곡 안 에너지 백분위(결합기 특징 `rms_pct`)는 곡의 추천 구간 전체로 센다 — 후보만 보고 세지 않는다.
- 하드 제외만큼 더 받아 와서 "제외하지 않은 것 중 상위 K" 가 된다.
- 첫 chorus 는 후보 밖에 있어도 찾는다.
- 온보딩 씨앗은 곡별 합으로 만들어도 예전 식(구간 원본 평균)과 같다
  (`test_onboarding_seeds_equal_the_direct_mean`).

## 합성 부하 측정

`python -m ai_server.tools.bench_request_path` (1만 곡 × 9구간 = 9만 구간, 1536차, 결합기·학습 모델 포함).
전·후 수치는 MR 에 싣는다 — 수치는 기계마다 다르므로 이 문서에 굳히지 않는다.
