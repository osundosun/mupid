# Spring ↔ FastAPI 연동 계약

작성 2026-09-14 · 대상 `S15P21A204-231`

이 문서는 **계약만** 정한다. Spring 자바 구현과 JPA·DDL 수정은 백엔드 담당이고, 이 문서를
근거로 진행한다. AI 담당은 FastAPI·배치·Docker·테스트를 맡는다.

수치는 2026-09-14 기존 AI 서버(`~/rec_mupid`, 포트 8100) 실측이다. 표시 없는 값은 추정이
아니라 실측이며, 확정되지 않은 것은 **미결**로 적었다.

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

### 레거시 문자열 ID

AI 내부에는 문자열 곡 ID(`T000148`, `JAMENDO_1048629`)와 구간 ID(`T000148#3`)가 남아 있다.
**내부 API 응답에 나가지 않는다.** 호환용으로만 보존하며 이름은 `source_track_id`,
`source_section_uid` 다.

숫자 ↔ 문자열 매핑은 AI 쪽 `data/proc/mupid/id_map.parquet` 한 곳에서만 한다.

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
4) MySQL embedding_id 에 vector ID 저장
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

한 곡 최초 등록은 아래 순서다. `music_chunking` 과 `ai_batch` 는 `music_id` 확보 뒤
**병렬** 실행할 수 있고, Python 환경과 Docker 이미지는 분리한다.

```
음악 메타 등록 → music_id 확보
   ├─ music_chunking : Opus/RTP + SeaweedFS
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
| `embedding_id` | `moment:{music_section_id}:mert-v1` | 2장 |

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
MUPID_AI_NLP_URL=              # 자연어 해석기. 비우면 QueryPlan 을 받는 경로만 쓴다
MUPID_POOL=                    # 추천 후보 풀 (기본 flow)
MUPID_POOL_EXTRA=              # 추가 풀. 쉼표로 구분 (예: jamendo,own)
MUPID_COMBINER=                # 결합기 파일명
```

**토큰은 양쪽을 동시에 설정해야 한다.** AI 서버에만 `MUPID_AI_INTERNAL_TOKEN` 을 넣고
Spring 에 `MUPID_RECOMMENDER_INTERNAL_TOKEN` 을 주지 않으면 **추천 호출이 전부 401** 이
된다. 배포 설정만으로 조용히 죽는 형태라 한 쌍으로 다룬다.

#### 아직 연결되지 않은 것

아래는 **계획이며 현재 코드에 참조가 없다**(2026-09-15 확인). 벡터 검색은 Pinecone 이
아니라 메모리의 NumPy 행렬로 전수 비교하고, 카탈로그는 MySQL 이 아니라 Parquet 에서
읽는다. 곡 수가 적어 전수 비교가 정확도 손실 없이 충분하기 때문이다. 붙일 때를 위해
이름만 남겨 둔다.

```
MUPID_PINECONE_API_KEY=        # 미연결
MUPID_PINECONE_INDEX_HOST=     # 미연결
MUPID_PINECONE_NAMESPACE=      # 미연결
MUPID_MYSQL_URL=               # 미연결
MUPID_MYSQL_USER=              # 미연결
MUPID_MYSQL_PASSWORD=          # 미연결
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
| 4 | 산출물 515MB 를 Docker 이미지에 넣을지 외부에 둘지 (실측 후 결정) |
| 5 | AI 가 MySQL 을 직접 읽을지, Spring 이 필요한 메타를 넘길지 |
| 6 | FastAPI 인증 방식 — 아래 참조 |

### 인증

현재 `/internal` 에 인증이 **없다.** AI 서버를 외부에 노출하지 않고 내부망에서만 Spring 이
부르는 것을 전제로 한다. 공개 위치에 둔다면 다음 중 하나가 필요하다.

| 안 | 내용 |
|---|---|
| A | 네트워크 격리 — Docker 내부 네트워크, 외부 포트 미개방 (**권장**) |
| B | 공유 비밀 헤더 — `MUPID_AI_INTERNAL_TOKEN` 을 양쪽 환경변수로 |
| C | mTLS |

A 를 권한다. 추가 의존성이 없고 현재 배포 구조로 충분하다. 확정 전까지 외부 포트를 열지
않는다.
