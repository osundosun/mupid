# 이규원 — 개인 학습 진행사항

**담당 역할:** Frontend<br>
**학습 주제:** React + TypeScript 기반 Text-to-SQL 프론트엔드 구현<br>
**학습 기간:** 2026-08-24 ~ 2026-08-28<br>
> `master`에는 일자별 학습 기록과 브랜치명을 관리합니다.<br>
> 구현 코드는 각 일차 학습 브랜치에서 확인할 수 있습니다.

---

## 전체 학습 계획

| 일차 | 날짜 | 학습 주제 | 작업 브랜치 | 상태 |
|---|---|---|---|---|
| 1일차 | 2026-08-24 | TypeScript와 프로젝트 구조 | `study/이규원/day01-react-typescript` | ✅ 완료 |
| 2일차 | 2026-08-25 | React 컴포넌트와 입력 폼 | `study/이규원/day02-query-form` | ✅ 완료 |
| 3일차 | 2026-08-26 | 비동기 API와 요청 상태 | `study/이규원/day03-api-integration` | ✅ 완료 |
| 4일차 | 2026-08-27 | 결과 UI와 사용자 기능 | `study/이규원/day04-result-history-ui` | ✅ 완료 |
| 5일차 | 2026-08-28 | 테스트와 리팩터링 | `study/이규원/day05-test-refactor-docs` | ⬜ 예정 |

---

## 날짜별 학습 기록

| 기록 파일 | 날짜 | 주제 | 상태 |
|---|---|---|---|
| `260824.md` | 2026-08-24 | TypeScript와 프로젝트 구조 | ✅ 완료 |
| `260825.md` | 2026-08-25 | React 컴포넌트와 입력 폼 | ✅ 완료 |
| `260826.md` | 2026-08-26 | 비동기 API와 요청 상태 | ✅ 완료 |
| `260827.md` | 2026-08-27 | 결과 화면 사용자 기능 | ✅ 완료 |

각 날짜의 학습 개념, 구현 내용, 문제 해결 과정과 검증 결과는 해당 날짜의 Markdown 파일에 분리해 기록합니다.

---

## 학습 기술 스택

| 분류 | 스택 | 활용 내용 | 상태 |
|---|---|---|---|
| Language | TypeScript | API 요청·응답 및 컴포넌트 타입 정의 | 🔄 진행 중 |
| UI | React | 컴포넌트 기반 입력·결과 화면 구현 | 🔄 진행 중 |
| Build Tool | Vite | 개발 서버와 프로덕션 빌드 | ✅ 적용 |
| Code Quality | ESLint | TypeScript·React 정적 검사 | ✅ 적용 |
| Styling | CSS | 반응형 폼과 결과 테이블 | 🔄 진행 중 |
| Web API | Clipboard API | 생성 SQL 복사 | ✅ 적용 |

## 브랜치 운영 방식

```text
master                                  # 일자별 진행 README
├─ study/이규원/day01-react-typescript   # 1일차 결과
├─ study/이규원/day02-query-form         # 2일차 결과
├─ study/이규원/day03-api-integration    # 3일차 결과
├─ study/이규원/day04-result-history-ui  # 4일차 결과
└─ study/이규원/day05-test-refactor-docs # 5일차 예정
```

일차별 브랜치에서 학습 및 구현을 완료한 뒤, 날짜별 Markdown 파일과 이 README의 상태를 갱신합니다.
