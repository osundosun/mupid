# AI 추천 서비스

`S15P21A204-218` · 2026-09-14

기존 Jupyter 서버의 추천 서비스를 옮겨 온 FastAPI 서비스다. **BE 는 GPU 서버에 접속하지 않고
이 저장소만으로 실행할 수 있다.**

추천 점수·가중치·필터·랭킹은 원본 그대로다. 재학습하지 않는다.

---

## 실행

```bash
python -m venv .venv && . .venv/Scripts/activate      # Linux: . .venv/bin/activate
pip install -r ai_server/requirements.txt
pip install --index-url https://download.pytorch.org/whl/cpu torch==2.6.0

export MUPID_AI_ARTIFACT_DIR=/모델·색인을/둔/경로
uvicorn ai_server.app.main:app --host 0.0.0.0 --port 8100
```

Python **3.12**. GPU 는 필요 없다 — `torch` 는 반드시 CPU 빌드로 받는다. 기본 PyPI 판은
CUDA 라이브러리를 끌고 와 수 GB 가 된다.

### 환경변수

| 변수 | 기본값 | 뜻 |
|---|---|---|
| `MUPID_AI_ARTIFACT_DIR` | `ai_server/artifacts` | 모델·색인 루트 (**필수**). 서비스·매핑표·라벨러가 모두 이 하나를 기준으로 경로를 만든다 |
| `MUPID_AI_PORT` | `8100` | 포트 |
| `MUPID_ID_MAP` | `<ARTIFACT_DIR>/data/proc/mupid/id_map.parquet` | 숫자 ID 매핑표. **시험용 매핑표를 물릴 때만** 지정한다 |
| `MUPID_AI_NLP_URL` | (없음) | 자연어 해석기 주소. 비우면 `search` 가 501 |
| `MUPID_POOL` | `flow` | 후보 풀의 주 출처 (`moments.parquet` 의 `source`) |
| `MUPID_POOL_EXTRA` | `jamendo,own` | 주 출처에 **더해** 후보로 넣을 출처 (쉼표 구분) |
| `MUPID_COMBINER` | `logistic_flow_stage1.json` | 점수 결합기 파일 이름 |
| `MUPID_AI_INTERNAL_TOKEN` | (없음) | `/internal` 호출 토큰. 비우면 검사하지 않는다 |

**`MUPID_POOL`·`MUPID_POOL_EXTRA`·`MUPID_COMBINER` 는 추천 결과 자체를 바꾼다.** 앞의 둘은
후보에 오를 곡 집합을, 마지막은 순위를 바꾼다. 색인과 짝이 맞지 않는 결합기를 넣으면
서비스는 정상처럼 뜨면서 순위만 틀어진다. 기본값을 그대로 두는 것을 권한다.

경로 해석 결과는 `/health` 의 `paths` 와 `/internal/ready` 응답에서 확인한다. 설정한 대로
읽고 있는지는 그 값으로 판별한다 — 환경변수 **값 자체는 응답에 싣지 않는다.**

값은 `.env.example` 을 보고 배포 환경에 넣는다. 실제 값은 커밋하지 않는다.

## 모델·색인

**Git 에 넣지 않는다.** 별도로 전달받아 `MUPID_AI_ARTIFACT_DIR` 아래에 이 구조로 둔다.

```
<ARTIFACT_DIR>/
└─ data/
   ├─ proc/mupid/
   │  ├─ moments.parquet            구간 메타
   │  ├─ moments_emb.npy            MERT 임베딩
   │  ├─ features_v1.npz            랭킹 입력 특징
   │  ├─ stage1_section.npy         구간 랭킹 표현
   │  ├─ stage1.pt                  랭킹 모델 (torch 판)
   │  ├─ stage1_weights.npz        랭킹 모델 (NumPy 판, 있으면 우선)
   │  ├─ content.parquet            곡 특징 (BPM 등)
   │  ├─ flow_meta.parquet          곡 메타
   │  ├─ labels_service.parquet     사용자 선호 구간
   │  ├─ logistic_flow_stage1.json  결합기
   │  ├─ clap_moments.npz           (선택) 자연어 의미 점수
   │  ├─ serve/                     prefs · onboarding
   │  └─ logs/                      행동 이벤트
   └─ test_pipeline/                jamendo_catalog 등
```

앞의 네 색인은 **같은 행 번호로 서로를 가리킨다.** 하나만 교체하면 엉뚱한 곡을 추천하면서도
서비스는 정상처럼 뜬다. 반드시 묶음으로 교체한다.

