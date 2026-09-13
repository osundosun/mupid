# 프론트엔드 스택

`frontend/` 에서 화면을 고치거나, 백엔드 API 를 프론트에 붙이거나, 프론트 빌드·CI 가 왜 이렇게 생겼는지 알아야 할 때 읽는다. `api/AGENTS.md` 는 백엔드 전용이고 `frontend/` 에는 대응하는 AGENTS.md 가 없다. 이 문서가 그 자리를 대신한다. 실제 실행·검증 명령과 지켜야 할 규약은 아래 각 절에, 이 영역에서 반복해서 사람을 넘어뜨린 것들은 [함정과 실패 사례](../failure/frontend.md) 에 있다.

## 어느 디렉터리가 제품인가

트리가 두 벌이다. **`frontend/` 하나만 제품이다.**

| | `App/` | `frontend/` |
|---|---|---|
| package name | `sites-project` | `mupid-production` |
| git 추적 파일 | 86 | 204 |
| README 제목 | `Mupid 프론트엔드 프로토타입` | `MUPID Production` |
| 루트 컴포넌트 | `components/mufeed-app.tsx` | `components/mupid-app.tsx` |
| `.openai/hosting.json` | `project_id` 가 남아 있음 | `{"d1": null, "r2": null}` |
| CI | 없음 | `infra/ci/frontend.yml` |

- 두 `package.json` 은 `name` 필드를 빼면 완전히 동일하다.
- `infra/ci/frontend.yml` 의 `rules.changes` 는 `frontend/**/*` 와 `infra/ci/frontend*`, `infra/deploy/receive-front.py`, `.gitlab-ci.yml` 만 감시한다. `App/` 을 고쳐도 파이프라인이 돌지 않는다. 다만 `frontend-verify` 의 세 번째 규칙 `- if: '$CI_PIPELINE_SOURCE == "web"'` 에는 `changes` 가 없어, 수동(web) 트리거 파이프라인에서는 변경 경로와 무관하게 이 잡만 실행된다.
- `frontend/README.md`: "기존 MUPID 프로토타입에서 분리한 본개발용 프로젝트입니다."
- `frontend/` 는 `App/` 의 상위집합이다. `App/` 에만 있는 파일은 없다. 신규 작업은 전부 `frontend/` 에 넣는다.

## 스택

`frontend/package.json` 에 적힌 버전 그대로다.

| 영역 | 패키지 | 버전 |
|---|---|---|
| 프레임워크 | `vinext` | 1.0.0-beta.5 |
| React | `react` / `react-dom` / `react-server-dom-webpack` | 19.2.6 |
| 번들러 | `vite` / `@vitejs/plugin-rsc` | 8.0.13 / 0.5.26 |
| 스타일 | `tailwindcss` / `@tailwindcss/postcss` | 4.2.1 |
| UI 킷 | `shadcn` + `@base-ui/react` | 4.18.0 + 1.7.0 |
| 아이콘 · 차트 | `lucide-react` / `recharts` | 1.31.0 / 3.8.0 |
| Cloudflare | `wrangler` / `@cloudflare/vite-plugin` | 4.92.0 / 1.37.1 |
| 린트 · 포맷 | `oxlint` / `oxfmt` / `oxlint-tsgolint` | 1.76.0 / 0.61.0 / 7.0.2001 |
| 타입 | `typescript` | 5.9.3 |

Node 요구 버전은 `engines.node: ">=22.13.0"`, CI 이미지는 `node:22-bookworm-slim`.

### `next` 패키지 없이 Next 규약을 쓴다

`package.json` 에 `next` 가 없고 `package-lock.json` 에도 `node_modules/next` 항목이 없다. 그런데 소스는 Next API 를 쓴다.

```
frontend/app/layout.tsx            import type { Metadata, Viewport } from 'next'
frontend/app/email/verify/page.tsx import type { Metadata } from 'next'
frontend/components/mupid/primitives.tsx    import Image from 'next/image'
frontend/components/mupid/vinyl-record.tsx  import Image, { type ImageProps } from 'next/image'
frontend/next.config.ts            import type { NextConfig } from 'next'
```

