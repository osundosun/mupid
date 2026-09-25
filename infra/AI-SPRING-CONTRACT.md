# Spring ↔ FastAPI 연동 계약

작성 2026-09-14 · 대상 `S15P21A204-231`

이 문서는 **계약만** 정한다. Spring 자바 구현과 JPA·DDL 수정은 백엔드 담당이고, 이 문서를
근거로 진행한다. AI 담당은 FastAPI·배치·Docker·테스트를 맡는다.

수치는 2026-09-14 기존 AI 서버(`~/rec_mupid`, 포트 8100) 실측이다. 표시 없는 값은 추정이
아니라 실측이며, 확정되지 않은 것은 **미결**로 적었다.

---

## 2026-09-23 계약 변경 — AI 는 Spring 을 부르지 않는다

**이 절이 아래 모든 절보다 우선한다.** 대상 `S15P21A204-423`.

구현: AI `app/catalog_body.py`(받기·검증), Spring `api/.../ai/catalog/`(후보 수집
`RecommenderCatalogService`, 검색 조건 `QueryPlanFilter`, 조건 어휘 `RecommenderFacets`),
적재 `music_preprocessing analysis-values`. 아래 절에서 `/internal/facets`·카탈로그 적재·
`MUPID_API_*` 를 말하는 대목은 이 절 이전의 기록이다.

### 무엇이 어긋나 있었나

§0 은 처음부터 "FastAPI 는 추천 계산만. 상태를 갖지 않는다" 였다. 2026-09-22(416)에
추천 서버가 기동 때 Spring 내부 카탈로그 API(`GET /api/v1/internal/ai/catalog`)를
ROLE_SYSTEM 리프레시 토큰으로 불러 곡·구간 메타를 통째로 들고 있게 바뀌었고, 이 문서는
고쳐지지 않았다. 하루 만에 그 구조에서 장애가 여럿 났다.

| 증상 | 원인 |
|---|---|
| 라벨러가 6일째 매일 실패 (423) | 곡 번호 매핑이 비어 재생 신호 전부 폐기 |
| 추천 서버 배포 롤백 | 토큰 7일 만료 → 카탈로그 적재 실패 → Ready 불가 |
| 카탈로그 0건 | Spring 은 snake_case, AI 는 camelCase 로 읽음 |
| 기동 순서 의존 | Spring → AI 호출에 AI → Spring 호출이 더해져 서로를 기다림 |

### 흐름

| 흐름 | 방향 | 무엇이 오가나 |
|---|---|---|
| 추천 요청 | Spring → AI | 후보와 그 값(`catalog`, 아래) + 기존 필드 |
| 행동 데이터 | AI → ClickHouse (읽기 전용) | 청취·구간 신호 (Q8, 그대로) |
| 배치의 곡 번호·구간 경계 | AI 배치 → ClickHouse `v_track_sections_current` | Spring 이 MySQL 구간을 저장할 때 함께 씀(435) |
| **AI → Spring** | **없다** | |

적재 파이프라인(`music_preprocessing`, `ai_server/tools/register_*`)이 분석 결과를 Spring
API 로 **등록**하는 것은 그대로다. MySQL 에 쓰는 길은 Spring 하나라는 계약이고, 추천
서버가 Spring 을 읽는 것과 다른 일이다.

### 역할

**Spring 이 한다** — MySQL 이 정본인 판단은 전부 여기서 끝낸다.

| 일 | 전에는 | 설명 |
|---|---|---|
| 후보 제외 | AI (카탈로그 교집합) | 삭제된 곡(`music.is_deleted`), 재생할 수 없는 곡(스트림 없음) |
| 검색 조건 필터 | AI `filter_query_pool` | 아래 "검색 조건" |
| 조건 어휘·선택지(`facets`) | AI `/internal/facets` | 코드표와 새 컬럼에서 만든다. AI 의 `/internal/facets` 는 없앤다 |

**AI 가 한다** — 벡터와 모델이 필요한 계산만.

- 벡터 검색(Pinecone), 선호 구간 묶음(DNA), 학습 점수, 로지스틱 결합
- 온보딩 씨앗 (조건을 단계적으로 풀며 씨앗 12개를 찾는 계산 — 후보 **값**이 필요해 Spring
  이 미리 걸러 줄 수 없다)
- 무드 가점(`filters.mood` 는 게이트가 아니라 재정렬 재료), CLAP 가점, 이어 듣기 흐름 감점,
  선호 BPM 게이트

### 요청 본문 `catalog`

모든 추천 경로(`next`·`daily`·`search`·`similar`)에 **필수**로 싣는다. 열 단위 배열이다 —
같은 위치가 같은 행이다. 행 객체 배열보다 약 60% 작다(실측, 아래).

```json
"catalog": {
  "tracks": {
    "track":       ["T000001", "T000002"],
    "duration_ms": [151978, 203000],
    "bpm":         [128.0, null],
    "bpm_requested_min": [120, null],
    "bpm_requested_max": [130, null],
    "language":    ["korean", null],
    "category":    ["female_korean_ballad", null],
    "genres":      [["ballad"], []],
    "moods":       [["calm", "warm"], []],
    "has_vocal":   [true, null],
    "vocal_gender":["F", null]
  },
  "sections": {
    "external_id":   ["T000001#0", "T000001#1"],
    "section_label": ["INTRO", "CHORUS"],
    "start_ms":      [0, 15500],
    "end_ms":        [15500, 45000],
    "energy":        [0.031, 0.087]
  }
}
```

| 필드 | MySQL | 값 |
|---|---|---|
| `track` | `music_section_analysis.external_id` 의 `#` 앞 | 문자열 곡 ID |
| `duration_ms` | `streams.duration_ms` | 곡 길이. AI 가 `rel_start = start_ms / duration_ms` 를 센다 |
| `bpm` | `music_analysis.bpm` **(신규)** | 측정 BPM. 모르면 `null` |
| `bpm_requested_min`·`_max` | `music_analysis.bpm_requested_min`·`_max` **(신규)** | 생성 때 요청한 BPM 범위 |
| `language` | `music_analysis.language` **(신규)** | `korean`·`english`·`japanese`… |
| `category` | `music_analysis.category` **(신규)** | 생성 스타일 원문(`female_korean_ballad`) |
| `genres`·`moods` | `music_genres`·`music_moods` → `codes.code_name` | 코드 이름 그대로 |
| `has_vocal`·`vocal_gender` | `music_analysis` | 그대로 |
| `section_label` | `music_section_analysis.section_label` | ENUM 이름 그대로(`CHORUS`) |
| `start_ms`·`end_ms` | `music_section_analysis` | 그대로 |
| `energy` | `music_section_analysis.energy` **(신규)** | 구간 RMS(0~1). 모르면 `null` |

**`sections` 에 없는 구간은 후보가 아니다.** `tracks` 에 없는 곡의 구간은 보내지 않는다.
`exclude_tracks` 는 지금처럼 따로 싣는다 — 사용자마다 다른 것을 `catalog` 에 섞으면 본문이
사용자마다 달라져 AI 가 파생 결과를 재사용할 수 없다.

