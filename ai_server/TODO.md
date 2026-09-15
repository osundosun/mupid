# ai_server 할 일

`2026-09-14` · BE 쪽에서 코드를 읽고 정리한 목록이다. **AI 팀 소유 코드에 대한 제안**이며,
`app/legacy/` 와 추천 로직은 "원본 그대로" 가 원칙이므로 손대기 전에 합의가 필요하다.

우선순위는 **채팅 기능(`POST /api/v1/ai/chat`)이 목업을 걷어내는 데 필요한 순서**로 잡았다.
지금 그 자리는 `api/src/main/java/com/osundosun/mupid/api/ai/tool/AiRecommendToolImpl.java` 가 카탈로그 앞쪽 곡에 정해진 사유를
붙여 채우고 있다.

관련 문서: [README.md](README.md) · [`api/knowledge/architecture/integrations.md`](../api/knowledge/architecture/integrations.md) · [`api/knowledge/api-todo.md`](../api/knowledge/api-todo.md)

---

## P0 — 고쳐야 하는 것 (버그·조용한 실패)

### 1. `search` 가 조건을 말없이 풀어 버린다

`internal_api.py:226` 은 `MUPID_AI_NLP_URL` 로 열고 닫는데, 실제 파싱은
`recommender.py:46` 의 **다른 변수** `MUPID_NLP_URL`(기본 `http://127.0.0.1:8200/parse`)로 나간다.
게이트만 채우고 파서가 없으면 `internal_api.py:237` 이 `_error` 를 버리고 `plan=None` 으로
추천을 계속한다 — **사용자가 적은 조건을 무시한 채 일반 추천이 나간다.**

501 을 둔 주석의 취지("조건을 몰래 푸는 것과 같다")와 정확히 반대다.

- 변수 이름을 하나로 통일한다
- 파싱 실패는 `SEARCH_NOT_CONFIGURED` 또는 `INTERNAL_ERROR` 로 올린다. 조용히 넘기지 않는다

### 2. `ids.py` 만 `MUPID_AI_ARTIFACT_DIR` 을 보지 않는다

`ids.py:30-34` 가 `ai_server/data/proc/mupid/` 를 하드코딩한다. `recommender.py`·`m09`·`m10`·`m11`
은 전부 환경변수를 읽는다. README 의 "비우면 `<ARTIFACT_DIR>/data/proc/mupid/id_map.parquet`"
와 어긋나서, 문서대로 `MUPID_AI_ARTIFACT_DIR` 만 세팅하면 **매핑을 못 찾아 `/internal/ready` 가
영구 503** 이다. (`MUPID_ID_MAP` 을 명시하면 우회된다.)

`ids.MOMENTS` 도 같은 뿌리를 쓰므로 `--report` CLI 도 함께 어긋난다.

### 3. `reason` 이 사용자에게 보여 줄 문장이 아니다

`recommender.py:1505` 가 만드는 실제 값:

```
내 선호 구간 묶음 dna3(대표 JAMENDO_1083659#2)와 이 곡 4번 chorus 구간 72~96초가
유사도 0.812 · 선호 확률 0.634 · 학습모델 점수 0.551 · BPM 122(선호 118)
```

README 는 이 필드를 "사람이 읽는 추천 이유" 라고 적어 두었고, **화면이 그대로 그린다**
(`frontend/lib/ai-chat.ts` → 곡 줄의 `reason`). 실서버로 갈아 끼우는 순간 사용자가 내부
문자열 ID 와 코사인 값을 보게 된다.

셋 중 하나로 정한다:

| 안 | 내용 | 비고 |
|---|---|---|
| a | Spring 이 `reason_code` → 한국어 문장으로 매핑, `reason` 은 로그 전용 | 결정적·비용 0. **기본안** |
| b | ai_server 가 짧은 사용자용 문구를 한 필드 더 내려준다 | 계약 확장 |
| c | LLM 이 `reason` 을 풀어 쓴다 | 곡별 문구가 매번 달라지고 느려짐 |

