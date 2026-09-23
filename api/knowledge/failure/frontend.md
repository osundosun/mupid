# 프론트엔드

30건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

[← 함정 목록](README.md) · [지식 저장소](../README.md)

---

## 프론트엔드

### 자산 스크립트와 테스트 2개가 `ERR_MODULE_NOT_FOUND` 로 죽는다

- **원인**: `scripts/build-favicons.mjs`, `scripts/optimize-assets.mjs`, `scripts/build-cursor.mjs` 와 `tests/brand.test.mjs`, `tests/mascot.test.mjs` 가 `import sharp from 'sharp'` 를 쓰는데 `frontend/package.json` 의 dependencies·devDependencies **어디에도 sharp 가 없다.** `package-lock.json` 의 `node_modules/sharp` 는 `miniflare`(Cloudflare 계열 devDependency)의 전이 의존성이다. CI 는 `MUPID_DEPLOY_TARGET=ec2` 로 빌드해 Cloudflare 플러그인을 아예 로드하지 않으므로 빌드 경로에서는 miniflare 가 필요 없는데 lockfile 만이 sharp 를 붙잡고 있다.
- **해결**: 자산 스크립트를 계속 쓸 거라면 `sharp` 를 devDependencies 에 명시적으로 추가한다. Cloudflare 의존성을 정리하면 한꺼번에 깨진다.
- **근거**: `frontend/package.json`, `frontend/package-lock.json`

### `next` 가 설치되지 않았는데 소스가 `next/*` 를 import 한다

- **원인**: `package.json` 에 `next` 가 없고 `package-lock.json` 에 `node_modules/next` 항목도 **0건**이다. 그런데 `app/layout.tsx`, `app/email/verify/page.tsx` 가 `from 'next'` 로 타입을 가져오고 `components/mupid/primitives.tsx`, `components/mupid/vinyl-record.tsx` 가 `next/image` 를 쓴다. 해석은 vinext 와 `@vinext/types@1.0.0-beta.2` 에 전적으로 의존한다(`tsconfig.json` 의 `"types": [… "vinext/types"]`).
- **해결**: vinext 가 `1.0.0-beta.5` 고정이라 버전을 올릴 때 이 shim 이 깨질 수 있다. 에디터·`tsc` 가 `vinext/types` 를 못 잡으면 `Cannot find module 'next'` 가 쏟아진다. `next.config.ts` 도 `import type { NextConfig } from 'next'` 를 쓰는데 이 파일은 저장소 어디에서도 참조되지 않으면서 `tsconfig.json` 의 `include` 에는 걸린다.
- **근거**: `frontend/package.json`, `frontend/tsconfig.json:12`

### `npm start` 가 동작하지 않는다

- **원인**: `"start": "wrangler dev --config dist/server/wrangler.json"` 인데, `MUPID_DEPLOY_TARGET=ec2` 로 빌드하면 `vite.config.ts` 가 Cloudflare 플러그인을 건너뛰므로 `dist/server/` 가 생성되지 않는다.
- **해결**: 어느 타깃으로 빌드했는지 먼저 확인한다. CI 는 EC2 타깃으로만 빌드하고 `dist/client/index.html` 존재만 확인하므로 **Cloudflare 경로는 CI 에서 한 번도 빌드되지 않는다** — 그쪽이 깨져도 파이프라인이 잡아주지 못한다.
- **근거**: `frontend/package.json`, `infra/ci/frontend.yml`

### 테스트를 돌리면 실패가 나오는데 코드는 멀쩡하다

- **원인**: 소스 텍스트를 정규식·`includes` 로 검증하는 테스트가 리팩터링에 그대로 깨졌다. 현재 확인된 낡은 단언:
  - `tests/mascot-details.test.mjs:31`, `tests/mascot.test.mjs:112` 의 `src="/mupid-cupid-side.webp"` — `components/mupid/mascot.tsx` 는 더 이상 `<img>` 를 쓰지 않고 `.mupid-mascot` 클래스만 붙이는 `<span>` 이며 그림은 CSS 배경으로 옮겨갔다. 소스에서 `mupid-cupid-side` 참조는 테스트와 `scripts/optimize-assets.mjs` 에만 남아 있다.
  - `tests/playback-details.test.mjs:45` 의 `formatTime(m.time)` — `components/mupid/mini-timeline.tsx:14` 는 스크럽 중 draft 값을 쓰도록 바뀌어 `formatTime(position)` 이다.
  - `tests/reference-layout.test.mjs:43` 의 `/\.player-waveform:focus-within\s*\{[^}]*outline:/` — `app/mupid.css:1263` 이 `.player-waveform:has(.playback-range:focus-visible)` 로 바뀌었다.
- **해결**: **테스트 실패가 곧 버그를 뜻하지 않는다.** UI 를 손대면 해당 단언을 함께 갱신한다. `tests/typography.test.mjs` 는 `chat-handwriting` 등장 횟수가 정확히 4일 것을, `tests/brand.test.mjs` 는 `--record-tone: saturate(0.45) contrast(0.76) brightness(1.06)` 문자열이 그대로 있을 것을 요구한다.
- **근거**: 위 각 파일

