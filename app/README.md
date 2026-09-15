# 뮤피드 앱 셸 (Tauri)

`frontend/` 를 그대로 감싸 모바일 앱으로 만든다. **웹 배포는 그대로 유지한다** —
화면 코드는 한 벌이고, 웹이냐 앱이냐로 갈리는 것은 바깥 네 군데뿐이다.

| | 웹 | 앱 |
| --- | --- | --- |
| HTTP | 브라우저 `fetch` + Vite/nginx 프록시 | **Rust (reqwest)** — `src/http.rs` |
| 저장소 | `localStorage` | **SQLite** — `src/db.rs`, `src/store/` |
| 앨범 아트 | 원격 주소 그대로 | Rust 가 받아 `mupidimg` 스킴으로 |
| 화면 | RSC 서버 (Cloudflare Worker) | `output: 'export'` 정적 번들 |
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

화면 쪽 짝은 `frontend/lib/platform/` 에 있다.

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

## 아직 안 한 것

- **iOS**: `cargo tauri ios init` 은 macOS 에서만 돈다. Rust 코드는 그대로 쓴다.
- **오프라인 재생**: 오디오 청크는 캐시하지 않는다. 용량 상한과 만료 정책이 필요하다.
- **깊은 링크 / 푸시 알림**: 아직 계획 없음.
- **가로 화면의 좌우 인셋**: 값은 `--safe-area-inset-left/right` 로 이미 오지만
  쓰는 규칙이 없다. 세로에서는 둘 다 0 이라 티가 나지 않는다. 가로로 돌렸을 때
  노치나 제스처 바 쪽으로 붙는 것은 하단 메뉴와 미니 플레이어다.

