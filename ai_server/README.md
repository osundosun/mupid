# AI 추천 서비스 인수인계서

2026-09-14 · `S15P21A204-218`

백엔드 담당자가 추천 서비스를 이어받아 **실행하고 연결하고 검증**하는 데 필요한 것만 적는다.

ID·오류·재시도·타임아웃 계약은 아래가 기준이며 여기서 되풀이하지 않는다.
`origin/ai/docs/S15P21A204-231-integration-contract` 브랜치의
`infra/AI-SPRING-CONTRACT.md` · `infra/DEPLOYMENT-CONTRACT.md` ·
`infra/AI-INTEGRATION-2026-09-11.md`

---

## 1. 범위와 담당

```
React → Spring Boot → FastAPI → 추천 모델·색인
```

| | 담당 |
|---|---|
| 백엔드 | FastAPI 실행 환경, Spring 호출, MySQL ID 연결, 운영 적용 |
| AI | 원본 코드·모델·색인 전달, 알고리즘·산출물 검증 지원 |

React 는 FastAPI 를 직접 호출하지 않는다.

**Docker 적용은 보류다.** 우선 Python 3.12 가상환경에서 FastAPI 를 직접 실행한다.

이 디렉터리에는 **이 문서만 둔다.** FastAPI 코드·Dockerfile·requirements·모델·색인·`.env`·
테스트는 아직 만들지 않는다.

## 2. 전달할 코드

GPU 서버 `~/rec_mupid` 기준.

| 파일 | 역할 |
|---|---|
| `src/m16_service.py` | 추천 계산 |
| `src/internal_api.py` | Spring 용 내부 API |
| `src/ids.py` | 레거시 ↔ MySQL ID 매핑 |
| `src/m09_labels.py` | 행동 라벨 |
| `src/m10_features.py` | 특징 구성 |
| `src/m11_stage1.py` | 랭킹 모델 로드 |

뒤 세 개는 함수 안에서 지연 import 된다(6장). 여섯 개를 한 묶음으로 가져간다.

데모 UI, Jupyter 노트북, 로그·캐시, 원본 음원은 전달하지 않는다.

## 3. 전달할 모델·색인

```
moments.parquet        구간 메타
moments_emb.npy        MERT 임베딩
features_v1.npz        랭킹 입력 특징
stage1_section.npy     구간 랭킹 표현
stage1.pt              랭킹 모델 가중치
```

**앞의 네 색인은 같은 행 번호로 서로를 가리킨다.** 한 파일만 교체하면 엉뚱한 곡을
추천하면서도 서비스는 정상처럼 뜬다. 묶음 단위로 전달하고 묶음 단위로 교체한다.

전달 전에 `manifest.json` 을 함께 만든다. 없으면 받는 쪽이 무결성을 확인할 수 없다.

| 항목 | 내용 |
|---|---|
| `filename` | 파일명 |
| `bytes` | 바이트 크기 |
| `sha256` | SHA-256 |
| `rows` / `dims` | 행 수와 차원 |
| `model_version` / `data_version` | 모델·데이터 버전 |

## 4. 실행 조건

| | |
|---|---|
| Python | 3.12 |
| 웹 | FastAPI, Uvicorn |
| 데이터 | NumPy, Pandas, PyArrow |
| 모델 | **CPU** PyTorch |
| 군집 | SciPy |

- 모든 모듈의 산출물 경로를 **`MUPID_AI_ARTIFACT_DIR` 하나로 통일**한다(6장).
- 비밀값과 대용량 산출물은 Git 에 넣지 않는다.

## 5. API · ID · 준비 상태

### 필수 API

```
GET  /health
GET  /internal/ready
POST /internal/recommendations/daily
POST /internal/recommendations/next
POST /internal/recommendations/search
```

`search` 는 자연어 해석기가 있어야 조건을 뽑는다. 해석기 없이 열면 사용자가 적은 조건을
말없이 무시하게 된다 → `S15P21A204-219`.

### 추천 결과 최소 필드

```
music_id  music_section_id  start_time_ms  end_time_ms  section_label
rank_order  score  reason_code  reason
```

