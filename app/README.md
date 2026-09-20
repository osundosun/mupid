# 뮤피드 앱 셸 (Tauri)

`frontend/` 를 그대로 감싸 모바일 앱으로 만든다. **웹 배포는 그대로 유지한다** —
화면 코드는 한 벌이고, 웹이냐 앱이냐로 갈리는 것은 바깥 네 군데뿐이다.

| | 웹 | 앱 |
| --- | --- | --- |
| HTTP | 브라우저 `fetch` + Vite/nginx 프록시 | **Rust (reqwest)** — `src/http.rs` |
| 저장소 | `localStorage` | **SQLite** — `src/db.rs`, `src/store/` |
| 앨범 아트 | 원격 주소 그대로 | Rust 가 받아 `mupidimg` 스킴으로 |
| 화면 | RSC 서버 (Cloudflare Worker) | `output: 'export'` 정적 번들 |
| 재생 표시 | 화면 안에만 | **상단 알림·잠금화면** — `src/media.rs` |
| 시스템 바 여백 | `env(safe-area-inset-*)` | **안드로이드가 잰 인셋** — `gen/android/.../SafeArea.kt` |

## 왜 HTTP 를 Rust 로 옮겼나

서버에 CORS 설정이 없고 리프레시 토큰 쿠키가 `SameSite=Strict; Secure` 다. 웹에서는
프록시로 같은 출처인 척해서 넘겼지만, 앱 웹뷰의 출처는 `http://tauri.localhost`
같은 것이라 그 방법이 통하지 않는다. 네이티브에서 요청하면 두 제약이 함께 사라진다.

쿠키 항아리는 Rust 가 들고 SQLite 에 적는다. 그래서 앱을 껐다 켜도 로그인이 유지된다.

## 빌드와 실행

사전 준비: Rust, Android SDK/NDK, JDK 17+, `cargo install tauri-cli`.
`ANDROID_HOME` 과 `NDK_HOME` 이 잡혀 있어야 한다.

```bash
# 데스크톱에서 화면만 빠르게 보기 (개발 서버 + 웹뷰)
cd app && cargo tauri dev

# 안드로이드 기기/에뮬레이터에 띄우기
cd app && cargo tauri android dev

# APK
cd app && cargo tauri android build --apk --target aarch64

# 최초 1회 — 안드로이드 프로젝트 생성 (gen/android)
cd app && cargo tauri android init
```

`beforeDevCommand` · `beforeBuildCommand` 는 **저장소 루트에서** 돈다. 그래서
`npm --prefix frontend ...` 이고, `frontendDist` 같은 경로는 이 파일 기준이다.

## 검증

```bash
cd app && cargo test          # 저장소 왕복·계정 분리·캐시 만료
cd frontend && node --experimental-strip-types --test tests/platform-transport.test.mjs
cd frontend && node --experimental-strip-types --test tests/media-session.test.mjs
cd frontend && node --experimental-strip-types --test tests/safe-area.test.mjs
```

Rust 시험은 실제 SQLite 파일을 쓴다. 메모리 DB 로는 WAL 과 마이그레이션이 돌지 않아
정작 확인하고 싶은 것을 못 본다.

## API 서버 주소

화면은 `/api/v1/...` 경로만 만든다. 원본 주소를 붙이는 일은 Rust 가 한다
(`src/config.rs`). 기본값은 `https://j15a204.p.ssafy.io` 이고, 빌드할 때 바꾼다.

`option_env!` 이라 **컴파일 시점에 박힌다.** `build.rs` 가
`cargo:rerun-if-env-changed=MUPID_API_ORIGIN` 을 내보내므로 값을 바꾸면 다시
컴파일된다 — 그 줄이 없으면 cargo 는 환경 변수가 바뀐 줄 모르고 예전 주소가 박힌
결과물을 그대로 쓴다.

### 로컬 Spring 서버에 붙이기

기기에는 PC 의 `localhost` 가 없다. `adb reverse` 로 기기의 포트를 PC 로 넘긴다.

```bash
adb reverse tcp:9900 tcp:9900          # 기기의 9900 → PC 의 9900 (nginx)
cd app && MUPID_API_ORIGIN=http://localhost:9900 cargo tauri android dev
```