이 해석은 vinext 가 제공한다. 타입은 `frontend/tsconfig.json` 의 `"types": ["node", "@cloudflare/workers-types", "vinext/types"]` 와 전이 의존성 `@vinext/types@1.0.0-beta.2` 가 담당한다. **Next.js 의 App Router 파일 규약과 API 표면만 빌려 쓰고 런타임은 Vite + RSC 다.** vinext 버전을 올릴 때 이 shim 이 깨질 수 있다 — [함정과 실패 사례](../failure/frontend.md) 참고.

## 배포 타깃: `MUPID_DEPLOY_TARGET`

환경변수 하나가 빌드·메타데이터를 두 갈래로 가른다. 세 파일이 이 값을 본다.

| 파일 | `=== 'ec2'` | 그 외(기본) |
|---|---|---|
| `frontend/vite.config.ts` | plugins `[vinext()]` 만 | `[vinext(), sites(), cloudflare(...)]` |
| `frontend/next.config.ts` | `{ output: 'export' }` | `{}` |
| `frontend/app/layout.tsx` | `siteUrl = https://j15a204.p.ssafy.io` | `siteUrl = https://mupid-studio.gyuone.chatgpt.site` |

Cloudflare 경로에서는 플러그인을 **동적 import** 한다. 이유가 주석에 남아 있다.

```ts
// frontend/vite.config.ts
process.env.WRANGLER_WRITE_LOGS ??= 'false';
process.env.WRANGLER_LOG_PATH ??= '.wrangler/logs';
process.env.MINIFLARE_REGISTRY_PATH ??= '.wrangler/registry';

// Wrangler snapshots its log path while the Cloudflare plugin is imported.
const { cloudflare } = await import('@cloudflare/vite-plugin');
```

Wrangler 가 플러그인 import 시점에 로그 경로를 스냅샷하므로, 그 전에 경로를 프로젝트 로컬로 고정해야 한다. **이 `await import` 를 정적 import 로 바꾸지 말 것.**

- `cloudflare()` 설정은 `viteEnvironment: { name: 'rsc', childEnvironments: ['ssr'] }`, `config: localBindingConfig`.
- `localBindingConfig` 는 `main: 'vinext/server/fetch-handler'`, `compatibility_flags: ['nodejs_compat']`. d1/r2 바인딩은 `frontend/.openai/hosting.json` 에서 읽는데 현재 둘 다 `null` 이라 빈 배열이 된다.
- `@openai/sites-vite-plugin` 과 `.openai/hosting.json` 은 **정적 import** 라 ec2 타깃에서도 로드된다. 플러그인으로 적용되지 않을 뿐이니 devDependency 를 지우면 ec2 빌드도 깨진다.
- `CODEX_SANDBOX === 'seatbelt'` 이면 HMR 을 폴링으로 바꾼다.

`npm start` 는 `wrangler dev --config dist/server/wrangler.json` 이므로 Cloudflare 타깃으로 빌드했을 때만 동작한다. CI 는 ec2 타깃으로만 빌드한다.

## 라우팅

파일 기반 라우트는 4개뿐이다.

| 경로 | 파일 | 렌더 |
|---|---|---|
| (root layout) | `frontend/app/layout.tsx` | `lang="ko"`, metadata/viewport |
| `/` | `frontend/app/page.tsx` | `<MupidApp />` |
| `/mupid` | `frontend/app/mupid/page.tsx` | `<MupidApp />` (같은 컴포넌트) |
| `/email/verify` | `frontend/app/email/verify/page.tsx` | `<EmailVerificationPage />` |

- `/mupid` 는 `frontend/mupid-proxy.conf` 의 Apache 프록시가 상위 도메인에 붙이는 경로다(`ProxyPass "/mupid/" ...`).
- `/email/verify` 는 `robots: { index: false, follow: false }`, `referrer: 'no-referrer'` 를 건다.

**앱 내부 화면 전환은 파일 라우팅이 아니라 쿼리스트링이다.** `frontend/hooks/use-mupid.ts` 의 `go(next)` 가 `?view=<page>` 를 `history.pushState` 로 넣고, `popstate` 리스너 `readPage()` 가 다시 읽는다. `home` 은 파라미터를 지운다.

