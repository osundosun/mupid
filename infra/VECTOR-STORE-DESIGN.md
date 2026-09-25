# 추천 서버 저장소 재설계 — Qdrant · ClickHouse 피처 저장소 · 모델 묶음

작성 2026-09-25 · 상태 **제안(설계)** · 대상 `S15P21A204-423` 후속

이 문서는 추천 서버(`ai_server`)가 곡·구간 데이터를 **어디에 두고 어떻게 읽는지**를 다시
정한다. 연동 계약(`infra/AI-SPRING-CONTRACT.md`)의 2026-09-23 절을 대체할 **계약 v3**의
근거다. 수치는 표시가 없으면 2026-09-24~25 운영 실측이다.

---

## 0. 요약

> **쓰기는 언제나 배치 → Spring API → 저장소. Spring 이 MySQL 과 ClickHouse 에 같은 내용을
> 쓰고, AI 는 ClickHouse(서비스 값)와 Qdrant(자기 파생 데이터)를 읽기만 한다. ai_server 는
> 모델과 계산만 들고 상태 없이 돈다.**

| 무엇 | 지금 | 바꾼 뒤 |
|---|---|---|
| 곡·구간 서비스 값 (장르·BPM·라벨·삭제…) | 요청마다 본문 `catalog` (약 497KB) | **ClickHouse 피처 저장소** — Spring 이 MySQL 과 함께 쓴다 |
| 순간 벡터·특징 | 색인 판 파일 (Pinecone 은 이그레스 소진으로 꺼 둠) | **Qdrant** — AI 가 오디오에서 만든 파생 데이터 |
| 모델 (가중치·보정기·PCA·통계·μ) | 색인 판에 섞여 있음 | **모델 묶음** — 배치용 API 로 Object Storage 에 올리고, **CI 가 이미지에 굽는다** |
| 요청 본문 | 약 497KB, 곡 수에 비례 | **수백 B~2KB**, 곡 수와 무관 |
| 검색 조건 | Spring `QueryPlanFilter` | AI 가 ClickHouse 로 풀어 Qdrant 에 넘김 |
| 정확성 (삭제·재생 가능) | Spring 이 후보에서 뺌 | **Spring 이 응답에서 최종으로 뺌** |

지키는 원칙 여섯:

1. **쓰기 관문은 Spring API 하나.** `JupyterHub(music_preprocessing) → 배치 → Spring API →
   MySQL, ClickHouse`. 배치·관리자 화면 어느 쪽도 저장소에 직접 쓰지 않는다.
2. **MySQL 과 ClickHouse 는 같은 내용을 가진다.** 이 문서에서 가장 중요한 불변식이다 (§8).
3. **AI 는 Spring·MySQL 을 부르지 않는다** (2026-09-23 계약 그대로). 저장소를 읽기만 한다.
4. **모델 버전과 그 모델이 만든 데이터는 한 짝으로만 쓴다.**
5. **Object Storage 는 음악 청크와 같은 길로만 쓴다 — Nginx + Spring Security + SeaweedFS(Filer).**
   Spring 은 인증·경로만 정하고(`auth_request`·`X-Accel-Redirect`), 바이트는 Nginx 가 Filer 와 직접
   주고받는다. Filer 는 밖에 열지 않는다(결정 기록 0016). **모델 가중치 파일**이 이 길로 오간다 (§6.5).
   벡터는 파일이 아니라 곡당 약 80KB 의 데이터라 음악·구간 등록과 같은 **Spring API 본문**으로 받는다 (§6.6).
6. **ROLE_SYSTEM 계정은 쓰는 곳마다 따로 둔다.** 리프레시 토큰은 7일 만료이고 쓸 때마다 회전한다 —
   여러 곳이 같은 토큰을 쓰면 한쪽이 다른 쪽을 끊는다. **ai_server 는 ROLE_SYSTEM 을 갖지 않는다.**

### 위험은 세 축에 모인다

| 축 | 질문 | 다루는 곳 |
|---|---|---|
| **① 저장소 정합성·가용성** | ClickHouse·Qdrant 가 MySQL 과 같은 내용인가. 죽으면 어떻게 되나 | §8, §10.1 |
| **② 모델–데이터 버전 정합성** | 서버의 모델 버전과 Qdrant 의 파생 벡터가 같은 모델에서 나왔나. 동결 값이 낡지 않았나 | §10.2 |
| **③ 계산 경로 확장성** | 저장소를 나눠도 요청 경로가 전체를 훑지 않는가 | §10.3 |

규모가 늘 때 **가장 먼저 막히는 곳은 ③**(ai_server 코드에 남은 풀 전체 연산)이고, **가장
중요한 불변식은 ①의 MySQL–ClickHouse 정합성**이다.

---

## 1. 배경 — 왜 다시 설계하나

| 날짜 | 일 | 드러난 문제 |
|---|---|---|
| 09-22 | 추천 서버가 기동 때 Spring 카탈로그 API 를 ROLE_SYSTEM 토큰으로 읽음(416) | 토큰 만료·파싱 불일치로 배포 롤백, 라벨러 6일 실패(423) |
| 09-23 | 요청 본문 `catalog` 로 전환(!281) | 본문이 곡 수에 비례해 커진다 |
| 09-24 | Pinecone 전환(431)이 운영에 처음 올라감 | 콜드스타트가 요청마다 풀 전체 원본 벡터(~9천×1536)를 fetch → 요청당 40~112초, **무료 요금제 월 이그레스 1GB 소진**, 추천 전면 중단 |
| 09-24 | 로컬 벡터로 복구 | 수신기가 `moments_emb.npy` 를 거절해 **수동으로** 판을 둠 (§12) |

공통 원인:

- **색인 판이 성격이 다른 데이터를 행 번호로 묶어 한 덩어리로 배포**한다. 곡 하나가 늘어도 판
  전체를 다시 잘라 올려야 하고, 가중치만 바꿀 수도 없다.
- **곡 데이터를 요청마다 옮긴다.** 거의 바뀌지 않는 같은 0.5MB 를 요청마다 다시 보낸다.

---

## 2. 규모 기준

| 기준 | 곡 | 구간 | 쓰임 |
|---|---|---|---|
| 운영(데모) | 1,060 | 9,321 | 지금 |
| **검증** | 1만 | 약 9만 | 합성 데이터로 실제 부하를 건다 |
| **설계** | 10만 | 약 90만 | 요청 크기·메모리·적재가 이 규모에서 무너지지 않아야 한다 (계산으로 확인) |

데모는 1,060곡에서 늘지 않지만, 실 서비스를 전제로 설계한다.

---

## 3. 지금의 입력과 크기

### 3.1 요청 본문 (Spring → AI)

`catalog` 가 본문의 거의 전부다 (1,034곡 · 9,098구간, 2026-09-24).

| 부분 | 열 | 크기 |
|---|---|---|
| sections | `external_id` 109KB · `energy` 81KB · `section_label` 73KB · `start_ms` 55KB · `end_ms` 59KB | 377KB |
| tracks | `category` 30KB · `moods` 22KB · `genres` 18KB · 나머지 8열 각 4~10KB | 120KB |
| **합계** | | **497KB** (gzip 109KB. Spring 은 압축하지 않는다) |

구간당 약 54B → 1만 곡 약 5MB, **10만 곡 약 49MB**. 본문이 바뀐 첫 요청은 AI 가 후보 사본을
만드느라 2.5~3.3초 걸린다(실측).

> 연동 계약의 "약 317KB" 는 에너지·분석 값을 채우기 전 수치다.

### 3.2 요청 밖

| 대상 | 시점 | 크기 |
|---|---|---|
| ClickHouse 행동·선호 라벨 | 요청마다 | 사용자당 수 KB~수십 KB |
| ClickHouse `track_sections` | 라벨러·신호 변환 | 9,321행 |
| 색인 판 (node-b) | 기동 | 약 80MB (§4) |

---

## 4. 지금의 색인 판 — 무엇이 들어 있나

운영 판 `ea692ef6…-local-emb` (9,334행 · 1,062곡).

| 분류 | 파일 | 내용 |
|---|---|---|
| **구간 데이터** (곡 수에 비례, 행 정렬) | `moments.parquet` 0.4MB | 구간 목록, 곡 내 위치, `rms`, 출처, 행 번호 `emb_row` |
| | `moments_emb.npy` 57MB | MERT 임베딩 9334×1536 |
| | `features_v1.npz` 의 `X` | 랭킹 입력 특징 9334×285 (PCA 256 + 부가 29) |
| | `stage1_section.npy` 4.8MB | 구간 인코더 출력 9334×128 |
| | `clap_moments.npz` 2.4MB | CLAP 2182×512 fp16 — **23%만 있다** |
| **모델** (곡 수와 무관) | `stage1_weights.npz` | Dual Encoder 사용자 인코더 가중치 (NumPy 판, 서빙이 읽음) |
| | `stage1.pt` | 학습 원본 체크포인트(torch). 서빙 이미지에 torch 가 없어 **운영에선 안 쓰인다** |
| | `logistic_flow_stage1.json` | `model_sim` → 확률 보정기. 최종 점수의 척도·임계값 |
| | `features_v1.npz` 의 `pca_mean`·`pca_comp` | MERT → 256 PCA (FMA 적합) |
| | `features_info.json` · `feature_stats_v1.npz` | 특징 정의·열 순서, 동결 z-score 통계 |
| **판 정보** | `index_meta.json` · `slice_report.json` | 계약 버전·행 수 |
| **잔재** | `track_features.parquet` | MySQL 로 옮긴 BPM·언어·스타일의 옛 사본. 읽지 않는다 |

**파생 의존** — 축 ②의 근거다.

```
MERT Z ──PCA──┐
구간 위치·라벨·에너지 ─┴─▶ X (285) ──구간 인코더──▶ stage1_section (128)
```

- 가중치가 바뀌면 `stage1_section` 을, PCA 가 바뀌면 `X`·`stage1_section` 을 다시 계산해야 한다.
- `X` 에 **구간 라벨 원핫**이 들어 있어, MySQL 에서 라벨이 바뀌면 `X`·`stage1` 도 다시 계산해야 한다.
- 판 안에 제목 ID 로 된 옛 구간 236행이 섞여 있다 (MySQL 과 겹치는 건 9,098구간).

**누가 무엇을 읽나**

| 쪽 | 읽는 것 |
|---|---|
| 서빙 | 사용자 인코더 가중치 + 보정기 (1MB 미만) + 구간 벡터·특징 |
| 분석 배치 (새 곡 → X·구간 벡터) | PCA · 특징 정의 · 동결 통계 · 구간 인코더 가중치 · μ |
| 학습 | `stage1.pt` |

---

## 5. 목표 구조