**스프링(8800)이 아니라 nginx(9900) 를 가리켜야 한다.** 조회 API 는 스프링에 직접
붙어도 되지만, 오디오 청크 내려받기는 스프링이 `X-Accel-Redirect` 헤더만 주고 본문을
비운 채 끝낸다 — 실제 바이트는 nginx 가 오브젝트 스토리지에서 채워 넣는다. 8800 으로
붙이면 화면은 멀쩡한데 재생만 "청크 파일이 너무 짧습니다" 로 실패한다.

**주소는 `localhost` 여야 한다. 공유기 IP(`192.168.x.x`)나 에뮬레이터의
`10.0.2.2` 로 붙이면 로그인은 되는데 앱을 껐다 켤 때마다 풀린다.**

리프레시 토큰 쿠키가 `Secure` 로 내려오기 때문이다 (`AuthController`). 쿠키
항아리는 그 쿠키를 **https 이거나 루프백 주소일 때만** 실어 보낸다
(`cookie_store` 의 `is_secure`: `https`, `localhost`, 루프백 IP). 사설 IP 는 그
어느 쪽도 아니라 재발급 요청에 쿠키가 빠지고 서버는 401 을 준다. 로그인 직후에는
액세스 토큰이 응답 본문으로 오니 멀쩡해 보이고, 다시 켰을 때만 티가 난다 —
그래서 더 헷갈린다. `adb reverse` 는 에뮬레이터에서도 되므로 언제나 이쪽을 쓴다.

평문 HTTP 는 문제가 되지 않는다. Android 의 cleartext 차단은 자바 계층 네트워크와
웹뷰에 걸리는 것이고, 이 앱의 요청은 Rust 가 직접 소켓을 연다. 개발 서버를 띄우는
웹뷰 쪽은 debug 빌드에서 `usesCleartextTraffic=true` 라 함께 열려 있다
(`gen/android/app/build.gradle.kts`).

포트는 `9900`(nginx) 이고 그 뒤에 스프링이 `8800`(`MUPID_PORT`) 으로 있다. 웹 개발
서버의 `/api` 프록시도 같은 9900 을 본다.

## SQLite

앱 데이터 디렉터리의 `mupid.db` 한 파일이다. 스키마는 `src/db.rs` 의 `SCHEMA_V1`,
버전은 `PRAGMA user_version` 으로 관리한다 — 새 마이그레이션을 넣을 때
`SCHEMA_VERSION` 을 올리고 `migrate()` 에 단계를 더한다.

담는 것:

- **취향·컬렉션** — 분위기·장르, 좋아요/무시/기록, 저장 구간, 플레이리스트, 프로필 사진
- **세션** — 리프레시 토큰 쿠키 항아리, 지금 열려 있는 계정 키
- **캐시** — 곡 카탈로그와 구간 분석 응답

담지 않는 것: 액세스 토큰(수명이 짧아 메모리에만 둔다), 오디오 청크(용량 관리
정책이 따로 필요해 아직 손대지 않았다).

계정마다 `user_key` 로 나눠 담는다. 로그인 전에는 `local`, 로그인하면 사용자
번호다. 로그아웃하면 그 계정 몫과 쿠키·캐시를 지운다 — 한 기기를 두 사람이 쓸 때
뒷사람이 앞사람의 컬렉션을 보면 안 된다.

## 커맨드

| 커맨드 | 하는 일 |
| --- | --- |
| `http_send` | 요청 하나. 응답은 `[4바이트 길이][메타 JSON][본문]` 한 덩어리 |
| `http_abort` | 진행 중인 요청 취소 (`AbortSignal` 이 붙은 것만) |
| `http_clear_cookies` | 기기에 남은 로그인 쿠키 삭제 |
| `store_load_preferences` / `store_save_preferences` | 취향·컬렉션 스냅샷 |
| `store_read_photo` / `store_save_photo` / `store_remove_photo` | 프로필 사진 |
| `session_set_user_key` | 지금 저장이 어느 계정 몫인지 |
| `cache_get` / `cache_put` / `cache_clear` | 서버 응답 캐시 |
| `media_session_update` / `media_session_clear` | 상단 알림·잠금화면 플레이어 |