### 테스트가 CI 에도 npm 스크립트에도 없다

- **원인**: `frontend/tests/` 에 `node:test` 기반 `.test.mjs` 21개가 추적되고 있지만 `package.json` scripts 는 `dev/build/start/lint/format` 뿐이라 `npm test` 가 없다. 실행 방법은 `frontend/README.md` 의 `node --experimental-strip-types --test` 한 줄뿐이다.
- **해결**: 프런트를 바꿨으면 이 명령을 직접 돌린다. oxlint(`.oxlintrc.json`)와 `tsc --noEmit` 도 CI 에서 돌지 않는다.
- **근거**: `frontend/package.json`, `frontend/README.md`, `infra/ci/frontend.yml`

### `npm run format` 을 전체 실행하면 리뷰 불가능한 diff 가 나온다

- **원인**: `.oxfmtrc.json` 이 `printWidth: 80` 인데 실제로 적용되지 않은 파일이 많다 — `lib/services/index.ts` 는 80자 초과 라인이 25개, `hooks/use-mupid.ts` 는 18개, `lib/services/contracts.ts` 는 3개인 반면 `app/layout.tsx` 등은 0개다.
- **해결**: 전체 실행하지 말고 건드린 파일만 포맷한다. 소스 텍스트를 정규식으로 검증하는 테스트들도 함께 깨진다.
- **근거**: `frontend/.oxfmtrc.json`

### grep 결과에 오래된 중복 구현이 계속 걸린다

- **원인**: 죽은 코드가 상당량 남아 있다. `lib/demo-session.ts` 의 `createDemoSession` 은 어떤 컴포넌트도 import 하지 않고 `tests/demo-session.test.mjs` 만 붙잡고 있다 — 실제 세션 타입은 `lib/services/contracts.ts` 의 `AccountSession` 이다. `components/ui/` 60개 중 앱이 실제로 쓰는 것은 alert-dialog, carousel, dialog, pagination, slider, tabs 6개뿐이고 `hooks/use-mobile.ts` 는 미사용인 `components/ui/sidebar.tsx` 만 참조한다.
- **해결**: 코드를 찾을 때 `prototype/` 과 위 파일들의 히트는 무시한다. 프로토타입 단일 파일 앱(`prototype/components/mupid-app.tsx`)은 `frontend/` 에서는 이미 지웠다.
- **근거**: `frontend/lib/demo-session.ts`

### 폐쇄망·차단 환경에서 데모가 통째로 실패한다

- **원인**: Pretendard 9종을 `https://cdn.jsdelivr.net/gh/projectnoonnu/pretendard@1.0/` 에서 로드하고(`app/globals.css` 에 9건), 데모 곡 8개의 `audio` 필드가 전부 `https://www.soundhelix.com/examples/mp3/…` 다(`lib/mupid-data.ts` 에 8건). `dist/client` 를 그대로 EC2 정적 호스팅에 올린다.
- **해결**: 외부 도메인이 막히면 폰트는 대체 스택(`-apple-system`, `Malgun Gothic`)으로 떨어지고 재생은 전부 실패해 "음원을 불러오지 못했어요. 다른 곡을 선택해 주세요." 토스트만 뜬다. 시연 환경을 미리 확인한다.
- **근거**: `frontend/app/globals.css`, `frontend/lib/mupid-data.ts`

### `lib/track-comments.ts` 가 서비스 계층을 우회한다

- **원인**: `frontend/docs/server-integration.md` 는 "브라우저 저장은 여기에서만 접근합니다"(= `lib/services/index.ts`)라고 규정하지만, `trackComments.list()`/`save()` 는 `localStorage` 를 직접 호출하고 키(`mupid.track-comments.v1`)도 따로 관리한다. `MupidService` 에 댓글 메서드가 없다.
- **해결**: 서버 연결 시 이 파일만 별도로 갈아끼워야 하고, 로그아웃 시 개인정보 제거 대상에서 빠지기 쉽다는 점을 기억한다. 파일 주석도 이를 인정한다 — 실제 작성자 신원과 인가는 나중에 서버에서 와야 한다.
- **근거**: `frontend/lib/track-comments.ts`, `frontend/docs/server-integration.md`

### `demoSession` 을 인증 근거로 쓰면 안 된다

- **원인**: 프런트는 데모 로그인 성공 처리를 제거했고 비밀번호를 저장·전송하지 않는다. `demoSession` 은 React 메모리에만 존재하며 새로고침하면 초기화된다.
- **해결**: `frontend/docs/server-integration.md` 의 규칙을 지킨다 — 서버는 클라이언트의 `demoSession` 을 인증 근거로 쓰지 않는다. API 모드가 미구현이면 오류를 내고 데모로 **자동 우회하지 않는다.** 실제 인증 연결 시 기존 데모 저장 내용을 사용자 계정에 자동 업로드하지 않는다. 추천 어댑터는 Spring API 만 호출하고 브라우저를 내부 FastAPI/Kafka/DB 에 직접 연결하지 않는다.
- **근거**: `frontend/docs/server-integration.md`

### README 의 손글씨 폰트 설명이 코드와 반대다