```
JupyterHub (music_preprocessing 배치 · 학습, GPU)        ROLE_SYSTEM(배치용 계정)
   │  ① 곡·구간·분석 값 + 벡터 (Spring API, 곡당 약 80KB)   ② 모델 가중치 파일 (바이트)
   ▼                                                        ▼
Spring API (EC2) ◀────────── auth_request ─────────── Nginx ──▶ SeaweedFS Filer (internal)
   │   쓰기 관문 (유일)                                        (모델 묶음 보관, CI 가 받아 감)
   ├───────────────────────────────────────────────────────────┐
   ├─▶ MySQL        정본                                     │
   ├─▶ ClickHouse   같은 내용 (track_sections·track_features)│ 미반영 기록·재시도·대조 (§8)
   └─▶ Qdrant       벡터 (새 곡·재분석·모델 재계산)            │
                                                              │
ai_server (node-b) ──읽기──▶ ClickHouse (피처·행동)            │
   │              └──읽기──▶ Qdrant                            │
   │   이미지에 구운 서빙 가중치  ◀── CI (models.lock, ROLE_SYSTEM CI 계정)
   │                                                          │
   ▼ 순위 (external_id, limit + 여유분)                        │
Spring: 번역 + **최종 필터(삭제·재생 가능, MySQL 기준)** ──▶ 앱
```

### 5.1 저장소의 성격

| | ClickHouse 피처 저장소 | Qdrant |
|---|---|---|
| 쓰는 쪽 | **Spring** (MySQL 과 함께) | **Spring** (새 곡·재분석·모델 재계산 모두) |
| 담는 것 | 곡·구간 서비스 값 (+ 기존 행동 데이터) | 오디오에서 만든 벡터·특징 |
| 바뀌는 때 | 운영 데이터가 바뀔 때 | 곡이 들어올 때, **모델이 바뀔 때** |
| 성격 | **MySQL 의 사본** (Spring → AI 입력) | AI 의 **파생 데이터 저장소** |
| 복구 | MySQL 에서 다시 쓴다 (대조 작업) | node-b 로컬 스냅샷, 또는 **JupyterHub 가 벡터를 다시 보낸다** (벡터의 원본은 JupyterHub 전체 색인) |
| 복제 | 2벌 (node-a·node-b) | 단일 (node-b) |

**MySQL 값의 사본은 ClickHouse 한 곳뿐이다.** ClickHouse 는 이미 `track_sections`(구간 사본)를
들고 있고 이것 없이는 재생 이벤트를 구간으로 바꿀 수 없으므로(§5.3), 새 저장소를 두지 않고
그 자리를 넓힌다. **Qdrant 에는 MySQL 값을 넣지 않는다.**

### 5.2 모델은 두 곳에 걸쳐 있다

가중치(사용자 인코더·보정기)는 ai_server 가 모델 묶음으로 받아 운용하지만, **모델이 만든
결과**(`stage1_v{N}`, `X`)는 Qdrant 에 있다. 가중치를 바꾸면 Qdrant 의 파생 벡터도 같이 바꿔야
한다 — 축 ②.

### 5.3 ClickHouse `track_sections` — 이미 있는 사본

| | 내용 |
|---|---|
| 담는 것 | `music_section_analysis` 의 사본: 곡 번호·구간 번호·순번·`section_uid`(= `external_id`)·라벨·시작/끝 ms, 그리고 MySQL 에 없는 `analysis_version` |
| 쓰는 쪽 | Spring `MusicSectionServiceImpl.syncToAnalytics` — **MySQL 커밋 뒤** 같은 내용을 쓴다(435) |
| ClickHouse 안에서 | 재생 이벤트가 들어올 때 MV 가 재생 위치가 어느 구간인지 찾는다(`dict_track_sections`, `mv_play_section_coverage` JOIN). **못 찾으면 신호를 버린다** |
| AI 가 | 라벨러·`pull_signals` 가 곡 번호 ↔ 문자열 ID 변환에 쓴다(`v_track_sections_current`) |
| 엔진 | `ReplicatedReplacingMergeTree`, `mupid_all`(1 샤드 × 2 레플리카) |

**2026-09-25 대조 결과** — MySQL 과 두 레플리카가 완전히 같다.

| | MySQL | ClickHouse-0 (node-b) | ClickHouse-1 (node-a) |
|---|---|---|---|
| 구간 행 | 9,321 | 9,321 (`v1`) | 9,321 (`v1`) |
| 한쪽에만 있는 구간 | — | 0 | 0 |
| 값이 다른 구간 | — | 0 | 0 |
| 중복 키 | — | 0 | 0 |

다만 지금 맞는 것은 **쓰기가 한 번도 실패하지 않았기 때문**이다. 실패하면 로그만 남고 끝난다
(§8.1). 그리고 **삭제된 26곡의 구간 223개가 ClickHouse 에 그대로 있다** — MySQL 도 논리 삭제라
둘은 같지만, ClickHouse 는 삭제됐다는 사실을 모른다.

> **455 에서**: 구간 사본은 그대로 두고(MySQL 도 지우지 않는다) 삭제를 `track_features.is_deleted` 로
> 싣는다. 서빙은 그 표와 맞춰 삭제된 곡을 뺀다(§6.2). 배포 뒤 첫 대조·재시도가 26곡을 `is_deleted = true` 로 채운다.

### 5.4 소유권

| 저장소 | 쓰는 쪽 | 정본인가 |
|---|---|---|
| MySQL | Spring | **정본** |
| ClickHouse `track_sections`·`track_features` | Spring | MySQL 의 사본 (같아야 한다) |
| ClickHouse 행동·라벨 | 이벤트 파이프라인(Kafka)·라벨러 | 행동의 정본 |
| Qdrant | Spring | AI 파생 데이터의 정본 |
| SeaweedFS `system/model/` | Nginx → Filer (Spring 이 `auth_request` 로 허락) | 모델 가중치 파일의 정본. 목록은 MySQL(`object_storage_files`·`model_versions`) |
| JupyterHub 전체 색인 | 분석 배치 | **벡터의 원본.** Qdrant 는 여기서 Spring API 로 받은 사본이다 |

---

## 6. 저장소별 설계

### 6.1 Qdrant

컬렉션 `moments__{임베딩버전}` (예: `moments__mert-v1-95m`). 임베딩 모델이 바뀌면 새 컬렉션을
만들고 바꿔 끼운다.

| 항목 | 설정 |
|---|---|
| 포인트 ID | `UUIDv5(NS, external_id)`. Qdrant ID 는 정수·UUID 만 된다. `music_section_analysis_id` 는 구간 교체(`PUT /section`) 때 새로 발급되므로 쓰지 않는다 |
| `mert` 1536 | 중심화 방향 `u = (Z-μ)/‖Z-μ‖`, cosine. 원본 `on_disk`, int8 스칼라 양자화본만 메모리. **ANN 대상** |
| `stage1_v{N}` 128 | 모델 버전 N 의 구간 벡터. 사용자 벡터로 **직접 ANN** 가능 (개인화 후보) |
| `clap` 512 | 있는 구간만 (선택 벡터) |
| `feat_v1` 285 | 점수 계산용 fetch 전용. HNSW 끔 |
| payload | `external_id`, `track_id`, `r`(‖Z-μ‖), `source`, `emb_version`, `feat_version`, **`analysis_version`·`seg_start_ms`·`seg_end_ms`** (구간 지문) — **AI 가 만든 값과 구간 지문만** |

**용량 (설계 기준 90만 구간, 계산)**: 메모리 int8 `mert` 약 1.4GB + HNSW 약 0.5GB + `stage1`
약 0.5GB, 디스크 원본 약 8GB. node-b 가용 메모리 약 12GiB·디스크 여유 278GB 로 충분하다.
Qdrant 자체는 이 규모에서 병목이 아니다.

**CPU**: node-b 는 할당 가능 3코어에 ai·kafka·clickhouse-0·storage-volume-0·dbstudio 가 함께
있다. Qdrant 와 ai 에 CPU 요청값·한도를 건다.

**백업**: Qdrant 스냅샷을 **node-b 의 볼륨(PV)에만** 둔다. Object Storage 로 올리지 않는다 — Qdrant 는
JupyterHub 의 전체 색인에서 다시 보내 만들 수 있고, 스냅샷은 복구를 빠르게 하는 용도일 뿐이다. 그래서
백업 작업에는 쓰기 자격이 필요 없다. **node-b 디스크를 잃으면** JupyterHub 분석 배치가 전 곡의 벡터를
Spring API(§6.6)로 다시 보낸다 — 설계 기준 90만 구간이면 곡당 요청 10만 번이라, 재적재 시간을 재 두고
허용 범위를 정한다(§11 미결 12).

> **456 에서**: 정의는 `ai_server/app/qdrant_collection.json` 한 곳(Spring 457 도 이것으로 만든다), 읽기는
> `ai_server/app/vector_store.py` 의 `QdrantVectorStore`(`MUPID_VECTOR_BACKEND=qdrant`, 기본 local), 매니페스트는
> `infra_k8s/base/qdrant.yaml`·`qdrant-networkpolicy.yaml`, 계약은 `ai_server/docs/QDRANT.md`.

### 6.2 ClickHouse 피처 저장소

#### 표

| 표 | 지금 | 바꾼 뒤 |
|---|---|---|
| `track_sections` | 구간 사본 (있음) | 그대로. `analysis_version` 을 MySQL 과 같은 값으로 |
| **`track_features`** (신규) | — | 곡 단위 사본: `track_id`(music_id), `track`(문자열 곡 ID), `duration_ms`, `bpm`, `bpm_requested_min/max`, `language`, `category`, `genres` Array, `moods` Array, `has_vocal`, `vocal_gender`, **`is_deleted`, `playable`**, `analysis_version`, `version`(Spring 이 쓴 시각) |
| ~~`section_features`~~ (선택) | — | → **`track_sections` 에 `energy` 열을 더했다 (455).** MySQL 에서 같은 행(`music_section_analysis.energy`)이라 같은 요청이 같이 바꾸고, 같은 곡 단위 교체·미반영 기록·대조를 그대로 탄다. 표를 나누면 구간 하나를 두 표에서 교체하고 옛 순번도 두 번 지워야 한다 |

- 둘 다 `track_sections` 와 같은 방식: `ReplicatedReplacingMergeTree`, `mupid_all`, **곡 단위 교체**,
  버전 컬럼.
- 값의 정의는 지금 `catalog` 열(연동 계약 2026-09-23 절 표)을 그대로 옮긴다.
  → 455: Spring `TrackFeatureSource` 가 `RecommenderCatalogServiceImpl.build` 와 같은 규칙으로 만들고
  (`TrackFeatureMysqlTest`), AI `feature_store.Lookup.body()` 가 본문과 같은 값·순서를 낸다(`test_feature_store.py`).
- 미반영 기록은 종류를 나눈다(`kind = TRACK_FEATURES`) — 입구가 거의 겹치지 않고, 한쪽 실패가 다른 쪽을
  막지 않게. 이유와 쓰기 입구 목록은 `api/knowledge/architecture/analytics-sync.md`.