**크기** (2026-09-23 실측, 1,034곡·9,098구간, 조건 없는 최악): 열 단위 본문 약 317KB
(gzip 62KB), AI 파싱 4ms. 구간이 10만을 넘으면 다시 설계한다.

**AI 는 본문 없이는 계산하지 않는다.** 같은 본문이 거듭 오면 파생 결과(곡 메타 표·온보딩
표)를 **본문 해시로** 재사용해도 된다 — 캐시이지 상태가 아니다. 본문이 바뀌면 다시 만든다.

### 검색 조건 (Spring 이 건다)

`search` 는 Spring 이 QueryPlan 의 조건으로 `catalog` 를 **걸러서** 보낸다. 옮기는 규칙은
지금 AI(`recommender.filter_query_pool`, `_canonicalize_query_tags`, `_qmatch`)와 같다.

| 조건 | 대상 값 | 비교 |
|---|---|---|
| `filters.genre` / `exclude.genre` | 곡 장르 코드 ∪ 스타일(`category` 에서 보컬·언어 접두어를 뗀 것) | 토큰 일치 |
| `filters.language` / `exclude.language` | `language` | 토큰 일치 |
| `filters.instrument` / `exclude.instrument` | 구간 악기, 없으면 곡의 구간 악기 합집합. `other`·`그 외 악기` 는 근거로 쓰지 않는다 | 토큰 일치 |
| `filters.vocal` / `exclude.vocal` | `instrumental` / `voice` + `male`·`female`·`mixed` | 토큰 일치 |
| `exclude.mood` | 곡 무드 | 토큰 일치 |
| `filters.flow` / `exclude.flow` | 구간 라벨(소문자) | 토큰 일치 |
| `filters.bpm_min`·`bpm_max` | 요청 BPM 범위의 중간값, 없으면 측정 BPM | 이상·이하 |
| `filters.tempo_direction` | 위와 같은 BPM, 기준은 `current_track` | 더 빠름/느림 |
| `filters.energy_direction` | `energy`, 기준은 `current_section`(없으면 곡 평균) | 더 높음/낮음 |

요청 값은 먼저 카탈로그 어휘로 옮긴다 — 소문자, `-`·공백은 `_` 로 바꾼 뒤 어휘에 **정확히**
있는 값만 남는다(`rock` 이 어휘에 없으면 `indie_rock` 으로 넓혀지지 않는다). 남은 값과 후보
값의 비교가 "토큰 일치" 이고, `_` 경계 부분 일치다 — 어휘의 `ballad` 는 스타일
`korean_ballad` 에 맞는다.

**어휘에 없는 필수 조건은 조용히 버리지 않는다.** `genre`·`language`·`vocal`·`instrument`
중 요청했는데 카탈로그 어휘로 하나도 옮겨지지 않은 것이 있으면 AI 를 부르지 않고 빈 결과와
`unsatisfiable` 을 돌려준다 — 조건을 몰래 풀면 사용자가 말한 것과 다른 곡이 나간다.

`filters.mood` 는 거르지 않는다(가점). `activity`·`goal` 은 카탈로그 필드가 없어 무시하고
그 사실을 진단에 남긴다. QueryPlan 원문은 지금처럼 `query_plan` 으로 함께 보낸다 — AI 가
무드 가점과 진단에 쓴다.

### MySQL 신규 컬럼

| 표 | 컬럼 | 형 | 적재 |
|---|---|---|---|
| `music_analysis` | `bpm` | `DOUBLE NULL` | `PUT /api/v1/music/{id}/analysis` |
| `music_analysis` | `bpm_requested_min`·`bpm_requested_max` | `SMALLINT NULL` | 같음 |
| `music_analysis` | `language` | `VARCHAR(32) NULL` | 같음 |
| `music_analysis` | `category` | `VARCHAR(64) NULL` | 같음 |
| `music_section_analysis` | `energy` | `DOUBLE NULL` | `PUT /api/v1/music/{id}/section` 의 줄마다 |

전부 오디오 분석·생성 조건에서 나온 값이고 쓰는 쪽이 분석 파이프라인 하나라 `music_analysis`
에 모은다. 기존 행은 AI 색인(`track_features.parquet`, `moments.parquet` 의 `rms`)에서
한 번 옮긴다.

### Pinecone 적재 대상

색인의 추천 풀(`MUPID_POOL`) 전체다. **카탈로그와 교집합을 내지 않는다** — 적재 도구도
Spring 을 부르지 않는다. 지워진 곡의 벡터가 남아도 Spring 이 보낸 `catalog` 에 없으므로
후보가 되지 않는다.

### 걷어내는 것

| 쪽 | 무엇 |
|---|---|
| AI | `app/catalog_api.py`, `app/spring_api.py` 의 카탈로그 경로, 기동 적재·TTL 갱신, `/internal/catalog/refresh`, `/internal/facets`, `index_contract.check_catalog` |
| Spring | `AiCatalogController`·`Service`·`Repository`·DTO·응답 3종·시험 (`ai/` 아래 10개 파일) |
| 설정 | AI 파드의 `MUPID_API_BASE_URL`·`MUPID_API_REFRESH_TOKEN` (`MUPID_AI_SERVER_ENV`) |

---

## 2026-09-16 계약 변경 — 숫자 ID 를 AI 에 보내지 않는다

**아래 §1·§4·§5 의 ID 규약이 바뀌었다.** 옛 내용은 맥락을 위해 남겨 두되, 지금 구현은
이쪽이다.

### 무엇이 바뀌었나

| | 옛 계약 | 지금 |
|---|---|---|
| 요청의 곡·구간 | `current_music_id: 1796` · `exclude_music_ids` | `current_track: "T000901"` · `current_section` · `exclude_tracks` |
| 응답 항목 | `music_id` · `music_section_id` · `start_time_ms` · `end_time_ms` · `section_label` · `reason` | `external_id` · `rank_order` · `score` · `reason_code` · `ref_external_id` |
| 번역 주체 | AI (`id_map.parquet`) | **Spring** (`music_section_analysis.external_id` 조회) |
| 사람이 읽는 문구 | AI 가 만들어 보냄 | **Spring** 이 `reason_code` 로 만듦 (`RecommendReason`) |
| 온보딩 | 배치(`fetch_onboarding`)가 미리 받아 둠 | **요청 본문**에 실어 보냄 |
| 행동 데이터 | 배치가 파일로 옮겨 둠 | **요청마다 ClickHouse 직접 조회** |
| `/internal/ready` | 추천 풀 **전량** 매핑 필요 | 색인 적재만 확인 |

`user_id` 는 그대로 숫자다 — ClickHouse `listen_events` 의 조회 키다.

### 왜

AI 색인의 키는 문자열(`T000901#2`)이고 MySQL 은 정수다. 둘을 잇는 것은
`music_section_analysis.external_id` 한 컬럼뿐이다. 그 번역을 **AI 가 하면** 매핑표를
보관해야 하고, 전량이 채워질 때까지 `/internal/ready` 가 503 이라 곡 한 곡만 덜 적재돼도
추천이 통째로 멈췄다.

