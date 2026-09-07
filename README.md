# 특화프로젝트

## 커밋 컨벤션 (husky)

커밋 메시지 제목 끝에 브랜치명에서 추출한 JIRA 티켓 번호가 `[S15P21A204-xx]` 형태로 **자동 추가**됩니다.

```
feat: 로그인 API 구현        →  feat: 로그인 API 구현 [S15P21A204-44]
```

### 최초 1회 설정

레포를 클론한 뒤 **프로젝트 루트**에서 아래를 실행하면 훅이 설치됩니다.

```bash
npm install
```

### 동작 규칙

- 티켓 번호는 현재 브랜치명에서 `S15P21A204-<숫자>` 패턴으로 찾습니다.
  - 브랜치명 컨벤션: `<type>/S15P21A204-<번호>-<branch-name>` (예: `back/feat/S15P21A204-44-husky-setting`)
- 브랜치명에 티켓 번호가 없으면 커밋이 **거부**됩니다.
- 아래의 경우는 예외로 두어 메시지를 변경하지 않습니다.
  - `A204 회고 봇`(`a204-retro-bot@noreply.local`)의 자동 커밋
  - merge / squash 커밋, rebase·cherry-pick·revert 진행 중의 커밋
  - 제목에 이미 해당 티켓 번호가 들어 있는 경우

훅 스크립트: [.husky/commit-msg](.husky/commit-msg)
