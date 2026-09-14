# Git 컨벤션

브랜치 이름과 커밋 메시지. **커밋 훅이 실제로 강제하는 유일한 규약**이 여기 있다.

[← 컨벤션](README.md) · [지식 저장소](../README.md)

---

## 브랜치

### 이름 규칙

두 문서가 조금 다르게 적는다.

- `README.md` — `<type>/S15P21A204-<번호>-<branch-name>` (예: `back/feat/S15P21A204-44-husky-setting`)
- `api/AGENTS.md` — `back/<type>/<티켓>-<요약>` (예: `back/chore/S15P21A204-41-create-spring-project`)

합치면 `<영역>/<type>/S15P21A204-<번호>-<영문-요약>` 이다.

### 영역 접두사

원격에 실제로 존재하는 접두사와 티켓 번호 사용 여부.

| 접두사 | 예시 | 티켓 번호 |
|---|---|---|
| `back/` | `back/feat/S15P21A204-184-clickhouse-docker-compose` | 붙는다 |
| `front/` | `front/chore/ec2-deploy` | 없다 |
| `infra/` | `infra/chore/pre-integration-deploy-0909` | 없다 |
| `ci/` | `ci/claude-project-review` | 없다 |
| `common/docs/` | `common/docs/add-reports-yejun-jung` | 없다 |
| `study/<한글이름>` | `study/이규원/day01-react-typescript`, `study/안규리` | 없다 |

`api/AGENTS.md` 가 "백엔드 외 작업은 `study/<이름>/…`, `common/docs/…` 를 쓴다"라고 이 이원 체계를 명문화했다. 학습 기간(2026-08-24~28)에는 개인 학습 브랜치를, 본개발 진입 후에는 도메인 접두사를 쓴다.

**티켓 번호가 없는 브랜치에서는 커밋 훅이 커밋을 거부한다.** 아래 "훅이 커밋을 거부하는 경우" 참조.

### type 어휘는 고정돼 있지 않다

원격에 `back/feat/S15P21A204-184-...` 와 `back/feature/S15P21A204-185-...` 가 공존한다. `api/AGENTS.md` 는 `<type>` 자리의 어휘를 정해 두지 않았다. 브랜치명으로 필터링하는 CI rule 을 새로 쓸 때 `feat` 만 매치시키면 누락된다.


## 커밋 메시지

### 형식

```
<type>: <한글 설명> (S15P21A204-<번호>)
```

한글 Conventional Commits 다 (`api/AGENTS.md`). 티켓 번호는 제목 맨 끝에 붙는다.

### 허용 타입 (10종)

| 타입 | 쓰는 때 |
|---|---|
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 동작은 그대로, 구조만 바꿈 |
| `perf` | 성능 개선 |
| `test` | 테스트 추가·수정 |
| `docs` | 문서 (지식 저장소, README, API 문서) |
| `ci` | 파이프라인 정의 (`infra/ci/`, `.gitlab-ci.yml`) |
| `chore` | 위 어디에도 없는 잡일 |
| `chore(deps)` | 의존성 추가·제거·버전 변경 |
| `chore(conf)` | 설정값 (`application.yaml`, `.env.example`, compose) |

이 열 가지만 쓴다. `chore` 의 스코프는 `deps` 와 `conf` 둘뿐이고 그 외에는 스코프를
붙이지 않는다.

> 과거 커밋에는 `docs(retro):`, `chore(ci):`, `fix(ci):`, `chore(front):` 처럼 이 목록
> 밖의 스코프가 남아 있다. 되돌리지 않되 새로 쓰지는 않는다 — 파이프라인 변경은
> `chore(ci)` 가 아니라 `ci` 다.

### 실제로 쓰인 타입

현재 브랜치 164 커밋의 제목 기준.

| 타입 | 건수 | | 타입 | 건수 |
|---|---:|---|---|---:|
| `docs:` | 45 | | `fix:` | 5 |
| `docs(retro):` | 20 | | `chore(ci):` | 4 |
| `chore:` | 17 | | `test:` | 1 |
| `feat:` | 16 | | `fix(ci):` | 1 |
| `refactor:` | 7 | | `chore(front):` | 1 |
| `ci:` | 6 | | `merge:` | 1 |

나머지는 GitLab 병합 커밋 31건과 초기 정리 커밋들(`Delete README.md`, `회고 폴더 신설: 날짜별 기록 구조` 등)이다.

### 티켓 번호 자동 삽입 (husky)

**설치는 저장소 루트에서 `npm install` 한 번.** `package.json` 의 `"prepare": "husky"` 가 `core.hooksPath` 를 `.husky/_` 로 설정한다.

git 이 추적하는 husky 파일은 `.husky/commit-msg` **하나뿐**이다. 실제로 git 이 실행하는 shim 인 `.husky/_/` 는 `.husky/_/.gitignore` 가 `*` 라 통째로 미추적이다. 즉 클론 직후에는 훅이 전혀 걸리지 않고 티켓 없는 커밋이 조용히 만들어진다. `README.md` 가 이것을 "최초 1회 설정"으로 안내하는 이유다.