**Spring 이 하면** 그 문제가 사라진다. Spring 은 응답에 제목·아티스트·이미지를 붙이려고
어차피 MySQL 을 조회한다 — 조회 조건을 `music_id` 에서 `external_id` 로 바꾸는 것뿐이라
쿼리 수가 같다.

결과로 AI 는 모델과 임베딩 색인만 들고 나머지는 받거나 직접 읽는다. §0 의 "FastAPI 는
추천 계산만. 상태를 갖지 않는다" 가 원래 그 뜻이었다.

### Spring 이 지켜야 할 것 (추가)

| # | |
|---|---|
| A | MySQL 에 없는 `external_id` 는 **버리고 센다.** 지어내지 않는다 |
| B | 응답 조회는 순서를 보장하지 않는다. `rank_order` 로 다시 정렬한다 |
| C | 모르는 `reason_code` 에서 **빈 문자열을 화면에 내보내지 않는다** — 폴백 문장을 둔다 |
| D | 온보딩은 **코드표 이름 그대로** 보낸다. AI 태그로 옮기는 것은 저쪽 일이다 |

### 남은 것

- `id_map.parquet` 은 **배치에만** 남는다. ClickHouse 행동 표가 숫자 `track_id` 를 쓰기
  때문에 `pull_signals` 가 필요로 한다. 서빙은 읽지 않는다 (구간 표를 쓰던 `push_sections` 는
  451 에서 지웠다 — 구간 표는 Spring 이 쓴다)
- `labels_service.parquet`(선호 구간 등급)은 **배치 유지**. 전체 사용자 통계로 정해지고
  모델이 그 정의로 학습돼 있어, 요청마다 한 사람치로 바꾸면 점수 분포가 달라진다

---

## 0. 자리 배치

```
Frontend
   │  공개 JSON (musicId, musicSectionId, recommendationId)
   ▼
Spring Boot 공개 API        ← 인증·권한·영속화는 전부 여기
   │  내부 JSON (music_id, music_section_id, recommendation_id)
   ▼
FastAPI AI 내부 API         ← 추천 계산만. 상태를 갖지 않는다
   ├─ MySQL          곡·구간 메타 (읽기)
   ├─ Pinecone       MERT 구간 임베딩
   └─ 로컬 산출물     랭킹 모델·색인
```

Frontend 는 FastAPI·Pinecone·MySQL·ClickHouse 를 직접 부르지 않는다. FastAPI 는 Spring
에서만 호출한다.

---

## 1. 식별자와 변환 규칙

> **2026-09-16 변경됨.** 아래 표의 내부 JSON 칸은 옛 계약이다. 지금은 AI 에
> 숫자 ID 를 보내지 않는다 → 위 "2026-09-16 계약 변경".

| 대상 | MySQL | 내부 JSON (Spring→AI) | 공개 JSON (FE) | Java |
|---|---|---|---|---|
| 곡 | `music.music_id` **INT** | `music_id` | `musicId` | `Integer` |
| 구간 | `music_section_analysis.music_section_id` **INT** | `music_section_id` | `musicSectionId` | `Integer` |
| 추천 요청 | `daily_recommendations.recommendation_id` **BIGINT** | `recommendation_id` | `recommendationId` | `Long` |

- 내부 JSON 은 `snake_case`, 공개 JSON 은 `camelCase`. 변환은 Spring 이 한다.
- 모든 시간은 **0 이상의 정수 밀리초**(`start_time_ms`, `end_time_ms`, `position_ms`).
  초→밀리초 변환식은 `round(seconds * 1000)` 이며, `.5` 는 파이썬 `round()` 규칙대로
  짝수 쪽으로 붙는다(`0.0005s → 0ms`, `0.0015s → 2ms`). Spring 이 다른 반올림을 쓰면
  1ms 씩 어긋난다.
- `end_time_ms > start_time_ms` 를 양쪽에서 검증한다.
- 과거 표기 `song`·`songId`·`songs` 는 쓰지 않는다. `music`·`musicId`·`music_id` 로 통일한다.

> **확정 (2026-09-15)**
> · 구간 PK 는 `music_section_id` 로 본다 (Q1). AI 계약 필드명은 그대로다 —
>   실제 DB 컬럼이 `music_section_analysis_id` 로 남더라도 값만 맞으면 되고,
>   내보낼 때 별칭으로 맞춘다. **DB·BE 팀 확정이 남아 있다.**
> · 구간 외부 ID 컬럼은 `external_id VARCHAR(64) UNIQUE` 로 본다 (Q2).
>   실측 최댓값이 36자인데 flow ID 는 제목 길이만큼 늘어나므로 50 으로는 모자란다.
>   **DB·BE 팀 확정이 남아 있다.**
> · 곡 문자열 ID 를 위한 `music.source_track_id` 는 **만들지 않는다** (Q3).
>   숫자 `music_id` 하나를 공통 키로 쓰고, 문자열은 AI 안에만 둔다.

### 레거시 문자열 ID

AI 내부에는 문자열 곡 ID(`T000148`, `JAMENDO_1048629`)와 구간 ID(`T000148#3`)가 남아 있다.
**내부 API 응답에 나가지 않는다.** 호환용으로만 보존하며 이름은 `source_track_id`,
`source_section_uid` 다.

숫자 ↔ 문자열 매핑은 AI 쪽 `data/proc/mupid/id_map.parquet` 한 곳에서만 한다.
`source_track_id` 는 DB 컬럼이 아니라 **구간 UID 의 `#` 앞을 잘라 만든 값**이다(Q3).
`music_id` 는 파생하지 않는다 — Spring 이 발급한 값을 그대로 받는다(Q4).

| 컬럼 | 뜻 |
|---|---|
| `source_track_id` | 레거시 곡 ID |
| `music_id` | MySQL `music.music_id` |
| `source_section_uid` | 레거시 구간 ID |
| `music_section_id` | MySQL `music_section_analysis.music_section_id` |

`music_id ↔ source_track_id` 와 `music_section_id ↔ source_section_uid` 는 **1:1** 이어야
한다. 위반(1:N, N:1, 빈 값, 비정수, 0 이하)이 하나라도 있으면 적재를 거부하고 서비스가
준비 완료로 올라오지 않는다. **숫자 ID 를 AI 가 만들어내지 않는다.**

---

## 2. Pinecone

### vector ID

```
moment:{music_section_id}:mert-v1
예) moment:15231:mert-v1
```

`music_section_id` 와 임베딩은 **1:1**. ID 에서 `music_section_id` 를 그대로 역추적할 수
있고, 거기서 MySQL `music_id` 로 이어진다.

### metadata

| 키 | 타입 | 용도 |
|---|---|---|
| `music_id` | int | 곡 단위 중복 제거·필터 |
| `music_section_id` | int | 역추적 |
| `section_label` | string | 구간 종류 필터 |
| `start_time_ms` / `end_time_ms` | int | 재생 위치 |
| `model_version` | string | `mert-v1` |

벡터 차원은 **1536** (MERT 구간 임베딩, 실측).

### 저장 순서와 실패 처리

```
1) MySQL 구간 메타 저장          → music_section_id 발급
2) vector ID 생성                moment:{music_section_id}:mert-v1
3) Pinecone upsert
4) MySQL external_id 에 vector ID 저장
5) 양쪽 검증 (개수·역추적)
```

