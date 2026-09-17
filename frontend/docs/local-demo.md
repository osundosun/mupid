# 로컬 음악 데모

`.env.local`에 `VITE_MUPID_LOCAL_DEMO=true`를 설정한 뒤 개발 서버를 **완전히 종료하고 다시 실행**한다. 환경 변수 변경 시 HMR만으로는 이전 값이 남을 수 있다.

- 접속: http://localhost:3000/login
- 계정: `demo@naver.com` / `1234` (실제 서비스 계정이 아님)
- 로그인, 새로고침 시 세션 복원, 로그아웃, 로컬 음악 목록·검색·커버·MP3 재생 지원
- 일시정지, 탐색, 다음/이전 곡, 볼륨 조절은 기존 플레이어 UI 사용
- AI 추천, 댓글, 서버 리포트 등 백엔드 기능은 제공하지 않으며 명시적으로 안내한다. 데모 모드에서는 서비스 API 및 청취 이벤트를 원격 서버로 보내지 않는다.

음원 파일은 `work/local-demo/audio/`, 커버는 `work/local-demo/covers/`, 목록은 `work/local-demo/catalog.json`에 둔다. 현재 원본 `flow_music_data`의 첫 20곡을 가져왔다. 목록은 `Track[]`이며 `localAudio`에 `/local-demo/audio/파일명.mp3`를 지정한다. `musicId`는 넣지 않는다. 실제 음원 길이는 ffprobe로 측정했다.

파일들은 Git에서 무시되는 `work/`에 저장하며 개발 서버에서만 제공한다. `public/`에 두지 않으므로 배포 번들에 복사되지 않는다. 개발 모드, 환경 변수, localhost 조건이 모두 맞아야 데모 로그인이 활성화된다.

실제 서비스로 돌아가려면 데모 변수를 `false`로 바꾸고 `MUPID_API_ORIGIN`을 지정한 뒤 서버를 완전히 재시작한다. 이때 실제 서비스 계정으로 로그인해야 한다.
