# 앱 셸 (Tauri) 연결

같은 화면 코드가 웹과 앱 양쪽에서 돈다. 웹 배포는 지금까지와 똑같다 — 이 문서는
**앱일 때만 달라지는 것**과, 그 갈림길이 어디에 있는지를 적는다.

셸 자체(Rust·SQLite·안드로이드 빌드)는 [`app/README.md`](../../app/README.md).

## 갈림길은 네 군데뿐

| 무엇 | 웹 | 앱 | 고르는 곳 |
| --- | --- | --- | --- |
| HTTP | `fetch` | Rust 커맨드 | `lib/platform/index.ts` → `createPlatformTransport()` |
| 저장소 | `localStorage` | SQLite | `lib/services/index.ts` → `mupidService` |
| 이벤트 전송 | `fetch(keepalive)` | Rust 커맨드 | `lib/analytics/index.ts` |
| 앨범 아트 | 서버가 준 경로 (+`VITE_MUPID_API_BASE`) | `mupidimg` 스킴 | `lib/platform/image.ts` → `toImageSource()` |

화면 컴포넌트에는 플랫폼 분기가 없다. **새로 넣지 말 것** — 분기가 퍼지면 웹에서만
되는 화면, 앱에서만 되는 화면이 생긴다. 갈라야 할 일이 생기면 `lib/platform/` 안에서
가른다.

판별은 `isTauri()` 하나다 (`lib/platform/bridge.ts`). Tauri 가 웹뷰에 심는
`window.__TAURI_INTERNALS__` 를 본다 — 사용자 에이전트 문자열로는 안드로이드 크롬과
구분되지 않는다.

## HTTP

`ApiClient` 는 무엇이 요청을 보내는지 모른다. `Transport` 하나만 갈아 끼웠다
(`lib/platform/transport.ts`). 그래서 재발급 큐·오류 규약·청크 스트리밍은 웹과 앱이
같은 코드를 쓴다.

앱에서 달라지는 것:

- **주소**: `ApiClient` 는 `/api/v1/...` 까지만 만들고, 원본 주소는 Rust 가 붙인다.
  그래서 `VITE_MUPID_API_BASE` 는 앱에서 쓰지 않는다.
- **쿠키**: `withCredentials` 를 보지 않는다. 리프레시 토큰 쿠키는 Rust 의 쿠키
  항아리가 들고 SQLite 에 적는다 — 앱을 껐다 켜도 로그인이 유지되는 것이 이 때문이다.
- **본문**: 바이너리와 첨부는 base64 로 넘긴다. 배열로 넘기면 IPC 직렬화가 파일
  크기에 비례해 느려진다.
- **응답**: `[4바이트 길이][메타 JSON][본문]` 한 덩어리로 온다. JSON 과 바이너리에
  같은 경로를 쓰려는 것이다 — 둘로 나누면 오류 응답 규약이 조용히 어긋난다.

취소는 그대로 `AbortSignal` 이다. 신호가 붙은 요청에만 식별자를 만들어 Rust 쪽
`http_abort` 로 끊는다.

## 저장소

`MupidService` 계약은 그대로다 (`lib/services/contracts.ts`). 앱 구현은
`lib/platform/service.ts` 이고, `localStorage` 자리에 SQLite 가 들어간다.

곡은 **번호로만** 저장한다. 곡 정보는 서버의 것이고, 복사해 두면 제목이
바뀐 곡이 그 기기에서만 옛 이름으로 남는다. 읽을 때 번호로 서버에 물어(곡 일괄 조회)
찾지 못한 번호는 버린다.

계정 전환은 `lib/api/session.ts` 가 맡는다 — 로그인·세션 복원에 성공하면
`setStoredAccount(user_id)` 로 어느 계정 몫인지 먼저 알리고, 그 다음에 취향을 읽는다.
로그아웃하면 그 계정 몫과 쿠키·캐시를 지운다.

`lib/services/index.ts` 는 구현을 **부를 때** 불러온다. 정적 import 로 두면 웹
번들에도 `@tauri-apps/api` 가 섞이고, 이 모듈만 떼어 검사하는
`tests/services.test.mjs` 가 상대 경로를 풀지 못한다.

## 서버에 닿지 않으면

**서비스를 열지 않는다.** 웹·앱 모두 처음 켜질 때 `GET /api/actuator/health` 를 보고,
`UP` 이 아니면 닫을 수 없는 창에 "다시 시도" 만 남긴다(`components/mupid/server-gate.tsx`,
S15P21A204-444). 음악 스트리밍이라 서버 없이 할 수 있는 일이 없다.

그래서 서버 응답을 기기에 남겨 두던 캐시(`withCache`, 곡 목록·구간 분석)는 없앴다.
묵은 곡 목록으로 화면을 채우면 목록은 보이는데 재생이 안 되는 상태가 된다.
SQLite 스키마 v2 가 예전 기기에 남은 `cache` 표를 지운다.

## 앱에서만 부딪히는 것들

웹에서는 서버가 조용히 해 주던 일이 앱에는 없다. 실제로 부딪혀 고친 것들이다.

### 앨범 아트가 전부 깨졌다