- **원인**: `frontend/README.md:20` 은 "AI 대화·추천 질문·입력창에만 … `GriunGyuwonThin-small.ttf`(그리운 규원체 Thin)를 적용합니다" 라고 적지만, `app/globals.css` 에는 해당 `@font-face` 가 없고 `--font-chat: var(--font-mupid)` 로 Pretendard 를 쓴다. `tests/typography.test.mjs:10` 의 `assert.doesNotMatch(css, /Griun|Gyuwon/)` 가 재유입을 막고 있다 — **롤백된 결정인데 README 만 남았다.**
- **해결**: README 를 근거로 판단하지 않는다. `public/fonts/GriunGyuwonThin-small.ttf` 도 참조 없이 남아 있다.
- **근거**: `frontend/README.md:20`, `frontend/app/globals.css`, `frontend/tests/typography.test.mjs:10`

### `scripts/remove-mascot-checkerboard.py` 는 저장소에서 실행할 수 없다

- **원인**: 입력·출력 경로가 저장소 밖 개인 경로로 하드코딩되어 있고(임시 클립보드 파일은 이미 없다) 다른 `.mjs` 스크립트와 달리 `import.meta.url` 상대 경로를 쓰지 않는다.
- **해결**: 일회성 실행 기록으로만 본다. 마스코트 자산을 다시 만들려면 경로부터 고친다.
- **근거**: `frontend/scripts/remove-mascot-checkerboard.py`

### `prototype/` 에서 Sites 배포를 실행하면 개인 프로토타입을 덮어쓴다

- **원인**: `frontend/.openai/hosting.json` 은 `{"d1": null, "r2": null}` 로 정리됐지만 `prototype/.openai/hosting.json` 에는 `"project_id": "appgprj_6a96142907848191bb46392959b6ed47"` 가 그대로 있다. `prototype/README.md` 도 이를 인정한다.
- **해결**: `prototype/` 에서 Sites 배포 명령을 실행하지 않는다.
- **근거**: `prototype/.openai/hosting.json`, `prototype/README.md`

### 로그인해도 `/explore` 새로고침이 `/home` 으로 떨어진다 — 해소됨

- **원인**: 화면 가드(`guardPath`)가 **세션 복원이 끝나기 전에** 돌았다. 새로고침 직후에는 비로그인으로 보여 `/login` 으로 밀리고, 복원이 끝나면 그 `/login` 을 다시 판정해 `/home` 으로 보낸다. 원래 주소는 그 사이에 사라진다. 북마크·새로고침이 전부 홈으로 떨어졌다.
- **해결**: 복원이 끝날 때까지(`authChecked`) 가드를 돌리지 않는다. 온보딩 여부까지 확인한 뒤에 판정해야 `/onboarding` 으로 잘못 보내지도 않는다. 그동안 화면은 "로그인 상태를 확인하고 있어요" 로 둔다 — 먼저 그리면 시작 화면이 깜빡이고 비로그인에게 앱 골격이 잠깐 보인다.
- **근거**: `frontend/hooks/use-mupid.ts`, `frontend/components/mupid-app.tsx`

### 로그아웃해도 소리가 계속 나고 앞사람의 좋아요가 남는다 — 해소됨

- **원인**: 로그아웃이 `<audio>` 만 멈췄다. 청크 스트리밍 재생기(`lib/player`)는 그대로 돌아 소리가 계속 났다. 좋아요·관심없음·최근 감상도 계정에서 끌어온 값인데 비우지 않아 다음 사람이 앞사람 기록을 봤다.
- **해결**: `getPlayer()?.stop()` 을 함께 부르고, 계정에서 끌어온 상태를 모두 비운다.
- **근거**: `frontend/hooks/use-mupid.ts` 의 `signOutDemo`

### 브라우저 저장만으로 로그인 상태가 된다 — 해소됨

- **원인**: `bootstrap` 이 `localStorage['mupid.demo-session.v1']` 를 보고 세션을 만들어 돌려줬다. 그 값만 넣으면 로그인 없이 서비스가 열린다. 표시를 되살리는 것과 인증은 다른 일이다.
- **해결**: 브라우저 저장에서 세션을 복원하지 않는다. 남아 있던 값은 지운다. 세션은 쿠키의 리프레시 토큰으로 서버에 물어 복원한다.
- **근거**: `frontend/lib/services/index.ts`

### 곡이 끝나도 다음 곡으로 넘어가지 않을 때가 있다 — 해소됨

- **원인**: `lib/player/engine.ts` 의 `drain()` 이 **이미 돌고 있으면 그냥 돌아갔다**(`if (this.draining) return`). 루프가 링 버퍼를 다 비운 직후에 도착한 청크는 아무도 처리하지 않는다. 그 자리가 마지막 청크였다면 재생은 `buffering` 에서 멈춘 채 `ended` 에 닿지 못하고, `ended` 가 없으니 다음 곡으로 넘어가는 일도 일어나지 않는다. 청크가 도착하는 시점에 달린 문제라 **간헐적으로만** 보였다.
- **해결**: 돌아가지 말고 `drainPending = true` 로 표시해 두고, 진행 중이던 루프가 `do … while (this.drainPending)` 로 한 번 더 돌게 한다. 끝난 뒤 `buffering` 이면 이어서 `play()` 한다.
- **근거**: `frontend/lib/player/engine.ts` 의 `drain()`