`labels_service.parquet` 을 빠뜨리면 오류 없이 **모든 사용자가 콜드스타트 추천**을 받는다.
이식 중 실제로 겪었다. 이런 "있어도 되지만 없으면 기능이 조용히 꺼지는" 파일은 `/health`
의 `artifacts` 가 이름과 이유를 함께 보여준다. 기동 로그에도 한 줄 남는다.

```json
"artifacts": { "missing": ["labels_service.parquet"],
               "files": { "labels_service.parquet":
                          { "exists": false, "why": "개인화. 없으면 모든 사용자가 콜드스타트다" } } }
```

## API

BE 는 아래만 호출한다. 내부 JSON 은 `snake_case` 다.

| 메서드 | 경로 | 뜻 |
|---|---|---|
| GET | `/health` | 프로세스 생존 |
| GET | `/internal/ready` | 요청을 받을 수 있는가 |
| GET | `/internal/facets` | QueryPlan 에 쓸 수 있는 태그 어휘 |
| GET | `/internal/metrics` | 운영 지표 (프로세스 내 카운터) |
| POST | `/internal/recommendations/daily` | 오늘의 추천 |
| POST | `/internal/recommendations/next` | 이어 듣기 |
| POST | `/internal/recommendations/search` | 자연어 검색 |
| POST | `/internal/similar` | 기준 구간과 닮은 다른 곡의 구간 |

### 요청

```json
{ "recommendation_id": 5001, "user_id": 15, "limit": 10,
  "exclude_music_ids": [], "current_music_id": 148,
  "current_music_section_id": 1204, "position_ms": 58000 }
```

`recommendation_id`·`user_id` 만 필수. 뒤 세 개는 `next` 전용이다.
타입이 엄격하다 — `"5001"` 같은 문자열이나 실수 `limit` 은 400 으로 거부한다.

`search` 는 조건을 **누가 뽑았느냐**에 따라 둘 중 하나를 채운다. 둘 다 없으면 400 이다.

```json
{ "recommendation_id": 5001, "user_id": 15, "limit": 10,
  "query_plan": { "filters": { "genre": ["pop"], "instrument": ["piano"],
                               "vocal": ["female"], "flow": ["chorus"] },
                  "exclude": { "mood": ["aggressive"] } } }
```

| 필드 | 쓰는 경우 | 해석기 |
|---|---|---|
| `query_plan` | 호출자(Spring 의 LLM)가 이미 조건을 뽑았다 | **불필요** |
| `query` | 원문만 보낸다 | 필요. 없으면 501 |

`query_plan` 은 `filters`·`exclude` 만 읽는다. 밑줄로 시작하는 키는 내부 전용이라 버린다.
쓸 수 있는 태그는 `GET /internal/facets` 가 알려준다.

### 응답

```json
{ "recommendation_id": 5001,
  "items": [
    { "music_id": 900108, "music_section_id": 9001318,
      "start_time_ms": 79490, "end_time_ms": 92740,
      "section_label": "VERSE", "rank_order": 1, "score": 1.0,
      "reason_code": "COLD_START", "reason": "…" }
  ],
  "shortfall": 0, "model_version": "mupid-stage1-v1" }
```

- 같은 `music_id` 는 한 번만 나온다
- 후보가 모자라면 임의로 채우지 않고 `shortfall` 에 부족분을 적는다
- `section_label` 은 ERD ENUM 과 같은 대문자
- `reason_code` 6종

  | 코드 | 언제 |
  |---|---|
  | `SIMILAR_PREFERRED_MOMENT` | 청취 이력에서 만든 선호 구간과 닮았다 |
  | `ONBOARDING_MATCH` | 이력이 없어 온보딩 답변으로 뽑았다 |
  | `QUERY_SEMANTIC_MATCH` | 자연어 검색 + 의미 유사도 적용 |
  | `QUERY_FILTER_MATCH` | 자연어 검색 (조건 필터만) |
  | `EXPLORE_NEW` | 탐색 슬롯 |
  | `COLD_START` | 근거가 없다 |

  **늘 수 있으니 모르는 값에서 실패하지 말 것.** 폴백 문장을 반드시 둔다.
- `reason` 은 **AI 서버가 만든 안전한 기본 문장**이다. 내부 ID·모델 수치는 들어가지 않는다.
  Spring 이 자체 문구를 쓰려면 `reason_code` 로 갈아끼운다