- **`is_deleted`·`playable` 을 싣는다.** 지금은 삭제된 곡의 구간이 ClickHouse 에서 살아 있는
  것처럼 보인다(§5.3). 곡 삭제·복구, 스트림 상태 변경도 Spring 이 ClickHouse 에 쓴다.

#### 읽는 법 (AI)

| 용도 | 방법 |
|---|---|
| 후보 수백 개의 특징 | `track_features`·`track_sections` 를 `FINAL` 로, `WHERE section_uid IN (…)`. 서빙용으로 **`section_uid` 정렬 키를 가진 표(또는 딕셔너리)** 를 둔다 — 지금 정렬 키 `(analysis_version, track_id, section_index)` 로는 전체를 훑는다. → **455: 딕셔너리 `dict_serving_sections`**(`section_uid` 키, `COMPLEX_KEY_HASHED_ARRAY`, 원본 `v_serving_sections` = 곡의 현재 분석 버전 구간만). 표를 하나 더 두면 Spring 이 두 표를 교체하고 옛 행을 두 번 지워야 하지만, 사전은 원본을 통째로 다시 읽어 지운 구간이 남지 않는다. 원본을 따라오는 데 최대 30초(`INVALIDATE_QUERY`). 곡 특징은 `track_features_local FINAL WHERE track_id IN (…)` — 조회 한 번에 둘 다 |
| 조건 풀기 (검색) | `SELECT count() / section_uid … WHERE has(genres, …) AND language IN … AND NOT is_deleted` |
| 곡 번호 ↔ 문자열 ID (배치) | 지금처럼 `v_track_sections_current` |

- `ReplacingMergeTree` 는 병합 전에 옛 행이 잠깐 남는다. **읽을 때 `FINAL`(또는 곡 단위 최신 행)**
  로 읽는다. 표가 작아 비용은 작다.
- AI 는 **읽기 전용 서빙 계정** `mupid_serving`(`api/clickhouse/users/20-serving-user.xml`)으로 읽는다.
  (지금 AI 가 쓰는 `mupid` 는 읽기 전용이 아니었다 — 라벨러가 `section_labels` 에 쓴다.)
- **지연은 먼저 잰다** (§13 4단계). 1만 곡 합성에서 조건 풀기·특징 조회 p95 가 기준을 넘으면,
  그때 ClickHouse **앞에** 순수 캐시(ClickHouse 에서 파생, MySQL 과 무관)를 검토한다.

#### 서빙 트래픽과 분석 트래픽

ClickHouse 에는 관리자 대시보드·MUBTI 리포트 같은 분석 조회도 돈다. 서빙 조회가 밀리지 않게
**서빙 전용 사용자·프로파일**(`max_execution_time`, 동시성 한도)을 두고, 서빙은 가까운
레플리카(node-b 의 `clickhouse-0`)를 읽는다.

→ **455**: 프로파일 `serving` — `readonly = 1`(질의에서 한도를 못 푼다), `max_execution_time = 2`,
`max_concurrent_queries_for_user = 16`, `max_memory_usage = 1GB`, `max_result_rows = 20만`. 읽는 표·사전이 전부
`mupid_all` 이라 붙은 노드 안에서 끝나므로, ai 는 헤드리스 이름 대신 `clickhouse-0.clickhouse:8123` 에 붙는다
(`MUPID_CLICKHOUSE_SERVING_URL`). 비밀(`MUPID_CLICKHOUSE_SERVING_PASSWORD`)이 없으면 ClickHouse 가 빈 비밀번호로
계정을 여므로(25.3 확인), 파드는 그때 난수로 계정을 잠그고 뜬다.

### 6.3 모델 묶음

학습이 JupyterHub 의 정해진 자리(`~/mupid_models/stage1/v{N}/` — 기존 폴더와 섞지 않는다)에 묶음을
만든다. **Object Storage 에 올리는 것은 CI 다** — `models.lock` 이 master 에 병합되면 GitLab Runner 가
JupyterHub 에서 받아 올린다(아래 "반영 흐름"). 학습 작업은 ROLE_SYSTEM 이 필요 없다.

```
~/mupid_models/stage1/v{N}/          (JupyterHub, 학습 산출물)
  └─ CI 가 받아 ─▶ system/model/stage1/v{N}/   (Object Storage, 채택된 버전만)
  manifest.json          버전·체크섬·학습 데이터·평가 지표(hit@k)·임베딩 버전·특징 버전
  user_encoder.npz       서빙
  combiner.json          서빙
  section_encoder.npz    분석 배치 (구간 벡터 계산)
  pca.npz                분석 배치 (pca_mean·pca_comp)
  features_info.json     분석 배치·학습
  feature_stats.npz      분석 배치
  mu.npy                 분석 배치 (중심화, 동결)
  onboarding_seeds.npz   서빙 (조건별 씨앗 — 배치가 미리 계산)
  stage1.pt              학습 (서버는 받지 않는다)
```

#### `models.lock` — 운영에 쓸 모델의 정본

```yaml
stage1:
  version: v3
  jupyter_dir: mupid_models/stage1/v3
  files:                              # 파일별 sha256 — 병합 뒤 JupyterHub 파일이 바뀌면 CI 가 멈춘다
    manifest.json:        3f1a…
    user_encoder.npz:     9c2e…
    combiner.json:        77b0…
    onboarding_seeds.npz: 1d4f…
    section_encoder.npz:  a0c3…
    pca.npz:              5e19…
    features_info.json:   c8d2…
    feature_stats.npz:    0b7a…
    mu.npy:               e41f…
  serving: [user_encoder.npz, combiner.json, onboarding_seeds.npz]
```

`stage1.pt`(학습 원본)는 싣지 않는다 — JupyterHub 에만 둔다.

#### 반영 흐름

```
① 학습 (JupyterHub, AI 담당)        ~/mupid_models/stage1/v{N+1}/ 에 묶음 + manifest.json(지표·체크섬)
② 재계산 (music_preprocessing)      전 구간 stage1_v{N+1} 을 벡터 API 로 전송 (§6.6)
                                    → Qdrant 에 새 이름으로 덧붙음. 서빙 중인 v{N} 은 그대로
③ MR: models.lock 을 v{N+1} 로      MR 파이프라인이 JupyterHub 에서 파일을 받아 sha256 대조,
                                    manifest 의 지표를 MR 에 남긴다 (업로드는 하지 않는다)
④ 병합 → master 파이프라인
   model-publish (models.lock 이 바뀐 커밋만)
     a. JupyterHub 에서 파일 받기 (기존 MUPID_DATA_ENV 자격, deploy-jupyter.py 의 Hub)
     b. models.lock 의 sha256 과 대조 — 다르면 멈춘다
     c. 관문: Spring 에 stage1_v{N+1} 이 전 구간에 채워졌는지 묻는다 — 아니면 멈춘다
     d. 배치용 API(§6.5) 3단계로 Object Storage 에 올린다 (system-ci)
                                    model_versions: 후보
   k8s-deploy-ai (매번)
     e. models.lock 의 서빙 파일을 Object Storage 에서 받아(X-Accel-Redirect) sha256 확인 → 이미지에 굽기
     f. 롤링 업데이트. 새 파드는 stage1_v{N+1} 존재를 확인하고 Ready
     g. 성공하면 Spring 에 알린다       model_versions: 채택 (v{N} 은 폐기 예정)
```

- **Git 에 남는다.** `models.lock` 의 변경(누가·언제·어떤 체크섬), MR 본문(지표), 이미지 태그(= 커밋 SHA).
  되돌리기는 `models.lock` 을 되돌리는 커밋 또는 이미지 롤백이다.
- **JupyterHub 에 기대는 것은 모델이 바뀌는 배포뿐이다.** 평소 AI 배포(e)는 Object Storage 에서 받는다 —
  JupyterHub 가 죽어도 코드 배포는 된다.
- **Object Storage 에는 채택된(병합된) 버전만 쌓인다.** 실험 버전은 JupyterHub 에만 있다.
- **같은 버전 번호로 다른 내용을 올릴 수 없다.** Spring 이 버전·파일별 sha256 이 다르면 거절한다(불변).
- **운영 서버는 스스로 새 버전을 찾지 않는다.** 새 가중치가 든 새 이미지로 파드가 교체되는 것이 곧 반영이다.
  서버는 기동 때 "내 모델 버전에 맞는 `stage1` 이 Qdrant 에 있는가" 만 본다(없으면 준비 판정 거부 → 옛 파드가
  계속 돈다, §10.2-1). 파드끼리 다른 모델로 추천하는 시간이 롤링 업데이트 동안뿐이다.
- AI 파드는 토큰도 Filer 접근도 필요 없다. 토큰 만료로 뜨지 못하는 일이 구조적으로 없다(9/23 롤백의 원인).
- 분석 배치(music_preprocessing)는 자기 ROLE_SYSTEM 계정으로 Object Storage 에서 분석용 파일을 받는다.
  **아직 채택되지 않은 v{N+1} 의 재계산(②)** 에 쓸 파일은 JupyterHub 의 학습 산출물을 직접 읽는다(같은 서버).

#### `model_versions` (MySQL, Spring 이 관리)

| 상태 | 언제 | 누가 |
|---|---|---|
| 후보 | ④-d 업로드·체크섬 확인 완료 | CI (`system-ci`) |
| 채택 | ④-g 배포 성공 | CI |
| 폐기 | 다음 버전 채택 뒤 검증이 끝났을 때. Qdrant `stage1_v{N}` 삭제와 함께 | 사람 (운영 절차). 파일은 되돌리기를 위해 한동안 남긴다 |

#### μ(중심화 평균) 동결

지금 μ 는 풀 평균이라 곡이 늘거나 줄 때마다 바뀌고, Pinecone 적재는 그때마다 **전체를 지우고
다시 넣었다.** μ 를 **임베딩 버전마다 한 번 재어 고정**한다.

- 기준: **1,060곡**(삭제 26곡 포함)의 구간 중 임베딩이 있는 것. 제목 ID 옛 행은 뺀다.
- 이후 새 곡은 이 μ 로 `u`·`r` 을 계산해 upsert 만 한다. 전체 재적재는 임베딩 모델을 바꿀 때와
  드리프트가 기준을 넘을 때(§10.2-2)만.
- → **457**: `music-preprocessing vectors-freeze-mu` 가 `registered.json` 에 번호가 있는 곡(= 1,060곡, 삭제 포함 —
  제목 ID 옛 행과 Jamendo 는 번호가 없어 저절로 빠진다)으로 한 번 재어 `~/mupid_models/stage1/v1/mu.npy`(+ `mu.json`:
  구간 수·sha256·판의 풀 평균과의 거리)에 쓴다. **있으면 덮지 않는다.** 모델 묶음 v1 에 서빙 파일로 싣는 것은
  첫 채택 MR 의 일이다 — 그 전까지 ai 는 판의 풀 평균으로 읽으므로 `pool_mean_l2_diff` 를 보고 전환한다.