```ts
const go = (next: Page) => {
  if (!onboardingSubmittedAt && next !== 'welcome') next = 'welcome';
  ...
  if (next === 'home') url.searchParams.delete('view');
  else url.searchParams.set('view', next);
  window.history.pushState({ mupid: true }, '', url);
```

화면 목록은 `frontend/lib/mupid-data.ts` 의 `Page` 유니온이다: `home` `search` `library` `report` `profile` `player` `moment` `similar` `welcome`.

> 새 화면을 추가하려면 ① `Page` 유니온 ② `use-mupid.ts` 의 `pageNames` 배열 ③ `components/mupid-app.tsx` 의 `m.page === '...' && <View />` 분기, 세 곳을 같이 고친다. `pageNames` 에 없는 `view` 값은 `home` 으로 떨어진다.

## 컴포넌트 조직

| 위치 | 개수 | 내용 |
|---|---|---|
| `frontend/app/` | 4 | 라우트 + CSS 21개(`globals.css` 포함) |
| `frontend/components/mupid-app.tsx` | 1 | `'use client'` 루트 셸 |
| `frontend/components/mupid/` | 22 | 화면·도메인 컴포넌트 |
| `frontend/components/ui/` | 60 | shadcn 생성물 |
| `frontend/hooks/` | 3 | `use-mupid`, `use-home-player-dock`, `use-mobile` |

**RSC 경계**: `app/` 아래에는 `'use client'` 가 하나도 없다. 클라이언트 경계는 `components/mupid-app.tsx` 와 `components/mupid/email-verification.tsx` 에서 시작한다. `mascot.tsx`, `playback-status.tsx`, `lyrics.tsx` 처럼 지시어가 없는 파일은 클라이언트 컴포넌트에서 import 되어 경계를 물려받는다.

`components/mupid-app.tsx` 구성:

- `MupidApp` — `mupidService.bootstrap()` 의 로딩/실패/빈 목록/재시도만 담당한다. 곡이 없으면 `LoadedMupidApp` 을 만들지 않는다.
- `LoadedMupidApp` — header + side-nav + main + mini-player + mobile-nav + `FloatingChat` + 다이얼로그.
- 내비게이션 배열이 3개다. `navigation`(5개) 이 원본이고, `desktopNavigation` 은 여기에 `player` 를 끼워 6개, `mobileNavigation` 은 `player` 가 플레이리스트 자리를 차지해 5개다. 항목을 늘릴 때 셋의 관계(`navigation` 을 slice/spread 로 재사용)를 깨지 않는다.

화면 컴포넌트 배치:

| 파일 | export |
|---|---|
| `components/mupid/home.tsx` | `HomeView`, `HomeNotifications` |
| `components/mupid/discovery.tsx` | `PlayerView`, `MomentView`, `SimilarView` (+ `explore.tsx` 의 `SearchView` 재수출) |
| `components/mupid/collection.tsx` | `LibraryView`, `ReportView`, `ProfileView`, `WelcomeView` |
| `components/mupid/primitives.tsx` | `Brand`, `AlbumArt`, `Heading`, `PageHeader`, `TrackRow`, `Waveform`, `PlayerControls`, `LikeButton`, `EmptyState` |

### `components/ui/` 는 base-ui 기반이다

`frontend/components.json` 의 `style` 이 `base-nova` 이고, 60개 중 **38개가 `@base-ui/react/*` 를 감싼다. `@radix-ui` 를 import 하는 파일은 0개이고 `package.json` 에도 `@radix-ui` 를 직접 선언하지 않았다.** `@radix-ui` 를 전제한 shadcn 예제를 그대로 가져오면 이 트리와 어긋난다. 컴포넌트를 추가하기 전에 같은 계열 파일이 무엇을 감싸는지 확인한다.

살아 있는 앱 코드가 실제로 import 하는 건 6개뿐이다: `alert-dialog`, `carousel`, `dialog`, `pagination`, `slider`, `tabs`. `@/components/ui/` 를 grep 하면 `button` 과 `chart` 가 더 잡히는데, 둘 다 죽은 코드인 `components/mufeed-app.tsx`(44·45행) 에서만 쓴다.

## 상태: 단일 모델 객체

