# 컨벤션 (convention)

**팀이 지키기로 한 규칙.** 커밋·브랜치·MR·문서를 남기기 전에 읽는다.

여기는 *지켜야 할 규칙*만 담는다. 왜 그렇게 정했는지는 [decision/](../decision/README.md), 어기다 깨진 사례는 [failure/git-and-convention.md](../failure/git-and-convention.md) 에 있다.

| 문서 | 다루는 것 |
|---|---|
| [git.md](git.md) | 브랜치 이름 규칙, 커밋 메시지 형식, husky 티켓 자동 삽입, 커밋·푸시 시점 |
| [merge-request.md](merge-request.md) | MR 정책, 파이프라인이 실제로 검사하는 범위 |
| [api-docs.md](api-docs.md) | Bruno 컬렉션 구조와 요청 파일 추가 절차 |
| [testing.md](testing.md) | 백엔드·프론트 테스트 규약 |
| [files.md](files.md) | 줄바꿈 정책, 실행 권한 |
| [documentation.md](documentation.md) | 회고 기록 규칙, 문서 갱신 의무 |

## 규약이 정의된 위치

| 파일 | 다루는 범위 | 강제 수단 |
|---|---|---|
| `README.md` | 커밋 훅 설치·동작 (팀 전원 대상) | 없음 (안내) |
| `.husky/commit-msg` | 커밋 메시지에 티켓 삽입 / 티켓 없으면 거부 | **훅** (설치했을 때만) |
| `package.json` | 훅 배포용 루트 패키지 (`prepare: husky`) | — |
| `api/AGENTS.md` | `api/` 안에서의 작업·커밋·브랜치 규칙 | 합의 |
| `api/docs/opencollection.yml` | API 규약 (응답 형식·오류 코드·인증·enum) | 합의 |
| `.gitattributes` | 줄바꿈 정책 | git |
| `.gitlab-ci.yml` + `infra/ci/*.yml` | 파이프라인 검증 범위 | CI |
| `회고/README.md` | 회고 파일명 규칙 | 합의 |

`git ls-files` 기준으로 `.gitlab/merge_request_templates/`, `CODEOWNERS`, `.editorconfig`, commitlint·lint-staged 설정은 저장소에 **없다.** 커밋 타입 어휘(`feat:` 등)를 검사하는 장치가 없다는 뜻이다 — 훅은 티켓 번호만 본다.

## 기계로 강제되는 정도

**대부분은 사람이 지키는 규칙이다.** 그래서 문서로 남긴다.

| 규칙 | 강제 |
|---|---|
| 커밋 티켓 번호 | ✅ `.husky/commit-msg` (`npm install` 한 로컬에서만) |
| 줄바꿈 | ✅ `.gitattributes` |
| 브랜치 네이밍 | ❌ 합의만 |
| 커밋 타입 어휘 | ❌ 검사 없음 |
| 백엔드 테스트 | ❌ **CI 는 build-only** |
| 코드 포맷 | ❌ 백엔드에 포매터·`.editorconfig` 없음 |
| 프론트 린트 | oxlint/oxfmt 존재, CI 는 build 만 검사 |
