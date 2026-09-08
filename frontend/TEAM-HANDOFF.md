# 프론트엔드 전달 안내

## 실행

Node.js 22.13 이상에서 아래 명령을 실행합니다.

```bash
cd frontend
npm ci
npm run dev -- --port 3001
```

접속 주소: http://localhost:3001

```bash
npx tsc --noEmit --incremental false
npm run build
```

## 현재 범위

- React / TypeScript 기반 UI이며 현재 실행 환경은 Vinext / Vite입니다.
- 온보딩, 회원가입·로그인 UI, 이메일 인증 진입 화면, 음악 플레이어, 플레이리스트, 프로필 및 취향 화면을 포함합니다.
- 곡별 시점 댓글: 작성 시점 고정, 시간 클릭 시 재생 이동, 정렬, 수정·삭제를 제공합니다. 브라우저 로컬 저장이며 사용자 간 공유나 서버 인증은 구현하지 않았습니다.
- 실제 로그인·회원가입·이메일 인증 및 추천 서버 API는 아직 연결되지 않았습니다. 연결되지 않은 인증을 성공 처리하지 않습니다.
- 이메일 링크 진입 경로는 `/email/verify?action=SIGNUP&code=인증번호` 또는 `/email/verify?action=PW_RESET&code=인증번호`입니다.
- 서버 연동 경계는 `lib/services/`와 `docs/server-integration.md`, 이메일 인증은 `docs/email-verification.md`를 참고하세요.
- 개인 임시 Sites 배포의 프로젝트 ID는 제거했습니다. 이 저장소에 올리는 것만으로 기존 임시 사이트가 배포되지는 않습니다.
- 현재 Cloudflare/Vinext 빌드 구성을 유지했습니다. 팀 EC2/Nginx 배포로의 전환은 별도 작업입니다.

기존 README의 자산 제작 기록 및 개인 배포 관련 문구는 과거 이력이며, 팀 전달 범위는 이 문서를 기준으로 확인해 주세요.