`frontend/hooks/use-mupid.ts`(511줄, `'use client'`) 가 앱 전체 상태를 하나의 객체로 반환하고, 타입을 반환값에서 파생시킨다.

```ts
export type MupidModel = ReturnType<typeof useMupid>;
```

**모든 화면 컴포넌트는 `{ model: m }: { model: MupidModel }` 단일 prop 만 받는다.** Context 도, 상태 관리 라이브러리도 없다. `frontend/docs/server-integration.md` 6항이 현 단계 방침을 밝힌다 — "서버 페이징·캐시가 필요해지면 TanStack Query를 도입합니다. 현재 단계에서 라이브러리나 배포 체계는 바꾸지 않았습니다."

저장은 직렬화된다. `saveQueue` ref 가 프로미스 체인을 이어 순서를 보장하고, 실패하면 토스트를 띄운다.

```ts
saveQueue.current = saveQueue.current.catch(() => {}).then(() =>
  mupidService.savePreferences({ onboardingSubmittedAt, nickname, moods, liked, ignored, history, moments, playlists })
).catch(() => { if (mountedRef.current) setToast('변경 내용을 저장하지 못했어요. 다시 시도해 주세요.'); });
```

나머지 훅:

- `use-home-player-dock.ts` — `useDesktopPlayerDock` 은 `useSyncExternalStore` 로 `(min-width: 1024px)` 를 구독하고 서버 스냅샷은 `false`. `useHomePlayerDock` 은 `#home-listening-controls` 에 IntersectionObserver(`threshold: [0, 0.7, 1]`, `rootMargin: '0px 0px -100px 0px'`)를 걸되, `.mini-player` 안에 포커스가 있으면 도크를 숨기지 않는다.
- `use-mobile.ts` — shadcn 기본형. `components/ui/sidebar.tsx` 만 쓰는데 그 sidebar 를 앱이 쓰지 않는다.

## 백엔드 API 연동

경계는 `frontend/docs/server-integration.md` 가 정의한다.

```
화면 → useMupid / 프로필·로그인 컴포넌트 → MupidService → 데모 저장소
```

| 파일 | 역할 |
|---|---|
| `frontend/lib/services/contracts.ts` | `MupidService` 인터페이스와 `Preferences` / `Bootstrap` / `SignupInput` / `AccountSession` |
| `frontend/lib/services/index.ts` | 데모 구현(`createDemoService`), 모드 선택(`createService`), 싱글턴 `mupidService` |
| `frontend/lib/mupid-data.ts` | 데모 곡 8개, 초기 컬렉션, 공용 음악 계산 함수 |

`MupidService` 의 메서드: `bootstrap`, `savePreferences`, `login`, `logout`, `signup`, `requestEmailVerification`, `readPhoto`, `savePhoto`, `removePhoto`, `recommend`. `mode` 는 `'demo' | 'api'`.

### 지켜야 할 규약

1. **브라우저 저장소는 `lib/services/index.ts` 안에서만 접근한다.** 키는 `mupid.sky.preferences.v1`, `mupid-profile-photo` 두 개. (`lib/track-comments.ts` 가 이 규칙을 어기고 있다 → [함정과 실패 사례](../failure/frontend.md))
2. **연결되지 않은 기능은 성공으로 처리하지 않는다.** `login` / `signup` / `requestEmailVerification` 은 전부 `unavailable` 로 묶여 `'서버가 아직 연결되지 않았어요. 입력한 정보는 저장·전송되지 않았습니다.'` 를 throw 한다.
3. **API 모드는 데모로 자동 우회하지 않는다.**
   ```ts
   if (!api || api.mode !== 'api') throw new Error('실제 API 어댑터가 필요합니다. 데모로 자동 전환하지 않습니다.');
   ```