- **Pinecone 저장이 실패하면 그 구간을 성공으로 표시하지 않는다.** 4·5단계를 진행하지 않고
  해당 곡을 재처리 대상으로 남긴다.
- 재실행은 **같은 vector ID 로 upsert** 한다. `music_section_id` 가 같으면 ID 가 같으므로
  중복 벡터가 생기지 않는다.
- 반대로 MySQL 만 성공하고 Pinecone 이 비어 있으면 추천에서 그 구간이 조용히 빠진다.
  5단계 검증이 이를 잡는다.

---

## 3. AI 배치 (`ai_batch`) 입력·출력

### 입력

| 항목 | 값 |
|---|---|
| `music_id` | MySQL 이 발급한 INT |
| 음원 | SeaweedFS 경로 또는 로컬 파일 |
| 재처리 여부 | 단계별 성공 기록 |

한 곡 최초 등록은 아래 순서다. `music_preprocessing` 과 `ai_batch` 는 `music_id` 확보 뒤
**병렬** 실행할 수 있고, Python 환경과 Docker 이미지는 분리한다.

```
음악 메타 등록 → music_id 확보
   ├─ music_preprocessing : Opus/RTP + SeaweedFS
   └─ ai_batch       : All-In-One → Essentia → MERT
결과 검증 → 서비스 사용 가능
```

| 상태 | 조치 |
|---|---|
| 청킹 성공 · AI 실패 | AI 만 재실행 |
| AI 성공 · 청킹 실패 | 청킹만 재실행 |
| 원본 변경 | 양쪽 재실행 |
| 모두 완료 | 건너뜀 |

### 출력 (MySQL 저장 필드)

**`music_section_analysis`** — 곡당 구간. 실측 곡당 중앙값 9구간(최소 1, 최대 25),
구간 길이 중앙값 17.4초(최소 3.6, 최대 72.0).

| 컬럼 | 값 | 근거 |
|---|---|---|
| `music_id` | 입력 그대로 | |
| `section_label` | ENUM 9종 | 아래 매핑 |
| `index` | 0부터 | All-In-One 구간 순서 |
| `start_time_ms` / `end_time_ms` | 정수 ms | `round(sec*1000)` |
| `external_id` | `moment:{music_section_id}:mert-v1` | 2장 |

**구간 종류 매핑** — AI 는 소문자 8종을 만든다(실측). ERD ENUM 은 9종이다.

| AI 출력 | ERD ENUM |
|---|---|
| `intro` `verse` `chorus` `bridge` `break` `inst` `solo` `outro` | 대문자로 그대로 (`INTRO` `VERSE` …) |
| — | `PRE_CHORUS` 는 **AI 가 만들지 않는다** |

변환 규칙은 `label.upper()` 하나면 충분하다. `PRE_CHORUS` 행은 생기지 않는다.

**`music_analysis`** — 곡 단위. Essentia 결과.

| 컬럼 | AI 값 | 어휘 |
|---|---|---|
| `has_vocal` | `voice` / `instrumental` | 3종(`unknown` 포함) |
| `vocal_gender` | `male` / `female` | 3종(`unknown` 포함) |
| BPM | All-In-One·Essentia 두 값 보유 | 어느 쪽을 쓸지 **미결** |

**태그 어휘** (2026-09-14 실측, 추천 풀 1,063곡 기준)

| 대상 테이블 | AI 어휘 수 | 예 |
|---|---|---|
| `genres` ← `music_genres` | **91종** | `K-pop`, `Ballad`, `Alternative Rock`, `Soundtrack` |
| `moods` ← `music_moods` | **31종** | `energetic`, `love`, `happy`, `melodic`, `relaxing`, `epic` |
| `instruments` ← `music_analysis_instruments` | **22종** | `drums`, `bass`, `piano`, `synthesizer`, `guitar` |

- 장르·무드·악기는 **`codes` 테이블에 합치지 않고 위 별도 테이블을 쓴다**(2026-09-14 확정).
  현재 JPA 는 `codes` 를 쓰고 있어 백엔드 수정이 필요하다 →
  [open-questions B8](../api/knowledge/open-questions.md).
- AI 원본 장르는 Discogs400 의 `Parent---Child` 형식(`Pop---K-pop`)이고, 서비스는 `Child`
  만 쓴다. `genres` 테이블에 어떤 형태로 넣을지는 **미결**.
- 위 어휘를 `genres`/`moods`/`instruments` 에 선적재할지, 배치가 없으면 만들지는 **미결**.

### 산출물 (추천 서빙용, 실측 총 약 515MB)

| 파일 | 크기 | 행 |
|---|---|---|
| `moments_emb.npy` | 385MB | 65,540 × 1536 |
| `features_v1.npz` | 93MB | 65,540 × 285 |
| `stage1_section.npy` | 33MB | 65,540 × 128 |
| `moments.parquet` | 3.2MB | 65,540 |
| `stage1.pt` | 357KB | 랭킹 모델 |

네 색인은 **행 번호로 서로를 가리킨다.** 버전이 섞이면 엉뚱한 곡을 추천하면서도 서비스는
정상 기동한다(2026-09-09~10 에 두 번 발생). 그래서 기동 시 무결성 검증이 필수다(6장).

---

## 4. FastAPI 내부 API

> **2026-09-16 변경됨.** 요청·응답 필드가 문자열 ID 기반으로 바뀌었고,
> `/internal/facets`·`/internal/similar`·`/internal/metrics` 가 추가됐다.
> 현재 계약은 `ai_server/README.md` 의 "API" 절이 기준이다.

전 경로 `/internal` 아래. Spring 에서만 호출한다.

| 메서드 | 경로 | 용도 |
|---|---|---|
| POST | `/internal/recommendations/daily` | 오늘의 추천 (곡 목록) |
| POST | `/internal/recommendations/next` | 이어 듣기 |
| POST | `/internal/recommendations/search` | 자연어 검색 |
| GET | `/internal/ready` | 실서비스 준비 완료 |
| GET | `/internal/id-map` | 숫자 ID 매핑 적재 상태 |
| GET | `/health` | 기동 여부 |

### 요청

공통 필드 — 세 경로가 모두 갖는다.

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `recommendation_id` | integer | ✅ | 1 ~ 2⁶³−1 |
| `user_id` | integer | ✅ | ≥ 1 |
| `limit` | integer | | 1~50, 기본 5 |
| `exclude_music_ids` | integer[] | | 기본 `[]` |

`next` 추가 필드

| 필드 | 타입 | 필수 | 비고 |
|---|---|---|---|
| `current_music_id` | integer \| null | | |
| `current_music_section_id` | integer \| null | | |
| `position_ms` | integer \| null | | ≥ 0. **현재 추천 계산에 쓰지 않는다** |

`search` 추가 필드

| 필드 | 타입 | 필수 | 제약 |
|---|---|---|---|
| `query` | string | ✅ | 1~500자 |

