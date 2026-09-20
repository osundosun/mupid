# Git · 커밋 규약

10건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## Git · 커밋 규약

### 라벨 없이 MR 을 만들면 CI 가 빨간불로 남는다

- **원인**: `area-preflight`(`infra/ci/labels.yml`)가 변경 경로를 스스로 계산해(=`git diff`)
  영역 라벨과 맞춰 보고, 모자라면 실패한다. GitLab 의 `rules` 는 **파이프라인을 만드는 시점**에
  `CI_MERGE_REQUEST_LABELS` 를 읽는데, MR 을 만든 직후 라벨을 붙이면 그 시점의 값은 이미 비어 있다.
- **증상**: 로그가 두 시점을 한 화면에 보여줘서 헷갈린다. `area-preflight` 의 판정은 실행 시점이라
  **최신 라벨을 정확히 안다** — "이 MR 은 Frontend 영역을 건드렸는데 Frontend 라벨이 없습니다" 라고
  말한 뒤 바뀐 경로와 복구 명령을 찍는다. 반면 영역 verify job 은 `rules` 가 `when: never` 로
  걸러 둔 상태라 **목록에 아예 없다.** 그래서 "라벨은 붙어 있는데 검증은 하나도 안 돌았다" 가 된다.
- **해결**: 라벨을 **MR 생성과 같은 명령에서** 붙인다. 나중에 붙였다면 **새 파이프라인을 만든다** —
  로그가 그 두 줄을 그대로 찍어 주니 복사해서 쓴다.
  ```bash
  # 만들 때 함께 (이렇게 해야 rules 가 처음부터 라벨을 본다)
  glab mr create --label Backend --label Frontend ... --label Infra
  # 이미 만들었다면 라벨을 붙인 뒤 **새 파이프라인**
  glab mr update <iid> --label Data
  glab api --method POST "projects/<프로젝트>/merge_requests/<iid>/pipelines"
  ```
  새 파이프라인을 만들면 `area-preflight`·`area-gate` 가 통과하고 영역 verify job 이 나타난다.
  그게 라벨이 인식된 증거다.
- **함정: job 재시도(↻)는 복구가 아니다.** GitLab 문서 그대로 —
  *"The configuration for a pipeline is only fetched when the pipeline is created. When you rerun a
  job, uses the same configuration each time."* 실측(2026-09-17, MR !102):
  파이프라인 `200778` 의 라벨 게이트를 재시도한 job `556721` 은 스크립트가 최신 라벨
  (`AI,Backend,Data,Frontend`)을 찍고도 실패했고, 그 파이프라인의 job 목록에는 영역 verify 가
  없었다. **job 재시도가 만드는 것은 job 의 실행이지 job 자체가 아니다.**
  같은 커밋(`18b464c9`)으로 **새 파이프라인을 만들면** 영역 verify 4개가 전부 돌아 success 였다
  (파이프라인 `200895`). 검증 범위는 커밋의 함수가 아니라 **트리거의 함수**다.
- **주의**: 라벨을 나중에 붙여도 **이미 만들어진 파이프라인은 되살아나지 않는다.** GitLab 은
  라벨 변경으로 MR 파이프라인을 다시 돌리지 않는다. 그래서 `area-gate`(`infra/ci/area-gate.yml`)가
  따로 있다 — 영역 job 이 남긴 marker(`.areas/<영역>`)와 기대 목록을 대조해, job 목록이 아니라
  **실제로 돌아서 성공한 검증**을 본다.
- **`area-gate` 는 별도 stage 에 있고 needs 가 없다.** 영역 job 을 `needs` 로 걸면 그 job 이
  실패했을 때 게이트가 `skipped` 로 가서 **누락 원인을 말해 줄 사람이 사라진다.** stage 를 나누고
  `when: always` 를 주면 영역 job 이 실패해도 게이트가 반드시 돌아 실패 원인을 보고한다. needs 가
  없으면 앞 stage 의 아티팩트를 기본으로 받으므로 영역 job 목록을 손으로 적지 않아도 된다.
  그래서 게이트는 누락을 두 원인으로 갈라 말한다 — ① 그 영역 job 이 실패했다(그 job 의 로그를 본다)
  ② 그 영역 job 이 파이프라인에 아예 없다(라벨을 붙이고 새 파이프라인을 만든다).
- **라벨을 하나만 붙이는 것도 같은 클래스의 실패다.** 영역 job 하나가 조용히 빠지고 파이프라인은
  초록으로 보인다. `b0130b2` 는 `infra/ci/data.yml` 을 건드렸는데 라벨은 `Backend,Frontend` 뿐이라
  `data-verify` 가 돌지 않았고 파이프라인 `200362` 는 `success` 였다. 빠진 job 은 흔적을 남기지 않는다.
- **근거**: `infra/ci/labels.yml`, `infra/ci/area-gate.yml`, `infra/ci/area_diff.py`,
  `infra/ci/{frontend,backend,ai-server,data,infra}.yml` 의 `rules`,
  `knowledge/convention/merge-request.md`(영역 라벨 항목)

### 클론만 했는데 티켓 번호 없는 커밋이 그대로 만들어진다

- **원인**: git 이 추적하는 husky 파일은 `.husky/commit-msg` 하나뿐이다. 실제로 git 이 실행하는 shim 인 `.husky/_/` 는 `.husky/_/.gitignore` 가 `*` 라서 통째로 미추적이고, `core.hooksPath=.husky/_` 설정은 `package.json` 의 `prepare: husky` 가 실행돼야 생긴다.
- **해결**: 클론 직후 **저장소 루트에서 `npm install` 을 최초 1회** 실행한다.
- **근거**: `package.json`, `.husky/commit-msg`, `README.md`