### 6.4 보안

지금 `mupid` 네임스페이스에는 **NetworkPolicy 가 없고**, 기본 설정의 Qdrant 는 인증이 없다.

| 대상 | 쓰기 | 읽기 |
|---|---|---|
| ClickHouse `track_*` | **Spring 쓰기 계정만** | ai·라벨러 (읽기 전용 계정, 지금 그대로) |
| Qdrant | Spring (쓰기 키) | ai·라벨러 (읽기 키) |
| Object Storage `system/model/` | **CI 만** — Nginx → Filer, Spring `auth_request` (`system-ci`) | Spring `X-Accel-Redirect` (ROLE_SYSTEM) — CI·분석 배치 |

- Qdrant API 키(읽기·쓰기 분리), NetworkPolicy 로 위 표의 파드만 접근.
- **Qdrant·ClickHouse 를 클러스터 밖에 노출하지 않는다.** 배치는 Spring API 로만 들어온다.
- 비밀은 `MUPID_AI_SERVER_ENV`·`MUPID_API_ENV` 로 나눠 준다 (지금 구조 그대로).

**ROLE_SYSTEM 계정 (쓰는 곳마다 하나)**

| 계정 | 쓰는 곳 | 하는 일 | 토큰 보관 |
|---|---|---|---|
| `system-preprocessing` | music_preprocessing (JupyterHub) | 곡·구간·분석 값·벡터 등록(모델 재계산 포함), 분석용 가중치 받기 | JupyterHub 토큰 파일. 180일 토큰 — 회전하지 않아 파일을 다시 쓰는 코드가 같은 값을 받고 쓰지 않는다 |
| `system-ci` | GitLab CI | 모델 가중치 올리기(④-d), 서빙 가중치 받기(④-e), 재계산 완료 확인(④-c), 채택 알림(④-g) | 보호된 CI 변수 `MUPID_SYSTEM_CI_REFRESH_TOKEN`. 180일 토큰 — 회전하지 않아 CI 가 변수를 다시 쓸 일이 없다 (§11 결정 기록) |

**ROLE_SYSTEM 토큰 (S15P21A204-454, 미결 10 의 결정)** — ROLE_SYSTEM 만 로그인·계정 생성·관리자 발급
(`POST /api/v1/user/system/{userId}/token`) 때 리프레시 토큰 만료를 **180일까지** 지정한다(비우면 7일).
ROLE_SYSTEM 의 리프레시 토큰은 **회전하지 않는다** — 재발급은 액세스 토큰만 새로 주고 같은 리프레시 토큰이
만료까지 쓰인다. 새면 관리자가 **계정 단위로 일괄 폐기**한다(`DELETE /api/v1/user/system/{userId}/token`,
Redis 에 "이 시각 이전 발급분 무효" 경계, 액세스 토큰까지 막는다). 다른 역할은 지금과 같다(7일·회전).
계정을 쓰는 곳마다 나누는 이유는 이제 회전 충돌이 아니라 **폐기 범위**다.
| — | 학습 작업 (JupyterHub) | **갖지 않는다** — 산출물을 JupyterHub 에 두기만 한다 | — |
| — | ai_server | **갖지 않는다** | — |

### 6.5 배치용 Object Storage API (모델 가중치 파일)

음악 청크 업로드(결정 기록 0016)의 **요청 → 업로드 → 완료** 3단계를 그대로 따른다. ROLE_SYSTEM
전용(`@SystemRole`)이다. **모델 묶음 파일만** 이 길로 오간다 — 버전별로 보관되고 CI 가 받아 가는
파일이기 때문이다. 올리는 쪽은 **CI 뿐**이다(§6.3 반영 흐름 ④-d). 벡터는 §6.6.

| 단계 | 경로 | 하는 일 |
|---|---|---|
| ① 요청 | `POST /api/v1/system/objects` | 종류(`MODEL`)·이름·버전·파일명·크기·sha256 을 받는다. 허용 종류·크기를 확인하고 경로(`system/{kind}/…`)를 정해 MySQL 에 "업로드 중"으로 기록한다 |
| ② 업로드 | `PUT /upload/system/{objectId}` → Nginx `auth_request` → Filer | Spring 은 인증·경로만 답하고, 바이트는 Nginx 가 Filer 로 바로 보낸다. Authorization 은 Filer 로 넘기지 않는다 |
| ③ 완료 | `POST /api/v1/system/objects/{objectId}/complete` | Spring 이 Filer 에서 **스트리밍으로** 읽어 sha256 을 확인하고 "완료"로 바꾼다. `MODEL` 이면 `model_versions` 를 갱신한다 |
| 받기 | `GET /api/v1/system/objects/{objectId}` | Spring 이 인증 뒤 `X-Accel-Redirect`, Nginx 가 Filer 에서 보낸다 |

- `object_storage_files.category` 는 ENUM 이라 `MODEL` 추가는 **ERD 변경**이다 (파형
  `WAVEFORM` 을 넣을 때와 같은 절차 — `migrate-waveform-2026-09-22.sql`).
- Nginx 에 `/upload/system/` 블록을 청크 블록과 같은 모양으로 더한다. 파일 크기 상한은 Spring 이 갖는다.
- **구현 (S15P21A204-453)**: `api/…/system/` (`SystemObjectController`·`ModelVersionController`), 표 `system_objects`·`model_versions`(`migrate-system-objects-2026-09-25.sql`), Nginx `/upload/system/`, 명세 `api/docs/System/`. 버전의 파일 목록은 ① 요청마다 싣는 `version_files`(= `models.lock` 의 `files` 키)로 정하고, `model_versions` 에 후보 앞 상태 `UPLOADING` 을 더했다.
- **CI (S15P21A204-454)**: `models.lock`(저장소 루트, 처음엔 `stage1: null` — 채택 없음), `infra/ci/model_publish.py`
  (`verify`·`publish`·`bake`·`adopt`), `infra/ci/model.yml`(`model-verify`·`model-publish`), `k8s-deploy-ai` 가
  굽기·채택. 이미지의 모델 자리는 `/app/ai_server/models/stage1/`(+ CI 가 쓰는 `model.json`), ai_server 는
  `app/model_bundle.py` 로 그 자리를 먼저 보고 없으면 판을 본다. 어긋난 점:
  - **MR 대조(③)는 기능 브랜치에서 형식만 본다.** `MUPID_DATA_ENV` 가 보호 변수라 기능 브랜치 MR 파이프라인에
    주어지지 않는다. sha256 대조는 병합 뒤 `model-publish` 가 **멈추는 쪽으로** 한다(④-b).
  - **관문(④-c)은 스위치다** — `MUPID_MODEL_RECOMPUTE_GATE`(기본 꺼짐, 경고만). 확인 API 는 457 이 만들었다 —
    `GET /api/v1/system/vectors/stage1/{version}/status` → `data.complete`(스크립트도 이 경로로 맞췄다). 켜는 것은
    운영 결정이고 방법은 `infra/ci/VARIABLES.md`.
  - 파드의 `stage1_v{N}` 존재 확인(④-f)은 456 이후다.

### 6.6 벡터 등록 API

벡터는 **Spring API 본문으로 받고, Spring 이 바로 Qdrant 에 쓴다.** Object Storage 를 거치지 않는다.

**왜 Filer 를 거치지 않나** — 크기가 평범한 API 수준이다(곡당 구간 약 9개 기준).

| 종류 | 차원 | 곡당 (float32) |
|---|---|---|
| `mert` | 1536 | 55KB |
| `feat_v1` | 285 | 10KB |
| `stage1_v{N}` | 128 | 4.6KB |
| `clap` | 512 (fp16) | 9KB |
| **합계** | | **약 80KB** (JSON 숫자 배열이면 약 200KB) |

음악 청크를 Nginx → Filer 로 보내는 이유는 파일 하나가 수 MB~수백 MB 인 음원 바이트를 JVM 힙에
올리지 않기 위해서다. 벡터는 그 규모가 아니다. Nginx 일반 요청 한도(20MB)는 물론, 지금 AI 에 보내는
`catalog` 본문(497KB)보다도 작다.

**엔드포인트 — 종류별이 아니라 용도별 둘**

| 경로 | 용도 | 담는 것 |
|---|---|---|
| `PUT /api/v1/music/{musicId}/vectors` | 새 곡·재분석 | 그 곡의 **모든 종류**를 한 요청에 (`mert`·`r`·`feat_v1`·`stage1_v{N}`·`clap`), `analysis_version` |
| `PUT /api/v1/system/vectors/stage1/{version}` | 모델 재계산 | 여러 곡의 `stage1_v{version}` 만, **1,000구간 단위** (요청당 약 0.5MB) |

- 종류별로 나누면 `mert` 는 들어갔는데 `stage1` 이 아직 없는 **반쯤 들어간 곡**이 생긴다. 한 요청에 모두
  담으면 곡은 전부 있거나 전부 없다.
- 둘 다 ROLE_SYSTEM 전용이고 **곡 단위(또는 구간 단위) 교체**라 여러 번 보내도 결과가 같다(멱등).

**본문 형식** — 바이너리. float32 리틀엔디언 배열을 base64 로 싣거나 `application/octet-stream`
(메타는 헤더·쿼리). JSON 숫자 배열은 2.5배 크고 파싱이 느리다. Spring 은 받자마자 검증한다:

- 구간 수·순서가 MySQL 의 그 곡 구간(`external_id`)과 같은가
- 차원이 `manifest` 의 임베딩·특징·모델 버전과 맞는가
- `analysis_version` 이 MySQL 의 현재 값과 같은가 (다르면 거절 — 옛 분석의 벡터가 새 구간에 붙지 않게)
- NaN·무한대가 없는가

> **457 의 결정(미결 5)**: **JSON 안에 벡터마다 float32 리틀엔디언 바이트의 base64.** 메타(구간 순서 = `sections[]` 의
> 순서, `analysis_version`·`emb_version`·`feat_version`·`source`, 모델 버전 = `stage1` 사전의 키)는 같은 JSON 에 싣는다.
> `application/octet-stream` 을 버린 이유 — 종류가 다섯이고 `clap` 은 구간마다 있거나 없어, 바이트 한 덩어리로는
> 경계를 헤더에 적는 틀을 새로 만들어야 한다. 실측 구간당 약 10.5KB(4구간 곡 42KB) — 곡 9구간이면 약 95KB 다.

**실패와 재시도 — 원본은 배치가 가진다.** Spring 은 벡터를 따로 보관하지 않는다.

