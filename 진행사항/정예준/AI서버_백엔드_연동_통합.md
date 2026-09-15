# AI 추천 서버 · 백엔드 연동 통합 정리

두 갈래로 나뉘어 진행하던 작업을 `develop` 위에서 합쳤다. 이 문서는 **무엇이 들어갔고,
둘이 어디서 만나며, 아직 비어 있는 곳이 어디인지**를 정리한다.

| 항목 | 값 |
|---|---|
| 작업일 | 2026-09-15 |
| 대상 브랜치 | `ai/feature/S15P21A204-218-add-recommend-service` (16커밋) |
| | `back/feature/S15P21A204-344-connect-api-and-ai-servers` (11커밋) |
| 기준 | `origin/develop` (`59b7308`) |
| 결과 | 114파일 변경 · +10,559 / −231 |
| 충돌 | **0건** |
| 제외 | `ai/docs/S15P21A204-231-integration-contract` (이번 병합 대상 아님) |

---

## 1. 병합 절차

두 브랜치가 건드린 파일을 먼저 대조했다. 겹치는 파일이 **0건**이라 텍스트 충돌은
처음부터 발생할 수 없는 상태였다. 실제 위험은 다른 데 있었다(4절).

```
back 변경 87건 (api/**, frontend/** 위주)
ai  변경 14건 (ai_server/** 위주)
교집합 0건
```

`back` 쪽만 `develop` 보다 10커밋 뒤처져 있어 리베이스가 필요했다. 버리는 브랜치에서
먼저 시험해 11커밋이 전부 깨끗하게 얹히는 것을 확인한 뒤 본 브랜치에 적용했다.

```
1. back 브랜치를 origin/develop 으로 리베이스   dcf8f71 → c5f96fa (충돌 0)
2. develop 에 ai 브랜치 병합    (--no-ff)
3. develop 에 back 브랜치 병합  (--no-ff)
```

`ai` 를 먼저 올렸다. 백엔드가 참조하는 계약이 `develop` 에 먼저 있어야 하기 때문이다.

> 리베이스 이전 `back` 브랜치 HEAD 는 `dcf8f71` 이다. 되돌릴 일이 생기면 이 값을 쓴다.

---

## 2. AI 추천 서버에 들어간 것

### 내부 API — `/internal`

Spring 만 부르는 전제의 내부 전용 경로다. nginx 로 공개하지 않는다.

| 메서드 | 경로 | 용도 |
|---|---|---|
POST | `/internal/recommendations/next` | 다음 곡 추천 |
POST | `/internal/recommendations/daily` | 오늘의 추천 |
POST | `/internal/recommendations/search` | 자연어·QueryPlan 추천 |
POST | `/internal/similar` | 닮은 구간 |
GET | `/internal/facets` | QueryPlan 에 쓸 수 있는 태그 어휘 |
GET | `/internal/metrics` | 운영 지표 (프로세스 내 카운터) |
GET | `/internal/id-map` | 숫자 ID 매핑 적재 상태 |
GET | `/internal/ready` | 실서비스 준비 완료 여부 |
GET | `/health` | 프로세스 생존 (접두사 밖) |

### 그 밖에 들어간 것

- **QueryPlan 직접 수용** — 자연어 해석기를 AI 서버에 두지 않고 백엔드가 만든 계획을
  그대로 받는다. mood 는 필터가 아니라 부드러운 재정렬로 쓴다
- **랭킹 모델 NumPy 판** (`stage1_np.py`) — PyTorch 판과 동치임을 검증했다. 배포에서
  무거운 의존성을 걷어낸다
- **ERD ENUM 방어** — ENUM 에 없는 구간 라벨은 응답에서 제외한다. 백엔드가 파싱하다
  터지는 대신 그 건만 빠진다
- **운영 지표·산출물 무결성 검사** — 어떤 산출물이 없는지 기동 시 알린다
- **`/ready`** — 산출물이 다 갖춰졌을 때만 참을 돌려준다. 헬스체크와 분리했다
- **CI** (`infra/ci/ai-server.yml`), Dockerfile 빌드 수정

### 기동을 막던 버그 두 건

배포에서 실제로 서비스가 뜨지 않았던 원인이라 따로 적는다.

1. **Dockerfile 이 빌드되지 않았다** (`ae88856`)
2. **진단 로그가 서비스를 죽였다** (`c8fee3f`) — 기동 로그에 한글과 `—` 가 들어 있는데
   컨테이너 로케일이 `C` 면 `print()` 가 `UnicodeEncodeError` 를 낸다. 그 호출이
   lifespan 안에 있어 **애플리케이션이 뜨지 못했다.** 무엇이 없는지 알려 주려던 줄이
   서비스를 통째로 내린 셈이다.

   두 겹으로 막았다. `Dockerfile` 에 `LANG=C.UTF-8` · `PYTHONIOENCODING=utf-8` 을 넣고,
   `app/log.py` 를 두어 **어떤 경우에도 예외를 올리지 않게** 했다(실패하면 바이트로,
   그것도 막히면 ASCII 로 낮춰 쓰고 조용히 포기한다). 로그를 어디로 흘려보내느냐는
   운영 쪽 사정이고, 거기서 실패해도 추천은 계속돼야 한다.