### 끝까지 들은 곡은 다시 눌러도 아무 일이 없다 — 해소됨

- **원인**: 재생 위치(`anchorPosition`)가 곡 끝에 붙어 있는데 그 자리에서 그대로 시작하니 곧바로 `ended` 만 다시 나갔다. 한곡 반복이 안 되던 것도 같은 이유다 — 같은 곡을 다시 `load()` 해도 위치가 끝이라 재생이 시작되지 않는다.
- **해결**: `load()` 는 같은 곡이고 상태가 `ended` 면 위치를 0 으로 되돌린다. `play()` 는 현재 위치에 재생할 청크가 없으면 처음부터 튼다.
- **근거**: `frontend/lib/player/engine.ts` 의 `load()`, `play()`

### 한 곡이 끝났는데 두 곡이 넘어간다 — 해소됨

- **원인**: 다음 곡으로 넘기는 것을 불리언 `advancing` 으로 막았는데, 비동기 `load()` **앞에서** 풀어 버려 같은 종료 신호가 두 번 들어오면 두 번 넘어갔다. 게다가 종료 판정을 `<audio>` 의 `ended` 와 재생기 상태 두 곳에서 따로 하고 있었다.
- **해결**: `<audio>` 경로를 없애 재생 경로를 청크 재생기 하나로 모으고, 넘김 방지를 곡 단위(`endedFor.current === musicId`)로 바꾼다. 다시 `playing` 이 되면 푼다.
- **근거**: `frontend/hooks/use-mupid.ts`

### 화면에 서버에 없는 곡이 섞여 보인다 — 해소됨

- **원인**: `lib/mupid-data.ts` 가 데모 곡 8개와 재생목록·저장한 구간을 상수로 들고 있었고, 서버 카탈로그는 그 위에 얹혔다. 눌러도 음원이 없으니 재생되지 않는다.
- **해결**: `tracks`·`initialPlaylists`·`initialMoments` 를 모두 빈 배열로 둔다. 곡 목록은 `lib/stream-catalog.ts` 가 `GET /api/v1/music` 으로 받아온 것만 쓴다. `Track.audio`(정적 파일 경로) 필드와 `public/demo-covers/` 50장도 함께 지웠다. 테스트가 쓰던 데모 곡은 `tests/fixtures/tracks.mjs` 로 옮겼다.
- **근거**: `frontend/lib/mupid-data.ts`, `frontend/lib/services/index.ts`, `frontend/tests/fixtures/tracks.mjs`

### 상단바에 프로필 사진이 끝내 안 보인다 — 해소됨

- **원인**: 사진을 보여 주는 자리가 둘인데(상단바의 동그란 아이콘, 마이페이지의 편집 자리) 각자 다른 데서 읽었다. 마이페이지는 `lib/profile-photo.ts` 로 로그인 여부에 따라 계정/브라우저를 갈라 읽었지만, 상단바는 `mupidService.readPhoto()` — **브라우저 저장만** 봤다. 계정에 올린 사진은 브라우저에 없으니 상단바에는 영영 나오지 않는다. `mupid-app.tsx` 의 `setPhoto` 는 그 값만 받아 늘 빈 문자열이었다.
- **해결**: 사진 상태를 `lib/profile-photo.ts` 의 `profilePhotoStore` 한 곳에 두고 두 자리가 함께 본다. 마이페이지에서 바꾸면 상단바도 그 자리에서 같이 바뀌고, 로그아웃하면 `clear()` 로 지운다. object URL 의 수명도 스토어가 쥔다 — 화면마다 만들고 놓다 보면 새는 자리가 생긴다.
- **근거**: `frontend/lib/profile-photo.ts`, `frontend/components/mupid-app.tsx`, `frontend/components/mupid/profile-photo.tsx`

### 재생 막대를 누르면 그 자리로 갔다가 되돌아왔다가 요동친다 — 해소됨

- **원인**: 두 가지가 겹쳤다.
  1. `engine.seek()` 가 **페이드아웃을 기다린 뒤에야** 위치를 바꿨다. 그 120ms 동안 티커가 200ms 마다 옛 위치를 그대로 내보내서, 막대가 누른 자리로 갔다가 원래 자리로 돌아왔다가 다시 튀었다.
  2. 화면의 `seek` 이 `startPlayback` 을 불러 **곡을 통째로 다시 걸었다.** 막대를 끄는 동안 `change` 가 쉬지 않고 들어오는데 그때마다 청취 이벤트가 나가고 최근 감상에 쌓였으며, 다시 건 재생이 옛 자리에서 시작했다가 뒤늦게 목적지로 튀었다.
- **해결**: 티커를 먼저 멈추고 자리를 확정한 뒤에 소리를 맞춘다. 옮기는 중에 또 들어오면 목적지만 갈아 두고 돌고 있는 쪽이 마지막 자리까지 이어서 처리한다(`seekPending`) — 여러 번이 한꺼번에 돌면 페이드와 예약이 엇갈린다. 화면 쪽은 이미 들고 있는 곡이면 위치만 옮기고, 그 곡을 아직 안 들고 있을 때만 새로 튼다.
- **근거**: `frontend/lib/player/engine.ts` 의 `seek()`, `frontend/hooks/use-mupid.ts`

