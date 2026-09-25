# ai_server 할 일

`2026-09-14` 작성 · `2026-09-15` 코드와 대조해 갱신. BE 쪽에서 코드를 읽고 정리한 목록이며
**AI 팀 소유 코드에 대한 제안**이다. 추천 점수·가중치·필터·랭킹은 "원본 그대로" 가 원칙이므로
손대기 전에 합의가 필요하다.

`2026-09-16` 갱신 — `app/legacy/` 는 없어졌다. 세 모듈이 `app/labeler.py`,
`app/features.py`, `app/stage1_torch.py` 로 올라왔고 `sys.path` 조작과 맨 이름 import 가
빠졌다. **계산은 한 줄도 바꾸지 않았다** — 바뀐 것은 배치와 import 경로뿐이다.

우선순위는 **채팅 기능(`POST /api/v1/ai/chat`)이 목업을 걷어내는 데 필요한 순서**로 잡았다.
**그 목업은 걷혔다** — `api/src/main/java/com/osundosun/mupid/api/ai/tool/AiRecommendToolImpl.java`
가 `RecommenderClient` 로 추천 서버에 실제로 물어본다. 남은 것은 아래 11·12·13 이다.

관련 문서: [README.md](README.md) · [`api/knowledge/architecture/integrations.md`](../api/knowledge/architecture/integrations.md) · [`api/knowledge/api-todo.md`](../api/knowledge/api-todo.md)

---

## P0 — 고쳐야 하는 것 (버그·조용한 실패)

### 1. `search` 가 조건을 말없이 풀어 버린다

> **2026-09-15 해소.** 변수를 `MUPID_AI_NLP_URL` 하나로 통일했다(`recommender.py` 의
> `NLP_PARSE_URL`) — 게이트와 실제 호출이 같은 값을 본다. 파싱 실패는 `internal_api.py`
> 가 `raise ApiError(*error)` 로 올린다. 조건을 버리고 일반 추천으로 넘어가지 않는다.

`internal_api.py:226` 은 `MUPID_AI_NLP_URL` 로 열고 닫는데, 실제 파싱은
`recommender.py:46` 의 **다른 변수** `MUPID_NLP_URL`(기본 `http://127.0.0.1:8200/parse`)로 나간다.
게이트만 채우고 파서가 없으면 `internal_api.py:237` 이 `_error` 를 버리고 `plan=None` 으로
추천을 계속한다 — **사용자가 적은 조건을 무시한 채 일반 추천이 나간다.**

501 을 둔 주석의 취지("조건을 몰래 푸는 것과 같다")와 정확히 반대다.

- 변수 이름을 하나로 통일한다
- 파싱 실패는 `SEARCH_NOT_CONFIGURED` 또는 `INTERNAL_ERROR` 로 올린다. 조용히 넘기지 않는다

### 2. `ids.py` 만 `MUPID_AI_ARTIFACT_DIR` 을 보지 않는다

> **2026-09-15 해소.** 경로 계산을 `app/paths.py` 한 곳으로 모았다. `ids.py` 는
> `paths.id_map_path()` 를 쓰고, `MUPID_ID_MAP` 이 빈 문자열이면 미설정으로 본다
> (`.env` 에 `MUPID_ID_MAP=` 로 남겨 두면 `Path("")` 가 현재 디렉터리를 가리켰다).
> 해석된 경로는 `/health` 의 `paths` 와 503 메시지에 실려 나온다.

`ids.py:30-34` 가 `ai_server/data/proc/mupid/` 를 하드코딩한다. `recommender.py`·`m09`·`m10`·`m11`
은 전부 환경변수를 읽는다. README 의 "비우면 `<ARTIFACT_DIR>/data/proc/mupid/id_map.parquet`"
와 어긋나서, 문서대로 `MUPID_AI_ARTIFACT_DIR` 만 세팅하면 **매핑을 못 찾아 `/internal/ready` 가
영구 503** 이다. (`MUPID_ID_MAP` 을 명시하면 우회된다.)

`ids.MOMENTS` 도 같은 뿌리를 쓰므로 `--report` CLI 도 함께 어긋난다.

### 3. `reason` 이 사용자에게 보여 줄 문장이 아니다

> **2026-09-15 해소 — a안.** `internal_api.py` 의 `_REASON_TEXT` 가 `reason_code` 를
> 한국어 문장으로 바꾼다. 추천기 내부 `reason` 은 진단용으로 남고 응답에 나가지 않는다.
> 모르는 코드용 폴백 문장(`_REASON_FALLBACK`)도 뒀다 — 코드는 늘 수 있다.
> README 의 필드 설명도 맞췄다.

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

