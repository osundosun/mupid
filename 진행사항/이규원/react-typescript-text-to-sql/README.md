# React + TypeScript Text-to-SQL 프론트엔드 학습

React와 TypeScript의 타입 시스템과 프로젝트 구조를 학습하기 위해, 자연어 질문부터 SQL 결과 테이블까지의 데이터 흐름을 단계별로 구현하는 개인 학습 프로젝트입니다. 이 폴더는 1일차에 생성했으며 일자별 학습 브랜치에서 기능을 확장합니다.

## 1일차 구현 범위

- Vite 기반 React + TypeScript 프로젝트 구성
- ESLint 기반 정적 검사 환경 구성
- `type`, `interface`, union type, optional-friendly row type 적용
- `Record<string, string | number | null>`을 사용한 동적 SQL 결과 행 정의
- Generic `ApiResponse<T>`를 사용한 공통 API 응답 타입 정의
- 타입이 적용된 Mock Text-to-SQL 데이터 작성
- Mock 응답을 SQL 코드와 결과 테이블로 렌더링
- API, 컴포넌트, 훅, Mock, 타입, 유틸리티 디렉터리 분리

## 2일차 구현 범위

- Skeleton-2 Text-to-SQL의 `db_id`, `question`, `query` 데이터 구조 확인
- `DatabaseSelect`, `QueryForm`, `ExampleQuestionList` 컴포넌트 분리
- `useState<QueryRequest>`를 사용한 Controlled Component 구현
- Select와 Textarea의 변경 이벤트, Form 제출 이벤트에 TypeScript 타입 적용
- 질문 미입력, 공백 입력, 10자 미만, 240자 초과 유효성 검사
- 예시 질문 선택 시 데이터베이스와 질문을 함께 갱신
- 제출한 요청을 Mock API와 SQL 결과 화면에 반영
- `aria-invalid`, `aria-describedby`를 사용한 입력 오류 안내

## 실행 방법

```bash
npm install
npm run dev
```

## 검증 방법

```bash
npm run lint
npm run build
```

브라우저에서는 다음 동작을 확인합니다.

1. 빈 질문을 제출하면 오류 메시지가 표시되는지 확인합니다.
2. 예시 질문을 선택하면 DB와 질문이 함께 변경되는지 확인합니다.
3. 정상 제출 후 선택한 DB와 질문이 결과 화면에 반영되는지 확인합니다.
4. 화면 너비가 좁아져도 폼과 결과 테이블을 사용할 수 있는지 확인합니다.

## 폴더 구조

```text
src/
├─ api/          # API 호출 계층
├─ components/   # 화면 컴포넌트
├─ hooks/        # 재사용 React Hook
├─ mocks/        # 개발용 Mock 응답
├─ types/        # 요청·응답 TypeScript 타입
├─ utils/        # 포맷 등 공통 함수
├─ App.tsx
└─ main.tsx
```

## 데이터 흐름

```text
질문 입력 → API 요청 → SQL 응답 → SQL 코드 + 결과 테이블
```

현재는 입력 폼과 데이터 흐름을 확인하기 위해 Mock 응답을 사용합니다. 다음 단계에서 Mock API를 비동기로 전환하고 로딩·성공·실패·재시도 상태를 구현할 예정입니다.