- Spring 은 **Qdrant 에 쓴 뒤에 응답한다.** 실패하면 오류를 돌려주고, 배치가 같은 요청을 다시 보낸다.
- 새 곡 등록에서 MySQL·ClickHouse 는 커밋됐는데 벡터가 아직(또는 실패로) 없는 곡은 미반영 기록에
  `VECTOR` 로 남는다(§8.2). 대조 작업이 이런 곡 목록을 내보내면 배치가 다시 보낸다.
- 그 사이 AI 는 **벡터가 없는 구간을 후보에서 뺀다.** 틀린 곡이 나가는 게 아니라 그 곡이 잠시 추천되지
  않을 뿐이다.

> **457 에서 (구현 위치)**
>
> | 무엇 | 어디 |
> |---|---|
> | 곡 전체 `PUT /api/v1/music/{id}/vectors` · 재계산 `PUT /api/v1/system/vectors/stage1/{v}` · 관문 `GET …/stage1/{v}/status` · 미반영 목록 `GET /api/v1/system/vectors/pending` | `api/…/vector/` (`MusicVectorController`·`SystemVectorController`·`VectorServiceImpl`), 명세 `api/docs/Vector/` |
> | 컬렉션 정의 | `api/src/main/resources/qdrant/qdrant_collection.json` — `ai_server/app/qdrant_collection.json` 의 **바이트 사본**(api 빌드 문맥이 `api/` 뿐이다). 다르면 `ai_server/tests/test_qdrant_schema_copy.py` 가 멈춘다 |
> | Qdrant REST (SDK 없음) · 전용 스레드 풀(동시 2 + 대기 8, 넘치면 429) | `QdrantRestClient`·`VectorWriteExecutor`, 설정 `qdrant.*`(`MUPID_QDRANT_URL`·`MUPID_QDRANT_API_KEY`) |
> | 미반영 `VECTOR` · 매일 04:50 Qdrant 대조 | `VectorSync`·`VectorReconciler` (§8.2) |
> | 배치 쪽 보내기 | `music_preprocessing` `vectors.py`, 명령 `vectors-freeze-mu`·`vectors-send [--pending]`·`vectors-stage1`, `api_client.put_vectors` 등 |
>
> - **payload 의 구간 지문·`track_id` 는 MySQL 에서 싣는다.** 배치가 보내는 것은 벡터·`r`·`source`·버전뿐이다.
> - **곡 단위 교체는 upsert 뒤 삭제다** — 그 곡(`track_id`)의 포인트 중 이번에 없는 id 를 지운다. 순서를 뒤집으면 그 사이
>   곡이 통째로 빈다.
> - `stage1` 은 모델 버전 → 벡터 사전이다(`{"v1": …}`). 포인트를 통째로 갈아 끼우므로 재계산 중 새 곡은 두 버전을 함께 싣는다.
>   모델 벡터 이름이 컬렉션에 없으면 Spring 이 덧붙인다(456 도구와 같은 본문). 컬렉션 자체는 만들지 않는다 — 운영 절차.
> - `mert` 는 단위 벡터여야 받는다(‖u‖ = 1 ± 1e-3) — 원본 `Z` 를 잘못 보내면 거절된다. `Cosine` 벡터는 Qdrant 가 저장 때
>   정규화하므로 `stage1` 의 원본 노름은 Qdrant 에 남지 않는다(458 이 Qdrant 에서 `stage1` 을 읽을 때 볼 것).
> - 삭제된 곡은 벡터를 받지 않는다(410). 대조가 MySQL 에 살아 있지 않은 곡의 포인트를 지운다(§7.4) — MySQL 쪽이 비었거나
>   Qdrant 곡의 절반 넘게를 지우게 되면 지우지 않고 알린다.
> - 새 곡 야간 배치(`analysis` ⑥ 뒤)에 붙이는 것은 후속이다 — 지금은 명령으로 보낸다. 붙이기 전까지 새 곡은 미반영 `VECTOR` 로
>   남고, `vectors-send --pending` 이 채운다.

---

## 7. 데이터 흐름

### 7.1 새 곡·재분석 — 배치 → Spring API

1. **분석 배치 (JupyterHub GPU)**: MERT → `Z` → 동결 μ 로 `u`·`r` → 동결 통계·PCA 로 `X` → 구간
   인코더로 `stage1_v{N}`. 곡 단위로 묶는다.
2. **곡·구간·분석 값 등록**: 지금처럼 `PUT /music/{id}/section`·`/analysis`. Spring 이 MySQL 에 쓰고
   커밋 → 같은 `analysis_version` 으로 ClickHouse 에 쓴다. 실패하면 미반영 기록에 남아 재시도된다 (§8).
3. **벡터 등록**: `PUT /music/{id}/vectors` (§6.6, 곡당 약 80KB 바이너리). Spring 이 MySQL 의 구간·
   `analysis_version` 과 대조해 검증하고 **바로 Qdrant 에 upsert** 한 뒤 응답한다. 실패하면 배치가 다시 보낸다.

```
배치 ──PUT /section·/analysis──▶ Spring ──▶ MySQL (커밋) ──▶ ClickHouse   (미반영 기록·재시도)
배치 ──PUT /vectors───────────▶ Spring ──검증──▶ Qdrant ──▶ 응답           (실패 시 배치가 재전송)
```

**판을 잘라 올리는 과정이 없어진다.** 구간의 정체성(`external_id` + `analysis_version`)을 정하는
것이 Spring 이므로, 그 순간 세 저장소에 같은 버전이 들어간다 — 재분석 뒤 구간 불일치(§10.1-1)가
구조적으로 줄어든다.

적재 처리량(곡당 GPU 시간)은 요청 경로 밖이지만 10만 곡 규모에서는 목표를 둔다 (§10.3-6).

### 7.2 운영 데이터 변경 (관리자·API)

장르·무드 수정, 곡 삭제·복구, 스트림 상태 변경 — 모두 Spring 을 지난다. Spring 이 MySQL 에 쓰고
같은 트랜잭션 안에서 미반영 기록을 남긴 뒤, 커밋 뒤 ClickHouse 에 쓴다 (§8.2).

### 7.3 모델 교체

§6.3 "반영 흐름" 그대로다. 요약하면:

1. **학습 (JupyterHub)**: 묶음을 `~/mupid_models/stage1/v{N+1}/` 에 둔다. 아무 데도 올리지 않는다.
2. **재계산 (music_preprocessing)**: 전 구간 `stage1_v{N+1}` 을 `PUT /system/vectors/stage1/{N+1}` 로
   1,000구간씩(§6.6). Qdrant 에 새 이름으로 덧붙고, 서빙 중인 `stage1_v{N}` 은 그대로다. 구간 정체성이
   바뀌지 않으므로 MySQL·ClickHouse 에는 쓰지 않는다.
3. **MR**: `models.lock` 을 v{N+1} 로. MR 파이프라인이 JupyterHub 파일의 체크섬을 대조하고 지표를 남긴다.
4. **병합**: CI 가 JupyterHub 에서 받아 체크섬·재계산 완료를 확인하고 Object Storage 에 올린 뒤, 새 가중치를
   구운 이미지로 배포한다.
5. **되돌리기**: `models.lock` 되돌리기 또는 이미지 롤백. 검증이 끝나면 `stage1_v{N}` 을 지운다(§10.2-3).

**모든 쓰기가 Spring 을 지난다** — 모델 재계산도 예외가 없고, 클러스터 안에 ROLE_SYSTEM 토큰이 없다.

### 7.4 곡 삭제·재생 불가

MySQL 이 정본. Spring 이 ClickHouse `track_features.is_deleted`·`playable` 을 같이 고친다. 그 사이의
틈은 Spring 최종 필터가 막는다. Qdrant 벡터는 대조 작업이 MySQL 목록과 비교해 물리 삭제한다.

### 7.5 구간 라벨만 변경

`X` 가 라벨 원핫에 의존하므로 `feat_v1`·`stage1` 재계산 대상이다. Spring 이 라벨이 바뀐 구간을
재계산 대기 목록에 남기고, 분석 배치가 가져가 벡터를 다시 보낸다(§6.6). 의존 자체를 끊을지는 §11-3.

---

## 8. MySQL–ClickHouse 정합성

**불변식: 커밋된 MySQL 변경은 유한한 시간 안에 ClickHouse 에 같은 내용으로 반영된다. 그 시간
동안의 틈은 Spring 최종 필터가 정확성을 지킨다.**

### 8.1 지금의 약점

- Spring 이 커밋 뒤 ClickHouse 에 쓰다 실패하면 **로그만 남기고 끝난다**
  (`MusicSectionServiceImpl.writeToAnalytics`). 그 곡은 재생 신호가 조용히 버려지고, 사람이
  `ai_server/tools/push_sections.py` 로 메워야 한다.
- 그 `push_sections.py` 는 ClickHouse 에 **HTTP 로 직접 쓴다** — 쓰기 관문 원칙을 우회한다.
- ClickHouse 는 곡 삭제를 모른다 (§5.3).

### 8.2 장치

| # | 장치 | 하는 일 |
|---|---|---|
| 1 | **미반영 기록** | MySQL 트랜잭션 안에서 `analytics_sync_pending(music_id, kind, analysis_version, created_at)` 에 행을 남긴다. 커밋과 한 몸이라 빠지지 않는다. `kind` 는 `CLICKHOUSE`(Spring 이 재시도)·`VECTOR`(벡터가 아직 없음 — 배치가 보내면 지운다). → `VECTOR` 는 457: 구간 교체·관리자 구간 CRUD 가 올리고, 벡터 등록이 성공하면 검증 때 읽은 revision 으로 지운다. Spring 재시도는 이 종류를 돌지 않는다 |
| 2 | **커밋 뒤 즉시 쓰기** | 지금처럼 `afterCommit` 에서 ClickHouse 에 쓰고, 성공하면 미반영 행을 지운다. Qdrant 는 벡터 등록 API 가 요청 안에서 쓴다 (§6.6) |
| 3 | **재시도 작업** | Spring 스케줄 작업이 1분마다 남은 행을 다시 쓴다. 쓰기는 **곡 단위 교체**라 몇 번을 써도 결과가 같다(멱등). api 가 여러 벌이면 리더 잠금(예: ShedLock) |
| 4 | **대조 작업** | Spring 이 매일 곡별 구간 수·값 체크섬을 MySQL 과 **두 레플리카** 에서 비교한다(2026-09-25 에 손으로 한 것을 자동화). 어긋난 곡은 미반영 기록에 넣는다. Qdrant 는 `analysis_version`·존재 여부를 비교한다 → 457 `VectorReconciler`(04:50): 곡마다 `external_id` 집합과 구간 지문(`analysis_version`·`seg_start_ms`·`seg_end_ms`)을 payload 스크롤로 비교, 살아 있지 않은 곡의 포인트는 지운다 |
| 5 | **같은 기준** | `analysis_version` 을 MySQL 에도 둔다. Spring 이 같은 값을 세 저장소에 쓴다 |
| 6 | **읽기 규칙** | ClickHouse 는 `FINAL`(또는 곡 단위 최신 행)로 읽는다 |
| 7 | **우회 제거** | `push_sections.py` 의 ClickHouse 직접 쓰기를 걷어낸다. 복구는 대조 작업 → 재시도가 맡는다 |