`section_label` 은 ERD ENUM 과 같은 대문자다(`CHORUS`, `VERSE` …). `reason` 은 저장된 특징과
`reason_code` 로 만드는 정해진 문장이며 자유 생성하지 않는다.

### ID 규칙

| 대상 | 타입 |
|---|---|
| `music_id` | MySQL `INT` |
| `music_section_id` | MySQL `INT` |
| `recommendation_id` | MySQL `BIGINT` |
| Pinecone vector ID | `moment:{music_section_id}:mert-v1` |

- **AI 는 숫자 ID 를 만들지 않는다.** MySQL 이 발급한 값을 받아 쓴다.
- 레거시 문자열 ID 와 MySQL 숫자 ID 는 **1:1** 이어야 한다.

### `/internal/ready` 200 조건

`/health` 가 200 이어도 트래픽을 받지 않는다. 아래가 **모두** 맞아야 한다.

1. 모델 로드 성공
2. 색인 4종 행 수·순서 일치
3. `emb_row` 가 0부터 연속
4. ID 매핑 1:1
5. 추천 풀 전체가 숫자 ID 로 매핑됨

## 6. 확인된 이식 문제

원본은 **파일 위치를 기준으로 경로와 모듈을 계산한다.** 그대로 복사하면 아래 순서로 실패한다.

| 증상 | 원인 | 조치 |
|---|---|---|
| `ModuleNotFoundError: m10_features` | 함수 안 지연 import | `m09`·`m10`·`m11` 을 함께 두고 import 경로 고정 |
| 엉뚱한 경로 조회 | 모듈마다 ROOT 를 따로 계산 | 전부 `MUPID_AI_ARTIFACT_DIR` 하나만 보게 한다 |
| `ModuleNotFoundError: scipy` | `build_dna` 의 계층 군집 | 런타임 의존성에 `scipy` 포함 |

**세 건 모두 정적 검사로는 안 잡힌다.** 실제 추천 API 를 호출해야 드러난다.

### 응답 시간

| 사용자 | p50 | p95 |
|---|---|---|
| 청취 이력 없음 | 0.30초 | 0.30초 |
| 청취 이력 있음 | **2.22초** | 2.23초 |

초기 Spring 읽기 타임아웃은 **5초**로 둔다. 이력 사용자가 느린 원인은 요청마다 행동 로그를
전수 스캔하는 구조다. 개선은 `S15P21A204-217`, Pinecone 실제 연결은 `S15P21A204-214`.

## 7. 백엔드 진행 순서

1. 원본 코드와 모델·색인 수령
2. manifest 검증 (크기·SHA-256·행 수)
3. Python 3.12 가상환경 구성
4. 산출물 경로 통일
5. `/health` 확인
6. MySQL ID 매핑 생성·1:1 검증
7. `/internal/ready` 확인
8. daily·next·search 실제 응답 확인
9. 기존 8100 과 고정 입력 결과 비교
10. Spring 연결 및 실패 대체 처리

## 8. 완료 조건과 미완료 항목

### 완료 조건

- `/health`, `/internal/ready` 200
- 색인·manifest·ID 오류 0건
- 추천 응답 필드 정상 (5장 목록)
- 한 응답에서 같은 `music_id` 중복 0건
- **결과 없음과 서버 장애를 구분** — 조건에 맞는 곡이 없으면 임의로 채우지 않고 빈 결과와 사유
- 기존 8100 과 회귀 비교 기록
- 이력 유무별 응답 시간 측정
- 비밀값·음원·대용량 산출물 Git 포함 0건
- React 가 FastAPI 를 직접 호출하지 않음

### 아직 미완료

| 항목 | 막고 있는 것 |
|---|---|
| 원본 코드 전달 위치 | 어디에 둘지 미정 |
| 모델·색인 전달 위치와 manifest | manifest 미생성 |
| SciPy 포함 후 실제 추천 응답 | **한 번도 성공 확인 못 함** |
| MySQL 숫자 ID 연결 | 적재 전 |
| 8100 회귀 비교 | 위 두 건에 막힘 |
| Spring 통합 호출 | 백엔드 착수 전 |
| 운영 재시작·종료·롤백 | 실행 방식 확정 전 |

이 항목을 통과하기 전에는 이식을 완료로 처리하지 않는다.
