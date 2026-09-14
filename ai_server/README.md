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

| 변수 | 뜻 |
|---|---|
| `MUPID_AI_ARTIFACT_DIR` | 모델·색인 루트 (**필수**) |
| `MUPID_AI_PORT` | 포트. 기본 8100 |
| `MUPID_ID_MAP` | 숫자 ID 매핑표. 비우면 `<ARTIFACT_DIR>/data/proc/mupid/id_map.parquet` |
| `MUPID_AI_NLP_URL` | 자연어 해석기 주소. 비우면 `search` 가 501 |

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
   │  ├─ stage1.pt                  랭킹 모델
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
이식 중 실제로 겪었다. 기동 로그의 곡·구간 수와 추천 `reason_code` 를 확인할 것.

## API

BE 는 아래만 호출한다. 내부 JSON 은 `snake_case` 다.

| 메서드 | 경로 | 뜻 |
|---|---|---|
| GET | `/health` | 프로세스 생존 |
| GET | `/internal/ready` | 요청을 받을 수 있는가 |
| POST | `/internal/recommendations/daily` | 오늘의 추천 |
| POST | `/internal/recommendations/next` | 이어 듣기 |
| POST | `/internal/recommendations/search` | 자연어 검색 |

### 요청

```json
{ "recommendation_id": 5001, "user_id": 15, "limit": 10,
  "exclude_music_ids": [], "current_music_id": 148,
  "current_music_section_id": 1204, "position_ms": 58000 }
```

`recommendation_id`·`user_id` 만 필수. 뒤 세 개는 `next` 전용, `query` 는 `search` 전용이다.
타입이 엄격하다 — `"5001"` 같은 문자열이나 실수 `limit` 은 400 으로 거부한다.

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
- `reason_code` 4종: `SIMILAR_PREFERRED_MOMENT` · `QUERY_SEMANTIC_MATCH` · `EXPLORE_NEW` ·
  `COLD_START`. 늘 수 있으니 모르는 값에서 실패하지 말 것

### 오류

| `error_code` | HTTP | 뜻 |
|---|---|---|
| `INVALID_REQUEST` | 400 | 타입·범위·미정의 필드 |
| `MUSIC_ID_MAPPING_NOT_FOUND` | 404 | 숫자 ID 매핑 없음 |
| `SECTION_ID_MAPPING_NOT_FOUND` | 404 | 〃 (구간) |
| `NO_CANDIDATES` | 404 | **조건에 맞는 곡이 없음 — 장애가 아니다.** 빈 목록으로 전달 |
| `SEARCH_NOT_CONFIGURED` | 501 | 자연어 해석기 미설정 → `S15P21A204-219` |
| `INDEX_NOT_READY` | 503 | 색인·매핑 미완. 재시도 대상 |
| `INTERNAL_ERROR` | 500 | 그 밖. 재시도 대상 |

읽기 타임아웃 **5초**, 재시도는 `503`·`500`·타임아웃에만 1회. `400`·`404` 는 재시도하지 않는다.

### `/internal/ready`

`/health` 가 200 이어도 트래픽을 받지 않는다. 아래가 모두 맞아야 200 이다.

1. 모델 로드 2. 색인 4종 행 수 일치 3. `emb_row` 0부터 연속
4. 매핑 1:1 5. 추천 풀 전체가 숫자 ID 로 매핑됨

**MySQL 매핑이 없으면 503 이 정상이다.** 가짜 ID 로 통과시키지 않는다.

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

모델·색인이 없으면 통과로 위장하지 않고 건너뛴다. 시험용 ID 매핑은 임시 디렉터리에만
만들고 운영 파일로 남기지 않는다.

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
`extra_hosts` 와 `MUPID_AI_BASE_URL` 을 맞춰야 한다. **nginx 에 노출하지 않는다** —
`/internal` 은 인증이 없고 Spring 만 호출하는 전제다.