**타입은 엄격하다.** `"148"` 같은 문자열, `3.5` 같은 실수, 정의되지 않은 필드는 전부
`400 INVALID_REQUEST` 로 거부한다. Spring 이 숫자를 문자열로 싸서 보내면 실패한다.

### 응답 (세 경로 공통)

```json
{
  "recommendation_id": 5001,
  "items": [
    {
      "music_id": 148,
      "music_section_id": 1204,
      "start_time_ms": 55000,
      "end_time_ms": 76000,
      "rank_order": 1,
      "score": 0.87,
      "reason_code": "SIMILAR_PREFERRED_MOMENT"
    }
  ],
  "shortfall": 0,
  "model_version": "mupid-stage1-v1"
}
```

| 필드 | 타입 | 필수 | 뜻 |
|---|---|---|---|
| `recommendation_id` | integer | ✅ | 요청 값 그대로 |
| `items` | object[] | ✅ | `rank_order` 1부터 연속 |
| `items[].music_id` | integer | ✅ | |
| `items[].music_section_id` | integer | ✅ | |
| `items[].start_time_ms` | integer | ✅ | ≥ 0 |
| `items[].end_time_ms` | integer | ✅ | > `start_time_ms` |
| `items[].rank_order` | integer | ✅ | ≥ 1 |
| `items[].score` | number | ✅ | 클수록 상위 |
| `items[].reason_code` | string | ✅ | 아래 4종 |
| `shortfall` | integer | ✅ | `limit` 에 모자란 수 |
| `model_version` | string | ✅ | 현재 `mupid-stage1-v1` |

**`reason_code`** — 4종. 새 값이 늘 수 있으므로 Spring 은 모르는 값을 만나면 실패하지 말고
그대로 통과시킨다.

| 값 | 뜻 |
|---|---|
| `SIMILAR_PREFERRED_MOMENT` | 사용자가 좋아한 구간과 유사 |
| `QUERY_SEMANTIC_MATCH` | 자연어 질의와 의미적으로 일치 |
| `EXPLORE_NEW` | 탐색 슬롯 |
| `COLD_START` | 취향 데이터 부족 |

**곡 중복 없음** — 같은 `music_id` 는 응답에 한 번만 나온다. 같은 곡의 여러 구간이 후보에
있으면 최종 점수가 가장 높은 구간 하나만 남긴다. Spring·FE 는 여러 응답을 누적할 때
**이미 가진 `music_id` 를 다시 추가하지 않는다.**

**후보가 모자랄 때** 임의의 곡을 채우지 않는다. 실제 개수만 담고 `shortfall` 에 부족분을
적는다.

### 오류

```json
{ "error_code": "MUSIC_ID_MAPPING_NOT_FOUND",
  "message": "숫자 music_id 매핑을 찾을 수 없습니다." }
```

| `error_code` | HTTP | 언제 | Spring 권장 |
|---|---|---|---|
| `INVALID_REQUEST` | 400 | 타입·범위·미정의 필드 | 버그. 로그 후 5xx |
| `INVALID_TIME_RANGE` | 400 | 구간 시간이 뒤집힘 | 데이터 결함 신고 |
| `MUSIC_ID_MAPPING_NOT_FOUND` | 404 | 숫자 곡 ID 매핑 없음 | 해당 곡 미적재 |
| `SECTION_ID_MAPPING_NOT_FOUND` | 404 | 숫자 구간 ID 매핑 없음 | 위와 같음 |
| `NO_CANDIDATES` | 404 | 조건에 맞는 곡이 없음 | **빈 목록으로 FE 전달** |
| `INDEX_NOT_READY` | 503 | 색인·매핑 미완 | 재시도 대상 |
| `INTERNAL_ERROR` | 500 | 그 밖 | 재시도 대상 |

`NO_CANDIDATES` 는 **장애가 아니다.** 명시 조건(장르·언어·보컬 성별 등)에 맞는 곡이 없을
때 조건을 몰래 풀어 무관한 곡을 주는 대신 빈 결과를 준다. 메시지는
`"해당되는 노래가 없습니다."` 이고, Spring 은 이를 200 + 빈 배열로 FE 에 전달한다.

### 타임아웃·재시도

| 항목 | 값 | 근거 |
|---|---|---|
| 연결 타임아웃 | 2초 | |
| 읽기 타임아웃 | **5초** | 실측 최대 2.35초의 약 2배 여유 |
| 재시도 | `503`·`500`·타임아웃만 **1회** | |
| 재시도 간격 | 300ms 고정 | |
| 재시도 금지 | `400`·`404` | 다시 보내도 같은 결과 |

추천은 조회성이라 재시도가 안전하다. 단 `recommendation_id` 를 그대로 다시 보내
같은 요청임을 알 수 있게 한다.

**응답 시간 실측 (2026-09-14, 12회)**

| 사용자 | p50 | p95 | 최대 |
|---|---|---|---|
| 청취 이력 없음 (콜드스타트) | 0.30초 | 0.30초 | 0.31초 |
| 청취 이력 있음 | **2.22초** | 2.23초 | 2.35초 |

이전 문서의 "0.26초" 는 **이력이 없는 사용자만** 잰 값이었다. 이력이 있는 사용자가 느린
이유는 요청마다 이벤트 로그(현재 22,084건)를 **네 번 전수 스캔**하기 때문이고, 로그가
커질수록 선형으로 늘어난다. 지금 값으로 3초 타임아웃을 두면 이력이 쌓인 사용자부터
추천이 끊긴다.

> **추천 성능 개선이 필요하다** — 사용자별 행동 집계를 캐시하거나 파일을 나누면 해결된다.
> 추천 점수·랭킹을 바꾸지 않는 범위의 개선이며 `S15P21A204-217` 에서 다룬다.
> 개선 후에는 읽기 타임아웃을 다시 낮출 수 있다.

### FastAPI 장애 시 대체 동작

AI 가 죽어도 서비스 전체가 멈추지 않아야 한다. 우선순위 순으로:

1. 최근 성공한 `daily_recommendations` 를 그대로 보여준다
2. 그것도 없으면 인기순·최신순 등 AI 없는 목록
3. 그것도 없으면 빈 목록 + "추천을 준비 중" 안내

**금지**: 무작위 곡으로 채우기, 오래된 추천을 새 추천인 것처럼 표시하기.

### `/health` 와 `/internal/ready`

| | `/health` | `/internal/ready` |
|---|---|---|
| 뜻 | 프로세스가 떠 있다 | 실서비스 요청을 받을 수 있다 |
| 실패 조건 | 응답 없음 | 색인 불일치 또는 매핑 미완 |
| 쓰는 곳 | 컨테이너 liveness | 배포 게이트 · readiness |

```json
GET /internal/ready   200
{ "ready": true, "tracks": 1063, "sections": 10007 }

GET /internal/ready   503
{ "error_code": "INDEX_NOT_READY",
  "message": "숫자 ID 매핑 미완료 — 곡 1063건, 구간 10007건 미매핑" }
```

**현재 503 이다.** MySQL 적재가 없어 매핑이 0건이기 때문이며, 가짜 매핑으로 통과시키지
않는다.

---

## 5. Spring DTO 예시

> **2026-09-16 변경됨.** 실제 DTO 는
> `api/src/main/java/com/osundosun/mupid/api/ai/client/RecommenderDto.java` 다.