루트에 node 프로젝트가 있는 이유도 이 훅 하나 때문이다 — `package.json` 의 `description` 이 그대로 "Git hook(husky) 관리용 루트 패키지"다. 훅을 배포하려고 만든 껍데기이지 루트가 node 프로젝트인 것이 아니다.

이 훅 자체가 회고에서 나왔다. `회고/2026-09-04.md` 정예준 항목의 Try 가 "Git 커밋 후 자동으로 지라 티켓 연결해주는 자동화 시도"이고, 3일 뒤 `f205ec5 chore: husky JIRA 티켓 설정 추가 [S15P21A204-44]` (2026-09-07) 가 들어왔다.

### 훅의 5단계

`.husky/commit-msg` 를 위에서부터.

1. `COMMIT_SOURCE="$2"` 가 `merge`/`squash` 면 `exit 0` — **동작하지 않는 죽은 코드다.** commit-msg 훅은 인자를 1개만 받는다. 상세는 [함정과 실패 사례](../failure/git-and-convention.md).
2. 저자가 `"A204 회고 봇 <"*` 또는 `*"<a204-retro-bot@"*` 이면 `exit 0` — 회고 취합 자동 커밋 20건을 위한 예외. 이름과 이메일 두 패턴으로 이중 검사한다.
3. `$(git rev-parse --git-dir)` 아래 `rebase-merge`/`rebase-apply` 디렉터리, `CHERRY_PICK_HEAD`/`REVERT_HEAD`/`MERGE_HEAD` 파일이 하나라도 있으면 `exit 0` — merge 커밋이 실제로 예외 처리되는 것은 1단계가 아니라 이 `MERGE_HEAD` 검사 덕분이다. **훅을 고칠 때 1단계를 믿고 3단계를 지우면 merge 커밋 메시지가 오염된다.**
4. `git symbolic-ref --quiet --short HEAD` 로 브랜치명을 얻어 `grep -oE 'S15P21A204-[0-9]+' | head -n 1`. 비어 있으면 안내 4줄을 출력하고 `exit 1`.
5. awk 로 주석(`^[[:space:]]*#`)·빈 줄을 건너뛴 첫 줄에, 티켓이 아직 없으면 뒤쪽 공백을 지우고 ` (<티켓>)` 을 덧붙인다.

### 괄호 형태의 변천

현재 훅이 자동으로 만드는 형태는 소괄호다.

```
chore: 셸 스크립트 실행 권한 부여 (S15P21A204-184)
```

`api/AGENTS.md` 의 예시도 같은 소괄호이고, 손으로 적은 커밋도 이 형태를 따랐다.

```
fix: 회원 정보 수정 시 비밀번호가 평문으로 저장되는 문제 수정 (S15P21A204-42)
```

과거 훅은 대괄호 `[S15P21A204-N]` 를 넣었다. 5단계의 중복 검사가 `index($0, ticket) == 0` — 괄호를 뺀 순수 문자열 `S15P21A204-42` 로만 비교하기 때문에 대괄호도 "이미 있음"으로 판정돼 그대로 통과한다. 그래서 이력에는 `[S15P21A204-184]` 와 `(S15P21A204-42)` 두 형태가 섞여 있다. 혼재는 버그가 아니라 이 설계의 직접적 산물이다.

> 로그를 티켓으로 검색할 때는 `S15P21A204-[0-9]+` 로 찾는다. `\(S15P21A204-` 만 쓰면 대괄호 이력을 놓친다.

한 브랜치 안에서는 형태를 통일한다.

### 훅이 커밋을 거부하는 경우

- 브랜치명에 티켓 번호가 없을 때 — `study/안규리`, `common/docs/…`, `ci/…`, `front/…`, `infra/…`, `develop`, `master` 전부 해당
- detached HEAD (태그 체크아웃, `git bisect` 등) — 4단계가 `git symbolic-ref` 로만 브랜치명을 얻기 때문

우회는 `git commit --no-verify` 또는 `HUSKY=0 git commit …`. `git merge --squash` 도 예외가 아니다 — 상세는 [함정과 실패 사례](../failure/git-and-convention.md).

티켓을 못 찾으면 거부까지 하는 것은 "티켓을 조용히 빼먹은 커밋을 만들지 않겠다"는 선택이고, 대가로 티켓을 쓰지 않는 브랜치 흐름 전체가 훅과 충돌한다. 이 훅은 팀 전체가 아니라 지라 티켓을 쓰는 백엔드 작업 흐름만 겨냥해 설계됐다.

### 커밋·푸시 시점

`api/AGENTS.md` 마지막 줄 — **"커밋·푸시는 요청받았을 때만 한다."** AI 에이전트로 작업할 때 특히 지킨다.