화면 쪽 짝은 `frontend/lib/platform/` 에 있다.

Rust 가 먼저 말을 거는 통로는 하나뿐이다 — 알림·잠금화면·이어폰 버튼을 누르면
`media-session://control` 이벤트가 웹뷰로 간다.

## 상단 알림과 잠금화면 플레이어

노래가 울리는 동안 알림 그늘과 잠금화면에 곡과 조작 버튼이 뜬다.

```
재생기 스토어 ─▸ useMediaSession ─▸ media_session_update ─▸ MediaSessionPlugin
                                                             │
     알림(PlaybackService) ·  잠금화면  ·  이어폰 버튼  ◂──────┘
                                    │
           media-session://control ◂┘ ─▸ 화면의 play/pause/next/seek
```

| 어디 | 무엇 |
| --- | --- |
| `frontend/hooks/use-media-session.ts` | 재생기 스토어를 구독해 올려보낸다 |
| `frontend/lib/platform/media-session.ts` | 무엇을 언제 보낼지. 웹에서는 아무 일도 안 한다 |
| `src/media.rs` | 앨범 아트를 받아 파일로 두고, 플러그인에 넘긴다 |
| `gen/android/.../MediaSessionPlugin.kt` | `MediaSession` 갱신, 버튼을 되돌려 보냄 |
| `gen/android/.../NowPlayingSession.kt` | 세션 한 벌(메타데이터·재생 상태·표지) |
| `gen/android/.../PlaybackService.kt` | 알림을 든 전경 서비스 |

**재생 위치를 0.2초마다 올려보내지 않는다.** `PlaybackState` 에 재생 속도를 함께
주면 안드로이드가 스스로 위치를 굴린다. 그래서 곡·재생 상태가 바뀌거나 실제
위치가 2초 넘게 어긋났을 때만 IPC 를 탄다 (`shouldPush`). 매 틱마다 보내면 곡
하나에 IPC 가 천 번 넘게 오간다.

**전경 서비스는 멈춰 있는 동안에도 내려오지 않는다.** 소리는 웹뷰가 내므로
안드로이드는 이 앱이 음악을 틀고 있다는 것을 따로 알지 못한다 — 전경 서비스가
붙어 있어야 화면을 끄고도 프로세스가 살아남는다. 게다가 안드로이드 12 부터는
뒤에 있는 앱이 전경 서비스를 **시작하지 못하므로**, 일시정지할 때 내려오면
잠금화면의 재생 버튼으로 다시 올라올 수가 없다. 알림을 쓸어 내면 그때 함께
내려간다.

**안드로이드 13+ 는 알림 권한을 묻는다.** 앱을 켤 때가 아니라 **처음 재생할 때**
묻는다. 거절해도 재생은 그대로 이어지고, 알림만 뜨지 않는다. 나중에 설정에서
켜면 앱으로 돌아오는 순간(`onResume`) 다시 뜬다.

**소리 초점(audio focus)은 아직 잡지 않는다.** 다른 앱이 음악을 틀어도 이 앱은
멈추지 않고, 알림이 울려도 잠깐 줄어들지 않는다. `AudioManager.requestAudioFocus`
를 붙이는 자리는 `NowPlayingSession` 이다.

### 배경 재생은 어디까지 되나

| 무엇을 했나 | 재생 | 플레이어 |
| --- | --- | --- |
| 홈으로 나감 · 화면 끔 · 다른 앱 | **이어진다** | 남는다 |
| 알림의 정지 · 알림을 쓸어 냄 | 끝난다 | 사라진다 |
| **최근 앱 목록에서 밀어냄** | **끝난다** | 사라진다 |

앞의 두 줄은 의도한 것이고, 마지막 줄은 **이 구조에서 어쩔 수 없는 것**이다.
그렇게 하기로 정했다.

소리를 내는 것은 웹뷰이고 웹뷰는 액티비티에 붙어 있다. 최근 앱에서 밀어내면
액티비티가 파괴되면서 웹뷰도 함께 사라진다. 게다가 그 정리 과정에서
안드로이드의 `libhwui` 가 죽는다 — 실측한 텀스톤이다.

