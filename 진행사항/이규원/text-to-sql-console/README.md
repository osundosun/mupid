# Text-to-SQL Console

React와 TypeScript로 자연어 질문부터 SQL 결과 테이블까지의 데이터 흐름을 설계하는 개인 학습 프로젝트입니다.

## 1일차 구현 범위

- Vite 기반 React + TypeScript 프로젝트 구성
- ESLint 기반 정적 검사 환경 구성
- `type`, `interface`, union type, optional-friendly row type 적용
- `Record<string, string | number | null>`을 사용한 동적 SQL 결과 행 정의
- Generic `ApiResponse<T>`를 사용한 공통 API 응답 타입 정의
- 타입이 적용된 Mock Text-to-SQL 데이터 작성
- Mock 응답을 SQL 코드와 결과 테이블로 렌더링
- API, 컴포넌트, 훅, Mock, 타입, 유틸리티 디렉터리 분리

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

현재는 타입과 화면 구조를 확인하기 위해 Mock 응답을 사용합니다. 다음 단계에서 질문 입력 컴포넌트와 비동기 API 상태를 연결할 예정입니다.
