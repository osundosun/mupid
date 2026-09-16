# AI 연동 — 막혀 있는 API·ERD 변경

Bruno 컬렉션 `api/docs/AI/` 의 `요구 *` 파일에 계약은 적었지만 **구현하지 않은 것들**과
그 이유를 적는다. 임의로 만들면 나중에 실제 결정과 어긋난 채 굳는다.

작성일 2026-09-15 · **2026-09-16 갱신** (1번 해소, ERD 실물 재확인)
대상 브랜치 `back/feature/S15P21A204-344-connect-api-and-ai-servers`

---

## 1. 구간 분석 결과 적재 — **2026-09-16 해소**

`PUT /api/v1/music/{musicId}/section` (`@SystemRole`)

| | |
|---|---|
| 상태 | **구현됨** |
| 구현 | `MusicSectionByMusicController` · `MusicSectionServiceImpl` · 테스트 14건 |
| AI 쪽 호출자 | `ai_server/tools/register_sections.py` |

이 표가 채워져야 숫자 `music_section_id` 가 생기고, 그래야 추천 응답이 구간을
가리킬 수 있다.

**경로와 인증이 제안과 달라졌다.** 제안은 `/api/v1/internal/...` + `X-Internal-Token`
이었지만, 저장소에 그 경로도 토큰을 검사할 필터도 없었다. 반면 `@SystemRole` 은 이미
있고 가사 통째 교체(`PUT .../lyric`)가 같은 방식이라 있는 것에 맞췄다. AI 쪽은
`fetch_onboarding` 이 쓰던 리프레시 토큰 흐름을 그대로 쓴다(`tools/_api_auth.py` 로
공용화).

### ERD 변경 — 세 건 중 둘은 이미 돼 있었다

2026-09-15 에 적은 이 항목은 **개발 DB 실물과 어긋나 있었다.** 실제로 확인한 결과:

| 항목 | 문서 주장 (09-15) | 실제 (09-16 확인) |
|---|---|---|
| 개발 MySQL | "테이블이 0개다" | **30개 존재** |
| 구간 PK | ERD/JPA 불일치 | **`music_section_analysis_id` 로 통일돼 있었다** |
| `mood_id` | `NOT NULL` 이라 INSERT 실패 | **컬럼 자체가 없었다** |
| 외부 ID | `VARCHAR(50)`, UNIQUE 없음 | 그대로였다 → **이번에 적용** |

남은 한 건은 이 `ALTER` 하나였다. Flyway 가 없고 `ddl-auto` 가 `none` 이라
(`decision/0011`) 사람이 직접 넣는다.

```sql
ALTER TABLE music_section_analysis
  MODIFY `external_id` VARCHAR(64) NOT NULL,
  ADD UNIQUE KEY `uk_music_section_analysis_external_id` (`external_id`);
```

**개발 MySQL(`mupid-dev-mysql`, 13306)에만 적용했다. 운영 DB 에는 아직이다.**

---

## 2. 오늘의 추천 적재

`POST /api/v1/internal/recommendation/daily`

| | |
|---|---|
| 상태 | **미구현** — Spring 컨트롤러 없음 |
| 담당 | BE (컨트롤러) · DB (ERD 1건) |
| 막는 것 | 구간을 가리킬 컬럼이 없다 |

### 필요한 ERD 변경

| 항목 | 현재 | 필요 | 왜 |
|---|---|---|---|
| `daily_recommendation_tracks.music_section_id` | **컬럼 없음** | 추가 (NULL 허용) | 없으면 "오늘의 추천" 이 **곡 단위로만** 남는다. 구간 추천이 이 프로젝트의 핵심인데 그 축이 사라진다 |
| `daily_recommendations.mood_code_id` | `NOT NULL` | 유지하되 값을 정할 주체 필요 | AI 가 묶음 분위기를 고르지 않으면 넣을 값이 없다 |

