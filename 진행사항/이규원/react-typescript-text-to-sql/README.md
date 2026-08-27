# React + TypeScript Text-to-SQL 프론트엔드 학습

React와 TypeScript의 타입 시스템과 프로젝트 구조를 학습하기 위해, 자연어 질문부터 SQL 결과 테이블까지의 데이터 흐름을 단계별로 구현하는 개인 학습 프로젝트입니다. 이 폴더는 1일차에 생성했으며 일자별 학습 브랜치에서 기능을 확장합니다.

## 1일차 구현 범위

- Vite 기반 React + TypeScript 프로젝트 구성
- `type`, `interface`, union type 적용
- `Record<string, string | number | null>`을 사용한 동적 SQL 결과 행 정의
- Generic `ApiResponse<T>`를 사용한 공통 API 응답 타입 정의
- Mock 응답을 SQL 코드와 결과 테이블로 출력

## 2일차 구현 범위

- Skeleton-2의 `db_id`, `question` 데이터 구조 확인
- `DatabaseSelect`, `QueryForm` 컴포넌트 분리
- `useState<QueryRequest>`를 사용한 Controlled Component 구현
- Select, Textarea, Form 이벤트에 TypeScript 타입 적용
- 빈 질문과 공백 입력 검증
- 제출한 요청을 Mock API와 SQL 결과 화면에 반영

## 3일차 구현 범위

- 동기 Mock 호출을 `Promise` 기반 비동기 `requestQuery` 함수로 전환
- `useQueryRequest` 커스텀 훅으로 요청 상태 로직 분리
- `idle`, `loading`, `success`, `error` 상태 연결
- `async/await`와 `try/catch`를 이용한 요청 처리
- `RequestStatusBanner`로 현재 상태 안내
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
2. 정상 제출 후 선택한 DB와 질문이 결과 화면에 반영되는지 확인합니다.
3. 제출 직후 로딩 화면이 나타나고 버튼이 비활성화되는지 확인합니다.
4. SQL 복사 후 버튼이 `Copied!` 상태로 바뀌는지 확인합니다.
5. 성공 요청이 최근 질문에 추가되는지 확인합니다.
6. 최근 질문을 다시 실행해도 중복 기록이 생기지 않는지 확인합니다.
7. 모바일 화면에서도 폼과 결과를 사용할 수 있는지 확인합니다.

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

현재는 실제 Text-to-SQL 서버 대신 지연 시간을 둔 Mock 비동기 호출을 사용합니다. 이후 `requestQuery` 내부를 `fetch` 구현으로 교체할 수 있습니다.