### linked worktree 마다 husky 훅이 죽어 있는데 `core.hooksPath` 는 정상으로 보인다

- **원인**: husky 의 러너 `.husky/_/` 는 `npm install` 의 `prepare: husky` 가 만드는 **로컬 산출물**이고, husky 가 스스로 만들어 둔 `.husky/_/.gitignore`(`*`) 때문에 저장소에 들어가지 않는다. 그래서 러너는 **worktree 마다 따로** 필요하다. 그런데 `core.hooksPath` 는 `.husky/_` 라는 **상대 경로**로 공유 `.git/config` 에 한 번만 저장된다 — linked worktree 전부가 같은 값을 본다. 그래서 러너가 없는 worktree 에서도 설정 조회 결과는 멀쩡하고, 확인해 보기 전까지 아무도 이상하다고 느끼지 못한다.
- **증상**: `git config --get core.hooksPath` 가 `.husky/_` 를 정상적으로 돌려주는데 커밋은 **전혀 검증되지 않는다.** `<type>(<scope>)` 도 티켓 번호도 없는 제목이 그대로 커밋된다. **git 은 존재하지 않는 hooksPath 를 오류로 알려주지 않는다** — 훅 파일이 없으면 그냥 건너뛴다.
- **실측**(2026-09-20, 티켓 S15P21A204-376): worktree 6개 중 **4개에 러너가 없었다**(`big_data_pjt` develop, `big_data_pjt_bug_fix_3`, `big_data_gen_music_pipeline`, `big_data_recommendation`). 러너가 있던 것은 `npm install` 을 돌린 2개뿐이었다. 러너가 없는 worktree 에서 규약 위반 제목(`훅 재현용 잘못된 메시지`)으로 커밋을 시도해 **exit 0 으로 그대로 커밋됐다**(확인용 브랜치와 커밋은 실증 후 제거했다).
- **해결**: 의심되면 먼저 한 줄로 확인하고, 실패하면 **그 worktree 의 루트에서** `npm install` 을 돌린다(`prepare: husky` 가 `.husky/_` 를 다시 만든다). 저장소 본체가 아니라 **지금 작업 중인 worktree 의 루트**다.

  ```bash
  # 훅 러너 생존 확인 (지금 작업 중인 worktree 루트에서)
  hp="$(git config --get core.hooksPath)"
  [ -n "$hp" ] && [ -f "$hp/commit-msg" ] \
    && echo "OK: 훅 러너 살아 있음 ($hp)" \
    || echo "NG: 훅이 죽어 있음 → 루트에서 npm install"
  ```

- **주의 — husky 버전 확인에 `husky --version` 을 쓰지 마라.** husky 9 의 `bin.js` 는 `argv[2]` 를 **훅 디렉터리 이름**으로 해석한다(`let a = p.argv[2]` → `git config core.hooksPath \`${a}/_\``). 그래서 `./node_modules/.bin/husky --version` 은 버전을 찍는 대신 **공유 `.git/config` 의 `core.hooksPath` 를 `--version/_` 로 덮어쓴다.** 그러면 git 이 `--version/_/pre-commit` 을 실행하려다 `env: unknown option -- version/_/pre-commit` 으로 실패해, 훅이 규약 메시지(`✖ 커밋 실패: …`)가 아니라 **엉뚱한 오류로** 커밋을 막는다. 버전은 `node_modules/husky/package.json` 의 `version` 필드로 본다. 값을 망쳤다면 러너가 있는 worktree 에서 `npm install` 을 다시 돌리면 된다(`prepare: husky` 가 값을 `.husky/_` 로 되돌린다).
- **근거**: `package.json`(`prepare: husky`), `node_modules/husky/bin.js`(`argv[2]` → `index.js` 의 `git config core.hooksPath`), `.husky/_/.gitignore`, `git config --show-origin core.hooksPath`, `git worktree list --porcelain`

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

- **원인**: 훅 5단계 중복 검사는 `index($0, ticket) == 0` — 괄호 없는 순수 문자열로만 비교한다. 그래서 손으로 `(S15P21A204-42)` 를 적든 과거 훅이 넣은 `[S15P21A204-184]` 든 훅이 "이미 있음" 으로 보고 새 괄호를 붙이지 않는다. 이력에 대괄호와 소괄호가 섞여 있는 이유다.
- **해결**: `S15P21A204-[0-9]+` 로 검색한다.
- **근거**: `.husky/commit-msg:44-56`, `git log --all --oneline`

### 커밋 타입(`feat:` 등)이 검증되지 않는다

- **원인**: `.gitlab/merge_request_templates/`, `CODEOWNERS`, `.editorconfig`, commitlint/lint-staged 설정이 저장소에 하나도 없다. 검증은 `.husky/commit-msg` **5단계**(`6c2d2ce`, S15P21A204-231 에서 추가)가 한다 — `feat|fix|docs|chore|refactor|test|ci` 7종 밖의 제목은 거부된다. 다만 이 훅은 `npm install` 을 돌린 로컬에서만 돌아 CI 에는 같은 검사가 없다.
- **해결**: 훅이 있는 로컬에서는 기계가 막지만, **CI 나 훅이 죽은 worktree 에서는 여전히 사람 합의뿐이다.** 그래서 커밋 타입 어휘의 최종 방어선은 [convention/git.md](../convention/git.md) 의 "허용 타입" 표다.
- **근거**: `.husky/commit-msg:43-58`, `git ls-files`

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