```
Abort message: 'FORTIFY: pthread_mutex_lock called on a destroyed mutex'
  #04 libhwui.so  WebViewFunctorManager::onContextDestroyed()
  #05 libhwui.so  RenderThread::destroyRenderingContext()
```

전경 서비스는 **안드로이드가 프로세스를 거둬 가려 할 때** 막아 주는 장치지,
액티비티를 지켜 주지도 크래시를 막아 주지도 않는다. `android:stopWithTask="false"`
도, 서비스를 붙들어 두는 것도 소용없었다 — 셋 다 해 보고 확인했다.

그래서 남은 선택은 "밀어내면 깔끔히 끝난다"뿐이고, 그것을 `MainActivity.onDestroy`
가 맡는다. 거기서 거두지 않으면 **눌러도 아무 일이 없는 플레이어**가 알림 그늘에
남는다. 거둘 때 순서가 중요하다.

1. **전경에서 먼저 내려온다.** 전경 서비스는 알림을 반드시 하나 들고 있어야 해서,
   올라서 있는 동안 `cancel()` 을 불러도 시스템이 도로 띄운다.
2. 그 다음에 알림을 지운다.

둘 다 **동기로** 해야 한다. `stopService` 는 비동기라 서비스의 `onDestroy` 가 돌기
전에 프로세스가 먼저 사라진다. 그래서 `PlaybackService.dismiss` 가 돌고 있는 서비스
인스턴스를 직접 붙잡아 손으로 내린다.

**플레이어를 누르면 앱으로 돌아온다.** 인텐트를 앱 아이콘과 똑같이 만든다
(`ACTION_MAIN` + `CATEGORY_LAUNCHER`). 그냥 `Intent(this, MainActivity)` 로 띄우면
열려 있던 화면 위에 새 태스크가 하나 더 생겨서, 돌아온 뒤 뒤로 가기를 누르면 같은
앱이 두 번 나온다. 앱이 살아 있으면 보던 화면 그대로 돌아오고, 밀어낸 뒤라면
플레이어 자체가 없으니 누를 일도 없다.

## 상태바·내비게이션 바 여백

화면을 시스템 바 밑까지 쓴다(`MainActivity` 의 `enableEdgeToEdge`). 그만큼을 비워
두라고 CSS 에 일러 주는 것이 `env(safe-area-inset-*)` 인데, **안드로이드 웹뷰는 그
값에 노치만 넣고 상태바·내비게이션 바는 넣지 않는다.** 노치 없는 기기에서는 넷 다
0 이라, 비워 두라고 적어 둔 규칙이 아무 일도 하지 않고 상단바가 상태바 밑으로
들어간다.

그래서 안드로이드가 잰 값을 직접 건넨다.

```
WindowInsets ─▸ SafeArea.kt ─▸ window.__mupidSafeArea (기기 픽셀)
                     │                    │
                     │      화면이 뜰 때 한 번 읽고 ◂┘
                     └─ 값이 바뀌면 mupid:safe-area 이벤트 ─▸ 다시 읽는다
                                          │
                     <html style="--safe-area-inset-top: 24px; …"
```

| 어디 | 무엇 |
| --- | --- |
| `gen/android/.../SafeArea.kt` | 인셋을 재서 읽어 갈 자리에 둔다 |
| `frontend/lib/platform/safe-area.ts` | 읽어서 `<html>` 에 적는다. 웹에서는 아무 일도 안 한다 |
| `frontend/app/globals.css` | `--safe-area-inset-*` 의 `env()` 기본값 |

**CSS 는 `env()` 를 직접 부르지 않는다.** 한 군데라도 직접 부르면 그 자리만 조용히
0 으로 떨어진다. `frontend/tests/safe-area.test.mjs` 가 이것을 지킨다.

**값을 밀어 넣지 않고 읽어 가게 둔다.** 첫 인셋이 도착하는 시점과 화면이 뜨는
시점의 순서를 보장할 수 없어서, 밀어 넣기만 하면 아직 빈 문서에 적히고 사라진다.
화면이 뜰 때 스스로 한 번 읽고, 그 뒤로는 이벤트를 듣는다.