작성 예시이며 백엔드가 프로젝트 규약에 맞춰 조정한다.

```java
// 요청 — 내부 JSON 은 snake_case 다. @JsonProperty 로 명시한다.
public record AiNextRequest(
        @JsonProperty("recommendation_id") Long recommendationId,
        @JsonProperty("user_id") Integer userId,
        @JsonProperty("limit") Integer limit,
        @JsonProperty("exclude_music_ids") List<Integer> excludeMusicIds,
        @JsonProperty("current_music_id") Integer currentMusicId,
        @JsonProperty("current_music_section_id") Integer currentMusicSectionId,
        @JsonProperty("position_ms") Integer positionMs) {}

// 응답
public record AiRecommendationItem(
        @JsonProperty("music_id") Integer musicId,
        @JsonProperty("music_section_id") Integer musicSectionId,
        @JsonProperty("start_time_ms") Integer startTimeMs,
        @JsonProperty("end_time_ms") Integer endTimeMs,
        @JsonProperty("rank_order") Integer rankOrder,
        @JsonProperty("score") Double score,
        @JsonProperty("reason_code") String reasonCode) {}

public record AiRecommendationResponse(
        @JsonProperty("recommendation_id") Long recommendationId,
        @JsonProperty("items") List<AiRecommendationItem> items,
        @JsonProperty("shortfall") Integer shortfall,
        @JsonProperty("model_version") String modelVersion) {}

public record AiErrorResponse(
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("message") String message) {}
```

`recommendationId` 만 `Long`, 나머지 ID 는 `Integer` 다(1장).

### 호출 순서 — 오늘의 추천

```
1) Spring: daily_recommendations INSERT  → recommendation_id(BIGINT) 발급
2) Spring → FastAPI POST /internal/recommendations/daily
3) FastAPI: 추천 계산 → items
4) Spring: daily_recommendation_* 에 items 저장 (music_id, rank_order, reason)
5) Spring → FE: musicId 등 camelCase 로 변환해 전달
```

`recommendation_id` 를 **먼저 발급**해야 AI 응답과 저장 레코드가 이어진다. AI 는 이 값을
그대로 돌려줄 뿐 스스로 만들지 않는다.

구간 정보는 영속하지 않는다 — 오늘의 추천은 곡 목록이고, 구간은 재생 시점에 AI 가 돌려주는
값을 쓴다([open-questions B7](../api/knowledge/open-questions.md)).

---

## 6. 기동 시 무결성 검증

`ai_server` 는 뜨자마자 아래를 확인하고, 하나라도 어긋나면 `/internal/ready` 를 실패로 둔다.

| 검사 | 실패 시 |
|---|---|
| 색인 4종 행 수 일치 (`moments`·`emb`·`features`·`stage1`) | ready 실패 |
| `emb_row` 가 0부터 연속 | ready 실패 |
| 랭킹 모델 로드 | ready 실패 |
| `id_map` 1:1 | 적재 거부 + ready 실패 |
| 추천 풀 전체 매핑 완료 | ready 실패 |

현재 실측: 4종 모두 **65,540행**, `emb_row` 연속 확인.

---

## 7. 환경변수

**변수명만 정한다. 실제 값은 `.env` 또는 배포 환경에 직접 넣는다. 값을 문서·로그·커밋에
남기지 않는다.** 저장소 규약대로 `MUPID_` 접두사를 쓴다
([decision/0002](../api/knowledge/decision/0002-mupid-env-prefix.md)).

### Spring 이 AI 를 부를 때

```
MUPID_RECOMMENDER_BASE_URL=          # `/internal` 앞까지. 비우면 곡 추천만 503, 대화는 된다
MUPID_RECOMMENDER_INTERNAL_TOKEN=    # 아래 MUPID_AI_INTERNAL_TOKEN 과 같은 값
```

> **`MUPID_AI_BASE_URL` 에 추천 서버 주소를 넣으면 안 된다.** 그 이름은 LLM
> 게이트웨이가 이미 쓰고 있어(`application.yaml` 의 `ai.base-url`), 거기에 넣으면
> **대화가 추천 서버로 나간다.** 대화와 추천은 서로 다른 서버다.

타임아웃은 환경변수가 아니라 `application.yaml` 에 값으로 둔다 — 배포마다 달라지면
장애 재현이 어렵다. 현재 `connect-timeout: 2s`, `read-timeout: 5s`, 그리고 500·503·
타임아웃에 한 번만 재시도(`retry-once`)다. 400·404 는 다시 불러도 같은 답이므로 재시도하지
않는다.

### AI 서버 (`ai_server`)

```
MUPID_AI_PORT=                 # 기본 8100
MUPID_AI_ARTIFACT_DIR=         # 산출물 디렉터리. 없으면 /internal/ready 가 거짓이다
MUPID_AI_INTERNAL_TOKEN=       # 비우면 헤더 검사를 하지 않는다 (기본 꺼짐)
MUPID_ID_MAP=                  # 숫자 ID 매핑표 경로
MUPID_POOL=                    # 추천 후보 풀 (기본 flow)
MUPID_POOL_EXTRA=              # 추가 풀. 쉼표로 구분 (기본 비움 — jamendo 는 서비스 곡이 아니다)
MUPID_COMBINER=                # 결합기 파일명
```

**토큰은 양쪽을 동시에 설정해야 한다.** AI 서버에만 `MUPID_AI_INTERNAL_TOKEN` 을 넣고
Spring 에 `MUPID_RECOMMENDER_INTERNAL_TOKEN` 을 주지 않으면 **추천 호출이 전부 401** 이
된다. 배포 설정만으로 조용히 죽는 형태라 한 쌍으로 다룬다.

### AI 가 온보딩 답변을 받을 때

감상 이력이 없는 사용자의 첫 추천은 온보딩 답변으로 만든다. Spring 이 아래 배치 전용
엔드포인트를 연다.

```
GET /api/v1/user/{userId}/onboarding     ROLE_SYSTEM | ROLE_ADMIN
→ { data: { user_id, submitted, submitted_at, moods[], genres[] } }
```

미제출은 404 가 아니라 **200 + `submitted:false`** 다. "아직 안 한 사람"과 "없는 사용자"는
로그 취급이 달라야 하므로 갈라져 있다. `submitted` 로 판단한다.

받는 쪽은 `ai_server/tools/fetch_onboarding.py` 이고 `serve/onboarding.parquet` 로 떨군다.

```
MUPID_API_BASE_URL=          # Spring 주소 (`/api/v1` 앞까지)
MUPID_API_REFRESH_TOKEN=     # ROLE_SYSTEM 계정의 리프레시 토큰
```

액세스 토큰은 `GET /api/v1/auth` 로 그때그때 받아 **메모리에만** 둔다. 파일·로그에 남기지
않는다.

**알아 둘 것 두 가지.**