> **2026-09-15 해소.** `MUPID_NLP_URL` 은 1번에서 없앴다. 나머지 셋과 뒤에 생긴
> `MUPID_AI_INTERNAL_TOKEN` 까지 README 표와 `.env.example` 에 넣었고,
> `MUPID_POOL*`·`MUPID_COMBINER` 가 **추천 결과 자체를 바꾼다**는 경고를 함께 적었다.
> 설정한 출처가 0건이면 기동 로그와 `/health` 의 `catalog.empty_sources` 에 나온다.

`MUPID_NLP_URL` · `MUPID_POOL` · `MUPID_POOL_EXTRA` · `MUPID_COMBINER`.
`.env.example` 과 README 표에 넣는다. 특히 `MUPID_POOL*` 는 **추천 풀 자체를 바꾸는** 값이다.

---

## P1 — 채팅이 실제 추천을 받으려면

### 5. 자연어 → QueryPlan 경로 (S15P21A204-219)

> **2026-09-15 해소 — 아래 대안을 채택했다.** `GET /internal/facets` 를 열었고
> Spring 의 대화 모델이 QueryPlan 을 직접 만든다(`AiRecommendToolImpl` →
> `RecommenderClient`). 필드마다 적용 방식이 다른 것을 facets 의 `enforcement` 로
> 함께 알려 준다 — 이걸 모르면 LLM 이 `mood` 로 거를 수 있다고 믿는다.
>
> **GPU 해석기는 세우지 않았다.** `query` 원문만 보내는 경로는 여전히 501 이고
> `_clap_embedding` → CLAP 의미 재정렬도 빠진 채다. 그 자리는 추천 서버의
> mood soft rerank(`MOOD_WEIGHT`)가 메운다.

`search` 가 501 이라 대화형 추천이 붙지 못한다. `nlp_demo/app.py`(Jupyter)는 Qwen fp16 +
CLAP 을 **cuda:0** 에 올린다 — 세우려면 GPU 박스가 하나 더 필요하다.

**대안: `/internal/facets` 하나만 연다.** QueryPlan 은
`{filters: {genre, language, mood, instrument, vocal, flow}, exclude: {...}}` 형태의 평범한
dict 이고 `_canonicalize_query_tags()`(`recommender.py:1090`)가 어차피 카탈로그 어휘로 잘라낸다.
어휘만 알려 주면 **이미 붙어 있는 LLM(gpt-5-mini)이 QueryPlan 을 직접 만들 수 있다.**

- 얻는 것: GPU 서비스 없이 자연어 검색
- 잃는 것: `_clap_embedding` → CLAP 의미 재정렬(`CLAP_WEIGHT = 0.25`)이 빠진다. 명시 필터는 그대로

### 6. `id_map.parquet` 생성 경로

> **2026-09-15 해소.** `music.source_track_id` 는 **만들지 않기로 했다**(Q3) — 숫자
> `music_id` 하나를 공통 키로 쓰고 곡 문자열 ID 는 구간 UID 의 `#` 앞에서 파생한다.
> 도구는 `ai_server/tools/build_id_map.py` 로 들어왔고 파생이 기본 경로다.

`work/build_id_map.py`(Jupyter)가 이미 있다 — MySQL CSV 2개를 받아 1:1 검증을 통과해야 저장한다.
**저장소로 가져오고 정기 실행 경로를 만든다.**

구간 외부 ID 는 `external_id VARCHAR(64) UNIQUE` 로 간다(Q2). UID 길이 상한을 50 → 64 로
올렸다. **DDL 반영은 DB·BE 팀 확정이 남아 있다** — 확정 전에 내보낸 표는 50자에서 잘릴 수
있고, 잘린 값끼리는 1:1 검사도 통과하므로 `build_id_map` 의 길이 경고를 무시하지 않는다.

### 7. 배포 경로 자체가 없다