### 8.3 지표·알림

| 지표 | 기준 (시작값) |
|---|---|
| 미반영 건수 | 0 이 정상 |
| 가장 오래된 미반영의 나이 | 10분 넘으면 알림 |
| 대조 불일치 수 (MySQL ↔ 레플리카별) | 1건 이상이면 알림 |
| Qdrant `analysis_version` 불일치 수 | 1건 이상이면 알림 |

AI 는 이 지표를 직접 만들지 않는다(MySQL 을 읽지 않으므로). Spring 이 내보내고, AI 는 요청 경로에서
만난 불일치(아래 §9.3-3)를 따로 센다.

---

## 9. 요청 경로 — 계약 v3

### 9.1 남는 페이로드

| 경로 | 필드 | 크기 |
|---|---|---|
| `daily` | `recommendation_id`, `user_id`, `limit`, `exclude_tracks`, `onboarding` | 177B |
| `next` | + `current_track`, `current_section` (제외 10곡) | 327B |
| `next` (제외 200곡) | | 2.2KB |
| `search` | + `query_plan` | 337B |
| `similar` | `external_id`, `limit`, `exclude_tracks` | 67B |

`catalog` 는 없다. 크기를 좌우하는 것은 `exclude_tracks`(곡당 약 10B)뿐이라 **상한을 둔다**
(`NextTrackRequest.excludeMusicIds` 에 지금 제한이 없다 → `@Size(max = 500)`, 약 5KB).

### 9.2 응답

AI 는 **`limit + 여유분`**(예: `limit` 의 절반, 최소 2)을 순위대로 돌려준다. Spring 이 최종 필터로
빼고 앞에서부터 `limit` 개를 자른다. 오늘의 모먼트(4개 고정)가 삭제된 곡 때문에 3개로 나가지 않게
한다. 여유분으로도 모자라면 `shortfall` 로 드러난다.

### 9.3 AI 안에서 (node-b 안)

1. **조건 풀기** — ClickHouse 에서 조건에 맞는 구간 수를 센다.
   - **적으면**(기준 1만 이하, §11-2) 그 ID 목록을 Qdrant `HasId` 사전 필터로. 재현율이 줄지 않는다.
   - **많으면** Qdrant 에서 필터 없이 top-K × 여유 배수를 뽑아 ClickHouse 값으로 거른다. 모자라면 한 번 더.
   - 같은 조건 조합의 결과는 짧게(수십 초) 캐시한다.
2. **후보 약 300개** → ClickHouse 에서 특징 조회 + Qdrant 에서 `feat`·`stage1` fetch.
3. **구간 지문 대조** — ClickHouse 의 `analysis_version` 과 Qdrant 의 `analysis_version` 이 다른 후보는
   버리고 센다. `is_deleted`·`playable` 이 참인 후보도 여기서 뺀다.
4. DNA·점수·보정기·무드/CLAP 가점·흐름 감점 → 순위.

**풀 전체를 읽는 경로는 없다.** 온보딩 씨앗은 모델 묶음의 사전 계산본을 쓴다.

### 9.4 Spring 에 남는 일

- **쓰기 관문**: MySQL·ClickHouse·Qdrant 쓰기, 미반영 기록·재시도·대조 (§8).
- 조건 어휘를 대화 모델에 준다 (`RecommenderFacets`, MySQL 기반 — 그대로).
- 응답 번역(`external_id` → 곡·구간)과 **최종 필터(삭제·재생 가능, MySQL 기준)**, `limit` 으로 자르기.
- `QueryPlanFilter`(09-23 에 Java 로 옮긴 조건 규칙)는 **AI 로 되돌아간다.** 규칙을 두 곳에 두지
  않도록 전환 때 Spring 쪽을 걷어낸다.

---

## 10. 위험과 보완 — 세 축

### 10.1 축 ① 저장소 정합성·가용성

| # | 위험 | 증상 | 보완 |
|---|---|---|---|
| 1 | **재분석 뒤 저장소들이 서로 다른 구간을 가리킨다** | 같은 `T000001#3` 인데 Qdrant 는 옛 경계의 벡터, ClickHouse 는 새 경계의 라벨·시각. 오류 없이 엉뚱한 구간이 추천된다 | Spring 이 한 번에 같은 `analysis_version` 을 쓴다(§7.1). 그래도 틈은 요청마다 지문 대조로 버린다(§9.3-3). 대조 작업이 Qdrant 불일치도 센다(§8.2-4) |
| 2 | **ClickHouse 쓰기 실패가 조용히 묻힌다** (지금도 있음) | 로그만 남고 그 곡의 재생 신호·특징이 빠진다 | 미반영 기록·재시도·대조 (§8.2) |
| 3 | **쓰기 관문 우회** (지금도 있음) | `push_sections.py` 가 ClickHouse 에 직접 쓴다 | 걷어낸다 (§8.2-7) |
| 4 | **ClickHouse 가 삭제를 모른다** (지금도 있음) | 삭제된 곡의 구간이 살아 있는 것처럼 보인다 | `is_deleted`·`playable` 을 싣고, 곡 삭제·복구·스트림 상태도 Spring 이 쓴다 (§6.2) |
| 5 | **ClickHouse 의 중요도가 올라간다** | 지금은 "죽어도 개인화만 빠지는" 저장소인데, 추천 입력 저장소가 된다 | 레플리카 2벌(node-a·node-b)이라 한쪽이 죽어도 읽는다. 둘 다 죽으면 벡터만으로 계속 추천(조건 검색 끔), 정확성은 Spring 최종 필터 |
| 6 | **서빙과 분석이 자원을 다툰다** | 대시보드·리포트 조회가 추천 조회를 늦춘다 | 서빙 전용 사용자·프로파일, 가까운 레플리카 (§6.2) |
| 7 | **ReplacingMergeTree 의 병합 전 중복** | `FINAL` 없이 읽으면 옛 행과 새 행이 함께 보인다 | 읽기 규칙 (§8.2-6) |
| 8 | **node-b 단일 장애점** | ai·Qdrant·Kafka·ClickHouse 한 벌이 node-b. node-b 가 죽으면 추천 전면 중단 | 데모는 받아들이고 명시한다. 실 서비스는 Qdrant 복제 2·노드 분산 |
| 9 | **인증·격리 부재** | 아무 파드나 벡터를 덮어써 추천 오염 | §6.4 |
| 10 | **최종 필터가 개수를 줄인다** | 삭제된 곡을 빼면 `limit` 보다 적게 나간다 | `limit + 여유분` (§9.2) |
| 11 | **회전 토큰 공유** | 같은 ROLE_SYSTEM 토큰을 두 곳이 쓰면 한쪽의 회전이 다른 쪽을 끊는다 (9/23 에 배치 토큰을 CI 변수로 복사해 쓴 것이 이 모양이었다) | 쓰는 곳마다 계정 하나 (§6.4). ai_server 는 갖지 않는다 |
| 12 | **Object Storage 우회** | Filer 를 직접 치는 경로가 생기면 인증·기록 없이 바이트가 오간다 | 원칙 5, Filer `internal`, NetworkPolicy |
| 13 | **벡터의 원본이 클러스터 밖(JupyterHub)에만 있다** | node-b 디스크(Qdrant·로컬 스냅샷)를 잃으면 JupyterHub 가 전부 다시 보내야 한다. JupyterHub 의 색인까지 잃으면 분석부터 다시 한다 | 재적재 시간을 재 두고 허용 범위를 정한다(§11 미결 12). JupyterHub 전체 색인의 보관 위치를 운영 문서에 적는다 |
| 14 | **곡은 있는데 벡터가 없다** | 새 곡 등록 뒤 벡터 요청이 실패하면 그 곡이 추천되지 않는다 | 미반영 기록 `VECTOR` + 대조 작업이 목록을 내고 배치가 재전송 (§6.6) |
| 15 | **병합 뒤 JupyterHub 의 파일이 바뀐다** | 공용 서버라 리뷰한 것과 다른 파일이 올라갈 수 있다 | `models.lock` 의 파일별 sha256 과 대조해 다르면 CI 가 멈춘다. Object Storage 는 같은 버전의 다른 내용을 거절한다 |
| 16 | **모델 배포가 JupyterHub 에 기댄다** | JupyterHub 가 죽으면 새 모델을 배포할 수 없다 | 모델이 바뀌는 커밋의 `model-publish` 만 기댄다. 평소 AI 배포는 Object Storage 에서 받는다 |

**장애 시 동작**

| 무엇이 죽나 | 추천 | 드러나는 곳 |
|---|---|---|
| ClickHouse 레플리카 하나 | 계속된다 (다른 레플리카를 읽는다) | ClickHouse 상태 |
| ClickHouse 둘 다 | 계속된다 — 조건 검색·개인화 끔, 벡터만 | `/health`, 진단 |
| Spring 의 ClickHouse 쓰기 | 계속된다 (값이 늦어진다, 미반영이 쌓인다) | 미반영 지표 알림 (§8.3) |
| Qdrant | **멈춘다** — 준비 판정 실패 | `/internal/ready` |
| SeaweedFS | 추천은 영향 없음 (가중치는 이미지에 있다). 새 곡 적재·모델 올리기·CI 빌드가 멈춘다 | 적재·CI 실패 |
| node-b | **전면 중단** (§10.1-8) | 전부 |
| Kafka | 추천은 영향 없음 (행동 이벤트 적재가 밀린다 — 지금과 같음) | 이벤트 파이프라인 |

### 10.2 축 ② 모델–데이터 버전 정합성

| # | 위험 | 증상 | 보완 |
|---|---|---|---|
| 1 | **가중치와 파생 벡터의 짝이 어긋난다** | 새 사용자 인코더 + 옛 `stage1` → 조용히 순위가 틀어진다 | 벡터 이름에 모델 버전(`stage1_v{N}`). 서버는 자기 버전의 벡터가 컬렉션에 없으면 준비 판정 거부. `manifest.json` 의 임베딩·특징 버전을 기동 때 컬렉션과 대조 |
| 2 | **동결 값이 낡는다** | μ(1,060곡)·PCA(FMA)·특징 통계를 동결했다. 곡이 늘고 분포가 바뀌면 유사도가 서서히 나빠진다 | 새로 들어온 벡터의 평균과 μ 의 거리, 새 특징의 z 분포를 지표로. 기준을 넘으면 **계획된 재기준화**(새 컬렉션에 전체 재적재 후 교체) |
| 3 | **옛 모델 벡터가 쌓인다** | `stage1_v{N}` 이 누적되어 메모리 증가 | 새 버전 검증 뒤 이전 버전 삭제를 자동화 (한 번에 둘까지만) |
| 4 | **라벨이 특징에 박혀 있다** | MySQL 라벨이 바뀌면 `X`·`stage1` 이 옛 라벨 기준 | 재계산 대기 목록 (§7.5). 의존 제거는 §11-3 |
| 5 | **이미지의 모델과 Qdrant 가 어긋난다** | `models.lock` 을 v{N+1} 로 바꿨는데 재계산이 덜 끝났다 | **CI 관문**(④-c)이 배포 전에 막는다. 그래도 빠지면 기동 때 `stage1_v{N+1}` 존재·행 수 확인 → 준비 판정 거부 → 옛 판이 계속 돈다 |