4. **시간 값은 전부 초 단위다.** `Track.duration`, `SavedMoment`, 구간 `start`/`end` 가 모두 같다. 실제 음원 길이와 서버 메타데이터를 맞춰야 한다.
5. **`demoSession` 은 인증 근거가 아니다.** React 메모리에만 있고 새로고침하면 사라진다. `frontend/docs/ui-auth-and-navigation.md`: "서버는 클라이언트의 `demoSession`을 인증 근거로 사용하면 안 됩니다."
6. **브라우저는 Spring API 만 호출한다.** `docs/server-integration.md`: "추천 어댑터는 Spring API만 호출하고 내부 FastAPI/Kafka/DB에 브라우저를 직접 연결하지 않습니다."
7. **저장된 값을 신뢰하지 않는다.** `bootstrap()` 은 `localStorage` 복원 시 트랙 id 존재 여부, `moment` 의 `start >= 0 && end > start && end <= track.duration`, 플레이리스트의 `id`/`name` 타입, 닉네임 20자 절단을 전부 재검증한다. JSON 파싱 실패는 try/catch 로 삼키고 데모 기본값으로 복구하되 **사용자 저장값을 지우지 않는다**("Recover using demo defaults; never clear the user's stored value here.").
8. `browserStorage` 는 화살표 함수로 감싸 모듈 초기화·SSR 시점에 `window` 를 읽지 않는다. 새 저장 접근을 추가할 때도 같은 형태를 유지한다.

### 실제 서버를 붙일 때

`docs/server-integration.md` 의 순서 그대로다.

1. 백엔드와 경로·DTO·ID 타입·오류 형식·페이지네이션·세션/CSRF 정책을 합의한다. **현재 계약은 확정된 백엔드 명세가 아니다** — `contracts.ts` 주석: "Frontend domain contract, not a promise about the future backend's DTOs."
2. `MupidService` 를 구현하는 API 어댑터를 추가한다. `bootstrap()` 이 응답을 UI 도메인 형식으로 정규화한다.
3. `createService('api', apiAdapter)` 로 바꾼다.
4. `savePreferences` 는 지금 스냅샷 하나를 통째로 저장하는 계약이다. 서버 API 로 매핑하거나 변경별 mutation 으로 쪼갠다.
5. 세션 복원·만료, 사용자별 캐시 초기화, 로그아웃 시 개인정보 제거를 함께 구현한다. 데모 저장 내용을 계정에 자동 업로드하지 않는다.

이메일 인증은 별도 서비스다. `frontend/lib/email-verification.ts` 의 `parseEmailLink` 는 쿼리 형식만 검증하고 신원을 만들지 않으며(코드 길이를 6자리로 가정하지 않고 2048자 제한 + 공백·제어문자 거부만 한다), `emailVerificationService.verify` 는 항상 `{ status: 'unavailable' }` 을 반환한다. 백엔드에 필요한 명세 목록은 `frontend/docs/email-verification.md` 에 있다.

## 이벤트 추적: `lib/analytics/`

청취 행동 22종을 모아 배치로 백엔드에 보내는 라이브러리다. 계약과 배경은 백엔드 지식 저장소에 있다 — [domain/listening-events.md](../domain/listening-events.md), [decision/0014](../decision/0014-event-collector-in-api.md), [decision/0015](../decision/0015-derive-interpretation-at-query-time.md).

| 파일 | 역할 |
|---|---|
| `types.ts` | 이벤트 타입·값 집합. `listen_events` 컬럼과 1:1 |
| `tracker.ts` | 큐·배치·재시도·저장소 백업, 재생 세션(`play_id`)과 재생 포인터, 기본 콘솔 싱크 |
| `transport.ts` | `Transport` 타입과 HTTP 구현 |
| `audio.ts` | `<audio>` 바인딩. 자동 이벤트 6종과 5초 하트비트 |
| `index.ts` | 공개 API 와 싱글턴 `analytics` |

### 지켜야 할 규약