### 서버 장애를 "아직 등록된 음악이 없어요" 로 알린다 — 해소됨

- **원인**: `fetchCatalog` 이 실패를 빈 배열로 삼켰다. 데모 곡 위에 얹는 값이던 시절에는 앱이 열리기만 하면 됐으니 맞는 판단이었지만, 데모 곡을 없앤 뒤로는 이것이 곡 목록의 전부다. `MupidApp` 은 "0곡" 과 "못 받아옴" 을 구별할 수 없어 서버가 죽어도 **"아직 등록된 음악이 없어요"** 라고 알렸다. 사용자는 다시 불러올 일이 아니라고 여기고 떠난다.
- **해결**: 취소(`signal.aborted`)가 아닌 실패는 그대로 던진다. 화면이 "음악을 불러오지 못했어요" 로 갈라 알린다.
- **덧붙임**: 곡이 0개여도 화면은 깨지지 않는다. `MupidApp` 의 `if (!data || !data.tracks.length)` 가 `LoadedMupidApp` 을 아예 띄우지 않아서, `useMupid` 의 `active = tracks[0]`(빈 목록이면 `undefined`)에 닿지 않는다. **다만 이 가드가 유일한 방벽이다** — 그 검사를 지나 곡 없는 `bootstrap` 이 들어오면 `active.title` 부터 터진다.
- **근거**: `frontend/lib/stream-catalog.ts`, `frontend/components/mupid-app.tsx:83`

### 곡이 하나도 없으면 앱 전체가 막힌다 — 해소됨

- **원인**: `MupidApp` 의 가드가 `if (!data || !data.tracks.length)` 였다. 곡이 0개면 `LoadedMupidApp` 을 아예 띄우지 않아, 곡이 필요 없는 마이페이지·플레이리스트·탐색 필터·리포트까지 함께 막혔다. 음악이 없는 것과 앱이 고장 난 것은 다른 일인데 화면은 똑같이 보였다.
- **왜 그렇게 되어 있었나**: `useMupid` 의 `active` 가 `tracks[0]` 로 시작하는데 타입은 `Track` 이다. 곡이 없으면 `undefined` 라 `active.title` 부터 터진다. 저 가드가 그걸 막는 **유일한 방벽**이었다.
- **해결**: `active` 를 `Track | null` 로 바꾸고 가드는 "아직 못 받아왔을 때" 만 남긴다. 곡을 다루는 화면(`PlayerView`·`MomentSelection`·`SimilarView`·`MobileListening`·`TrackComments`)은 첫 줄에서 지역 상수로 받아 없으면 빈 화면을 돌려주고, 홈의 "지금 듣는 곡" 카드·미니 플레이어·플레이리스트 담기처럼 **화면의 일부**인 자리는 그 조각만 접는다. `AlbumArt`·`VinylRecord`·`LikeButton` 은 곡이 없으면 빈 커버를 그리거나 아무것도 그리지 않는다.
- **주의**: `m.active` 를 지역 상수 `active` 로 받는 까닭은 JSX 안의 콜백에서는 `m.active` 의 좁히기가 풀리기 때문이다. `m.active` 를 그대로 쓰면 핸들러마다 `!` 를 붙이게 된다.
- **곁들여 고친 것**: `MiniTimeline` 은 끌던 값과 현재 곡이 **둘 다 없으면** `undefined === undefined` 가 참이 되어 없는 `draft` 를 읽었다.
- **근거**: `frontend/components/mupid-app.tsx`, `frontend/hooks/use-mupid.ts`, `frontend/components/mupid/*`

### 볼륨 조절이 뚝뚝 끊기고 위쪽은 줄여도 그대로다 — 해소됨

- **원인**: 세 가지가 겹쳤다.
  1. **눈금이 5%** (`step={0.05}`)여서 끌면 스무 칸을 계단처럼 뛰었다.
  2. **막대 위치를 그대로 이득에 꽂았다.** 사람은 소리 크기를 로그로 느끼는데, 1.0 → 0.5 는 -6dB 뿐이라 위쪽 절반은 줄여도 거의 그대로고 아래쪽 몇 칸에서 갑자기 사라진다. 같은 거리를 움직여도 구간마다 변화가 딴판이라 조절이 겉돈다.
  3. **한 칸마다 앱 전체가 다시 그려졌다.** 볼륨이 `useMupid` 의 state 라 값이 바뀔 때마다 모델이 새로 나가고 화면 전체가 다시 그려졌다 — 한 칸에 **32ms**. 눈금만 촘촘하게 했다가는 오히려 더 끊긴다.