1. **일곱 축 중 둘만 온다.** `onboarding_seeds()` 는 language·vocal_type·style·tempo·
   genre·mood·instrument 를 읽지만 `onboarding_survey` 에는 **mood·genre** 밖에 없다.
   나머지 다섯은 빈 값으로 채워 넣는다 — 빈 축은 필터를 건너뛰므로 동작에는 지장이 없고,
   대신 **개인화가 두 축으로 줄어든다.** 나머지를 넣으려면 ERD 변경이 먼저다.
2. **단건 조회다.** 사용자 수만큼 두드려야 한다. 전체 백필이 필요해지면 벌크 조회를
   요청한다 — BE 쪽에서 별도 티켓으로 얹는 것을 열어 두었다.

**어휘는 한 건도 겹치지 않는다** (2026-09-15 실측).

| 축 | Spring 코드표 | AI 카탈로그 | 그대로 넘겼을 때 일치 |
|---|---|---|---|
| mood | 8종 · 한국어 (`잔잔한`·`벅찬`…) | 31종 · 영문 태그 (`relaxing`·`epic`…) | **0 / 8** |
| genre | 8종 · 대분류 (`Rock`·`Jazz`…) | 91종 · 세분류 (`Alternative Rock`·`Smooth Jazz`…) | **0 / 8** |

변환 없이 넘기면 씨앗이 비고, 씨앗이 비면 **연결은 되는데 개인화가 0 이 된다.** 그래서
`fetch_onboarding.py` 가 옮겨 담는다.

- **mood** 는 뜻으로 이어야 해서 `MOOD_MAP` 표로 적는다 (`잔잔한` → relaxing·meditative·
  slow·background).
- **genre** 는 대분류 → 세분류라 이름 포함으로 자동 확장한다 (`Rock` → Alternative Rock·
  Pop Rock·Indie Rock·Hard Rock). 이름으로 못 잇는 것만 `GENRE_EXTRA` 에 적는다
  (`Electronic` → House·Downtempo·Synthwave…).
- 사람이 고른 **원문을 `mood_raw`·`genre_raw` 에 함께 남긴다.** 변환이 어긋났을 때
  되짚을 근거가 없으면 원인을 못 찾는다.

변환표가 카탈로그와 어긋나면 그 답변은 조용히 사라진다. 그래서 **8종이 전부 비지 않는지**
테스트가 지킨다 (`test_every_spring_code_reaches_the_catalog`). 카탈로그 어휘가 바뀌면
이 테스트가 먼저 깨진다.

### 구간 표는 Spring 이 넣는다 (선행 조건)

**이것이 없으면 행동 데이터가 통째로 버려진다.** `mv_user_section_signals` 는
`dictGet(dict_track_sections, ...)` 로 재생 위치가 어느 구간인지 찾고, 못 찾으면 그
이벤트를 버린다. 2026-09-15 에 실제로 재현했다 — 구간 표가 비어 있으니 좋아요를 눌러도
`user_section_signals` 에 한 줄도 안 남았고, 구간 1건을 넣자 바로 통과했다.

> **2026-09-25 (S15P21A204-451) — AI 는 이 표를 쓰지 않는다.** 예전에는 읽기 전용 원칙(Q8)의
> 예외로 `ai_server/tools/push_sections.py` 가 색인(`moments.parquet`)을 `id_map` 으로 번역해
> HTTP 로 직접 넣었다. 쓰기 관문(Spring)을 우회했고 원천이 MySQL 이 아니라 색인이라 두 저장소가
> 어긋날 여지가 있어 지웠다. AI 는 이제 **어떤 ClickHouse 표에도 쓰지 않는다**
> (`test_ai_server_never_writes_track_sections`).

쓰는 쪽은 Spring 하나다. 요구 1(`PUT /api/v1/music/{musicId}/section`)이 MySQL 에 구간을 넣고,
같은 트랜잭션에 `analytics_sync_pending` 행을 남긴 뒤, 커밋되면 `track_sections_local` 에 쓴다.
실패하면 그 행을 재시도 작업(1분)이 MySQL 의 지금 구간으로 메우고, 대조 작업(매일 04:40)이
레플리카마다 MySQL 과 비교해 어긋난 곡을 다시 넣는다 → `api/knowledge/architecture/analytics-sync.md`.

| 컬럼 | 값 |
|---|---|
| `track_id` | MySQL `music_id` |
| `section_uid` | 문자열 구간 ID(`T001001#3`). MySQL `external_id` 와 같은 값이다 |
| `analysis_version` | MySQL `music_section_analysis.analysis_version` (요청이 싣는 값, 지금 `v1`). `v_track_sections_current` 가 `v1` 만 고른다 |
| `fn` | MySQL `section_label` |
| `music_section_analysis_id` | MySQL 구간 PK |

`peak_ms` 는 2026-09-22 에 없앴다. MySQL 에 대응 컬럼이 없고 구간 안 정점을 산출한 적도
없어 줄곧 시작점이 들어가 있었다.

딕셔너리는 `LIFETIME(300~600초)` 로 10분 안에 새 구간을 집는다. 그 사이 들어온 이벤트는
버려진다 — 즉시 반영이 필요한 시나리오(시드·검증)는 `SYSTEM RELOAD DICTIONARY ON CLUSTER
mupid_all dict_track_sections` 를 **두 노드 모두**에 건다(한쪽만 하면 그 샤드로 간 조회가
구간을 못 찾는다 — 초기화 직후 ch-02 만 `NOT_LOADED` 였다).

### AI 배치가 행동 데이터를 읽을 때 (Q8)

AI 정기 배치가 **ClickHouse 를 읽기 전용으로 직접 조회한다.** Spring 이 밀어 주거나 Kafka 를
구독하지 않는다 — 배치가 증분으로 되짚어야 하는데, 밀어 주는 쪽은 재처리 때 과거 구간을
다시 받을 방법이 없다.

```
MUPID_CLICKHOUSE_URL=          # 읽기 전용 계정을 쓴다
MUPID_CLICKHOUSE_USER=
MUPID_CLICKHOUSE_PASSWORD=
```

도구는 `ai_server/tools/pull_signals.py` 다.

```
user_section_signals ──(워터마크 증분)──> events_service.jsonl ──> run_labeler
```

**등급 판정을 여기서 하지 않는다.** ClickHouse 가 구간을 판정하고 가중치까지 매겼지만,
등급은 라벨러 한 곳에서만 만든다 — 같은 구간을 들은 전체 사용자 평균·위치 편향·표본
보정이 함께 들어가야 하기 때문이다. 이 도구는 `signal` 을 **UI 이름 그대로** 적재하고,
라벨러 이름으로 바꾸는 일은 이미 있는 `UI_TO_LABELER` 에 맡긴다. 두 벌로 만들면 어긋난다.

두 번 돌려도 같다. 워터마크는 **성공했을 때만** 전진하고, 이벤트 ID 를 내용(사용자·구간·
신호·시각)에서 만들어 겹치면 건너뛴다. 경계의 같은 밀리초는 워터마크만으로 못 막으므로
ID 쪽이 최종 방어선이다.

### 운영 주기

| 작업 | 언제 | 명령 |
|---|---|---|
| 구간 표 적재·대사 | — | **AI 작업이 아니다.** Spring 이 적재 커밋 뒤 쓰고, 재시도(1분)·대조(매일)가 맞춘다 |
| 신호 수집 | 짧은 주기 | `pull_signals` |
| 라벨·선호 갱신 | 신호 수집 뒤 | `run_labeler` |