**기기 픽셀로 건네고 CSS 픽셀로 바꾸는 일은 화면이 한다.** `devicePixelRatio` 는
기기 밀도뿐 아니라 웹뷰가 잡은 배율까지 담고 있어, 네이티브의
`displayMetrics.density` 보다 이쪽이 정확하다.

**상태바 몫은 카드 바깥에서 비운다** (`responsive-polish.css` 의 셸 여백). 카드
안쪽 헤더에서 비우면 둥근 윗모서리를 시각·배터리가 가로지른다.

## 자동 업데이트

두 갈래가 있고, 둘 다 **빌드 타임스탬프가 버전을 대신한다** (semver 를 쓰지 않는다).

| 갈래 | 무엇을 바꾸나 | 판단 | 사용자가 하는 일 |
| --- | --- | --- | --- |
| 웹 | 번들 파일만 | `builtAt` 이 **다르면** 교체 | 없음 |
| 네이티브 | APK 자체 | `versionCode` 가 **클 때만** 안내 | 설치 |

- **웹**: 기동 뒤 조용히 내려받아 **디스크에만** 활성화한다. 실제 교체는 다음
  콜드 스타트다 — 화면을 다시 읽으면 듣던 곡이 끊긴다. `src/assets.rs` 의
  자산 프로바이더가 디스크 우선·임베드 폴백으로 서빙한다.
- **네이티브**: `versionCode` 를 `MUPID_NATIVE_BUILD`(epoch ms)에서 유도하고
  1000 으로 나눈다. 밀리초를 그대로 쓰면 안드로이드 상한(2,100,000,000)을
  넘어 **빌드는 되고 설치는 실패한다.**

**페이로드가 코드뿐인 이유가 중요하다.** 번들 39MB 중 이미지 82%·폰트 14%는
이미 APK 안에 있고, 임베드 레이어가 그 자리를 채운다. 그래서 코드만 보내면
318KB 로 줄어든다(`frontend/scripts/web-archive.mjs`). 이게 성립하는 근거는
**파일 이름이 고정**이라는 것이다(내용 해시가 붙은 파일이 없다).

**스탬프가 셋에서 같아야 한다.** 번들 안 `app/build.json`, `web.json`, 아카이브
이름이 어긋나면 앱이 자기 번들을 계속 다시 받는다. CI 가 프론트 빌드와 APK
빌드에 **같은** `MUPID_WEB_BUILD`/`MUPID_NATIVE_BUILD` 를 넣는다.

스탬프를 만드는 규칙은 `infra/ci/build-stamp.sh` 한 곳에 있다. 값은 **커밋
시각**에서 온다 — `date` 로 하면 job 마다·재시도마다 값이 달라져 웹 아카이브와
APK 의 짝이 어긋나고, 같은 파이프라인을 다시 돌릴 때마다 사용자 전원에게
재설치 안내가 새로 뜬다.

로컬에서 APK 를 구워 이 배선을 확인하는 법(실측 2026-09-18):

```bash
export MUPID_NATIVE_BUILD=$(sh infra/ci/build-stamp.sh)
export MUPID_WEB_BUILD=$MUPID_NATIVE_BUILD
cd app && cargo tauri android build --debug --features custom-protocol --apk --target aarch64
aapt2 dump badging <apk> | grep ^package      # versionCode 가 스탬프/1000 과 같은가
```

`aapt2 dump badging` 실측: `versionCode='1789704065'` — 스탬프 `1789704065000`
을 1000 으로 나눈 값이 그대로 박혔다. `libmupid_app_lib.so` 안에도 같은 값이
들어 있어 gradle 과 Rust 가 어긋나지 않는다.

### 매니페스트와 체크섬 파일을 함께 본다

`native.json` 의 `sha256` 만 믿지 않는다. APK 옆의 `mupid.apk.sha256` 과
**맞대어 보고**(`update.rs` 의 `verify_native_checksum`), 어긋나면 설치하지
않는다. 둘은 서로 다른 통로로 놓이므로 어긋난다는 것 자체가 한쪽이 틀렸다는
신호다. 해시뿐 아니라 파일 이름(`mupid-<커밋>.apk`)까지 본다.