1. **화면에서 이벤트를 직접 `fetch` 하지 않는다.** `analytics` 만 쓴다. 서버 계약이 바뀌어도 호출부를 고치지 않기 위해서다.
2. **`position_ms` 는 매번 `audio.currentTime` 을 실제로 읽어서 만든다.** 상태에 들고 있는 값을 쓰면 백그라운드 탭에서 틀어진다.
3. **구간 번호를 보내지 않는다.** 밀리초만 보낸다. 구간 매핑은 백엔드가 한다.
4. **가중치도 분류도 계산하지 않는다.** `TRACK_SKIP` 은 `skip_trigger` 와 `position_ms` 만 싣고, EARLY/AFTER_HOOK/LATE 분류는 ClickHouse 가 쿼리 시점에 만든다.
5. **`occurred_at` 은 이벤트가 생길 때 각인된다.** 배치로 늦게 나가도 발생 시각은 정확하다. 이 성질에 기대는 테스트가 있다.
6. `tracker.ts` 는 **값(value)을 relative import 하지 않는다.** 저장소 관례가 확장자 없는 import 인데 `node --experimental-strip-types` 는 그걸 해석하지 못한다. 타입 import 는 지워지므로 안전하다 — 덕분에 이 모듈만 의존성 설치 없이 `node --test` 로 돌아간다.

### 자동과 수동

`bindAudio(audio, analytics)` 가 자동으로 남기는 것은 `PLAY_RESUME` · `PLAY_PAUSE` · `PLAY_END` · `PLAY_STALL` · `PLAY_ERROR` · `PLAY_PROGRESS` 여섯이다. 나머지는 앱이 직접 부른다 — 오디오 엘리먼트만 봐서는 판단할 수 없기 때문이다. `seeking` 은 사용자가 슬라이더를 옮겼을 때와 앱이 `currentTime` 을 대입했을 때 똑같이 발생하고, 스킵은 무엇을 눌러 넘겼는지를 엘리먼트가 모른다.

`hooks/use-mupid.ts` 에 붙일 자리는 세 곳이다 — `startPlayback` 에서 `startPlay()`, 슬라이더 핸들러에서 `seek(before, after)`, 다음곡 버튼에서 `skip(position, 'NEXT_BUTTON')`.

### 아직 안 된 것

`index.ts` 의 싱글턴은 기본 전송이 **콘솔 싱크**다. 백엔드 `POST /api/v1/events` 가 생기고 액세스 토큰 보관 위치가 정해지면(→ [open-questions C1](../open-questions.md)) `createTracker({ transport: createHttpTransport({ authorization }) })` 로 바꾼다. 그때 호출부는 고치지 않는다.

## 스타일: Tailwind 4 + shadcn

**`tailwind.config` 파일이 없다. 만들지 않는다.** `components.json` 의 `tailwind.config` 가 빈 문자열이고, PostCSS 플러그인은 `frontend/vite.config.ts` 에서 인라인 주입된다.

```ts
css: { postcss: { plugins: [tailwindcss()] } }
```

토큰은 전부 CSS 에 있다. `frontend/app/globals.css` 가 쌓는 순서:

```
@import 'tailwindcss';
@import 'tw-animate-css';
@import 'shadcn/tailwind.css';
@import './mupid.css';   ... 로컬 CSS 20개 ...
```

- `@theme inline` 에서 `--font-*` / `--color-*` / `--radius-*` 를 `:root` 변수(`--background`, `--primary`, `--sky-pink` 등)에 매핑한다.
- **새 CSS 파일은 `frontend/app/` 에 두고 `globals.css` 에 `@import` 로 등록한다.** 라우트 전용 CSS 는 예외적으로 페이지가 직접 import 한다(`app/email/verify/verify.css`).
- 폰트 통일은 Tailwind 레이어 **바깥**에서 강제한다. 주석에 이유가 있다.
  ```css
  /* Keep this outside Tailwind layers so browser/control and utility defaults cannot diverge. */
  :where(html, body, body *) { font-family: var(--font-mupid) !important; }
  ```
- Pretendard 9종은 `https://cdn.jsdelivr.net/gh/projectnoonnu/pretendard@1.0/` 에서 외부 로드한다. 폐쇄망에서는 대체 스택(`-apple-system`, `Malgun Gothic`)으로 떨어진다.
- `@custom-variant dark (&:is(.dark *));` 는 선언되어 있으나 다크 팔레트 정의는 없다. 현재 화면은 라이트 전용이다.

## 자산 스크립트

`frontend/scripts/` 의 `.mjs` 3개는 모두 sharp 기반이고 **"생성 원본은 보존하고 배포본만 다시 만든다"** 는 규칙을 공유한다.