### 10.3 축 ③ 계산 경로 확장성 — 데이터가 늘 때 막히는 순서

| 순서 | 병목 | 언제 | 왜 | 대책 |
|---|---|---|---|---|
| **1** | **ai_server 코드의 O(풀) 연산** | 1만 곡부터 | 지금 코드는 "풀 전체가 메모리에 있다" 전제다. 조건 어휘(`catalog_facets`)가 전체 곡을, 온보딩 게이트(`track_ok`)·무드 비율·흐름 감점이 풀 DataFrame 을 훑는다. 후보를 좁혀도 **남은 O(N) 단계 하나가 전체를 지배한다** | 1단계에서 요청 경로의 O(N) 연산을 **전수 점검·제거**. 이 설계에서 가장 큰 작업이다. 점검표: [`ai_server/docs/REQUEST-PATH-COMPLEXITY.md`](../ai_server/docs/REQUEST-PATH-COMPLEXITY.md) (S15P21A204-452) |
| **2** | **ClickHouse 서빙 조회 지연·동시성** | 1만~10만 곡 | 분석용 DB 라 한 쿼리가 수 ms~수십 ms, 동시 쿼리 수에 한도가 있다. 지금 정렬 키로는 `section_uid IN (…)` 이 전체를 훑는다 | 서빙용 정렬 키(`section_uid`)·딕셔너리, 서빙 프로파일. 측정 뒤 기준을 넘으면 앞단 캐시 검토 |
| **3** | **중간 선택도 조건** | 10만 곡 | 예: 일본어 + 여성 보컬 + 코러스 ≈ 2% ≈ 1.8만 구간. 허용 목록 상한을 넘어 사후 필터로 가면 top-K 의 50배를 뽑아야 하고, 허용 목록으로 보내면 요청마다 UUID 1.8만 개(약 650KB)를 Qdrant 에 넘긴다 | **"Qdrant 에 MySQL 값을 넣지 않는다"의 대가다.** 전환 조건(§11 결정 기록)을 넘으면 자주 쓰는 필터 몇 개만 Qdrant payload 로 싣는 것을 재검토한다 (그때도 Spring 이 쓴다) |
| **4** | **대조 작업** | 10만 곡 | 매일 MySQL·레플리카 둘·Qdrant 전체 비교 | 곡 범위별 분할, 체크섬 단위 비교 |
| **5** | **node-b CPU 3코어** | 동시 사용자가 늘 때 | ai 의 Python 점수 계산·Qdrant 검색·ClickHouse-0·Kafka 가 나눠 쓴다 | CPU 요청값·한도, 규모가 오면 노드 추가 |
| **6** | **적재 처리량** (요청 경로 밖) | 10만 곡 | 곡마다 MERT·구간 분석(allin1)을 GPU 로 돈다 | 곡 단위라 나눠 돌리기 쉽다. 처리량 목표를 둔다 |
| **7** | **모델 재계산 반영** (요청 경로 밖) | 10만 곡 | 모델을 바꿀 때 전 구간 `stage1`(설계 기준 약 460MB)이 Spring API 를 지난다 — 1,000구간씩 약 900번 | 요청당 크기 상한(0.5MB)을 두고, 배치 쪽에서 동시 요청 수를 제한한다. 사용자 요청과 다른 스레드 풀·속도 제한 |

Qdrant 의 메모리·검색 속도는 설계 규모(90만 구간)에서 병목이 아니다.

---

## 11. 결정 기록과 미결

### 결정 기록

| 결정 | 이유 | 버린 대안 | 다시 볼 조건 |
|---|---|---|---|
| **쓰기 관문 = Spring API** | 정합성을 한 곳에서 책임진다. 배치·관리자 모두 같은 길 | 배치가 저장소에 직접 쓰기, ClickHouse 가 MySQL 을 직접 끌어오기(refreshable MV·MySQL 딕셔너리) | — |
| 피처 저장소 = **ClickHouse** | `track_sections` 사본이 이미 있고 없앨 수 없다. 레플리카 2벌. AI 가 이미 읽는다. 새 저장소가 없다 | Redis 를 node-b 에 따로 두기(사본이 둘이 된다, Spring 이 새 저장소를 알아야 한다) | 서빙 지연이 기준을 넘을 때 앞단 캐시 |
| 정합성 = **미반영 기록 + 재시도 + 대조** | 커밋과 한 몸이라 빠지지 않고, 어긋나도 찾아 고친다 | 커밋 뒤 한 번 쓰고 로그(지금), Kafka outbox(데모에 과함) | 변경량이 늘거나 소비자가 늘 때 Kafka |
| 벡터 적재도 **Spring 경유** (새 곡·재분석·모델 재계산) | 구간의 정체성을 정하는 순간 세 저장소에 같은 `analysis_version`. 클러스터 안에 쓰기 자격이 필요 없다 | 클러스터 안 적재·재계산 Job 이 Qdrant 에 직접 | — |
| 벡터는 **Spring API 본문(바이너리)으로 받아 바로 Qdrant 에** | 곡당 약 80KB 라 평범한 API 크기. 3단계 업로드·Filer 경유·고아 파일 정리가 없다 | 벡터 파일을 Nginx → Filer 로 올리고 Spring 이 읽기 | 곡당 벡터가 수 MB 로 커질 때(차원·구간 수 급증) |
| 벡터 API 는 **용도별 둘** (곡 전체 / 모델 재계산) | 반쯤 들어간 곡이 생기지 않는다 | 벡터 종류별 엔드포인트 | — |
| 모델 가중치는 **Nginx → Filer** (Spring 은 인증·경로만) | 버전별로 보관하고 CI 가 받아 가는 파일이다. 음악 청크와 같은 원칙 | Spring API 본문 | — |
| 서빙 가중치는 **CI 가 이미지에 굽는다** (`models.lock`) | ai_server 가 ROLE_SYSTEM 없이 뜬다. 버전이 git 에 남고 롤백이 이미지 하나 | 기동 때 AI 파드가 받기(토큰 만료·회전 충돌) | — |
| 모델 가중치 업로드는 **CI 가 병합 뒤 JupyterHub 에서 받아서** | 운영 반영의 방아쇠가 git 병합 하나다. 학습 작업에 자격이 필요 없고, Object Storage 에는 채택된 버전만 쌓인다. 기존 CI 의 JupyterHub 자격(`MUPID_DATA_ENV`)을 그대로 쓴다 | 학습 작업이 직접 올리기(학습용 ROLE_SYSTEM 계정이 하나 더 필요, 실험 버전까지 쌓인다) | — |
| ROLE_SYSTEM **계정은 쓰는 곳마다** | 폐기가 계정 단위라 한 곳이 새면 그곳만 끊는다 (처음 이유였던 회전 충돌은 아래 결정으로 사라졌다) | 계정 하나 공유 | — |
| ROLE_SYSTEM 토큰은 **회전하지 않고 180일까지**, 관리자가 **계정 단위 일괄 폐기** (2026-09-25, 미결 10) | CI 가 토큰을 보호 변수에 둔 채 다시 쓰지 않는다 — 변수 쓰기 권한(프로젝트 관리 토큰)을 CI 에 주지 않고, 쓰기 실패로 다음 빌드가 끊기는 일이 없다. 새면 폐기로 끊는다 | CI 가 회전 결과를 CI 변수에 다시 쓰기, Spring 에 별도 서비스 자격(API 키) | 토큰이 샌 사고가 나거나, 폐기 경계를 Redis 가 아니라 DB 에 둬야 할 때 |
| Qdrant 스냅샷은 **node-b 로컬(PV)** | 다시 만들 수 있는 데이터, 복구 가속용 | Object Storage 로 백업 | 재적재가 너무 오래 걸리게 될 때 |
| 벡터 DB = **Qdrant** (node-b) | 이그레스 요금 없음, named vector·양자화·필터, 스냅샷 | Pinecone 유료, 로컬 파일 | — |
| **Pinecone 제거** | 운영에서 한도 소진, 두 백엔드를 유지할 이유가 없다 | 옵션으로 남기기 | — |
| **Triton 쓰지 않음** | 서빙 모델이 1MB 미만 numpy, 로직 대부분이 데이터 처리, 클러스터에 GPU 없음 | ai_server 전체 전환 | 온라인 인코더가 필요해질 때 **별도** 추론 서비스로 |
| Qdrant 에 **MySQL 값을 넣지 않음** | 사본은 ClickHouse 하나 | 필터 필드를 Qdrant payload 에 | **사후 필터 여유 배수가 20배를 넘는 조건이 요청의 5% 이상**이면 재검토 (§10.3-3) |
| ClickHouse 장애 시 **벡터만으로 계속** | 피처는 품질 요소, 정확성은 Spring 최종 필터 | 준비 판정 실패로 멈추기 | — |
| μ **동결** (1,060곡) | 곡 추가마다 전체 재적재를 없앤다 | 풀 평균 재계산 | 드리프트 지표가 기준을 넘을 때 |
| 모델 묶음 분리 (Object Storage + `model_versions`) | 가중치만 교체·되돌리기, 버전 추적 | 색인 판에 동봉 | — |

### 미결

1. **쓰기 입구 전수 목록**: Spring 에서 곡·구간·분석·장르·무드·스트림·삭제를 바꾸는 API·배치를 모두
   찾아 미반영 기록을 남기게 한다. 하나라도 빠지면 불변식이 깨진다.
   → **곡 특징 범위도 채웠다 (S15P21A204-455):** 분석 값·장르/무드·곡 삭제·스트림 생성/상태/삭제, 관리자 화면의
   곡(복구 포함)·분석·장르·무드·스트림·**코드 이름**(그 코드를 단 곡 전부). 같은 문서의 표.
   → **`track_sections` 범위는 채웠다 (S15P21A204-451):**
   [`api/knowledge/architecture/analytics-sync.md`](../api/knowledge/architecture/analytics-sync.md) 의
   "쓰기 입구 전수 목록". 구간을 바꾸는 길은 요구 1(`PUT …/section`)과 관리자 자원 CRUD 둘이고 둘 다
   같은 트랜잭션에 미반영 기록을 남긴다. 곡 삭제·분석 값·장르·무드(`track_features`)는 455, 벡터는 457 에서
   같은 표에 행을 더한다. → **벡터(457)**: 구간 교체(요구 1)·관리자 구간 CRUD 가 `VECTOR` 를 올린다(같은 문서의 표).
