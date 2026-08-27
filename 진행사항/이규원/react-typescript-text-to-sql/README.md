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

## 3일차 구현 범위

- 동기 Mock 호출을 `Promise` 기반 비동기 `requestQuery` 함수로 전환
- `AbortController`와 `AbortSignal`을 이용한 진행 중 요청 취소
- `setTimeout`을 `abort` 이벤트와 연결해 대기 중에도 즉시 중단되도록 처리
- `QueryErrorCode` union과 `QueryRequestError`로 실패 원인 구분
- `unknown` 예외를 `toQueryError`로 정규화해 화면에서 단일 타입만 사용
- `useQueryRequest` 커스텀 훅으로 요청 상태·취소·재시도 로직 분리
- 진행 중 요청이 있을 때 새 요청을 막는 중복 요청 방지
- 요청 ID 비교로 취소되거나 뒤늦게 도착한 응답 무시
- 언마운트 시 진행 중 요청을 정리하는 cleanup 처리
- `RequestStatusBanner`로 상태 안내와 취소·재시도 버튼 제공
- 로딩 스켈레톤, 오류 안내, 빈 상태로 전환되는 결과 화면 구현

## 4일차 구현 범위

- Clipboard API를 이용한 생성 SQL 복사와 복사 완료 상태 표시
- `QueryHistoryEntry` 타입으로 최근 질문 데이터 구조 정의
- `useQueryHistory` 훅으로 현재 화면의 최근 성공 질문 최대 3개 관리
- 같은 DB와 질문의 중복 기록 제거 및 최신순 정렬
- 최근 질문 다시 실행
- 결과 활용 버튼과 최근 기록 카드의 반응형 레이아웃 구현

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
5. 제출 직후 로딩 스켈레톤이 나타나고 제출 버튼이 비활성화되는지 확인합니다.
6. 로딩 중 요청 취소를 누르면 직전 결과가 유지된 채 대기 상태로 돌아가는지 확인합니다.
7. 3번째 요청이 실패하면 오류 코드와 안내 문구가 표시되는지 확인합니다.
8. 재시도를 누르면 같은 질문으로 다시 요청해 결과가 표시되는지 확인합니다.
9. SQL 복사 후 버튼이 `Copied!` 상태로 바뀌는지 확인합니다.
10. 성공 요청이 최근 질문에 추가되고 최대 3개까지만 표시되는지 확인합니다.
11. 최근 질문을 다시 실행해도 중복 기록이 생기지 않는지 확인합니다.
12. 모바일 화면에서도 최근 질문과 실행 버튼을 사용할 수 있는지 확인합니다.

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

현재는 실제 Text-to-SQL 서버 대신 지연과 실패를 재현하는 Mock 비동기 호출을 사용하며, `requestQuery`가 `Promise`와 `AbortSignal` 계약을 그대로 따르므로 이후 `fetch` 구현으로 교체할 수 있습니다. 다음 단계에서는 컴포넌트 테스트와 접근성 점검, 상태 로직 리팩터링을 진행할 예정입니다.