어느 쪽이든 README 의 필드 설명은 실제와 맞춰야 한다.

### 4. 문서에 없는 환경변수 4개

`MUPID_NLP_URL` · `MUPID_POOL` · `MUPID_POOL_EXTRA` · `MUPID_COMBINER`.
`.env.example` 과 README 표에 넣는다. 특히 `MUPID_POOL*` 는 **추천 풀 자체를 바꾸는** 값이다.

---

## P1 — 채팅이 실제 추천을 받으려면

### 5. 자연어 → QueryPlan 경로 (S15P21A204-219)

`search` 가 501 이라 대화형 추천이 붙지 못한다. `nlp_demo/app.py`(Jupyter)는 Qwen fp16 +
CLAP 을 **cuda:0** 에 올린다 — 세우려면 GPU 박스가 하나 더 필요하다.

**대안: `/internal/facets` 하나만 연다.** QueryPlan 은
`{filters: {genre, language, mood, instrument, vocal, flow}, exclude: {...}}` 형태의 평범한
dict 이고 `_canonicalize_query_tags()`(`recommender.py:1090`)가 어차피 카탈로그 어휘로 잘라낸다.
어휘만 알려 주면 **이미 붙어 있는 LLM(gpt-5-mini)이 QueryPlan 을 직접 만들 수 있다.**

- 얻는 것: GPU 서비스 없이 자연어 검색
- 잃는 것: `_clap_embedding` → CLAP 의미 재정렬(`CLAP_WEIGHT = 0.25`)이 빠진다. 명시 필터는 그대로

### 6. `id_map.parquet` 생성 경로

`work/build_id_map.py`(Jupyter)가 이미 있다 — MySQL CSV 2개를 받아 1:1 검증을 통과해야 저장한다.
**저장소로 가져오고 정기 실행 경로를 만든다.**

**2026-09-15 해소.** `music.source_track_id` 는 **만들지 않기로 했다**(Q3) — 숫자 `music_id`
하나를 공통 키로 쓰고 곡 문자열 ID 는 구간 UID 의 `#` 앞에서 파생한다. `build_id_map` 이
그렇게 동작하도록 바꿨고, 파생이 기본 경로다.

구간 외부 ID 는 `external_id VARCHAR(64) UNIQUE` 로 간다(Q2). UID 길이 상한을 50 → 64 로
올렸다. **DDL 반영은 DB·BE 팀 확정이 남아 있다** — 확정 전에 내보낸 표는 50자에서 잘릴 수
있고, 잘린 값끼리는 1:1 검사도 통과하므로 `build_id_map` 의 길이 경고를 무시하지 않는다.

### 7. 배포 경로 자체가 없다

저장소 전체에서 `ai_server` · `8100` · `MUPID_AI_BASE_URL` 검색 결과 0건이다. Dockerfile,
compose 서비스, CI, 헬스체크가 전부 없다.

- `/internal` 은 인증이 없다 → **nginx 에 노출하지 않는다**
- Spring 내부 클라이언트: 읽기 타임아웃 5초, `503`·`500`·타임아웃에만 1회 재시도, 실패 시 대체 동작
- Spring 은 컨테이너, 이 서비스가 호스트면 `extra_hosts` 를 맞춰야 한다

---

## P2 — 추천이 개인화되려면 (지금은 전원 콜드스타트)

### 8. 행동 신호를 ClickHouse 조회로 (S15P21A204-217)

`user_behavior()`(`recommender.py:773`)가 **요청마다 `events_service.jsonl` 을 전수 스캔**한다.
p50 2.22초의 주범이다(콜드스타트는 0.30초).

ClickHouse `listen_events` 는 정렬키가 `(user_id, occurred_at, event_id)`, 샤딩 키가
`cityHash64(user_id)` 라 사용자 단위 조회가 1차 접근 경로로 설계돼 있다. `user_section_signals`
MV 는 이벤트→구간 매핑과 가중치까지 이미 물고 있다.

