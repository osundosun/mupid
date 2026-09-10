# Git · 커밋 규약

9건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## Git · 커밋 규약

### 클론만 했는데 티켓 번호 없는 커밋이 그대로 만들어진다

- **원인**: git 이 추적하는 husky 파일은 `.husky/commit-msg` 하나뿐이다. 실제로 git 이 실행하는 shim 인 `.husky/_/` 는 `.husky/_/.gitignore` 가 `*` 라서 통째로 미추적이고, `core.hooksPath=.husky/_` 설정은 `package.json` 의 `prepare: husky` 가 실행돼야 생긴다.
- **해결**: 클론 직후 **저장소 루트에서 `npm install` 을 최초 1회** 실행한다.
- **근거**: `package.json`, `.husky/commit-msg`, `README.md`

### detached HEAD 나 `develop` 에서 커밋이 거부된다

- **원인**: 훅 4단계가 `git symbolic-ref --quiet --short HEAD` 로만 브랜치명을 얻는다. detached HEAD(태그 체크아웃, `git bisect`, CI 체크아웃)에서는 `BRANCH` 가 비고 `TICKET` 도 비어 `exit 1` 이 난다. `study/안규리`, `common/docs/…`, `ci/…`, `develop`, `master` 같은 티켓 없는 브랜치도 마찬가지다. 3단계가 rebase 진행 중은 봐주지만 detached HEAD 그 자체는 봐주지 않는다.
- **해결**: `HUSKY=0 git commit …` 또는 `git commit --no-verify`.
- **근거**: `.husky/commit-msg:28-40`

### `git merge --squash` 후 커밋이 거부된다

- **원인**: squash 병합 시점에는 `.git/MERGE_HEAD` 가 없고 `.git/SQUASH_MSG` 만 있는데 훅은 `SQUASH_MSG` 를 보지 않는다. 그래서 3단계 예외를 통과하지 못하고, 현재 브랜치에 티켓이 없으면 4단계에서 거부된다.
- **해결**: `git commit --no-verify` 를 쓰거나 GitLab MR 의 squash 기능(서버 측 병합이라 훅이 돌지 않음)을 쓴다.
- **근거**: `.husky/commit-msg:20-26`

### 훅 1단계(`COMMIT_SOURCE`)는 죽은 코드다

- **원인**: `COMMIT_SOURCE="$2"` 를 읽어 `merge|squash` 를 거르려 하지만, git 의 **commit-msg 훅은 인자를 1개(메시지 파일 경로)만 받는다.** 두 번째 인자를 받는 것은 prepare-commit-msg 훅이다. 따라서 1단계 `case` 는 절대 매치되지 않고, merge 커밋이 실제로 예외 처리되는 것은 **3단계의 `MERGE_HEAD` 파일 검사** 덕분이다.
- **해결**: 훅 로직을 고칠 때 1단계를 믿고 3단계를 지우면 merge 커밋 메시지가 오염된다. `README.md` 의 "merge / squash 커밋 … 메시지를 변경하지 않습니다" 중 squash 는 근거가 없다.
- **근거**: `.husky/commit-msg:6-12`

### `\[S15P21A204-` 로 로그를 grep 하면 절반 이상을 놓친다

- **원인**: 훅 5단계 중복 검사는 `index($0, ticket) == 0` — 대괄호 없는 순수 문자열로만 비교한다. 그래서 `api/AGENTS.md` 예시대로 손으로 `(S15P21A204-42)` 를 적으면 훅이 "이미 있음" 으로 보고 대괄호를 붙이지 않는다. 현재 대괄호 10건 vs 소괄호 28건이다.
- **해결**: `S15P21A204-[0-9]+` 로 검색한다.
- **근거**: `.husky/commit-msg:44-56`, `git log --all --oneline`

### 커밋 타입(`feat:` 등)이 검증되지 않는다

- **원인**: `.gitlab/merge_request_templates/`, `CODEOWNERS`, `.editorconfig`, commitlint/lint-staged 설정이 저장소에 하나도 없다. `.husky/commit-msg` 는 티켓 번호만 본다 — `아무말:` 로 시작하는 제목도 통과한다.
- **해결**: Conventional Commits 준수는 전적으로 사람 합의다.
- **근거**: `.husky/commit-msg`, `git ls-files`

### 브랜치 type 으로 CI rule 을 걸면 누락된다

- **원인**: `feat` 과 `feature` 가 섞여 있다 — `back/feat/S15P21A204-184-clickhouse-docker-compose` 와 `back/feature/S15P21A204-185-add-music-streaming-api` 가 공존한다. `api/AGENTS.md` 는 `back/<type>/<티켓>-<요약>` 이라고만 하고 type 어휘를 고정하지 않는다. `codex/study/lee-gyuwon/…`, `fix/이규원/…` 처럼 영역 접두사 규칙을 벗어난 이력도 있다.
- **해결**: 브랜치명 매칭 규칙을 쓸 때 `feat` 만 매치시키지 않는다.
- **근거**: `api/AGENTS.md`, `git branch -a`

### `.env` 무시 규칙이 `api/` 밖에서는 적용되지 않는다

- **원인**: 저장소 루트 `.gitignore` 에는 `/node_modules` 와 `/.gradle-ci/` 뿐이다. `.env` 를 무시하는 건 `api/.gitignore`(1행), `frontend/.gitignore`(`.env*`), `api/docs/.gitignore`(`.env*`) 세 곳이고 각각 자기 디렉터리 하위에만 적용된다. `git check-ignore infra/deploy/.env` 는 exit 1 — **무시되지 않는다.** `infra/deploy/` 에는 `.env.example` 이 있어 그 옆에 `.env` 를 만들기 쉽다.
- **해결**: 운영 비밀값은 서버의 `/srv/mupid/.env`(root 600)에만 둔다. 로컬에 `infra/deploy/.env` 를 만들어야 하면 먼저 무시 규칙을 추가한다.
- **근거**: `.gitignore`, `api/.gitignore`, `infra/deploy/.env.example`

### Bruno 요청 파일에 개인 계정과 평문 비밀번호가 커밋되어 있다

- **원인**: `api/docs/Auth/로그인.yml` 의 `runtime.variables` 에 계정 이메일과 `USER_PASSWORD: password123` 이 하드코딩돼 있다. `api/docs/.gitignore` 는 `.env*` 만 막으므로 `.yml` 안의 값은 걸러지지 않는다.
- **해결**: 새 요청을 추가할 때 계정 값을 요청 파일에 박지 말고 환경 변수로 빼고 값은 비워 둔다 — `api/docs/environments/Local.yml` 이 토큰류를 빈 값 + description 으로 두는 올바른 예다.
- **근거**: `api/docs/Auth/로그인.yml`, `api/docs/environments/Local.yml`

---