- `ref_music_id` · `ref_music_section_id` 는 이 추천의 근거가 된 **사용자 자신의** 구간이다.
  "저장하신 이 구간과 닮아서" 를 만들 수 있다. 매핑이 없으면 비운다 — 근거가 없다고
  추천까지 실패시키지 않는다. 모델 점수·확률은 여전히 내보내지 않는다

### 오류

| `error_code` | HTTP | 뜻 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 타입·범위·미정의 필드 |
| `MUSIC_ID_MAPPING_NOT_FOUND` | 404 | 숫자 ID 매핑 없음 |
| `SECTION_ID_MAPPING_NOT_FOUND` | 404 | 〃 (구간) |
| `NO_CANDIDATES` | 404 | **조건에 맞는 곡이 없음 — 장애가 아니다.** 빈 목록으로 전달 |
| `SEARCH_NOT_CONFIGURED` | 501 | 자연어 해석기 미설정 (`MUPID_AI_NLP_URL` 없음) → `S15P21A204-219` |
| `INDEX_NOT_READY` | 503 | 색인·매핑 미완. 재시도 대상 |
| `INTERNAL_ERROR` | 500 | 그 밖. 재시도 대상 |

읽기 타임아웃 **5초**, 재시도는 `503`·`500`·타임아웃에만 1회. `400`·`404` 는 재시도하지 않는다.

### `/internal/metrics`

| 지표 | 보는 이유 |
|---|---|
| `cold_start_rate` | 온보딩·라벨 배치가 도는지. **이 값이 높으면 개인화가 꺼진 것이다** |
| `query_source` | 위 비율의 원천 (`labels` / `onboarding` / `none`) |
| `reason_codes` | 사유 분포. 한 코드로 쏠리면 게이트나 임계값을 의심한다 |
| `latency_seconds` | 경로별 p50·p95·max |
| `behavior_lookup_seconds` | 행동 로그 조회 시간. 개인화 사용자가 느린 원인을 분리한다 |
| `errors` | 코드별 건수와 실패율 |
| `ready` | `/internal/ready` 상태. 실패율만 보고는 원인을 못 가른다 |

**프로세스가 살아 있는 동안만 쌓인다.** 재시작하면 0 이라 `started_at`·`uptime_seconds` 를
함께 내보낸다. 장기 추세는 수집기가 주기적으로 읽어 쌓아야 한다.

지연은 평균을 내보내지 않는다 — 느린 요청 몇 건이 평균에 묻혀 괜찮아 보이는 그래프가 된다.

### `/internal/similar`

```json
{ "music_section_id": 9001318, "limit": 5, "exclude_music_ids": [] }
```

```json
{ "music_section_id": 9001318,
  "items": [ { "music_id": 900204, "music_section_id": 9002110,
               "start_time_ms": 41200, "end_time_ms": 68900,
               "section_label": "CHORUS", "rank_order": 1, "similarity": 0.83 } ],
  "shortfall": 0, "embedding_version": "mert-v1-95m" }
```

**개인화하지 않는다.** `user_id` 를 받지 않고, 받으면 400 이다. 같은 질문에 늘 같은 답이라
캐시할 수 있고 설명도 된다. 취향을 반영하려면 `recommendations/next` 를 쓴다.

한 곡당 가장 닮은 구간 **하나만** 남긴다. 같은 곡의 인접 구간이 목록을 채우면 "비슷한 곡"
으로 쓸모가 없다. `similarity` 는 코사인이고 순위 근거일 뿐 확률이 아니다.

### `/internal/facets`

자연어를 QueryPlan 으로 바꾸는 쪽(외부 해석기든 Spring 의 LLM 이든)이 읽는다. 선택지는
**카탈로그에서 직접** 만든다 — 없는 태그를 지어내면 조건이 어휘에 없어 후보가 0 이 된다.

```json
{ "catalog": { "tracks": 111, "moments": 65540 },
  "fields": {
    "genre":  { "enforcement": "gate", "excludable": true,
                "values": [{ "value": "pop", "tracks": 42 }],
                "style_values": [{ "value": "ko_ballad", "tracks": 9 }] },
    "mood":   { "enforcement": "score_only", "excludable": true, "values": [...] },
    "bpm_min": { "enforcement": "gate", "excludable": false, "range": [60, 180] }
  },
  "example": { "filters": { "genre": ["pop"], "instrument": ["piano"],
                            "vocal": ["female"], "flow": ["chorus"] },
               "exclude": { "mood": ["aggressive"] } } }
```

