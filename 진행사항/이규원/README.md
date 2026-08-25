# 이규원 — 개인 학습 진행사항

**학습 주제:** React + TypeScript 기반 Text-to-SQL 프론트엔드 구현  
**학습 기간:** 2026-08-24 ~ 2026-08-28  
**1일 학습 시간:** 4시간  
**누적 학습 프로젝트:** [`react-typescript-text-to-sql`](./react-typescript-text-to-sql)<br>
**2일차 작업 브랜치:** `study/이규원/day02-query-form`

---

## 학습 목표

- React와 TypeScript를 이용해 타입 안전한 프론트엔드 구조를 설계한다.
- 자연어 질문, API 요청, 생성 SQL, 결과 테이블로 이어지는 AI 서비스의 데이터 흐름을 이해한다.
- 로딩, 성공, 실패, 빈 결과 등 실제 API 연동에 필요한 UI 상태를 단계적으로 구현한다.
- 일자별 구현 결과와 문제 해결 과정을 문서로 남긴다.

## 전체 학습 계획

| 일차 | 주제 | 주요 산출물 | 상태 |
|---|---|---|---|
| 1일차 | TypeScript와 프로젝트 구조 | 타입, Mock 데이터, 기본 결과 화면 | ✅ 완료 |
| 2일차 | React 컴포넌트와 입력 폼 | DB 선택, 질문 입력 및 유효성 검사 | ✅ 완료 |
| 3일차 | 비동기 API와 요청 상태 | 로딩, 오류, 재시도, 요청 취소 | ⬜ 예정 |
| 4일차 | 결과 UI와 사용자 기능 | SQL 복사, 동적 테이블, 최근 기록 | ⬜ 예정 |
| 5일차 | 테스트와 리팩터링 | 컴포넌트 테스트 및 최종 문서화 | ⬜ 예정 |

---

## 일자별 진행사항

### 2026-08-24 — 1일차: TypeScript와 프로젝트 구조

#### 학습 목표

- React + TypeScript 학습 화면과 AI 서비스의 데이터 흐름을 설계한다.
- TypeScript의 주요 타입 문법을 API 요청·응답 구조에 적용한다.
- 실제 백엔드 없이도 확인 가능한 Mock 결과 화면을 구현한다.

#### 시간별 진행 내용

| 시간 | 진행 내용 |
|---|---|
| 1시간 | 최종 화면 스케치, 컴포넌트 구분, Vite 프로젝트 생성, ESLint 구성 |
| 1시간 | `type`, `interface`, union type, `Record`, Generic과 함수 반환 타입 학습 |
| 1시간 30분 | 디렉터리 분리, 요청·응답 타입과 Mock 데이터 작성, SQL·결과 테이블 렌더링 |
| 30분 | 실행 및 정적 검사, README에 학습 결과 정리 |

#### 설계한 데이터 흐름

```text
자연어 질문 입력
        ↓
타입이 적용된 API 요청
        ↓
Text-to-SQL 모델의 SQL 응답
        ↓
SQL 코드와 동적 결과 테이블 출력
```

#### 학습한 TypeScript 개념

- `RequestStatus` union type으로 요청 상태의 허용값 제한
- `QueryRequest`, `QueryResult` interface로 요청·응답 구조 명시
- `Record<string, string | number | null>`로 컬럼이 달라질 수 있는 SQL 행 표현
- `ApiResponse<T>` Generic으로 여러 응답에서 재사용 가능한 공통 타입 작성
- 함수의 반환 타입을 명시해 API 계층과 화면 계층 사이의 계약 확인

#### 구현 내용

- `Vite + React + TypeScript` 프로젝트 생성
- ESLint와 TypeScript ESLint 설정
- `api`, `components`, `hooks`, `mocks`, `types`, `utils` 폴더 분리
- 타입이 적용된 Text-to-SQL Mock 응답 작성
- 질문, 생성 SQL, 결과 테이블, 모델명, 응답 시간 출력
- 데스크톱과 모바일 화면을 고려한 반응형 레이아웃 작성

#### 확인한 TypeScript 오류 사례

`average_salary` 값에 문자열이나 숫자가 아닌 객체를 넣으면 `QueryRow` 타입과 일치하지 않아 컴파일 단계에서 오류가 발생한다. 이를 통해 API 응답 형태가 잘못됐을 때 실행 전에 문제를 확인할 수 있음을 확인했다.

#### 어려웠던 점과 해결 방법

SQL 결과는 질의에 따라 컬럼 이름이 달라지기 때문에 고정된 interface만으로 표현하기 어려웠다. 행을 `Record<string, string | number | null>`로 정의하고, 별도의 `columns` 배열 순서에 따라 테이블을 렌더링하도록 설계했다.

#### 1일차 산출물

- 프로젝트 기본 구조
- 요청·응답 TypeScript 타입
- Generic API 응답 타입
- Text-to-SQL Mock 데이터
- SQL 코드 및 결과 테이블 미리보기 화면
- 프로젝트 실행·검증 방법을 포함한 README

#### 다음 학습 계획

- 질문 입력 컴포넌트 구현
- Props와 이벤트 타입 적용
- 빈 질문과 공백 입력 유효성 검사
- 요청 중 버튼 비활성화 UI 구현

### 2026-08-25 — 2일차: React 컴포넌트와 Text-to-SQL 입력 폼