2. **선택도 기준**: 사전 필터 ID 목록 상한 (1만으로 시작).
3. **`X` 의 라벨 의존**을 끊을지 — 재학습 때 라벨을 `X` 에서 빼고 ClickHouse 값으로 점수에 더하는 안. AI 담당.
4. ~~**ClickHouse 서빙 지연 기준**: 1만 곡에서 특징 조회·조건 풀기 p95 목표.~~
   → **제안(2026-09-25, S15P21A204-455)**: 1만 곡에서 **서버 실행 p95 — 특징 조회(후보 300) 50ms, 조건 풀기
   100ms.** 둘을 합쳐도 추천 p95 목표(1초, §13 5단계)의 15% 이내라 앞단 캐시는 아직 두지 않는다. 측정
   (`ai_server/tools/bench_feature_store.py`, 로컬 dev 2노드 `mupid_all`, 합성 10,000곡 · 90,140구간, 각 300회):

   | 조회 | 서버 p50 | 서버 p95 | 왕복 p50 | 왕복 p95 |
   |---|---|---|---|---|
   | 특징 조회 — 후보 300 (삭제·재생 불가·없는 ID 섞음) | 12ms | 16ms | 27ms | 45ms |
   | 조건 풀기 — 조합 6가지 돌아가며 (맞는 구간 265 ~ 85,011) | 29ms | 56ms | 42ms | 75ms |

   서버 = ClickHouse `X-ClickHouse-Summary.elapsed_ns`, 왕복 = Windows 호스트 → Docker Desktop 포트(JSON 해석
   포함; 운영의 같은 노드 접속은 더 짧다). 사전 12MB·다시 읽기 0.08초. 병합이 덜 된 직후(파트 여러 개)에는 특징
   조회 서버 p95 가 25ms 까지 올랐다. 조건 풀기의 느린 쪽은 조건 없는 조합(8.5만 ID 를 정렬)이다 — 사전 필터로
   쓰는 것은 1만 이하일 때뿐이라(미결 2) 실제로는 앞쪽이다. 10만 곡은 dev 노드(4GiB 한도, 다른 표와 공유)가
   적재·병합 중 메모리 한도에 걸려 재지 못했다 — 5단계 부하 측정에서 운영과 같은 노드로 잰다.
5. ~~**벡터 본문 형식**: base64(float32 LE) JSON 과 `application/octet-stream` 중 무엇으로 할지, 메타(`external_id` 순서·
   `analysis_version`·차원·모델 버전)를 어디에 실을지.~~
   → **결정(2026-09-25, S15P21A204-457)**: JSON + 벡터마다 base64(float32 LE). 메타는 같은 JSON — 구간 순서는 `sections[]`
   순서, 모델 버전은 `stage1` 사전의 키, 차원은 바이트 길이 ÷ 4 로 정의와 대조한다. 이유는 §6.6.
6. **온보딩 매핑**: 온보딩 무드(1세대 코드)·장르 대분류가 곡 어휘로 옮겨지지 않아 대부분 사용자가
   콜드스타트다 (기존 문제). 코드표 쪽 대응표가 필요하다.
7. **CLAP 적용 범위 23%**: 검색어 재정렬이 대부분 곡에서 안 된다. 분석 배치에서 채울지.
8. **결합기 재현성**: `logistic_flow_stage1.json` 을 만드는 코드가 저장소에 없다.
9. **적재 처리량 목표**: 10만 곡 기준 곡당 GPU 시간과 하루 처리량.
10. ~~**CI 계정의 토큰 회전**: CI 가 빌드 때 리프레시 토큰을 쓰면 회전된 새 토큰을 CI 변수에 다시 써야 한다
    (쓰지 않으면 다음 빌드가 실패한다). CI 가 변수를 갱신할 권한을 가질지, 회전하지 않는 서비스 자격을
    Spring 에 따로 둘지 정한다.~~
    → **결정(2026-09-25, S15P21A204-454)**: ROLE_SYSTEM 만 리프레시 토큰 만료를 180일까지 지정하고 **회전하지
    않는다.** 관리자가 계정 단위로 일괄 폐기한다. 결정 기록 표와 §6.4 를 본다. 대가: 만료가 밀리지 않아
    사람이 만료 전에 갈아 끼운다(`User / 시스템 계정 토큰 발급`).
11. **ERD 변경**: `object_storage_files.category` 에 `MODEL`, 새 표 `model_versions`,
    `music_section_analysis.analysis_version`, `analytics_sync_pending`(종류에 `VECTOR` 포함).
12. **Qdrant 재적재 시간**: node-b 디스크를 잃었을 때 JupyterHub 가 전 곡의 벡터를 다시 보내는 데 걸리는
    시간(데모 1,060곡·설계 10만 곡)과 허용 범위.

---

## 12. 지금 운영 상태 — 전환 전 위험

- node-b 판 `ea692ef6…-local-emb` 은 **수신기를 거치지 않고 수동으로** 만들었다(행을 맞춘
  `moments_emb.npy` 추가, `current` 교체). `MUPID_PINECONE_ENABLED=false`.
- ~~`receive-index.py` 는 `moments_emb.npy` 를 거절하고 `slice_index` 는 배송에서 뺀다.~~
  **고쳤다(S15P21A204-450, 이 MR).** `slice_index` 가 싣고 수신기가 REQUIRED 로 받으며 행 수를 센다.
  배포 뒤 Ansible 로 수신기를 다시 설치해야(`infra_k8s/ansible/receivers.yml`) 판 업로드가 정식 경로로 된다.
- 로컬 중심화 평균이 요청 후보 기준으로 재지던 버그는 !285 로 고쳤다 (배포 대기).
- ~~MySQL–ClickHouse `track_sections` 는 2026-09-25 에 완전히 일치했지만, 쓰기 실패 시 로그만 남는
  구조는 그대로다 (§8.1).~~ **정합성 기반을 넣었다(S15P21A204-451, 배포 대기).** 미반영 기록
  `analytics_sync_pending`·1분 재시도·매일 레플리카별 대조·`analysis_version` MySQL 컬럼·대시보드 지표,
  `push_sections.py` 삭제. 운영 MySQL 마이그레이션(`migrate-analytics-sync-2026-09-25.sql`)을 앱보다 먼저 건다.
- 곡 삭제를 ClickHouse 가 모르던 것(§5.3)은 **`track_features.is_deleted` 로 싣게 했다(S15P21A204-455, 배포 대기).**
  운영 ClickHouse 마이그레이션(`api/clickhouse/migrations/05-track-features.sql`)을 api 보다 먼저 걸고, 첫 대조(또는
  손으로 넣는 미반영 행)가 1,060곡을 채운다. 서빙 읽기(`ai_server/app/feature_store.py`)는 아직 요청 경로에 없다(458).
- 벡터 등록 API(S15P21A204-457, 배포 대기)가 들어갔다. 운영 컬렉션 생성(456 도구) → `vectors-freeze-mu` → `vectors-send`
  (1,060곡) → 대조 불일치 0 확인 뒤에야 `MUPID_VECTOR_BACKEND=qdrant` 를 검토한다. 그 전까지 ai 는 local 이다.
- node-b 단일 장애점(§10.1-8)은 지금도 마찬가지다.

---

## 13. 전환 단계

| 단계 | 내용 | 축 | 끝났다는 기준 |
|---|---|---|---|
| 0 | 판 배송이 `moments_emb.npy` 를 싣고 받게 한다 (지금 상태를 정식 경로로) | ① | 수신기로 올린 판에서 로컬 모드가 뜬다 |
| 1 | **정합성 기반**: 쓰기 입구 전수 목록, 미반영 기록·재시도·대조, `analysis_version` MySQL 에, `push_sections` 우회 제거, 지표 | ① | 미반영·불일치 지표가 보이고, ClickHouse 를 잠깐 끊었다 이어도 불일치가 0 으로 돌아온다 |
| 2 | `VectorStore` 인터페이스 + local 구현. **요청 경로의 O(N) 연산 전수 점검·제거**, 온보딩 씨앗 사전 계산 | ③ | 요청 경로에 풀 전체 연산이 없다 (점검표). 시험 통과 |
| 3 | **배치용 Object Storage API — 모델 가중치 전용** (§6.5, Nginx `/upload/system/`, ERD) + `model-publish` CI 작업(JupyterHub → 체크섬 → 관문 → 업로드) + 모델 묶음 분리 (`model_versions`, μ 동결, `manifest.json`) + **CI 가 `models.lock` 으로 서빙 가중치를 이미지에 굽기** + ROLE_SYSTEM 계정 분리 | ② | `models.lock` 만 바꾼 MR 로 가중치 교체·롤백이 된다. ai_server 에 토큰이 없다. — API 는 453, CI·`models.lock`·이미지 굽기·ROLE_SYSTEM 장기 토큰은 454. 첫 채택(JupyterHub 묶음 → `models.lock` MR)과 μ 동결은 남았다 |
| 4 | ClickHouse `track_features`(+`is_deleted`·`playable`) · 서빙 정렬 키·프로파일 · AI 읽기 · 지연 측정 | ①③ | 1만 곡 합성에서 특징 조회·조건 풀기 p95 가 기준 안 — 455: 표·`energy` 열·미반영/재시도/대조·서빙 사전·`mupid_serving`·`feature_store.py`, 서버 p95 16ms / 56ms (기준 제안 50 / 100ms, 미결 4). 요청 경로 연결은 458 |
| 5 | Qdrant (node-b StatefulSet, 인증, 구간 지문, 로컬 스냅샷, NetworkPolicy) + **벡터 등록 API** (§6.6, 곡 전체 / 모델 재계산) + 재적재 시간 측정 | ①② | 1만 곡 합성 부하에서 추천 p95 < 1초. node-b 를 비운 뒤 JupyterHub 재전송으로 복구된다 — Qdrant 는 456, 벡터 등록 API·미반영 `VECTOR`·대조·관문·배치 명령은 457(로컬 왕복: Spring 으로 넣은 합성 곡이 local 과 같은 순위·점수). 운영 적재·재적재 시간 측정·`qdrant` 전환은 배포 뒤 |
| 6 | **계약 v3**: `catalog` 제거, 조건 풀기 AI 로, `QueryPlanFilter` 걷어내기, `exclude_tracks` 상한, `limit + 여유분` | ①③ | 요청 본문 < 5KB, 오늘의 모먼트가 늘 4개 |
| 7 | Pinecone 제거 (코드·SDK·CI 변수·문서·JupyterHub 야간 단계, 콘솔 인덱스 삭제) | — | `pinecone` 이 저장소에 없다 |