> **2026-09-15 해소.** `ai_server/Dockerfile`(torch 없는 CPU 이미지, `/health` 헬스체크) ·
> `infra/ci/ai-server.yml`(오프라인 계약 테스트, 실행 건수까지 확인) ·
> `infra_k8s/base/ai.yaml`(Service·Deployment) · `infra_k8s/image/ai.Dockerfile` 이 있다.
> Spring 클라이언트는 `RecommenderClient` 로 읽기 5초·`500`/`503`/타임아웃 1회 재시도까지
> 규약대로 붙었고, `NO_CANDIDATES`(404)는 장애가 아니라 빈 목록으로 답한다.
>
> 주소 환경변수는 **`MUPID_RECOMMENDER_BASE_URL`** 이다 — `MUPID_AI_BASE_URL` 은 LLM
> 게이트웨이가 이미 쓰고 있어 거기 넣으면 대화가 추천 서버로 나간다.
> 인수인계는 `infra/AI-SERVER-K8S-HANDOVER.md`. **배치 CronJob 은 11번에 남아 있다.**

저장소 전체에서 `ai_server` · `8100` · `MUPID_AI_BASE_URL` 검색 결과 0건이다. Dockerfile,
compose 서비스, CI, 헬스체크가 전부 없다.

- `/internal` 은 인증이 없다 → **nginx 에 노출하지 않는다**
- Spring 내부 클라이언트: 읽기 타임아웃 5초, `503`·`500`·타임아웃에만 1회 재시도, 실패 시 대체 동작
- Spring 은 컨테이너, 이 서비스가 호스트면 `extra_hosts` 를 맞춰야 한다

---

## P2 — 추천이 개인화되려면 (지금은 전원 콜드스타트)

### 8. 행동 신호를 ClickHouse 조회로 (S15P21A204-217)

> **2026-09-15 해소.** `ai_server/tools/pull_signals.py` 가 `user_section_signals` 와
> `listen_events`(PLAY_START·PLAY_END)를 워터마크 증분으로 읽어 행동 로그에 쌓는다.
> 전수 스캔도 함께 걷어냈다 — `user_behavior()` 는 로그 크기·mtime 으로 캐시하고,
> 중복 확인은 워터마크 파일의 직전 회차 ID 만 본다.

`user_behavior()`(`recommender.py:773`)가 **요청마다 `events_service.jsonl` 을 전수 스캔**한다.
p50 2.22초의 주범이다(콜드스타트는 0.30초).

ClickHouse `listen_events` 는 정렬키가 `(user_id, occurred_at, event_id)`, 샤딩 키가
`cityHash64(user_id)` 라 사용자 단위 조회가 1차 접근 경로로 설계돼 있다. `user_section_signals`
MV 는 이벤트→구간 매핑과 가중치까지 이미 물고 있다.

**효과가 가장 빨리 보이는 항목이다.** Spring 의 5초 타임아웃과도 직결된다.

### 9. 라벨 재생성 배치

> **2026-09-15 해소.** `python -m ai_server.tools.run_labeler` 로 돌린다. 신호 수집
> (`pull_signals`) 뒤에 이어 붙이면 된다. Kubernetes CronJob 으로 옮길 수 있게
> 종료 코드만 쓰고 상태를 남기지 않는다.

`labels_service.parquet` 이 없으면 **오류 없이 전원 콜드스타트**가 된다(README 가 이식 중 실제로
겪었다고 적어 둔 사고). 지금 이 파일을 채우는 경로가 없다.

매 요청 조회로 대체할 수 **없다** — 한 사용자의 등급 라벨이 남의 데이터에 의존한다
(`app/labeler.py:251-256`: baseline = 그 구간을 들은 **모든 사용자**의 평균 + 전역 위치 편향
prior + shrinkage κ=5). 게다가 등급 우선순위·도달 마스킹·인접 구간 병합 같은 규칙이 15개 상수와
함께 들어 있고, **모델이 이 정의로 학습됐다.**

→ 야간 배치로 ClickHouse 에서 이벤트를 당겨 `labeler` 를 그대로 돌린다. 하루 1회면 충분하다
(최근성은 8번이 담당한다).

### 10. 온보딩 취향 유입

> **2026-09-15 해소.** `ai_server/tools/fetch_onboarding.py` 가 받아
> `serve/onboarding.parquet` 로 떨군다. **다만 어휘가 한 건도 겹치지 않아**
> (mood 0/8 · genre 0/8) 변환표를 함께 둔 상태다. 축은 mood·genre 둘뿐이고,
> 나머지 다섯은 ERD 변경이 선행이다.

`serve/onboarding.parquet` 도 읽기 전용이다(`onboarding_of()`). Spring 에 온보딩 API 가 이미
있는데 AI 쪽으로 흐르지 않는다. **이력이 없는 신규 사용자의 첫 추천 품질**이 여기 걸린다.

