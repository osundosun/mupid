# AI 추천 서비스 이식 인수인계

작성일: 2026-09-14 · Jira: `S15P21A204-218`

## 1. 목적과 범위

Jupyter GPU 서버의 추천 데모를 팀 서비스에 연결하기 위해 백엔드 담당자가 확인해야 할
코드·산출물·API·검증 기준을 정리한다. 이 브랜치는 **문서만 전달**하며, 실험용
`ai_server/` 구현과 Docker 파일은 포함하지 않는다.

- 백엔드 담당: FastAPI 실행 환경 구성, Spring 호출·응답 조립, 운영 연결
- AI 담당: 원본 추천 코드·모델·색인 전달, 알고리즘 및 산출물 검증 지원
- 프런트는 FastAPI를 직접 호출하지 않는다.

전체 ID·DB·오류·재시도 계약은 원격 브랜치
`origin/ai/docs/S15P21A204-231-integration-contract`의 다음 문서를 우선 기준으로 삼는다.

- `infra/AI-SPRING-CONTRACT.md`
- `infra/DEPLOYMENT-CONTRACT.md`
- `infra/AI-INTEGRATION-2026-09-11.md`

## 2. 현재 원본과 실측 상태

원본은 GPU 서버 `~/rec_mupid`에 있다.

| 항목 | 현재 값 |
|---|---:|
| 추천 풀 | 1,063곡 / 10,007구간 |
| 전체 분석 색인 | 65,540행 |
| 구간 임베딩 | MERT 1536차원 |
| 랭킹 모델 | Dual Encoder 285→128, 약 349KB |
| 레거시 데모 | FastAPI 포트 8100 |
| 콜드스타트 응답 | p50·p95 약 0.30초 |
| 청취 이력 사용자 응답 | p50 2.22초, p95 2.23초, 최대 2.35초 |

청취 이력 사용자가 느린 원인은 요청마다 행동 로그를 네 번 전수 스캔하는 구조다. 이식과
분리해 `S15P21A204-217`에서 개선한다. 초기 Spring 읽기 타임아웃은 5초를 사용한다.

## 3. 전달 대상

### 코드

| 원본 | 역할 | 주의 |
|---|---|---|
| `src/m16_service.py` | 추천 계산 | 데모 UI·파일 저장 라우트는 제외하고 계산부만 사용 |
| `src/internal_api.py` | Spring용 내부 API | 숫자 ID와 엄격한 요청 검증 유지 |
| `src/ids.py` | 레거시↔MySQL ID 매핑 | 1:1 위반 시 준비 완료 거부 |
| `src/m09_labels.py` | 행동 라벨 계산 | 지연 import 대상 |
| `src/m10_features.py` | 특징 구성 | 지연 import 대상 |
| `src/m11_stage1.py` | 랭킹 모델 로드 | 지연 import 대상 |

PoC에서는 `m16_service.py` 1~1597행을 계산부로 분리했지만 이 행 번호는 고정 계약이 아니다.
최종 이식 시 실제 import와 호출 경계를 다시 확인한다.

### 추천 산출물

| 파일 | 기존 실측 | 용도 |
|---|---:|---|
| `moments.parquet` | 약 3.2MB / 65,540행 | 구간 메타데이터 |
| `moments_emb.npy` | 약 385MB / 65,540×1536 | MERT 임베딩 |
| `features_v1.npz` | 약 93MB / 65,540×285 | 랭킹 입력 특징 |
| `stage1_section.npy` | 약 33MB / 65,540×128 | 구간 랭킹 표현 |
| `stage1.pt` | 약 357KB | 랭킹 모델 가중치 |

기존 계약의 합계는 약 515MB이고, Docker PoC의 전체 준비 디렉터리는 약 569MB로 측정됐다.
포함 파일 범위 차이일 수 있으므로 전달 직전에 파일별 크기·SHA-256·행 수를 담은 manifest를
다시 만든다. 네 색인은 같은 행 번호로 연결되므로 한 파일만 교체하면 안 된다.

서비스 후보만 남긴 축소본은 10,007행·약 137MB로 생성됐고 다음 검사를 통과했다.

- 네 파일 행 수와 순서 일치
- 숫자값 유한성
- 표본 벡터 동일성
- 추천 풀 1,063곡 누락 없음

축소본 사용 여부는 기존 8100과 추천 결과 회귀 비교 후 결정한다.

## 4. 이식 시 확인된 함정

원본은 파일 위치를 기준으로 경로와 모듈을 계산한다. 그대로 복사하면 다음 순서로 실패했다.

| 증상 | 원인 | 조치 |
|---|---|---|
| 산출물 복사 실패 | 제외 설정에 산출물이 포함됨 | 배포 목록과 제외 목록을 대조 |
| `ModuleNotFoundError: m10_features` | 함수 안 지연 import | `m09`·`m10`·`m11`을 함께 전달하고 import 경로 고정 |
| 잘못된 `/app/app/data` 조회 | 각 legacy 모듈이 별도 ROOT 계산 | 모든 모듈이 `MUPID_AI_ARTIFACT_DIR` 하나만 사용 |
| `ModuleNotFoundError: scipy` | `build_dna`의 계층 군집 의존성 | 런타임 의존성에 `scipy` 포함 |