- **해결**: 눈금을 1%로 하고, 이득은 `gainFor(position) = position²` 로 굽혀 귀와 눈금을 맞춘다. 볼륨은 원래 재생기가 들고 있는 값이므로 `usePlayerVolume()` 로 **그 막대만** 구독한다. `usePlayer()` 를 쓰면 안 된다 — 재생 위치가 0.2초마다 흘러 그걸 타는 화면이 계속 다시 그려진다.
- **확인**: 위치 → 이득 `0→0, .25→.062, .5→.25, .75→.562, 1→1`. 100칸 끌기가 **32.07ms/칸 → 4.47ms/칸**(약 7배).
- **건드리지 말 것**: 값을 이득에 바로 꽂지 않고 `setTargetAtTime(…, 0.02)` 로 20ms에 걸쳐 옮기는 것은 '지직' 소리를 막는 장치다. 데스크톱 홈의 `<label>` 감싸개도 `.desktop-audio-options label` 에 배치가 걸려 있어 그대로 두어야 한다.
- **근거**: `frontend/lib/player/engine.ts`, `frontend/lib/player/index.ts`, `frontend/components/mupid/volume-slider.tsx`

### 재생 목록 순서를 바꾸자마자 새로고침하면 옛 순서로 돌아간다 — 해소됨

- **원인**: 큐 저장은 조작마다 보내지 않고 **1.2초 모았다가** 보낸다(`QUEUE_SAVE_DELAY`). 그래서 보내는 순간과 사용자가 새로고침하는 순간이 자주 겹치는데, **요청이 안 나간 것이 아니라 나간 요청이 취소된다.** 평범한 `fetch` 는 문서가 사라지면 함께 버려진다. 계측에서 본문까지 올바른 요청이 그대로 죽는 것을 확인했다.

  ```
  PUT /api/v1/user/queue  keepalive=false
  body={"music_ids":[310,67,68,309],"current_music_id":314,"revision":2}
  ```

  서버 판본은 오르지 않았고, 다시 열면 직전에 저장된 순서가 내려왔다. 화면만 새 순서였으므로 "저장이 안 된다" 가 아니라 "저장했는데 사라진다" 로 보인다.
- **해결**: 큐 저장을 `keepalive` 로 보낸다(`endpoints.ts` 의 `queue.replace`). 브라우저가 문서와 무관하게 전송을 마친다. 곡 번호 목록뿐이라 keepalive 의 64KB 상한과는 거리가 멀다. 아직 타이머가 돌지 않은 변경은 떠날 때 한 번 더 밀어낸다(`use-mupid.ts` 의 flush).
- **주의 1**: 떠나는 것을 `beforeunload` 로 잡으면 안 된다. 모바일 브라우저는 탭을 버릴 때 그걸 부르지 않는 경우가 많다. `visibilitychange`(hidden)와 `pagehide` 를 본다.
- **주의 2**: 대기 중인 저장을 **보내기 시작할 때 지우면 안 된다.** 요청이 도는 중에 떠나면 "밀어낼 것이 없다" 고 판단해 그대로 유실된다. 서버가 받았다고 확인해 준 뒤에 지운다. 그래서 같은 내용이 두 번 나갈 수 있는데, 판본이 함께 가므로 뒤의 것은 409 로 거절되고 아무것도 바뀌지 않는다.
- **확인**: 언로드 시점에 같은 주소로 두 요청을 띄워 보면 `keepalive: true` 만 도착하고 평범한 `fetch` 는 도착하지 않는다. 고친 뒤 "순서 변경 직후 즉시 새로고침" 을 두 번 돌려 순서가 유지되는 것과 Redis 값이 일치하는 것을 확인했다.
- **근거**: `frontend/lib/api/endpoints.ts` 의 `queue.replace`, `frontend/lib/api/transport.ts`, `frontend/lib/api/client.ts`, `frontend/hooks/use-mupid.ts`

### 소스를 고쳤는데 브라우저가 계속 예전 코드를 실행한다 (Vite 개발 서버)

- **원인**: HMR 이 모듈 import 주소에 `?t=<timestamp>` 를 박아 넣고, 브라우저가 **그 주소째로** 캐시한다. 그래서 파일을 고쳐도 옛 판이 계속 돌 수 있다. 증상이 헷갈리는데, 이미 지운 식별자에서 `ReferenceError` 가 나고 스택이 옛 타임스탬프를 가리킨다.

  ```
  ReferenceError: options is not defined
      at Module.saveQueue (http://localhost:3000/lib/play-queue.ts?t=1789532266190:67:77)
  ```

  디스크의 `play-queue.ts` 에는 `options` 가 한 글자도 없는데도 그렇다.
- **듣지 않는 처방**: 개발 서버 재시작, `node_modules/.vite` 삭제, 새 탭에서 열기, 주소에 쿼리 붙이기, `location.reload(true)`. 전부 같은 모듈 주소를 다시 쓰므로 소용이 없었다.
- **해결**: **포트를 바꿔 출처를 바꾼다.** 새 출처는 캐시가 비어 있어 모든 모듈을 새로 받는다.

  ```bash
  npx vite --port 3001 --strictPort
  ```
- **주의**: `curl` 로 "서버가 무엇을 주는지" 확인할 때도 캐시버스터를 붙여야 한다. `curl http://localhost:3000/lib/api/client.ts` 는 옛 응답을 보여 주고, `?t=$(date +%s)` 를 붙이면 고친 내용이 나온다. 이것 때문에 "서버가 옛 코드를 준다" 고 잘못 판단하기 쉽다.
- **근거**: `frontend/vite.config.ts`, 2026-09-16 재생 큐 유실 추적 중 확인

