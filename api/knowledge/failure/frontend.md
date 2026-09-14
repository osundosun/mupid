# 프론트엔드

16건. 증상으로 찾고, **원인 / 해결 / 근거** 순으로 읽는다. 새로 밟은 함정은 이 파일에 같은 형식으로 추가한다.

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

- **원인**: 죽은 코드가 상당량 남아 있다. `components/mufeed-app.tsx` 는 101,388 bytes 인데 저장소 전체에서 참조가 0건이다(프로토타입의 단일 파일 앱). `lib/demo-session.ts` 의 `createDemoSession` 은 어떤 컴포넌트도 import 하지 않고 `tests/demo-session.test.mjs` 만 붙잡고 있다 — 실제 세션 타입은 `lib/services/contracts.ts` 의 `AccountSession` 이다. `components/ui/` 60개 중 앱이 실제로 쓰는 것은 alert-dialog, carousel, dialog, pagination, slider, tabs 6개뿐이고 `hooks/use-mobile.ts` 는 미사용인 `components/ui/sidebar.tsx` 만 참조한다.
- **해결**: 코드를 찾을 때 `mufeed-app.tsx` 히트는 무시한다.
- **근거**: `frontend/components/mufeed-app.tsx`, `frontend/lib/demo-session.ts`

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

### `App/` 에서 Sites 배포를 실행하면 개인 프로토타입을 덮어쓴다

- **원인**: `frontend/.openai/hosting.json` 은 `{"d1": null, "r2": null}` 로 정리됐지만 `App/.openai/hosting.json` 에는 `"project_id": "appgprj_6a96142907848191bb46392959b6ed47"` 가 그대로 있다. `App/README.md` 도 이를 인정한다.
- **해결**: `App/` 에서 Sites 배포 명령을 실행하지 않는다.
- **근거**: `App/.openai/hosting.json`, `App/README.md`

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