서버는 이미지 주소를 `/api/v1/storage/image/{fileId}` 처럼 **경로로** 준다(사진 없는
계정의 기본 프로필만 `/PROFILE/default` 다). 웹에서는
같은 출처라 그대로 열리지만, 앱의 출처는 `http://tauri.localhost` 라 그 경로에는
아무것도 없다. `toImageSource()` 가 `http(s)://` 뿐 아니라 `/api/` 로 시작하는 주소도
`mupidimg` 스킴으로 넘긴다. 앱이 들고 있는 파일(`/sky-vinyl.webp`)은 건드리지 않는다.

**그래서 규칙은 하나다 — 서버가 준 이미지 주소는 `<img src>` 에 걸기 전에 반드시
`toImageSource()` 를 지난다.** 한 자리만 빠뜨려도 그 자리만 앱에서 조용히 깨진다.
실제로 리포트의 '오늘 들은 음악' 트리맵이 응답의 `image_url` 을 그대로 걸고 있었고
(`components/mupid/daily-album-treemap.tsx`), 배포된 앱에서만 커버가 전부 깨졌다.
개발 서버에서는 Vite 프록시가 `/api` 를 넘겨 주어 멀쩡했던 탓에 늦게 드러났다.

주소를 못 구했거나 못 읽은 자리는 목록의 커버(`AlbumArt`)와 같은 규칙으로
`resolveAlbumCover()` 가 기본 커버로 내린다. 기본 커버를 건 뒤에는 `onError` 를 떼어
다시 그리기가 끝없이 돌지 않게 한다.

웹도 늘 "경로 그대로" 는 아니다. 화면과 API 의 출처가 갈리는 배포에서는
`VITE_MUPID_API_BASE` 가 채워지고, `toImageSource()` 가 `/api/` 로 시작하는 주소에 그
값을 붙인다 — `fetch` 에는 `ApiClient` 가 붙여 주지만 `<img src>` 에는 붙여 줄 사람이
없다. 주소를 읽는 자리는 `lib/api/base-url.ts` 하나다(`session.ts` 에 두면
`platform → api/session → platform` 순환이 된다). 지금 배포(`ec2`)는 nginx 가 화면과
API 를 같은 출처로 서빙하므로 이 값은 비어 있다.

### 탭을 누를 때마다 화면 전체가 다시 떴다

라우터는 화면을 옮길 때 `<경로>?_rsc` 로 RSC 페이로드를 받아 간다. 자산 프로토콜은
그 표시를 모른 채 `explore.html` 을 그대로 줬고, 라우터는 "RSC 응답이 아니다" 라고
보고 문서를 통째로 다시 읽었다.

셸이 그 요청을 가로채 같은 경로의 `.rsc` 파일로 갈아 끼우고, 서버가 붙여 주던 헤더
둘을 대신 붙인다 (`app/src/lib.rs` 의 `serve_rsc`).

 - `Content-Type: text/x-component`
 - `X-Vinext-RSC-Compatibility-Id` — vinext 가 빌드마다 새로 만드는 값이라, 앱 빌드에서는
   `vite.config.ts` 의 `pinRscCompatibilityId` 로 **고정**한다. 셸의 상수와 같아야 하며,
   어긋나면 `npm run build:static` 이 빌드를 실패시킨다.

`ec2` 정적 배포도 같은 처지다 — nginx 도 `?_rsc` 표시를 모르니 화면을 옮길 때마다
전체를 다시 읽는다. 깨지지는 않고 느릴 뿐이라 이번에는 손대지 않았다. 고치려면 nginx
에서 그 표시를 보고 `.rsc` 파일을 위 두 헤더와 함께 내주면 된다.

### 글꼴이 시스템 기본값으로 떨어졌다

Pretendard 를 jsdelivr 에서 받고 있었는데 앱의 CSP 가 외부 폰트를 막는다. 실제로 쓰는
굵기(400·500·600·700·800)만 `public/fonts/pretendard/` 에 담아 자체 호스팅으로 바꿨다.
웹도 CDN 에 기대지 않게 되었다.

### 상태바·내비게이션 바

CSS 는 이미 `env(safe-area-inset-*)` 로 여백을 잡고 있었다. 문제는 네이티브 쪽이었다 —
밝은 배경 위에 흰 시스템 아이콘이 올라와 보이지 않았고, 3버튼 내비게이션 뒤에 회색
띠가 깔렸다. `MainActivity.kt` 에서 `SystemBarStyle.light` 에 투명 스크림으로 지정해
해결했다. 웹뷰는 그대로 화면 끝까지 쓴다.

## 빌드

앱은 `output: 'export'` 정적 번들을 쓴다. 서버가 없으니 RSC 서버 런타임을 태울 수 없다.

```bash
npm run build:static   # dist/client/ ← Tauri 의 frontendDist
npm run build:ec2      # 같은 산출물, EC2/nginx 배포용
npm run dev:app        # Cloudflare 플러그인 없이 개발 서버만
npm run build          # 지금까지의 Cloudflare Worker 배포 (그대로)
```

`MUPID_DEPLOY_TARGET` 이 `ec2` 또는 `app` 이면 정적 export 로 간다
(`next.config.ts` · `vite.config.ts`). 대상 이름은 두 파일에 같은 목록으로 있다.

## 아직 안 한 것

- 오디오 청크는 캐시하지 않는다. 오프라인 재생을 하려면 용량 상한과 만료 정책이
  먼저 필요하다.
- 이벤트 큐 백업은 앱에서도 웹뷰 `localStorage` 를 쓴다 (`lib/analytics/tracker.ts`).
  보내고 나면 사라지는 값이라 그대로 두었다.