#### 학습 목표

- Skeleton-2 Text-to-SQL의 `db_id`, `question`, `query` 구조를 프론트엔드 입력·결과 UI에 연결한다.
- React Controlled Component와 TypeScript 이벤트 타입을 이용해 폼 상태를 안전하게 관리한다.
- 필수 입력, 글자 수, 공백 입력을 검증하고 오류 메시지를 접근성 속성과 함께 제공한다.

#### 시간별 진행 내용

| 시간 | 진행 내용 |
|---|---|
| 1시간 | 명세서와 Skeleton-2 데이터 구조 확인, 입력 화면과 컴포넌트 설계 |
| 1시간 | `useState`, Props, `ChangeEvent`, `FormEvent`를 적용한 React 폼 학습 |
| 1시간 30분 | DB 선택, 질문 입력, 예시 질문, 유효성 검사 및 Mock 결과 연결 구현 |
| 30분 | ESLint·프로덕션 빌드·브라우저 동작 확인 및 README 정리 |

#### 명세서와 프론트엔드의 연결

| Skeleton-2 필드 | 프론트엔드 구현 |
|---|---|
| `db_id` | `DatabaseSelect`에서 질의 대상 데이터베이스 선택 |
| `question` | Controlled `textarea`로 자연어 질문 입력 및 검증 |
| `query` | 제출된 요청에 대한 생성 SQL과 결과 테이블 표시 |

#### 학습한 React·TypeScript 개념

- `useState<QueryRequest>` Generic으로 폼 상태 구조 고정
- `ChangeEvent<HTMLSelectElement>`, `ChangeEvent<HTMLTextAreaElement>`로 입력 이벤트 타입 명시
- `FormEvent<HTMLFormElement>`와 `preventDefault()`를 이용한 폼 제출 처리
- Props의 콜백 타입으로 자식 컴포넌트의 입력을 부모 상태와 결과 화면에 전달
- `trim()` 기반 공백 입력 검사와 최소·최대 글자 수 검증
- `aria-invalid`, `aria-describedby`로 오류 메시지와 입력 요소 연결

#### 구현 내용

- 데이터베이스 선택용 `DatabaseSelect` 컴포넌트 구현
- 자연어 질문을 관리하는 Controlled 입력 폼 구현
- Skeleton-2 예시를 포함한 예시 질문 선택 기능 구현
- 질문 미입력, 공백 입력, 10자 미만, 240자 초과 유효성 검사
- 제출된 `dbId`와 `question`이 Mock API 응답과 결과 화면에 반영되도록 연결
- 요청 중 입력과 제출 버튼이 비활성화되는 상태 처리
- 데스크톱과 모바일에서 사용할 수 있는 반응형 폼 레이아웃 작성

#### 어려웠던 점과 해결 방법

예시 질문을 선택하면 질문 내용뿐 아니라 해당 질문에 맞는 데이터베이스도 함께 바뀌어야 했다. 예시 항목을 `ExampleQuestion` 타입으로 정의하고 `dbId`와 `question`을 하나의 객체로 관리해 두 값을 동시에 갱신하도록 해결했다.

또한 HTML의 `required` 속성만 사용하면 검증 규칙과 안내 문구를 세밀하게 제어하기 어려웠다. 제출 시 별도 검증 함수를 실행하고, 오류 내용을 입력 요소의 접근성 속성과 연결해 사용자가 문제를 바로 확인할 수 있도록 구성했다.

#### 2일차 산출물

- 데이터베이스 선택 및 자연어 질문 입력 폼
- Props와 이벤트 타입이 적용된 React 컴포넌트
- 예시 질문 선택 기능
- 입력값 유효성 검사와 접근성 오류 안내
- 폼 제출과 연동되는 Mock SQL 결과 화면
- ESLint·프로덕션 빌드·브라우저 동작 검증 결과

#### 다음 학습 계획

- Mock API 호출을 비동기 함수로 전환
- 로딩·성공·실패 요청 상태별 UI 구현
- 오류 발생 시 재시도 기능 구현
- 중복 요청 방지와 요청 취소 방식 학습

---

## 학습 기술 스택

| 분류 | 스택 | 활용 내용 | 상태 |
|---|---|---|---|
| Language | TypeScript | API 요청·응답과 컴포넌트 타입 정의 | 🔄 진행 중 |
| UI | React | 컴포넌트 기반 결과 화면 구현 | 🔄 진행 중 |
| Build Tool | Vite | 개발 서버 및 프로덕션 빌드 | ✅ 적용 |
| Code Quality | ESLint | TypeScript·React 정적 검사 | ✅ 적용 |
| Styling | CSS | 반응형 레이아웃과 결과 테이블 | 🔄 진행 중 |

## 브랜치 전략

일자별 작업을 주제별 브랜치로 분리하고, 완료 후 Merge Request를 통해 `master`에 반영한다.

```text
study/이규원/day01-react-typescript   # 1일차
study/이규원/day02-query-form         # 2일차 완료
study/이규원/day03-api-integration    # 3일차 예정
study/이규원/day04-result-history-ui  # 4일차 예정
study/이규원/day05-test-refactor-docs # 5일차 예정
```

## 실행 및 검증

```bash
cd react-typescript-text-to-sql
npm install
npm run lint
npm run build
npm run dev
```