`enforcement` 를 반드시 읽을 것. **필드마다 적용 방식이 다르다.**

| 값 | 뜻 |
|---|---|
| `gate` | 하드 필터. 맞지 않는 곡은 후보에서 빠진다 |
| `score_only` | 걸러내지 않는다. 의미 점수에만 반영된다 |
| `gate_with_reference` | 기준 곡이 있어야 적용된다. 없으면 무시되고 진단에 남는다 |
| `ignored` | 받지만 아무 일도 하지 않는다 (카탈로그에 해당 필드가 없다) |

특히 **`filters.mood` 는 걸러내지 않는다.** 무드는 추론 폭이 커서 게이트로 쓰면 후보가
과하게 마른다. 대신 일치하는 곡을 **위로 올린다**(soft rerank, 가중치 `MOOD_WEIGHT`).
가까운 순위는 추월하지만 큰 격차를 뒤집지는 않는다. 반대로 `exclude.mood` 는 게이트다.

`filters` 에 쓰는 키는 `genre` · `language` · `vocal` · `instrument` · `mood` · `flow` ·
`bpm_min` · `bpm_max` · `tempo_direction` · `energy_direction` 이다. `vocal_type` 이 아니라
**`vocal`**, 구간은 `section_label` 이 아니라 **`flow`** 다.

### `/internal/ready`

`/health` 가 200 이어도 트래픽을 받지 않는다. 아래가 모두 맞아야 200 이다.

1. 모델 로드 2. 색인 4종 행 수 일치 3. `emb_row` 0부터 연속
4. 매핑 1:1 5. 추천 풀 전체가 숫자 ID 로 매핑됨

**MySQL 매핑이 없으면 503 이 정상이다.** 가짜 ID 로 통과시키지 않는다.

**부분 매핑도 `ready` 가 아니다.** 추천 풀 전량이 매핑돼야 한다. 절반만 매핑된 상태로
응답하면 매핑 안 된 곡이 **소리 없이 사라지고**, 그건 503 보다 나쁘다. 그래서 배포 규약은
"색인 적재가 끝날 때까지 `ready` 는 뜨지 않는다" 이다 — 그동안 Spring 은 대체 응답을 쓴다.

503 메시지에는 **매핑표 파일이 없는 것인지 매핑이 부족한 것인지**와 해석된 `id_map` 경로가
함께 들어간다. 경로 설정 실수와 적재 미완을 응답만 보고 구분하기 위해서다.

## ID 규칙

| 대상 | 타입 |
|---|---|
| `music_id` | MySQL `INT` |
| `music_section_id` | MySQL `INT` |
| `recommendation_id` | MySQL `BIGINT` |

AI 는 숫자 ID 를 만들지 않는다. MySQL 이 발급한 값을 `id_map.parquet` 으로 받아 쓰며, 레거시
문자열 ID 와 **1:1** 이어야 한다. 위반하면 적재를 거부한다.

전체 계약은 `origin/ai/docs/S15P21A204-231-integration-contract` 의
`infra/AI-SPRING-CONTRACT.md` 를 본다.

## 테스트

```bash
export MUPID_AI_ARTIFACT_DIR=/경로
pytest ai_server/tests -q
```

세 묶음이다.

| 파일 | 산출물 필요 | 무엇을 보나 |
|---|---|---|
| `test_contract_offline.py` | 없음 | 경로 해석·오류 코드·응답 문장·배포 파일 |
| `test_pipeline_synthetic.py` | 없음 (스키마만 진짜인 카탈로그를 만든다) | `recommend()` 전체 경로·게이트·중복 제거 |
| `test_smoke.py` | **필요** | 실제 색인으로 도는 추천 |

앞의 둘은 어디서든 돈다. `test_smoke.py` 는 색인이 없으면 통과로 위장하지 않고 건너뛴다.
시험용 ID 매핑은 임시 디렉터리에만 만들고 운영 파일로 남기지 않는다.

CI 는 **실제로 몇 건 돌았는지**를 확인한다. 색인이 없어 전부 건너뛴 초록불은 아무것도
지키지 않는 것과 같다.

### 확인한 것 (2026-09-14)

| 항목 | 결과 |
|---|---|
| 깨끗한 Python 3.12 환경 설치·전체 import | 통과 |
| `/health` 200 · 색인 65,540행 4종 일치 | 통과 |
| 시험 매핑으로 `/internal/ready` 200 | 통과 |
| `daily`·`next` 실제 추천 응답 | 통과 |
| 응답 필드·시간 범위·`music_id` 중복 0건 | 통과 |
| 잘못된 요청 400 · 후보 없음과 장애 구분 | 통과 |
| **기존 8100 과 고정 입력 3건 비교** | **곡·점수까지 완전 일치** |
| `search` (해석기 없음) 501 | 통과 |