> **Spring 쪽은 준비됐다** — `GET /api/v1/user/{userId}/onboarding` (`ROLE_SYSTEM` 전용).
> `{user_id, submitted, submitted_at, moods[], genres[]}` 를 돌려준다. 남은 것은 이걸 당겨
> `onboarding.parquet` 를 채우는 경로다. 다만 `onboarding_seeds()` 가 읽는 7축 중 Spring 이
> 가진 것은 **`genre` · `mood` 둘뿐**이다. 나머지(`language` · `vocal_type` · `style` ·
> `tempo` · `instrument`)는 `onboarding_survey` 에 칼럼이 없다 — ERD 변경이라 팀 합의가 먼저다.

### 11. 학습·라벨 갱신을 부를 방법

> **2026-09-15 일부 해소 — 여기가 지금 P0 다.** 배치 CLI 는 생겼다:
> `python -m ai_server.tools.{pull_signals,run_labeler,fetch_onboarding}`.
> 종료 코드만 쓰고 상태를 남기지 않아 CronJob 으로 그대로 옮길 수 있다.
>
> **남은 것은 스케줄러다.** `infra_k8s/base/ai.yaml` 에는 Service·Deployment 뿐이고
> CronJob 매니페스트가 없다 — 권장 설정은 `infra/AI-SERVER-K8S-HANDOVER.md` §7 에
> 적어 뒀다(`concurrencyPolicy: Forbid`. 같은 워터마크를 두 파드가 동시에 밀면 안 된다).
> **이게 안 돌면 8·9·10 을 다 해 놓고도 오류 없이 전원 콜드스타트다** —
> `/internal/metrics` 의 `cold_start_rate` 로 확인한다.
>
> HTTP 로 열지는 여전히 미정이다. `learn_incremental` 은 함수로만 있다.

`append_event` · `run_labeler` · `learn_incremental` 은 함수로만 있고 라우터가 없다.
**쓰기·학습 절반이 HTTP 로 나와 있지 않다.** 9·10 번을 배치로 할지 엔드포인트로 열지와 같은 결정이다.

> 스케줄러는 **cron/APScheduler 로 시작**한다. 작업이 둘뿐이라 Celery 는 아직 무겁다
> (워커마다 `features_v1.npz` 의 `X` ~97MB 가 올라가 `concurrency=1` 을 강제받는다).
> 사용자별 태스크로 팬아웃하고 개별 재시도가 필요해지면 그때 Celery 로 옮긴다. 브로커는 이미 있는 Redis.

---

## P3 — 다른 화면이 기다리는 것

### 12. 일일 추천 적재

> **미해결 · 차단됨.** Spring 적재 API 가 없고 `daily_recommendation_tracks` 에
> 구간을 가리킬 컬럼도 없다. 계약 제안은 `api/docs/AI/요구 2`,
> 차단 사유는 `infra/AI-API-BLOCKERS.md`.