**효과가 가장 빨리 보이는 항목이다.** Spring 의 5초 타임아웃과도 직결된다.

### 9. 라벨 재생성 배치

`labels_service.parquet` 이 없으면 **오류 없이 전원 콜드스타트**가 된다(README 가 이식 중 실제로
겪었다고 적어 둔 사고). 지금 이 파일을 채우는 경로가 없다.

매 요청 조회로 대체할 수 **없다** — 한 사용자의 등급 라벨이 남의 데이터에 의존한다
(`m09_labels.py:251-256`: baseline = 그 구간을 들은 **모든 사용자**의 평균 + 전역 위치 편향
prior + shrinkage κ=5). 게다가 등급 우선순위·도달 마스킹·인접 구간 병합 같은 규칙이 15개 상수와
함께 들어 있고, **모델이 이 정의로 학습됐다.**

→ 야간 배치로 ClickHouse 에서 이벤트를 당겨 `m09_labels` 를 그대로 돌린다. 하루 1회면 충분하다
(최근성은 8번이 담당한다).

### 10. 온보딩 취향 유입

`serve/onboarding.parquet` 도 읽기 전용이다(`onboarding_of()`). Spring 에 온보딩 API 가 이미
있는데 AI 쪽으로 흐르지 않는다. **이력이 없는 신규 사용자의 첫 추천 품질**이 여기 걸린다.

> **Spring 쪽은 준비됐다** — `GET /api/v1/user/{userId}/onboarding` (`ROLE_SYSTEM` 전용).
> `{user_id, submitted, submitted_at, moods[], genres[]}` 를 돌려준다. 남은 것은 이걸 당겨
> `onboarding.parquet` 를 채우는 경로다. 다만 `onboarding_seeds()` 가 읽는 7축 중 Spring 이
> 가진 것은 **`genre` · `mood` 둘뿐**이다. 나머지(`language` · `vocal_type` · `style` ·
> `tempo` · `instrument`)는 `onboarding_survey` 에 칼럼이 없다 — ERD 변경이라 팀 합의가 먼저다.

### 11. 학습·라벨 갱신을 부를 방법

`append_event` · `run_labeler` · `learn_incremental` 은 함수로만 있고 라우터가 없다.
**쓰기·학습 절반이 HTTP 로 나와 있지 않다.** 9·10 번을 배치로 할지 엔드포인트로 열지와 같은 결정이다.

> 스케줄러는 **cron/APScheduler 로 시작**한다. 작업이 둘뿐이라 Celery 는 아직 무겁다
> (워커마다 `features_v1.npz` 의 `X` ~97MB 가 올라가 `concurrency=1` 을 강제받는다).
> 사용자별 태스크로 팬아웃하고 개별 재시도가 필요해지면 그때 Celery 로 옮긴다. 브로커는 이미 있는 Redis.

---

## P3 — 다른 화면이 기다리는 것

### 12. 일일 추천 적재

