# 화면 라우팅

Vinext App Router의 파일 기반 라우팅을 사용합니다. React Router는 추가하지 않습니다.

- `app/(music)/layout.tsx`: 공통 앱과 오디오를 유지하는 레이아웃
- `app/(music)/*/page.tsx`: 실제 페이지 경로 등록
- `lib/routes.ts`: 화면 키 ↔ URL 매핑과 접근 조건
- `hooks/use-mupid.ts`: useRouter로 이동하고 usePathname으로 화면 동기화
- `components/mupid/demo-login.tsx`: /login, /signup에 맞춰 기존 인증 UI 표시

서비스 경로는 /home, /explore, /player, /playlists, /report, /profile, /moments, /similar입니다.
시작 화면은 /, 취향 설정은 /onboarding입니다. 기존 ?view= 링크와 /mupid는 새 주소로 교체합니다.
인증 화면 디자인은 기존 대화상자를 유지하지만 URL과 브라우저 뒤로 가기로 상태를 관리합니다.

플레이어(/player)와 탐색(/explore)은 비로그인에게도 공개합니다. 그 외 서비스 화면은 비로그인 → 로그인 / 로그인 후 취향 미설정 → 온보딩 / 완료 회원 → 서비스 화면 순으로 제한합니다.
댓글 등록은 authenticated 세션과 양의 정수 userId가 있어야 합니다. 수정·삭제는 authorId 일치가 필요하며, ID 없는 기존 댓글은 읽기만 가능합니다. 이 조건은 프론트 UI 제어이며 서버 권한 검증을 대체하지 않습니다.
프론트 가드는 화면 이동 제어일 뿐 보안 검증이 아닙니다. 실제 API는 서버에서 인증·권한을 검증해야 합니다.
세션 복원 API는 아직 미연결이므로 새로고침하면 로그인 상태가 복원되지 않습니다.
서버 연동 시 세션 확인이 끝날 때까지 가드를 대기시키고 회원별 onboarding_submitted_at을 매핑해야 합니다.

/email/verify는 음악 레이아웃 밖에 있어 인증 가드의 영향을 받지 않습니다.
이메일 인증 페이지로 나가면 음악 레이아웃이 해제됩니다. 음악 서비스 화면 사이에서는 재생기가 유지됩니다.