pytest 11건 전부 통과.

### 아직 확인하지 않은 것

- `search` 의 실제 자연어 추천 — 해석기가 없어 501 만 확인했다 → `S15P21A204-219`
- MySQL 실데이터 ID 매핑 — 시험용 임시 매핑으로만 검증
- Spring 통합 호출, 운영 재시작·종료·롤백
- Pinecone 연결 → `S15P21A204-214`

이력이 있는 사용자는 응답이 p50 **2.22초**다(콜드스타트 0.30초). 요청마다 행동 로그를 전수
스캔하는 구조이며 개선은 `S15P21A204-217`.

## BE 가 이어서 할 일

1. 모델·색인 수령 후 `MUPID_AI_ARTIFACT_DIR` 배치
2. 서비스 기동, `/health` 확인
3. MySQL 적재 후 `id_map.parquet` 생성 → `/internal/ready` 200
4. Spring 내부 클라이언트 연결 (5초 타임아웃, 1회 재시도, 장애 시 대체 동작)
5. 공개 API 에서 `snake_case` → `camelCase` 변환

Spring 은 컨테이너로 돈다. 이 서비스를 호스트에서 직접 실행하면 컨테이너가 닿도록
`extra_hosts` 와 `MUPID_RECOMMENDER_BASE_URL` 을 맞춰야 한다. **nginx 에 노출하지 않는다**
— `/internal` 은 Spring 만 호출하는 전제다.

> `MUPID_AI_BASE_URL` 이 아니다. 그 이름은 Spring 에서 이미 **LLM 게이트웨이**가 쓰고
> 있어(`api` 의 `ai.base-url`), 여기에 이 서비스 주소를 넣으면 **대화 요청이 추천 서버로
> 나간다.**

Spring 은 자연어 원문 대신 `query_plan` 을 보낸다. 대화 모델이 `/internal/facets` 의 어휘로
조건을 뽑기 때문에 **자연어 해석기를 따로 세우지 않아도** 조건이 걸린다. 배경과 결정
기록은 `S15P21A204-344` 에 있다.

네트워크 격리가 유일한 방어선이면 설정 실수 한 번에 그대로 열린다. `MUPID_AI_INTERNAL_TOKEN`
을 넣으면 `X-Internal-Token` 헤더를 검사한다. **양쪽을 동시에 설정해야 한다** — 한쪽만
켜면 추천이 통째로 401 이다. `/health` 는 오케스트레이터가 부르므로 검사 밖에 둔다.

## 숫자 ID 매핑 만들기

```bash
mysql ... --batch -e "
  SELECT s.external_id AS source_section_uid,
         s.music_section_analysis_id AS music_section_id,
         m.source_track_id, m.music_id
  FROM music_section_analysis s JOIN music m ON m.music_id = s.music_id
" > sections.tsv

MUPID_AI_ARTIFACT_DIR=/경로 python -m ai_server.tools.build_id_map sections.tsv
```

CSV·TSV 어느 쪽이든 읽는다. 컬럼 **이름**만 맞으면 순서와 구분자는 보지 않는다.

쓰기 전에 검사한다. 1:1 위반이면 파일을 만들지 않고, 구간 UID 가 **50자를 넘으면 경고**
한다 — `erd.sql` 의 `VARCHAR(50)` 을 넘기면 MySQL 이 조용히 잘라 저장하고, 그러면 1:1
검사를 통과하면서 매핑만 어긋난다. 실측 최댓값이 36자라 여유가 많지 않다.

곡 문자열 ID 컬럼이 DB 에 아직 없으면 `--derive-track-id` 로 구간 UID 의 `#` 앞을 쓸 수
있지만 권하지 않는다. 되짚을 근거가 DB 에 남지 않는다.

## 배치

```bash
MUPID_AI_ARTIFACT_DIR=/경로 python -m ai_server.tools.run_labeler
```

행동 로그 → 라벨 → 선호 구간(`labels_service.parquet`, `serve/prefs.parquet`).
**이것이 안 돌면 오류 없이 모든 사용자가 콜드스타트다.** `/internal/metrics` 의
`cold_start_rate` 로 확인한다.