### 손을 뗐는데 재생 목록의 줄이 커서를 계속 따라다닌다 — 해소됨

- **증상**: 손잡이를 잡아 순서를 바꾸다가 목록 **바깥에서** 버튼을 놓으면 끌기가 끝나지 않는다. 그 뒤로는 누르지 않았는데도 커서가 지나가는 곳마다 줄이 이리저리 옮겨 다닌다.
- **원인**: 끌기 이벤트를 **손잡이 버튼에 직접** 달고 `setPointerCapture` 로 붙잡아 두었다. 그런데 순서가 바뀔 때마다 그 줄이 자리를 옮기며 버튼이 다시 그려지고, 그때 **포인터 캡처가 풀린다.** 캡처가 없으니 목록 밖에서 놓은 `pointerup` 은 그 버튼에 닿지 않아 `dragUid` 가 그대로 남고, 이후의 `pointermove` 는 커서 아래에 있는 **다른 줄의 손잡이** 핸들러를 때린다 — 그 핸들러도 같은 `dragUidRef` 를 보므로 끌기가 이어지는 것처럼 동작한다.
- **해결**: 끄는 동안의 `pointermove`/`pointerup`/`pointercancel` 과 `blur` 를 **창(window)에서 듣는다.** 어디서 놓든 그 자리에서 순서가 확정된다. 손잡이에는 `pointerdown` 만 남긴다.
- **주의 1**: 창에 거는 함수는 **늘 이번 렌더의 것**이어야 한다. 한 번 건 함수를 그대로 두면 끄는 사이에 목록이 바뀌었을 때 옛 순서를 기준으로 자리를 확정한다. 최신 함수를 ref 에 담아 두고 `() => ref.current()` 를 건다. ref 대입은 렌더 중이 아니라 effect 안에서 한다 — `react-compiler` 규칙이 렌더 중 ref 접근을 막는다.
- **주의 2**: `blur` 도 끝으로 친다. 다른 창에서 손을 떼면 이쪽은 뗀 줄을 영영 모른다.
- **주의 3**: `pointerdown` 직후의 첫 `pointermove` 한 건은 effect 가 붙기 전이라 놓칠 수 있다. 실제 끌기에서는 초당 수십 건이 오므로 보이지 않는다. 다만 **시험에서 `pointerdown` 과 `pointermove` 를 한 프레임에 쏘면 아무 일도 일어나지 않아** 회귀로 오인하기 쉽다.
- **확인**: 첫 줄을 세 번째 자리까지 끈 뒤 독 **바깥**(60, 80)에서 놓으면 그 자리로 확정되고, 그 뒤 커서를 목록 위에서 움직여도 순서가 변하지 않는다. Redis 값도 화면과 일치한다.
- **근거**: `frontend/components/mupid/queue-list.tsx`

### 배포된 앱에서만 리포트 트리맵의 앨범 아트가 깨진다 — 해소됨

- **증상**: '내 취향' 리포트의 '오늘 들은 음악' 트리맵에서 타일의 앨범 아트가 뜨지 않는다. 데스크톱 앱에서는 엉뚱한 그림이, 모바일 앱에서는 깨진 이미지 아이콘이 자리를 채운다. **로컬 개발 서버에서는 멀쩡하다.**
- **원인**: 서버는 커버 주소를 `/api/v1/storage/image/{fileId}` 처럼 **경로로만** 준다(`StorageImageService.imageUrl` — 파일 번호 주소다). 트리맵만 그 값을 `<img src>` 에 그대로 걸고 있었다. 개발 서버에서는 Vite 프록시가 `/api` 를 백엔드로 넘겨(`vite.config.ts` 의 `apiProxy`) 같은 출처처럼 보이지만, 앱의 출처는 `http://tauri.localhost` 라 그 경로에는 아무것도 없다 — 웹뷰는 인터넷에 직접 나가지 않고 원격 이미지는 Rust 가 받아 `mupidimg` 스킴으로 돌려준다(`app/src/image.rs`).
- **해결**: 타일 아트를 `TileArt` 로 떼어 내고 `toImageSource()` 를 거치게 한다. 주소가 없거나 못 읽으면 목록 커버와 같은 규칙으로 `resolveAlbumCover()` 가 기본 커버로 내린다. 웹에서도 화면과 API 의 출처가 갈리는 배포를 위해 `toImageSource()` 가 `/api/` 경로에 `VITE_MUPID_API_BASE` 를 붙인다 — `fetch` 는 `ApiClient` 가 붙여 주지만 `<img src>` 는 아무도 붙여 주지 않는다. 주소를 읽는 자리는 `lib/api/base-url.ts` 하나로 모았다(`session.ts` 에 두면 `platform → api/session → platform` 순환이 된다).
- **되풀이를 막는 규칙**: **서버가 준 이미지 주소는 `<img src>` 에 걸기 전에 반드시 `toImageSource()` 를 지난다.** 한 자리만 빠뜨려도 그 자리만 앱에서 조용히 깨진다. 같은 사고가 MuBTI 캐릭터에서 한 번 있었고(`ReportMubtiArt`), 이번이 두 번째다. `frontend/tests/report-treemap-art.test.mjs` 가 트리맵이 다시 날것 경로로 돌아가지 못하게 막는다.
- **근거**: `frontend/components/mupid/daily-album-treemap.tsx`, `frontend/lib/platform/image.ts`, `frontend/lib/api/base-url.ts`, `frontend/docs/app-shell.md`

