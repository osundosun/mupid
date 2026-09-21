# 특화프로젝트

## 커밋 컨벤션 (husky)

커밋 메시지 제목 끝에 브랜치명에서 추출한 JIRA 티켓 번호가 `(S15P21A204-xx)` 형태로 **자동 추가**됩니다.

```
feat: 로그인 API 구현        →  feat: 로그인 API 구현 (S15P21A204-44)
```

> 과거 훅은 대괄호 `[S15P21A204-44]` 를 넣었습니다. `ce28ead (S15P21A204-185)` 이후로는
> 소괄호입니다. 이력에 두 형태가 섞여 있으니 로그를 티켓으로 검색할 때는
> `S15P21A204-[0-9]+` 로 찾으세요(대괄호 형태도 `\(S15P21A204-` 로는 안 잡힙니다).

### 최초 1회 설정

레포를 클론한 뒤 **프로젝트 루트**에서 아래를 실행하면 훅이 설치됩니다.

```bash
npm install
```

`.husky/_` 러너는 `npm install` 이 만드는 **로컬 산출물**이라 저장소에 들어가지 않습니다.
그래서 **linked worktree 마다 따로 필요합니다** — 설정(`core.hooksPath`)은 공유되지만 러너는
worktree 별 로컬 파일이기 때문입니다. 새 worktree 에서 훅이 안 도는 것 같으면 그 worktree
루트에서 `npm install` 을 한 번 더 실행하세요. 자세한 증상과 확인 절차는
[함정 기록](api/knowledge/failure/git-and-convention.md)에 있습니다.

### 동작 규칙

- 티켓 번호는 현재 브랜치명에서 `S15P21A204-<숫자>` 패턴으로 찾습니다.
  - 브랜치명 컨벤션: `<type>/S15P21A204-<번호>-<branch-name>` (예: `back/feat/S15P21A204-44-husky-setting`)
- 브랜치명에 티켓 번호가 없으면 커밋이 **거부**됩니다.
- 제목이 `<type>(<scope>): <설명>` 형식이 아니면 커밋이 **거부**됩니다. `type` 은
  `feat`·`fix`·`docs`·`chore`·`refactor`·`test`·`ci` 7종이고 `scope` 는 선택입니다.
- 아래의 경우는 예외로 두어 메시지를 변경하지 않습니다.
  - `A204 회고 봇`(`a204-retro-bot@noreply.local`)의 자동 커밋
  - merge / squash 커밋, rebase·cherry-pick·revert 진행 중의 커밋
  - 제목에 이미 해당 티켓 번호가 들어 있는 경우

훅 스크립트: [.husky/commit-msg](.husky/commit-msg)