Spring `GET /api/v1/recommendation/daily` 는 `daily_recommendations` 를 **읽기만** 한다
(`api/knowledge/api-todo.md` #12). 채우는 잡이 필요하다.

결과를 ai_server 가 MySQL 에 직접 쓰지 않는다 — 토픽이나 API 로 넘기고 **Spring 이 적재**한다.
스키마 소유권은 Spring 에 둔다(decision/0011).

### 13. `/internal/similar` — 비슷한 구간·파생곡

`moments_emb.npy` 가 이미 있어 계산은 되는데 노출이 없다. 화면은 데모 정렬 중이다
(api-todo #15, Pinecone S15P21A204-214).

### 14. `matched_dna_rep` 을 계약에 올리기

내부에는 있는데(`recommender.py:1504`) 계약이 버리는 값이다. **"당신이 저장한 그 순간과 닮아서"**
를 근거 있게 말할 수 있는 유일한 필드라 Mupid AI 패널 컨셉과 정확히 맞는다.
같이 버려지는 것: `moment_similarity` · `model_score` · `prob` · `clap_score` · `slot_source` · `diagnostics`.

---

## P4 — 실행기·운영·정리

### 15. 랭킹 실행기를 numpy 로 (torch 제거)

서빙에서 torch 가 하는 일은 **요청당 forward 한 번**뿐이다. 구간 임베딩은
`stage1_section.npy` 로 이미 구워져 있고 스코어링은 numpy matmul 이다(`recommender.py:1322`).

| 값 | |
|---|---|
| `in_dim` 285 (PCA 256 + side 29) | `features_info.json` |
| `DIM` 128 · `HIST_MAX` 32 | `app/legacy/m11_stage1.py:46-47` |
| 파라미터 ~88,000개 · 체크포인트 357KB | |
| 요청당 `(1,32,285)` forward ≈ **5 MFLOP** | |

**지연은 줄지 않는다**(병목은 8번). 얻는 것은 서빙 이미지에서 torch 를 걷어내는 것과
`--index-url` 설치 함정이 사라지는 것이다.

연산이 `Linear→LayerNorm→GELU→Linear` + 어텐션뿐이라 **numpy 30줄**이면 되고, GELU 의 exact erf
는 이미 의존성인 scipy 로 맞출 수 있다. ONNX 는 아키텍처가 계속 바뀔 때 값이 산다.

순서:

1. **파리티 하네스 먼저** — 산출물과 torch 가 같이 있는 Jupyter 박스에서 돌린다.
   `zu` 최대 절대오차 `< 1e-5`, **최종 `rank_order` 완전 일치**, 점수 차 `< 1e-6`
2. `stage1.pt` → `stage1_weights.npz` 변환기 (`in_dim`·`hist_max`·`half_life_days`·원본 sha256 동봉)
3. 실행기 교체 (float32 고정 · LayerNorm `eps=1e-5` 편향분산 · masked softmax `-1e9` · gmean 차감 · L2 정규화)
4. 서빙 이미지에서 torch 제거 (학습 경로는 계속 필요하다)

가중치 파일이 없을 때 **조용히 규칙 전용으로 떨어지지 않도록** `/health` 와 `check_integrity()` 에
상태를 드러낸다.

### 16. 산출물 상태를 더 드러내기

지금 `check_integrity()` 는 색인 4종만 본다. `labels_service.parquet` · `id_map.parquet` ·
(추가되면) 가중치 npz 의 유무를 `/health` 에 노출한다. **없어도 서비스가 정상처럼 뜨는 것**이
이 저장소가 반복해서 밟은 함정이다.

### 17. 관측

구조화 로깅과 `reason_code` 분포 지표. **콜드스타트 비율이 곧 9·10번의 성적표다.**
`/internal/ready` 는 모니터링 대상에 올린다.

### 18. 잔가지

- `main.py:56` `@app.on_event("startup")` → lifespan (FastAPI 0.141 에서 deprecated)
- `recommender.py:1531~` 데모 시절 스키마 5개(`NextReq`·`FeedbackReq`·`LearnReq`·`OnboardReq`·`SimReq`) 미사용
- `recommender.py:1475` `order = order` 무의미한 대입
- 테스트가 스모크뿐이고 산출물이 없으면 통째로 skip 된다 → 산출물 없이도 도는 단위 테스트(ID 변환, ms 변환,
  `music_id` 중복 제거, 오류 매핑)를 따로 둔다

---

## 정리 — 채팅 목업을 걷어내는 최단 경로

**5(b) → 6 → 7.** 세 개면 실제 추천이 화면에 닿는다.
다만 **9번 없이는 무엇을 물어도 콜드스타트 추천**이라 "AI 가 내 취향을 안다" 는 느낌은 나지 않는다.
데모 일정에 따라 9번을 7번과 같이 갈지가 실질적인 갈림길이다.