### 추천으로 튼 곡인데 play_histories.recommendation_reason 이 늘 NULL 이다

- **증상**: `selector = 'AI'` 인 재생 기록이 쌓이는데 `recommendation_reason` 만 전부 비어 있다. 실측(2026-09-20 개발 스택) — `AI` 38행 / 38행 NULL, `USER` 5행 / 5행 NULL.
- **원인**: **저장 로직은 처음부터 있었다.** `HistoryServiceImpl.recordPlay` 가 사유를 받아 `:71` 에서 컬럼에 넣고 있었고, 유일한 호출부(`EventServiceImpl:178`)도 사유를 넘기고 있었다. 비는 진짜 이유는 **넘길 값이 없기 때문**이다 — 청취 이벤트에 사유가 실리지 않는다. `PLAY_START` 의 고유 필드는 `position_ms` · `rec_id` · `rec_type` · `rec_rank` 뿐이고, `reason` 은 `PLAY_ERROR`(`MEDIA_ERR_NETWORK` 같은 코드 이름) 전용 칸이다(`domain/listening-events.md`). 그래서 `item.reason()` 이 **PLAY_START 에서는 구조적으로 항상 null** 이다. ClickHouse 실측이 이를 확정한다 — `PLAY_START` 607행 중 `reason` 비어있지 않은 것 0행, `rec_id` 비어있지 않은 것 0행. `reason` 이 채워진 이벤트는 `PLAY_ERROR`(11행) 하나뿐이었다.
- **해결**: 사유를 이벤트에 **싣지 않는 대신**, 화면이 이미 되돌려주는 상관 키 `rec_id`(+`track_id`)로 되짚는다. 사유 문장이 만들어져 나가는 자리 — `NextTrackService.next`(`withMusic` 직후)와 `AiRecommendToolImpl.recommend`(`withMusicDetails` 직후) — 에서 `RecommendationReasonCache.remember(recId, musicId, reason)` 로 적어 두고, `EventServiceImpl.reasonOf` 가 `entry_path = RECOMMEND` 일 때만 `find(recId, trackId)` 로 읽는다. 키는 `recommendation:reason:{rec_id}:{music_id}` — 추천 하나가 같은 곡을 두 줄로 줄 수 있어(순위가 다르면 다른 구간) 곡까지 키에 넣는다.
- **주의 1**: **"넣는 코드가 없다" 를 먼저 의심하지 않는다.** 이 티켓의 전제가 정확히 그것이었고 틀렸다. 증상이 "컬럼이 NULL" 일 때는 저장 코드를 고치기 전에 **값이 도착하는지**를 실측한다. ClickHouse 원본에서 `countIf(reason != '')` 를 세면 한 줄로 갈린다.
- **주의 2**: `api/` 만 고쳐서 풀 수 있다. 사유는 `frontend/lib/analytics/tracker.ts` 의 `startPlay` 가 **의도적으로** 보내지 않는 값이라(그 이벤트의 `reason` 은 `PLAY_ERROR` 용), 클라이언트를 고치는 대신 서버가 들고 있는 편이 맞다. 계약을 바꾸지 않는다.
- **주의 3**: 사유 조회는 **어떤 실패도 올려보내지 않는다.** 부가 정보라 Redis 가 죽었다고 재생 기록 자체를 잃으면 안 된다. 선곡 주체(`selector`)는 진입 경로만으로 정해지므로 사유와 무관하다.
- **주의 4**: Redis 키에 **TTL 을 반드시 둔다.** `maxmemory-policy` 가 설정돼 있지 않아 가득 차면 키가 밀려나는 것이 아니라 컨테이너가 죽는다(`PlayQueueRepository` 와 같은 지적). 7일로 잡았다 — 수집기가 받아 주는 과거 한도(`event.occurred-at-max-past`, 기본 72시간)보다 넉넉해야 늦게 도착한 재생도 사유를 찾는다.
- **확인**: `PlayHistoryReasonWriteTest` 가 실제 MySQL 에서 컬럼 값을 읽어 판정한다(추천 재생 → 사유 채워짐 / 직접 고른 재생 → 비어 있음 / 250자 → 100자로 잘림). `PlayHistoryReasonFromEventTest` 가 수집기가 사유를 넘기는지와 Redis 장애 시에도 수집이 계속되는지를 본다.
- **근거**: `api/src/main/java/com/osundosun/mupid/api/recommendation/service/RecommendationReasonCache.java`, `RecommendationReasonCacheImpl.java`, `api/src/main/java/com/osundosun/mupid/api/event/service/EventServiceImpl.java`, `api/src/main/java/com/osundosun/mupid/api/ai/tool/AiRecommendToolImpl.java`, `api/src/main/java/com/osundosun/mupid/api/recommendation/service/NextTrackService.java`
