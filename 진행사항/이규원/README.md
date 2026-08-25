# 이규원 — 개인 학습 진행사항

**담당 역할:** Frontend<br>
**학습 주제:** React + TypeScript 기반 Text-to-SQL 프론트엔드 구현<br>
**학습 기간:** 2026-08-24 ~ 2026-08-28<br>
> `master`에는 일자별 학습 기록과 브랜치 링크를 관리합니다.<br>
> 구현 코드는 각 일차 학습 브랜치에서 확인할 수 있습니다.

---

## 전체 학습 계획

| 일차 | 날짜 | 학습 주제 | 작업 브랜치 | 상태 |
|---|---|---|---|---|
| 1일차 | 2026-08-24 | TypeScript와 프로젝트 구조 | [`study/이규원/day01-react-typescript`](https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21A204/-/tree/study/%EC%9D%B4%EA%B7%9C%EC%9B%90/day01-react-typescript?ref_type=heads) | ✅ 완료 |
| 2일차 | 2026-08-25 | React 컴포넌트와 입력 폼 | [`study/이규원/day02-query-form`](https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21A204/-/tree/study/%EC%9D%B4%EA%B7%9C%EC%9B%90/day02-query-form?ref_type=heads) | ✅ 완료 |
| 3일차 | 2026-08-26 | 비동기 API와 요청 상태 | `study/이규원/day03-api-integration` | ⬜ 예정 |
| 4일차 | 2026-08-27 | 결과 UI와 사용자 기능 | `study/이규원/day04-result-history-ui` | ⬜ 예정 |
| 5일차 | 2026-08-28 | 테스트와 리팩터링 | `study/이규원/day05-test-refactor-docs` | ⬜ 예정 |

---

## 일자별 진행사항

### 2026-08-24 — 1일차: TypeScript와 프로젝트 구조

#### 학습 내용

- Vite 기반 React + TypeScript 프로젝트 구성
- `type`, `interface`, union type, optional property 학습
- 함수 인자와 반환 타입, `Record`, Generic 기초 학습
- `QueryRequest`, `QueryResult`, `ApiResponse<T>` 타입 정의
- API, 컴포넌트, Mock, 타입, 유틸리티 폴더 분리
- Text-to-SQL Mock 응답과 동적 결과 테이블 구현

#### 데이터 흐름

```text
자연어 질문 → API 요청 → SQL 응답 → SQL 코드 + 결과 테이블
```

#### 산출물

- React + TypeScript 기본 프로젝트 구조
- 요청·응답 TypeScript 타입
- Generic API 응답 타입
- Text-to-SQL Mock 데이터
- SQL 코드 및 결과 테이블 미리보기 화면

#### 작업 정보

- 브랜치: [`study/이규원/day01-react-typescript`](https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21A204/-/tree/study/%EC%9D%B4%EA%B7%9C%EC%9B%90/day01-react-typescript?ref_type=heads)
- 프로젝트 폴더: `day01-react-typescript`

---

### 2026-08-25 — 2일차: React 컴포넌트와 Text-to-SQL 입력 폼

#### 학습 내용

- 명세서와 Skeleton-2의 `db_id`, `question`, `query` 구조 확인
- React Controlled Component와 `useState<QueryRequest>` 적용
- `ChangeEvent`, `FormEvent`, Props 콜백 타입 학습
- 데이터베이스 선택 및 자연어 질문 입력 폼 구현
- 질문 미입력, 공백, 최소 10자, 최대 240자 유효성 검사
- 예시 질문 선택 시 데이터베이스와 질문 동시 변경
- `aria-invalid`, `aria-describedby`를 이용한 접근성 오류 안내
- 입력 데이터를 Mock API 및 SQL 결과 화면과 연결

#### 명세서와 프론트엔드 연결

| Skeleton-2 필드 | 프론트엔드 구현 |
|---|---|
| `db_id` | 질의 대상 데이터베이스 선택 |
| `question` | 자연어 질문 입력 및 유효성 검사 |
| `query` | 생성 SQL과 결과 테이블 표시 |

#### 산출물

- `DatabaseSelect`, `QueryForm`, `ExampleQuestionList` 컴포넌트
- 타입이 적용된 입력·제출 이벤트 처리
- 예시 질문 및 입력값 유효성 검사
- 폼 제출과 연결된 Mock SQL 결과 화면
- 반응형 입력 폼 레이아웃

#### 검증 결과

- `npm run lint` 통과
- `npm run build` 통과
- 빈 질문 오류, 예시 질문 선택, DB 동시 변경, 결과 반영 확인
- 브라우저 경고 및 오류 없음

#### 작업 정보

- 브랜치: [`study/이규원/day02-query-form`](https://lab.ssafy.com/s15-bigdata-dist-sub1/S15P21A204/-/tree/study/%EC%9D%B4%EA%B7%9C%EC%9B%90/day02-query-form?ref_type=heads)
- 누적 프로젝트 폴더: `react-typescript-text-to-sql`

---

## 학습 기술 스택

| 분류 | 스택 | 활용 내용 | 상태 |
|---|---|---|---|
| Language | TypeScript | API 요청·응답 및 컴포넌트 타입 정의 | 🔄 진행 중 |
| UI | React | 컴포넌트 기반 입력·결과 화면 구현 | 🔄 진행 중 |
| Build Tool | Vite | 개발 서버와 프로덕션 빌드 | ✅ 적용 |
| Code Quality | ESLint | TypeScript·React 정적 검사 | ✅ 적용 |
| Styling | CSS | 반응형 폼과 결과 테이블 | 🔄 진행 중 |

## 브랜치 운영 방식

```text
master                                  # 일자별 진행 README
├─ study/이규원/day01-react-typescript   # 1일차 결과
├─ study/이규원/day02-query-form         # 2일차 결과
├─ study/이규원/day03-api-integration    # 3일차 예정
├─ study/이규원/day04-result-history-ui  # 4일차 예정
└─ study/이규원/day05-test-refactor-docs # 5일차 예정
```

일차별 브랜치에서 학습 및 구현을 완료한 뒤, `master`의 이 README에 진행 결과와 브랜치 링크를 추가합니다.