정적 import 검사만으로는 잡히지 않는다. 실제 추천 요청까지 실행해야 한다.

## 5. 실행 및 배포 결정

Docker 적용은 2026-09-14 기준 **보류**다. 우선 배포 서버의 Python 3.12 가상환경에서
FastAPI를 직접 실행하고, 운영 프로세스 관리는 기존 인프라 방식에 맞춘다.

Docker PoC 참고값:

| 항목 | 값 |
|---|---:|
| 축소 산출물 | 137MB |
| 이미지 | 1.93GB로 보고됐으나 로컬 표시값은 2.12GB |
| 이미지 빌드 | 4분 21초 |
| 1,063곡·10,007구간 적재 | 1.34초 |

이미지 대부분은 CPU PyTorch·SciPy·Pandas 런타임이다. 이 수치는 Docker 채택 근거가 아니라
보류 판단을 위한 참고값이다. 실험 컨테이너·이미지·미완성 코드는 롤백했다.

## 6. 내부 API 최소 계약

호출 흐름은 `React → Spring → FastAPI`다. 내부 JSON은 `snake_case`, 프런트 공개 JSON은
`camelCase`로 Spring이 변환한다.

필수 API:

- `GET /health`: 프로세스 생존 확인
- `GET /internal/ready`: 모델·색인·ID 매핑 준비 확인
- `POST /internal/recommendations/daily`
- `POST /internal/recommendations/next`
- `POST /internal/recommendations/search`

추천 항목 최소 출력:

```json
{
  "music_id": 148,
  "music_section_id": 1204,
  "start_time_ms": 55000,
  "end_time_ms": 76000,
  "section_label": "chorus",
  "rank_order": 1,
  "score": 0.87,
  "reason_code": "SIMILAR_PREFERRED_MOMENT",
  "reason": "선호하는 후렴 구간과 흐름이 비슷해요"
}
```

`S15P21A204-218`에서는 곡·구간·점수·추천 사유를 제공한다. 추천 사유 원문은 저장된 특징과
`reason_code`로 만든 정해진 문장만 사용하며 자유 생성하지 않는다.

## 7. ID와 준비 완료 조건

- `music_id`, `music_section_id`: MySQL `INT`
- `recommendation_id`: MySQL `BIGINT`
- Pinecone ID: `moment:{music_section_id}:mert-v1`
- AI가 숫자 ID를 임의 생성하지 않는다.
- 레거시 문자열 ID와 MySQL 숫자 ID는 반드시 1:1이어야 한다.

`/health` 성공만으로 트래픽을 받지 않는다. `/internal/ready`는 다음이 모두 맞아야 200이다.

1. 모델 로드 성공
2. 색인 4종 행 수·순서 일치
3. `emb_row` 0부터 연속
4. 곡·구간 ID 매핑 1:1
5. 추천 풀 전체 숫자 ID 매핑 완료

현재 PoC는 `/health` 200까지 확인했고, MySQL 매핑이 0건이라 `/internal/ready`가
`503 INDEX_NOT_READY`를 반환하는 것까지 확인했다. 가짜 ID로 통과시키지 않는다.

## 8. 백엔드 이식 순서

1. 원본 코드·산출물 전달 경로와 manifest 확인
2. Python 3.12 가상환경과 최소 의존성 구성
3. 모든 모듈의 산출물 경로를 `MUPID_AI_ARTIFACT_DIR`로 통일
4. FastAPI 기동 후 `/health` 확인
5. MySQL 숫자 ID 매핑 생성·1:1 검증
6. `/internal/ready` 200 확인
7. daily·next·search 실제 추천 응답 확인
8. 기존 8100과 고정 입력 추천 결과 회귀 비교
9. Spring 내부 클라이언트와 5초 타임아웃·1회 재시도 연결
10. Spring 공개 API에서 메타데이터를 조립해 React에 전달

Pinecone 실제 연결은 `S15P21A204-214`, 행동 로그 성능 개선은 `S15P21A204-217` 범위다.

## 9. 완료 조건

- `/health` 200 및 `/internal/ready` 200
- 색인·manifest·ID 매핑 오류 0건
- 실제 추천 응답에 `music_id`, `music_section_id`, 구간 시간, 라벨, 점수, 사유 포함
- 한 응답에서 같은 `music_id` 중복 0건
- 후보가 없으면 임의 채움 없이 빈 목록과 사유 반환
- 기존 8100 대비 고정 입력 회귀 결과 기록
- 청취 이력 유무 각각 p50·p95 측정
- 비밀키·원본 음원·대용량 산출물 Git 포함 0건
- React가 FastAPI를 직접 호출하지 않음

## 10. 현재 미검증

- `scipy` 포함 후 실제 추천 응답
- 축소본과 전체본의 추천 결과 차이
- MySQL 실데이터 ID 매핑
- Pinecone upsert·검색
- Spring→FastAPI 통합 호출
- 직접 실행 환경의 재시작·종료·롤백

이 항목을 통과하기 전에는 추천 서비스 이식을 완료로 처리하지 않는다.