라벨은 한 사용자만 보고 만들지 않는다 — 같은 구간을 들은 전체 사용자 평균, 위치 편향,
표본이 적을 때의 보정이 함께 들어간다. 요청마다 한 사람씩 계산하는 방식으로 바꾸면
결과가 달라진다. 전체를 한 번에 돌리는 이 경로가 기준이다.

실패하면 종료 코드 1 이다. cron 이 알아차릴 수 있어야 한다. HTTP 로 열지 배치로 돌릴지는
S15P21A204-217 에서 정한다 — 어느 쪽이든 같은 함수를 부른다.

## 랭킹 모델 백엔드

서빙에서 torch 가 하는 일은 **사용자 벡터 한 번**(`enc_user`)뿐이다. 구간 벡터는 이미
`stage1_section.npy` 에 있고 점수는 그 행렬과의 내적이다. 그 한 번 때문에 CPU 빌드라도
수백 MB 인 torch 가 이미지에 들어간다.

`stage1_weights.npz` 가 있으면 NumPy 판을 쓴다. 없으면 종전대로 `stage1.pt` 를 torch 로
읽는다. 지금 어느 쪽인지는 `/health` 의 `model_backend` 로 확인한다.

```bash
MUPID_AI_ARTIFACT_DIR=/경로/artifacts python -m ai_server.tools.export_stage1_npz
```

내보내기 도구가 **동치를 확인한 뒤에만** 파일을 쓴다(오차 < 1e-5, 순위 완전 일치).
무작위 가중치로 두 판을 비교하는 테스트도 함께 돈다 — 산출물이 없어도 검증된다.

npz 를 배포하면 이미지에서 torch 를 뺄 수 있다. **먼저 `model_backend` 가 `numpy` 인지
확인하고** Dockerfile 의 torch 설치 줄을 지운다. 순서를 바꾸면 모델 없이 규칙 경로로
조용히 내려앉는다.

## 이미지

```bash
docker build -f ai_server/Dockerfile -t mupid-ai:<tag> .     # 컨텍스트는 저장소 루트
docker run -p 8100:8100 -v /경로/artifacts:/artifacts:ro mupid-ai:<tag>
```

`torch` 는 CPU 인덱스에서 받는다. 기본 PyPI 판은 CUDA 라이브러리를 끌고 와 수 GB 가 된다.
모델·색인은 이미지에 넣지 않는다 — 코드와 수명이 다르고, 색인만 교체하는 일이 더 잦다.

헬스체크는 `/health` 다. `/internal/ready` 를 쓰면 매핑표가 없는 동안 컨테이너가 재시작을
반복한다. 트래픽 차단은 오케스트레이터가 `ready` 로 따로 판단한다.

CI 는 `infra/ci/ai-server.yml` 의 `ai-server-verify` 가 오프라인 계약 테스트를 돌린다.
색인이 없으면 smoke 가 통째로 건너뛰므로, **실제로 몇 건 돌았는지**를 확인해 "전부
건너뛰고 초록불" 을 막는다.


| 파일 | 용도 |
|---|---|
| `app/recommender.py` | 모델·색인을 불러오고 추천 점수를 계산하는 핵심 코드 |
| `app/internal_api.py` | daily·next·search 요청을 받아 추천 계산을 호출하고 JSON 반환 |
| `app/ids.py` | MySQL 숫자 ID와 기존 AI 문자열 ID 연결·검증 |
| `app/legacy/m09_labels.py` | 재생·스킵·반복 같은 행동 로그를 선호 신호로 변환 |
| `app/legacy/m10_features.py` | MERT 특징과 BPM·태그 등 부가 특징을 모델 입력 형태로 구성 |
| `app/legacy/m11_stage1.py` | Dual Encoder 모델을 불러오고 구간별 점수를 계산 |
| `app/main.py` | FastAPI 앱 시작, `/health`, `/internal/ready` 제공 |

| 순서 | 단계 |
|---|---|
| 1 | BE가 `internal_api` 호출 |
| 2 | `ids` 가 숫자 ID 변환 |
| 3 | `m09_labels` 가 사용자 취향 계산 |
| 4 | `m10_features` 가 모델 입력 구성 |
| 5 | `m11_stage1`·`recommender` 가 추천 계산 |
| 6 | `internal_api` 가 결과 반환 |

| 구분 | 내용 |
|---|---|
| BE가 쓰는 것 | HTTP API (`/health`, `/internal/ready`, daily·next·search) |
| BE가 쓰지 않는 것 | 위 Python 파일 — FastAPI 서버 **내부에서만** 실행 |