---

## 3. 백엔드에 들어간 것

- **`RecommenderClient`** — 추천 서버 호출 클라이언트. 목업을 걷어내고 실제 호출로
  바꿨다 (`c5f96fa`)
- **`AiRecommendTool`** — LLM 이 도구로 부르는 추천 진입점. 하드코딩된 점수·이유가
  실제 응답으로 교체됐다
- **AI 대화 API** (`POST /api/v1/ai/chat`) 와 프런트 채팅 패널 연결
- **관리자 페이지** — 서버 자원 전반 조회·수정
- MUBTI 컬럼명 불일치로 조회가 통째로 실패하던 것 수정
- 기본 프로필 이미지·로고 교체, 도커 컴포즈 IPv6 off

### 설정 계약

이름이 헷갈리는 지점이 있어 그대로 옮겨 둔다.

```yaml
ai:                                   # LLM 게이트웨이 — 대화를 만든다
  base-url: ${MUPID_AI_BASE_URL:...}

recommender:                          # ai_server — 곡을 고른다. 위와 다른 서버다
  base-url: ${MUPID_RECOMMENDER_BASE_URL:}
  connect-timeout: 2s
  read-timeout: 5s                    # 이력이 있는 사용자는 p50 2.2초까지 걸린다
  retry-once: true                    # 500·503·타임아웃만 한 번. 400·404 는 재시도 안 함
```

`MUPID_AI_BASE_URL` 에 추천 서버 주소를 넣으면 **대화가 추천 서버로 나간다.** 이름이
비슷해서 실제로 틀리기 쉽다. `recommender.base-url` 을 비워 두면 곡 추천만 503 이고
대화는 정상 동작한다.

---

## 4. 두 쪽이 만나는 지점 — 검증 결과

파일이 겹치지 않았으므로 git 은 충돌을 보고하지 않는다. 그래서 **계약을 직접 대조했다.**

| 확인 항목 | 결과 |
|---|---|
| 경로 접두사 | AI `PREFIX = "/internal"` ↔ 백엔드 `"/internal/..."` **일치** |
| 호출 경로 | `recommendations/search`, `recommendations/next`, `facets` 3개 모두 **존재** |
| 필드 이름 | AI 는 `snake_case`, Java 는 camelCase → `PropertyNamingStrategies.SNAKE_CASE` 로 **매핑됨** |
| 응답 항목 | `music_id` · `music_section_id` · `start_time_ms` · `end_time_ms` · `section_label` · `rank_order` · `score` · `reason_code` **전부 대응** |
| AI 서버 테스트 | **88 passed · 11 skipped** (병합된 트리에서 실행) |

### 아직 비어 있는 곳 — 내부 토큰

AI 서버는 `X-Internal-Token` 헤더 검사를 지원한다. `MUPID_AI_INTERNAL_TOKEN` 에 값이
있을 때만 켜지고 기본은 꺼져 있다.

**그런데 백엔드 `RecommenderClient` 는 이 헤더를 보내지 않는다.** `RecommenderProperties`
에 토큰 항목도 없다.

> 지금은 양쪽 다 꺼져 있어 동작한다. 그러나 **누군가 AI 서버에
> `MUPID_AI_INTERNAL_TOKEN` 을 설정하는 순간 모든 추천 호출이 401 이 된다.**
> 배포 설정만으로 서비스가 조용히 죽는 형태라 위험도가 낮지 않다.

선택지는 둘이다.

1. 백엔드에 토큰 전송을 붙인다 (`RecommenderProperties.token` → 요청 헤더)
2. 붙일 때까지 **운영 환경에서 `MUPID_AI_INTERNAL_TOKEN` 을 설정하지 않는다**는 것을
   배포 문서에 못박는다

지금 방어선은 네트워크 격리 하나뿐이므로 1번을 권한다.

---

## 5. 다음에 할 것

| 우선도 | 항목 |
|---|---|
높음 | 백엔드에 `X-Internal-Token` 전송 추가 (4절) |
높음 | 통합 환경에서 실제 호출 1회 — `recommender.base-url` 을 채우고 `/internal/ready` → `next` 순서로 확인 |
중간 | `read-timeout: 5s` 가 실제 p95 를 덮는지 측정. 이력이 많은 사용자가 기준이다 |
중간 | `/internal/metrics` 를 운영 대시보드에 연결 |
낮음 | `ai/docs/S15P21A204-231-integration-contract` 를 이 병합 결과에 맞춰 갱신할지 판단 |

---

## 6. 재현 방법

```bash
# AI 서버 테스트 (병합된 트리 기준)
python -m pytest ai_server/tests -q        # 88 passed, 11 skipped

# 계약 대조
grep -n 'PREFIX =' ai_server/app/internal_api.py
grep -oE '"/internal[^"]*"' api/src/main/java/com/osundosun/mupid/api/ai/client/RecommenderClient.java
```