**재생할 때마다 도는 것은 없다.** 구간 표는 곡이 늘거나 경계가 바뀔 때만 변하고, 재생은
아무것도 바꾸지 않는다.

**쓰기 권한을 주지 않는다.** AI 는 어떤 DB 에도 직접 쓰지 않는다
([decision/0011](../api/knowledge/decision/0011-jpa-manages-schema.md)).

#### 아직 연결되지 않은 것

아래는 **계획이며 현재 코드에 참조가 없다**(2026-09-15 확인). 벡터 검색은 Pinecone 이
아니라 메모리의 NumPy 행렬로 전수 비교하고, 카탈로그는 MySQL 이 아니라 Parquet 에서
읽는다. 곡 수가 적어 전수 비교가 정확도 손실 없이 충분하기 때문이다. 붙일 때를 위해
이름만 남겨 둔다.

```
MUPID_PINECONE_API_KEY=        # 미연결
MUPID_PINECONE_INDEX_HOST=     # 미연결
MUPID_PINECONE_NAMESPACE=      # 미연결
MUPID_MYSQL_URL=               # 미연결 — 읽을 계획도 없다 (Q8: 행동 데이터는 ClickHouse)
MUPID_MYSQL_USER=              # 미연결
MUPID_MYSQL_PASSWORD=          # 미연결
MUPID_CLICKHOUSE_URL=          # 미연결 — 배치가 붙을 자리 (Q8)
MUPID_CLICKHOUSE_USER=         # 미연결
MUPID_CLICKHOUSE_PASSWORD=     # 미연결
```

Pinecone 을 붙일 때는 **경계를 수정한 구간의 임베딩 재추출이 함께 들어가야 한다.**
지금은 전수 비교라 원본 좌표 기준으로만 점수가 나가서 드러나지 않지만, 색인을 두면
낡은 벡터가 남아 수정 전 경계로 추천이 나간다.

### AI 배치 (`ai_batch`)

> `ai_batch/` 디렉터리는 **이 저장소에 아직 없다**(2026-09-15 확인). 아래 이름은 예정이다.

```
MUPID_AI_BATCH_DEVICE=
MUPID_AI_ARTIFACT_DIR=
MUPID_S3_ENDPOINT=
MUPID_S3_ACCESS_KEY=
MUPID_S3_SECRET=
MUPID_S3_BUCKET=
MUPID_PINECONE_API_KEY=
MUPID_PINECONE_INDEX_HOST=
MUPID_PINECONE_NAMESPACE=
MUPID_MYSQL_URL=
MUPID_MYSQL_USER=
MUPID_MYSQL_PASSWORD=
MUPID_JAMENDO_CLIENT_ID=       # Jamendo 를 다시 수집할 때만 필요
```

`MUPID_JAMENDO_CLIENT_ID` 는 기존 분석 결과만 이식한다면 필요하지 않다.

---

## 8. 담당 구분

### AI 담당 (이 저장소에서 구현)

- `ai_batch/` — All-In-One, Essentia, MERT, 재처리, 무결성 검증
- `ai_server/` — FastAPI 내부 API, 기동 검증, 중복 제거, 추천 이유, 빈 결과
- 두 Dockerfile (CPU 서빙 / 배치)
- `id_map` 생성·검증
- 테스트와 성능 실측
- 이 계약 문서

### 백엔드 담당

- Spring → FastAPI 호출 구현 (타임아웃·재시도·대체 동작)
- 공개 API `snake_case ↔ camelCase` 변환
- `daily_recommendations` 발급·저장
- `music_section_analysis`·`music_analysis` 적재 API 또는 배치 연동
- **ERD ↔ JPA 불일치 8건 수정** → [open-questions B8](../api/knowledge/open-questions.md)
- 장르·무드·악기를 `codes` 에서 별도 테이블로 이전
- 필요한 수동 DDL (`ddl-auto: none`)

### 아직 정해지지 않은 것

| # | 항목 |
|---|---|
| 1 | BPM 을 All-In-One 값과 Essentia 값 중 무엇으로 적재할지 |
| 2 | `genres` 에 Discogs 원형(`Pop---K-pop`)과 축약형(`K-pop`) 중 무엇을 넣을지 |
| 3 | 어휘 3종을 선적재할지, 배치가 없으면 만들지 |
| 4 | ~~산출물을 Docker 이미지에 넣을지~~ → **Kubernetes 설계와 함께 결정** (Q7) |
| 5 | ~~AI 가 MySQL 을 직접 읽을지~~ → **ClickHouse 를 읽기 전용 직접 조회** (Q8) |
| 6 | ~~FastAPI 인증 방식~~ → **공유 비밀 헤더 구현 완료** (아래 참조) |
| 7 | ~~ClickHouse 조회·학습 실행~~ → **배치 CLI 로 구현 완료**(`pull_signals`). HTTP 로도 열지는 여전히 미정 |
| 8 | ~~온보딩 데이터 전달 방식~~ → **`GET /api/v1/user/{userId}/onboarding` 로 확정.** 어휘 대조까지 끝났다. 남은 것은 벌크 조회 여부 |
| 9 | 데일리 추천 호출·DB 저장 API |
| 10 | 유사 구간·자동재생 API 연결 |
| 11 | 실제 DB 의 ERD·JPA 컬럼 통일 (Q1·Q2 확정 대기) |
| 12 | Kubernetes 배포·산출물 관리 방식 (Q6·Q7) |

### 인증

현재 `/internal` 에 인증이 **없다.** AI 서버를 외부에 노출하지 않고 내부망에서만 Spring 이
부르는 것을 전제로 한다. 공개 위치에 둔다면 다음 중 하나가 필요하다.

| 안 | 내용 |
|---|---|
| A | 네트워크 격리 — Docker 내부 네트워크, 외부 포트 미개방 (**권장**) |
| B | 공유 비밀 헤더 — `MUPID_AI_INTERNAL_TOKEN` 을 양쪽 환경변수로 |
| C | mTLS |

**A + B 로 정리됐다.** 네트워크 격리를 유지하되 공유 비밀 헤더도 구현했다 — 설정 실수 한
번이면 격리는 그대로 뚫리기 때문이다.

Spring 은 `MUPID_RECOMMENDER_INTERNAL_TOKEN`, AI 는 `MUPID_AI_INTERNAL_TOKEN` 에 같은 값을
넣는다. **기본은 꺼져 있고, 값을 넣는 쪽이 켜는 쪽이다.** 한쪽만 켜면 추천이 통째로 401 이
되므로 한 쌍으로 다룬다.

### 실행 환경 (Q6)

**Docker 로 간다는 결정은 취소됐다.** Kubernetes 설계가 끝날 때까지 보류한다. `Dockerfile`
과 CI 잡은 지우지 않고 그대로 둔다 — 빌드가 되는지는 계속 확인해야 하고, K8s 로 가더라도
이미지는 필요하다. 다만 `infra/deploy/compose.yml` 에 ai 서비스를 **넣지 않는다.**