| 스크립트 | 원본 | 출력 |
|---|---|---|
| `build-favicons.mjs` | `public/icons/mupid-icon-master.png` | `favicon-16/32`, `apple-touch-icon`(180), `icons/icon-192/512`, `icons/app-icon-1024`, `icons/icon-maskable-512` |
| `optimize-assets.mjs` | `public/<name>.png` (기본 `sky-background`, `sky-vinyl`, `mupid-cupid-side`) | 같은 이름 `.webp` (`quality: 84, alphaQuality: 100`) |
| `build-cursor.mjs` | `public/mupid-cursor-master.png` | `mupid-cursor-32/64.png` |

- maskable 아이콘은 358px 로 줄여 512 캔버스(`#bfdef7`) 중앙 (77, 77) 에 합성한다. 런처 마스크 안전 원 안에 초상이 들어가도록 한 여백이다.
- `optimize-assets.mjs` 는 인자 이름을 `/^[a-z0-9-]+$/` 로 검증한 뒤에만 파일을 만진다.
- `build-cursor.mjs` 는 마스터에 실제 알파가 없으면 throw 한다.
- `sharp` 는 `package.json` 어디에도 선언되어 있지 않다 → [함정과 실패 사례](../failure/frontend.md)
- `scripts/remove-mascot-checkerboard.py` 는 저장소 밖 개인 경로가 하드코딩된 일회성 실행 기록이다. 그대로 실행할 수 없다.

## 검증

`frontend/package.json` 의 스크립트는 `dev` `build` `start` `lint` `format` **다섯 개뿐이다. `test` 도 `typecheck` 도 없다.**

```bash
cd frontend
npm ci

npx tsc --noEmit --incremental false        # 타입
npm run lint                                # oxlint, 인자 없이
npm run format                              # oxfmt, 인자 없이
node --experimental-strip-types --test      # 테스트
MUPID_DEPLOY_TARGET=ec2 npm run build       # 빌드 (CI 와 같은 타깃)
```

- 린트: `frontend/.oxlintrc.json` 은 플러그인 8종(`eslint` `typescript` `unicorn` `oxc` `react` `import` `jsx-a11y` `nextjs`), `categories.correctness: "error"`, `options.typeAware`/`typeCheck` 를 켠다. 린트 예외는 사유를 함께 적는다 — 저장소 전체에 단 하나뿐인 예시가 형식이다.
  ```tsx
  {/* oxlint-disable-next-line jsx-a11y/media-has-caption -- Instrumental music sample. */}
  ```
- 포맷: `frontend/.oxfmtrc.json` 은 `singleQuote`, `printWidth: 80`. **`npm run format` 을 전체 실행하지 말 것** — 실제로는 파일마다 적용 여부가 다르다(`lib/services/index.ts` 80자 초과 25줄, `hooks/use-mupid.ts` 18줄, `app/layout.tsx` 0줄). 전체 실행하면 리뷰 불가능한 diff 가 나온다.

### 테스트의 성격

`frontend/tests/*.test.mjs` 21개, node:test 기반. jsdom 도 React 렌더러도 쓰지 않고 세 방식을 섞는다.

1. `.ts` 를 직접 import (Node 타입 스트리핑) — `email-verification`, `explore`, `mupid-data`, `track-comments`, `demo-session`, `analytics`. 이 방식이 되려면 대상 모듈이 값을 relative import 하지 않아야 한다 — 확장자 없는 지정자를 Node 가 해석하지 못하기 때문이다. `analytics.test.mjs` 는 여기에 더해 시계·저장소·전송을 주입해 타이머 없이 결정적으로 돌린다.
2. **소스 파일을 `readFile` 로 읽어 정규식·`includes` 로 단언** — 다수. 예: `typography.test.mjs` 가 `chat.tsx` 의 `className="(?:thinking )?chat-handwriting"` 등장 횟수가 정확히 4인지 센다.
3. `services.test.mjs` 는 `ts.transpileModule` 로 `lib/services/index.ts` 를 컴파일하고 `'../mupid-data'` 지정자를 절대 file URL 로 치환한 뒤 base64 `data:` URL 로 import 해서 메모리 저장소 더블을 주입한다.

`brand.test.mjs` / `mascot.test.mjs` 는 sharp 로 픽셀을 순회해 이미지 자산 자체를 검사한다.