`mood_code_id` 는 ERD 를 바꾸기보다 **AI 가 무엇을 넣을지 정하는 쪽**이 맞아 보인다.
지금 AI 는 구간 단위 mood 태그를 갖고 있지만 묶음 전체의 분위기 코드를 고르는 규칙이
없다. 온보딩 어휘 대조에서 드러났듯 Spring 코드표(8종 한국어)와 AI 태그(31종 영문)는
**한 건도 겹치지 않아** 자동으로 고를 수도 없다.

---

## 3. 자동재생 다음 곡 — Spring 쪽 호출 지점

| | |
|---|---|
| AI 쪽 | **구현 완료** (`POST /internal/recommendations/next`) |
| Spring 쪽 | **없음** |
| 담당 | BE |
| 막는 것 | 없음. ERD 변경도 필요 없다 |

`RecommenderClient` 가 이미 이 경로를 부를 수 있게 되어 있다. 공개 엔드포인트
(`POST /api/v1/recommend/next` 등)와 호출 지점만 있으면 된다. **네 건 중 가장 먼저
붙일 수 있다.**

단, `recommendation_id` 를 **Spring 이 발급**해야 한다. AI 는 만들지 않고 받은 값을
그대로 돌려준다. `daily_recommendations` 행을 먼저 만들지, 별도 채번을 둘지 정해야 한다.

---

## 4. 선택 구간 닮은 구간 — Spring 쪽 호출 지점

| | |
|---|---|
| AI 쪽 | **구현 완료** (`POST /internal/similar`) |
| Spring 쪽 | **없음** |
| 담당 | BE |
| 막는 것 | 없음 |

개인화가 없어 `user_id` 도 `recommendation_id` 도 받지 않는다. 가장 단순한 연결이다.

---

## 5. 공유 비밀

| | |
|---|---|
| 담당 | 인프라 · BE |
| 상태 | 양쪽 코드는 준비됨, 값 미배포 |

AI 는 `MUPID_AI_INTERNAL_TOKEN`, Spring 은 `MUPID_RECOMMENDER_INTERNAL_TOKEN` 을
읽는다. **같은 값이어야 한다.**

기본은 꺼져 있고 값을 넣는 쪽이 켜는 쪽이다. **한쪽만 설정하면 추천 호출이 통째로
401 이 된다** — 배포 설정만으로 조용히 죽는 형태라 한 쌍으로 다룬다.

---

## 붙일 수 있는 순서

| 순서 | 항목 | 선행 | 상태 |
|---|---|---|---|
| ~~0~~ | ~~구간 적재 (1)~~ | ~~MySQL DDL 적용~~ | **2026-09-16 완료** |
| 1 | 자동재생 다음 곡 (3) | 없음 | 남음 |
| 2 | 닮은 구간 (4) | 없음 | 남음 |
| 3 | 오늘의 추천 적재 (2) | `music_section_id` 컬럼 합의 + `mood_code_id` 결정 | 막힘 |

**구간 적재가 먼저 끝났다** — 나머지 셋이 전부 그 아래 달려 있었기 때문이다.
`/internal/ready` 가 503 인 동안에는 1·2 를 붙여도 503 이 돌아온다.

다만 적재 API 가 생긴 것과 **표가 실제로 채워진 것은 다르다.** `ready` 가 200 이
되려면 추천 풀 전량(곡 1,063 · 구간 10,007)이 매핑돼야 하고, 그러려면
`register_sections` 를 산출물과 함께 한 번 돌려야 한다.

---

## 이 문서에 넣지 않은 것

ClickHouse 직접 조회, `id_map` 생성, 라벨 배치, Kubernetes 작업은 **API 가 아니라
배치·인프라**다. API 문서에 넣지 않았다. 각각은 `infra/AI-SPRING-CONTRACT.md` 와
`infra/AI-SERVER-K8S-HANDOVER.md` 에 있다.