**체크섬 파일이 없으면 막지 않는다** — `404` 는 "그 통로를 아직 안 열었다" 일
수 있고, 그때까지 네이티브 갈래를 죽이면 안 된다. 내려받은 바이트는 매니페스트
해시로 그대로 검증한다.

### 매니페스트를 몇 번 요청하나 — **기동 1회에 2회가 정상**

이 숫자가 헷갈려서 한 번 잘못 짚었다(라이브 로그에 `native.json` 이 한 초에
4회 찍혀 "중복 요청 버그" 로 보였다). 실측으로 확인한 정상값을 적어 둔다.

```
콜드 스타트 1회
  기동 훅  checkForUpdates()  → update_status 1회  → web.json 1 + native.json 1
  안내 창  readUpdateState()  → update_status 1회  → web.json 1 + native.json 1
                                                      ─────────────────────────
                                                      web.json 2 + native.json 2
```

**2회인 이유는 소비자가 둘이기 때문이다.** 기동 훅은 "내려받을 게 있나" 를,
안내 창은 "설치 안내를 띄울까" 를 각각 판단한다. 하나로 합치려면 상태를 전역
스토어로 넘겨야 하는데, 그건 이 범위를 넘는다 — 그래서 **정상값을 여기 적는다.**

**내려받을 게 없으면 그 이상 늘지 않는다.** `checkForUpdates` 는
`webAvailable` 이 아니면 상태를 다시 읽지 않고 첫 값을 그대로 돌려준다. 예전에는
항상 다시 읽어서 기동당 `update_status` 가 3회, 즉 `web.json` 3 + `native.json` 6
이었다. 지금은 2 + 2 다.

**`native.json` 만 두 배였던 적이 있다.** `fetch_native_manifest` 가 `404` 를
보려고 요청을 한 번 보내고 본문을 받으려고 또 한 번 보냈다. 지금은 상태와 본문을
한 번에 받는다(`fetch_bounded_optional`). 그래도 `404` 구분은 그대로다 —
`classify_optional` 이 그 판단을 맡고, **`500` 같은 서버 고장을 "없음" 으로
삼키지 않는다**(시험이 그 구분을 고정한다).

로그에서 이 숫자가 **더 크게** 보이면 그때는 진짜 중복이다. 세는 법:

```sh
# 엣지 파드의 nginx 로그에서 앱이 보낸 요청만 (User-Agent 가 앱이다)
sudo k3s kubectl -n mupid logs deploy/edge --tail=2000 | grep MupidApp | grep download
```

### 번들 교체가 오래 걸리면 진행 화면이 뜬다

정상은 전체가 1초 안쪽이다(실측: 아카이브 344KB 를 **0.16초**에 내려받는다).
그래서 평소에는 **아무것도 안 뜬다** — 사용자에게는 "앱을 껐다 켜니 새 화면"
으로만 보이고, 그게 의도다.

느린 회선에서 수십 초 걸릴 때만 오버레이를 띄운다. **먼저 띄우고 빨리 끝나면
숨기는 방식이 아니다** — 그러면 깜빡임이 되고, 정상 경로가 대부분인데 매 기동마다
화면이 번쩍이면 대비책이 오히려 해가 된다. 타이머가 먼저 끝나야 그린다:

```
lib/platform/update.ts
  UPDATE_PROGRESS_DELAY_MS = 1500   ← 임계값. 한 곳에 있다.
  withSlowNotice(run, onSlow)       ← 타이머와 작업의 경주
```

**임계값을 감으로 조정하지 않으려고 계측을 넣었다.** `refresh_web` 이 두 구간을
나눠 남긴다:

```
웹 번들을 활성화했습니다: 1789721872000 (내려받기 152ms, 전개·검증 1180ms, 파일 53개)
```

읽는 법 — `mupid-` 로 걸러낸다(로거가 붙이는 표식):

```sh
adb logcat -d | grep "mupid-"
```

**이 값이 1500ms 에 가깝거나 크면 임계값을 올려야 한다.** 그러지 않으면 정상
업데이트에도 진행 화면이 떠서 "빠르면 안 띄운다" 는 의도가 깨진다. 지금 1500ms
는 추정값이고, 폰 실측으로 확정한다.