> 2번 방식 때문에 **동작 변화 없이 리팩터링만 해도 테스트가 깨지고, 테스트 실패가 곧 버그를 뜻하지도 않는다.** UI 를 고치면 해당 단언을 같이 갱신한다. 현재 실패 중인 것들과 그 이유는 [함정과 실패 사례](../failure/frontend.md) 에 있다.

## CI 와 배포

`.gitlab-ci.yml` 이 `infra/ci/frontend.yml` 을 include 한다. 3단계다.

| job | stage | 내용 |
|---|---|---|
| `frontend-verify` | verify | `node:22-bookworm-slim`, `MUPID_DEPLOY_TARGET: ec2`, `NEXT_TELEMETRY_DISABLED: '1'`. `npm ci --cache .npm --prefer-offline --no-audit --no-fund` → `npm run build` → `test -f dist/client/index.html`. 아티팩트 `frontend/dist/client/` 7일 |
| `frontend-package` | package | `infra/ci/frontend-release.sh package` — `frontend/dist/client` 를 `site.tar.gz` 로 묶고 sha256 을 만들어 GitLab generic 레지스트리 `mupid-web/$CI_COMMIT_SHA` 에 업로드 |
| `frontend-deploy` | deploy | `develop` + `$CI_COMMIT_REF_PROTECTED == "true"` + `$MUPID_DEPLOY_ENABLED == "true"` 일 때만. `resource_group: mupid-demo`, 환경 `demo`(`https://j15a204.p.ssafy.io`) |

배포는 패키지를 내려받아 `sha256sum -c` 로 검증한 뒤 tarball 을 stdin 으로 흘려보낸다.

```sh
ssh ... mupid-deploy@j15a204.p.ssafy.io "deploy-front $CI_COMMIT_SHA $checksum" < site.tar.gz
test "$(curl --fail --silent --show-error https://j15a204.p.ssafy.io/deployment.txt)" = "$CI_COMMIT_SHA"
```

서버 측 수신기는 `infra/deploy/receive-front.py` 다.

**CI 가 하지 않는 것**: `frontend-verify` 는 빌드와 `index.html` 존재 확인만 한다. **린트도 타입 검사도 테스트도 돌리지 않는다.** 그리고 ec2 타깃으로만 빌드하므로 Cloudflare 경로는 파이프라인이 한 번도 검증하지 않는다. 로컬에서 직접 돌려야 한다.

## 커밋

`.husky/commit-msg` 훅이 브랜치명에서 `S15P21A204-<번호>` 를 뽑아 커밋 제목 끝에 ` (S15P21A204-<번호>)` 로 붙인다.

- 브랜치명 컨벤션: `<type>/S15P21A204-<번호>-<branch-name>`
- 브랜치명에 티켓 번호가 없으면 **커밋이 거부된다.**
- merge / squash 로 만들어진 메시지, rebase·cherry-pick·revert 진행 중, `A204 회고 봇` 커밋은 예외다.

## 함정

이 영역에서 반복해서 사람을 넘어뜨린 것들은 [함정과 실패 사례](../failure/frontend.md) 에 모아 두었다. 요약만 적으면:

- `sharp` 가 선언 없이 miniflare 전이 의존성에 얹혀 있다
- 테스트 일부가 지금 깨져 있는데 CI 가 테스트를 돌리지 않아 드러나지 않는다
- 소스 텍스트 정규식 테스트는 리팩터링에 그대로 깨진다
- `next` 는 설치되지 않는데 소스가 `next/*` 를 import 한다
- `npm start` 는 ec2 타깃 빌드 뒤에는 동작하지 않는다
- `README.md` 의 손글씨 폰트 설명이 실제 코드와 반대다
- `lib/track-comments.ts` 가 서비스 계층을 우회해 localStorage 를 직접 쓴다
- 데모가 외부 CDN(Pretendard) 과 외부 오디오 호스트(soundhelix.com) 에 실시간 의존한다
- `components/mufeed-app.tsx`(101,388 bytes) 등 죽은 코드가 grep 결과를 오염시킨다
- `App/.openai/hosting.json` 에 개인 Sites 프로젝트 ID 가 남아 있다