Spring `GET /api/v1/recommendation/daily` 는 `daily_recommendations` 를 **읽기만** 한다
(`api/knowledge/api-todo.md` #12). 채우는 잡이 필요하다.

결과를 ai_server 가 MySQL 에 직접 쓰지 않는다 — 토픽이나 API 로 넘기고 **Spring 이 적재**한다.
스키마 소유권은 Spring 에 둔다(decision/0011).

### 13. `/internal/similar` — 비슷한 구간·파생곡

> **2026-09-15 해소(AI 쪽).** `POST /internal/similar` 로 열려 있다. Spring 쪽
> 사용자용 엔드포인트는 아직 없다 — `api/docs/AI/요구 4` 와 `infra/AI-API-BLOCKERS.md`.

`moments_emb.npy` 가 이미 있어 계산은 되는데 노출이 없다. 화면은 데모 정렬 중이다
(api-todo #15).

### 13-1. 벡터 저장소 — Pinecone 을 붙이지 않는다

> **2026-09-25 갱신 (S15P21A204-459).** 이 결정은 한 번 뒤집혔다가 다시 확정됐다. 2026-09-24
> Pinecone 전환(431)이 운영에 올라갔고, 콜드스타트가 요청마다 풀 전체를 fetch 해 무료 요금제
> 이그레스를 소진했다 — 로컬 벡터로 되돌렸고 Pinecone 은 코드에서 지웠다. 규모가 커질 때의
> 벡터 DB 는 **Qdrant** 다(`infra/VECTOR-STORE-DESIGN.md`). 아래는 2026-09-16 당시의 판단이다.

> **2026-09-16 결정.** `S15P21A204-214` 는 현재 규모에서 근거가 없다.

실측으로 판단했다.

| 항목 | 값 | 출처 |
|---|---|---|
| 색인 전체 | 537MB | `SEED-RUNBOOK.md` |
| `moments_emb.npy` | 403MB / 65,540행 | `ANALYSIS-BATCH.md` |
| **추천 풀** | **10,007구간 / 1,063곡** | `SEED-RUNBOOK.md` |

403MB 중 서빙이 읽는 것은 풀 10,007행뿐이다. 나머지는 PCA·Stage 1 을 학습시킨 FMA
곡이고 추천 후보에 들어가지 않는다. 풀만 남기면 **약 80MB** 이고, 그 정도면 벡터를
외부 저장소에 둘 이유가 없다 — 지금 하는 계산은 `Z[pool_rows] @ q` 한 번이고
10,007 × 1,536 은 약 15 MFLOP, 1ms 미만이다.

Pinecone 을 넣으면 그 자리에 **추천 요청마다 네트워크 왕복과 API 키 의존**이 생긴다.
지금 p50 이 2.22초인 원인은 벡터 검색이 아니라 행동 로그 전수 스캔이다
(`S15P21A204-217`) — 저장소를 바꿔도 그 숫자는 움직이지 않는다.

**다시 꺼낼 조건.** 추천 풀이 수십만 구간이 되어 (가) 색인이 노드 디스크나 파드
기동 시간에 부담이 되거나 (나) 완전 탐색이 응답 시간에 드러나기 시작할 때.
그 전까지는 `tools/slice_index.py` 로 자른 색인을 노드에 올려 쓴다.

`infra/AI-SPRING-CONTRACT.md` 의 벡터 저장소 절(§2)은 459 에서 Qdrant 로 고쳤다.

### 14. `matched_dna_rep` 을 계약에 올리기

> **2026-09-15 해소.** `ref_music_id` · `ref_music_section_id` 로 올렸다. **사용자
> 자신의** 구간이라 보여 줘도 새는 것이 없다. 매핑표에 없으면(추천 풀 밖의 오래된 취향 등)
> 추천을 실패시키지 않고 근거만 비운다.
>
> 나머지(`moment_similarity` · `model_score` · `prob` · `clap_score` · `slot_source` ·
> `diagnostics`)는 **그대로 버린다** — 모델 점수·확률은 내보내지 않는다.

내부에는 있는데(`recommender.py:1504`) 계약이 버리는 값이다. **"당신이 저장한 그 순간과 닮아서"**
를 근거 있게 말할 수 있는 유일한 필드라 Mupid AI 패널 컨셉과 정확히 맞는다.
같이 버려지는 것: `moment_similarity` · `model_score` · `prob` · `clap_score` · `slot_source` · `diagnostics`.

---

## P4 — 실행기·운영·정리

### 15. 랭킹 실행기를 numpy 로 (torch 제거)

> **2026-09-15 완료.** `app/stage1_np.py` 가 실행기이고 이미지에 torch 가 없다.
> `tools/export_stage1_npz.py` 로 `stage1_weights.npz` 를 굽는다.

서빙에서 torch 가 하는 일은 **요청당 forward 한 번**뿐이다. 구간 임베딩은
`stage1_section.npy` 로 이미 구워져 있고 스코어링은 numpy matmul 이다(`recommender.py:1322`).

| 값 | |
|---|---|
| `in_dim` 285 (PCA 256 + side 29) | `features_info.json` |
| `DIM` 128 · `HIST_MAX` 32 | `app/stage1_torch.py:46-47` |
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

> **2026-09-15 해소.** `main.py` 의 `artifact_status()` 가 `labels_service.parquet` ·
> `logistic_flow_stage1.json` · `stage1_weights.npz` · `stage1.pt` · `id_map.parquet` 를
> **없으면 무엇이 꺼지는지(`why`)와 함께** `/health` 에 싣는다. 기동 로그에도 한 줄 남고,
> 무결성 검사보다 먼저 찍는다 — 무결성이 깨지면 거기서 돌아 나가기 때문이다.
> 랭킹 실행기 상태는 `model_backend`·`model_error` 로 따로 드러난다(15번).

지금 `check_integrity()` 는 색인 4종만 본다. `labels_service.parquet` · `id_map.parquet` ·
(추가되면) 가중치 npz 의 유무를 `/health` 에 노출한다. **없어도 서비스가 정상처럼 뜨는 것**이
이 저장소가 반복해서 밟은 함정이다.

### 17. 관측

> **2026-09-15 해소.** `app/metrics.py` · `GET /internal/metrics`. `reason_codes` 분포와
> `cold_start_rate`(+ 그 원천인 `query_source`: `labels`/`onboarding`/`none`)를 낸다 —
> 온보딩으로 뽑은 추천까지 `COLD_START` 로 집계되던 것을 가른다. `/internal/ready` 상태도
> 같은 응답에 싣는다. 실패율만 보고는 원인을 못 가르기 때문이다.
>
> 지연은 평균을 내보내지 않는다(p50·p95·max). 행동 로그 조회 시간은 따로 센다.
> **프로세스가 살아 있는 동안만 쌓인다** — 장기 추세는 수집기가 주기적으로 읽어야 한다.

`/internal/ready` 는 모니터링 대상에 올린다. **콜드스타트 비율이 곧 9·10·11번의 성적표다.**

### 18. 잔가지

- ~~`main.py:56` `@app.on_event("startup")` → lifespan~~ **해소** (lifespan 으로 바꿨다)
- 데모 시절 스키마: `NextReq`·`FeedbackReq`·`OnboardReq`·`SimReq` 는 지웠고
  **`LearnReq` 만 남아 있다** (`recommender.py:1795` — `learn()` 이 쓴다)
- ~~`recommender.py:1475` `order = order` 무의미한 대입~~ **해소**
- ~~테스트가 스모크뿐~~ **해소** — `test_contract_offline.py`(1604줄)와
  `test_pipeline_synthetic.py`(368줄)가 산출물 없이 돈다. CI 가 "전부 건너뛰고 초록불" 을
  막으려고 실제 실행 건수(≥90)까지 확인한다

---

## 2026-09-16 — 구조가 바뀌었다

ai_server 가 **상태를 들고 있지 않도록** 재설계했다. 아래 목록 중 여럿이 그 과정에서
사라지거나 성격이 바뀌었다.

| | |
|---|---|
| 온보딩 | 요청 본문으로 받는다. `fetch_onboarding` 배치 **삭제** |
| 행동 데이터 | 요청마다 ClickHouse 직접 조회 (`app/clickhouse.py`) |
| 숫자 ID 번역 | **Spring** 이 한다. 서빙에서 `id_map` 의존 제거 |
| `/internal/ready` | 매핑 검사 제거, 색인 적재만 확인 |
| 사람이 읽는 문구 | Spring 의 `RecommendReason` 이 만든다 |

배경과 계약은 `infra/AI-SPRING-CONTRACT.md` 의 "2026-09-16 계약 변경" 에 있다.

---

## 정리 — 남은 것 (`2026-09-15`)

**5(b) → 6 → 7 은 끝났다.** 실제 추천이 화면에 닿는 경로는 열렸고, 개인화에 필요한
8·9·10 도 배치 CLI 까지 왔다. P0 네 개와 15·16·17 도 해소됐다.

| # | 남은 일 | 막고 있는 것 |
|---|---|---|
| **11** | 배치 CronJob 매니페스트 | **없으면 전원 콜드스타트다.** 우리 쪽 일이고 막는 것이 없다 |
| 12 | 일일 추천 적재 | Spring 적재 API·ERD 컬럼 부재 → `infra/AI-API-BLOCKERS.md` |
| 13 | `/internal/similar` 사용자용 엔드포인트 | Spring 쪽. AI 쪽은 열려 있다 |

**11번이 유일하게 우리가 지금 할 수 있는 것이고, 효과도 가장 크다.** 나머지 둘은 BE 합의가
선행한다.

그 밖에 확인이 남은 것:

- 10번의 어휘 불일치(mood 0/8 · genre 0/8). 변환표로 덮어 둔 상태라 실제 값으로 맞는지 못 봤다
- 6번 `external_id VARCHAR(64)` DDL 반영 — 확정 전에 내보낸 표는 50자에서 잘리고,
  **잘린 값끼리는 1:1 검사도 통과한다.** `build_id_map` 의 길이 경고를 무시하지 않는다
- 5번의 CLAP 의미 재정렬은 여전히 빠져 있다 (GPU 해석기 미구축)