### 진단 로그가 안 보이면 `logging::init()` 을 본다

`log` 크레이트는 **파사드**다 — 등록된 로거가 없으면 `log::info!` 가 **조용히
버려진다**(오류도 경고도 없다). 이 앱에는 한동안 로거가 없었고, 그래서
**20곳의 진단이 전부 죽어 있었다**(`http.rs` 6 · `media.rs` 6 ·
`update_net.rs` 4 · `lib.rs` 3 · `commands.rs` 1).

실제로 이 때문에 헤맸다 — 계측을 넣고 기기에서 아무것도 안 찍혔는데,
`assets.rs` 의 진단은 보였다. 그쪽은 `log` 가 아니라 `println!` 이었다.

`src/logging.rs` 가 stdout 으로 내보내는 최소 로거를 등록한다(`run()` 의 맨 앞).
`tauri-plugin-log` 를 쓰지 않은 이유는 파일 로테이션·웹뷰 전달까지 딸려 오기
때문이다 — 필요한 것은 logcat 에 한 줄 나오는 것뿐이다.

오류는 **로그만 남긴다**(`log::warn!` / `console.warn`). Firebase Crashlytics 를
붙일 때의 기록 지점을 주석으로 표시해 뒀다 — `lib/platform/update.ts` 의 `catch`
와 `src/commands.rs` 의 실패 분기다.

### Tauri 를 올릴 때 확인할 것

`src/assets.rs` 는 `Context.assets` 를 `generate_context!()` 결과에서 꺼내
`LayeredAssets` 로 감싼다. **`Context` 는 안정 API 가 아니다** — Tauri 문서가
"직접 만들지 말라"고 명시한다. `assets` 가 `pub` 필드라는 사실에 기대고 있어
포인트 릴리스에서 깨질 수 있다.

Tauri 를 올린 뒤에는 **디스크 번들이 실제로 서빙되는지**를 실기기에서 확인한다:

```sh
# 일반 debug 빌드로는 검증되지 않는다 (dev 서버로 프록시해 프로바이더를 안 탄다)
cargo tauri android build --debug --features custom-protocol --target aarch64 --apk
adb logcat -d | grep mupid-assets     # "디스크에서 서빙" 이 보여야 한다
```

`Context.assets` 가 더 이상 열려 있지 않으면 설계 7.1.2 의 대안
(`on_web_resource_request` 훅에서 본문 교체)으로 내려온다. 그때는 HTML 폴백·
MIME·CSP 해시를 직접 다시 구현해야 한다.

---

## 아직 안 한 것

- **iOS**: `cargo tauri ios init` 은 macOS 에서만 돈다. Rust 코드는 그대로 쓴다.
  잠금화면 플레이어는 `MPNowPlayingInfoCenter` 로 다시 만들어야 한다 —
  `src/media.rs` 의 커맨드와 이벤트는 그대로 두고 `#[cfg(target_os = "ios")]`
  가지만 채우면 화면 코드는 건드릴 일이 없다.
- **오프라인 재생**: 오디오 청크는 캐시하지 않는다. 용량 상한과 만료 정책이 필요하다.
- **최근 앱에서 밀어내도 이어지는 재생**: 디코딩과 스케줄링을 웹뷰 밖(Kotlin/Rust)
  으로 옮겨야 한다. `frontend/lib/player/engine.ts` 1,000줄이 프리롤·페이드·Seek·
  재시도·버퍼 언더런까지 실측으로 맞춰져 있어 옮기는 일이 작지 않다. 위의
  "배경 재생은 어디까지 되나"에 왜 지금 구조로는 안 되는지 적어 두었다.
- **깊은 링크 / 푸시 알림**: 아직 계획 없음.
- **가로 화면의 좌우 인셋**: 값은 `--safe-area-inset-left/right` 로 이미 오지만
  쓰는 규칙이 없다. 세로에서는 둘 다 0 이라 티가 나지 않는다. 가로로 돌렸을 때
  노치나 제스처 바 쪽으로 붙는 것은 하단 메뉴와 미니 플레이어다.
