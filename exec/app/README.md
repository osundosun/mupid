# 앱 (Tauri 안드로이드 셸) 포팅 매뉴얼

## 1. 개요

- `app/` 은 `frontend/` 의 정적 번들을 Tauri 2 로 감싼 **안드로이드 앱 셸**이다. 화면 코드는 웹과 같은 것을 쓴다 ([app/README.md](../../app/README.md)).
- HTTP 는 웹뷰 `fetch` 가 아니라 Rust(reqwest + rustls)가 보낸다. 로컬 저장소는 SQLite(`mupid.db`)다.
- 패키지 ID `com.osundosun.mupid`, 제품명 `뮤피드` ([tauri.conf.json](../../app/tauri.conf.json)).
- 배포물: 서명된 APK 1개(aarch64). CI 가 구워 엣지 노드로 보내면 `https://j15a204.p.ssafy.io/download/mupid.apk` 로 내려준다.
- 자동 업데이트는 두 갈래다. **웹 번들**(`/download/web.json` + `web-<builtAt>.tar.gz`)은 조용히 교체하고, **네이티브**(`/download/native.json`)는 `versionCode` 가 클 때만 재설치를 안내한다.
- iOS 는 아직 없다 (`cargo tauri ios init` 을 하지 않았다).

## 2. 기술 스택 · 버전

| 항목 | 버전 | 근거 |
| --- | --- | --- |
| Rust | MSRV `1.77.2` (edition 2021). CI 는 rustup **stable**(고정하지 않음, `--profile minimal`) | [Cargo.toml](../../app/Cargo.toml), [android.Dockerfile](../../infra/runner/images/android.Dockerfile) |
| Rust 타깃 | `aarch64-linux-android`, `armv7-linux-androideabi`, `i686-linux-android`, `x86_64-linux-android` (배포는 aarch64 만 굽는다) | [android.Dockerfile](../../infra/runner/images/android.Dockerfile), [build-apk.sh](../../infra/ci/build-apk.sh) |
| Tauri | `tauri 2.11.5`, `tauri-build 2.6.3` (Cargo.lock 기준) | [Cargo.lock](../../app/Cargo.lock) |
| Tauri CLI | `tauri-cli ^2` (`cargo install tauri-cli --version "^2" --locked`) | [android.Dockerfile](../../infra/runner/images/android.Dockerfile) |
| JDK | OpenJDK 17 (AGP 8.11 요구) | [android.Dockerfile](../../infra/runner/images/android.Dockerfile) |
| Android SDK | platforms `android-36`/`android-35`, build-tools `36.0.0`/`35.0.0`, cmdline-tools `11076708` | [android.Dockerfile](../../infra/runner/images/android.Dockerfile) |
| Android NDK | `27.2.12479018` | [android.Dockerfile](../../infra/runner/images/android.Dockerfile) |
| compileSdk / targetSdk / minSdk | `36` / `36` / `24` | [app/build.gradle.kts](../../app/gen/android/app/build.gradle.kts), [tauri.conf.json](../../app/tauri.conf.json) |
| Gradle / AGP / Kotlin | Gradle `8.14.3`(wrapper) / AGP `8.11.0` / Kotlin `1.9.25` | [gradle-wrapper.properties](../../app/gen/android/gradle/wrapper/gradle-wrapper.properties), [build.gradle.kts](../../app/gen/android/build.gradle.kts) |
| Firebase | google-services `4.5.0`, crashlytics-gradle `3.0.7`(3.0.8 은 Kotlin 메타데이터 불일치로 불가), firebase-crashlytics `20.1.1` | [build.gradle.kts](../../app/gen/android/build.gradle.kts), [app/build.gradle.kts](../../app/gen/android/app/build.gradle.kts) |
| Node.js | 22.x (`beforeBuildCommand` 로 프론트를 굽는다) | [android.Dockerfile](../../infra/runner/images/android.Dockerfile) |
| CI 이미지 | `mupid-ci-android:1` (debian bookworm-slim, **linux/amd64**, 러너 로컬에만 존재) | [android.Dockerfile](../../infra/runner/images/android.Dockerfile), [app.yml](../../infra/ci/app.yml) |
| IDE | > TODO: 팀 확인 (Android Studio / VS Code 등 사용 IDE와 버전) | - |

주요 Rust 의존성: `reqwest 0.12`(rustls-tls-webpki-roots — NDK 에 OpenSSL 을 붙이지 않음), `rusqlite 0.32`(bundled), `tokio 1`, `tauri-utils 2`(`html-manipulation-2`) — [Cargo.toml](../../app/Cargo.toml).

## 3. 빌드 절차

사전 준비: Rust, Android SDK/NDK, JDK 17+, `cargo install tauri-cli`, `ANDROID_HOME`·`NDK_HOME` 설정. `beforeDevCommand`/`beforeBuildCommand` 는 **저장소 루트**에서 `npm --prefix frontend ...` 로 돈다. 먼저 `npm --prefix frontend ci` 를 해 둔다.

### 3-1. 데스크톱 (개발용 화면 확인)

```bash
cd app && cargo tauri dev        # frontend dev:app (http://localhost:3000) + 웹뷰
cd app && cargo test             # 실제 SQLite 파일로 저장소·캐시 시험
```

리눅스에서는 `libwebkit2gtk-4.1-dev libgtk-3-dev librsvg2-dev libayatana-appindicator3-dev patchelf` 가 필요하다(`cargo test` 도 호스트 빌드라 필요) — [android.Dockerfile](../../infra/runner/images/android.Dockerfile).

### 3-2. 안드로이드

```bash
cd app && cargo tauri android dev                           # 기기/에뮬레이터
cd app && cargo tauri android build --apk --target aarch64  # APK (keystore.properties 없으면 서명 없음)
```

- `gen/android` 는 커밋돼 있으므로 `cargo tauri android init` 은 다시 하지 않는다(생성물을 손으로 고친 파일이 있다).
- 로컬 서버에 붙일 때: `adb reverse tcp:9900 tcp:9900` 후 `MUPID_API_ORIGIN=http://localhost:9900`. **nginx(9900)** 를 가리켜야 하고 주소는 `localhost` 여야 한다(사설 IP면 Secure 쿠키가 빠져 재기동 시 로그아웃) — [app/README.md](../../app/README.md).
- 업데이트 배선 확인용 로컬 빌드:

```bash
export MUPID_NATIVE_BUILD=$(sh infra/ci/build-stamp.sh)
export MUPID_WEB_BUILD=$MUPID_NATIVE_BUILD
cd app && cargo tauri android build --debug --features custom-protocol --apk --target aarch64
```

### 3-3. CI 빌드 (배포용)

[infra/ci/app.yml](../../infra/ci/app.yml) (루트 [.gitlab-ci.yml](../../.gitlab-ci.yml) 이 include).

| job | 언제 | 하는 일 |
| --- | --- | --- |
| `app-verify` | MR 에 **Frontend 라벨**이 있을 때, develop/master 에서 `app/**`·`frontend/**` 변경 시 | `cargo test --locked` |
| `app-build` | master 보호 브랜치 push + `MUPID_DEPLOY_ENABLED=true` + `MUPID_APP_ENV` 존재 + `app/**` 등 변경 시 (web/api 트리거는 수동) | [build-apk.sh](../../infra/ci/build-apk.sh) → [deploy-apk.sh](../../infra_k8s/ci/deploy-apk.sh) |
| `app-web-release` | master push + `frontend/**` 또는 `app/**` 변경 시 | `build:static` → `build:archive` → `cargo test` → [deploy-web.sh](../../infra_k8s/ci/deploy-web.sh) |

`build-apk.sh` 순서: `MUPID_APP_ENV` 파싱(`source` 하지 않고 `read_value`, CR 제거) → `google-services.json` 복원 → 빌드 스탬프 결정·`.release/app/build-stamp` 기록 → 키스토어·`keystore.properties` 생성(EXIT trap 으로 삭제) → `npm --prefix frontend ci` → `cargo tauri android build --apk --target aarch64` → `.release/app/mupid.apk` 복사 → `apksigner verify`.

## 4. 환경 변수

`option_env!` 로 읽는 값은 **컴파일 시점에 박힌다**. 바꾸려면 APK 를 다시 구워야 한다. [build.rs](../../app/build.rs) 가 `rerun-if-env-changed` 를 내보낸다.

| 변수 | 시점 | 필수 | 설명 · 출처 |
| --- | --- | --- | --- |
| `MUPID_API_ORIGIN` | 빌드 | 선택 | API 서버 주소. 비우면 `https://j15a204.p.ssafy.io` ([config.rs](../../app/src/config.rs)) |
| `MUPID_NATIVE_BUILD` | 빌드 | CI 필수 | 빌드 스탬프(epoch ms). `versionCode = 값/1000`. `CI` 가 설정돼 있는데 없으면 Gradle 이 실패. CI 가 [build-stamp.sh](../../infra/ci/build-stamp.sh) 로 정한다 |
| `MUPID_WEB_BUILD` | 빌드 | CI 필수 | 임베드 웹 번들의 나이. `MUPID_NATIVE_BUILD` 와 **같은 값** |
| `MUPID_BUILD_STAMP` | 빌드 | 선택 | 스탬프를 고정할 때만 CI Variable 로 등록. 없으면 커밋 시각에서 만든다 |
| `MUPID_CRASHLYTICS_ENDPOINT` / `MUPID_CRASHLYTICS_API_KEY` | 빌드(런타임 우선) | 선택 | 구 경로(비공개 수집기). SDK 전환 후 불필요, 둘 다 있어야 켜짐 ([crash.rs](../../app/src/crash.rs)) |
| `GOOGLE_SERVICES_JSON_BASE64` | CI | **필수** | `google-services.json` 의 base64. 없으면 google-services 플러그인이 빌드를 실패시킨다 |
| `ANDROID_KEY_ALIAS` / `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEYSTORE_BASE64` | CI | **필수** | 릴리스 서명 (5장) |
| `ANDROID_HOME` / `NDK_HOME` | 빌드 | 필수 | CI 이미지에 들어 있다 |
| `MUPID_LOG_LEVEL` | - | - | 사용하지 않음. 로거는 `Info` 고정 ([.env.example](../../app/.env.example)) |

GitLab CI/CD 변수 ([infra/ci/VARIABLES.md](../../infra/ci/VARIABLES.md)):

| CI 변수 | 형식 | 내용 |
| --- | --- | --- |
| `MUPID_APP_ENV` | **File**, Protected | [deploy.env.example](../../app/deploy.env.example) 을 채운 것 (위 표의 `MUPID_API_ORIGIN`, 서명 3종, `GOOGLE_SERVICES_JSON_BASE64`, 선택 Crashlytics 2종) |
| `MUPID_DEPLOY_K8S_SSH_KEY` | **File** | 엣지 노드 배포 계정(`mupid-deploy-k8s`) SSH 개인키 |
| `MUPID_DEPLOY_K8S_KNOWN_HOSTS` | **File** | 엣지 노드 호스트 키 |
| `MUPID_DEPLOY_ENABLED` | Variable | `true` 일 때만 자동 배포 |
| `MUPID_K8S_BASE_URL` | Variable | 선택. 배포 후 확인 URL (기본 `https://j15a204.p.ssafy.io`) |

`MUPID_APP_ENV` 작성 규칙: 줄 끝 주석 금지, 윈도우에서 만들었으면 CR 제거, base64 값은 한 줄(`base64 -w0`).

## 5. 서명 키 / keystore 처리

- 키스토어는 **저장소에 없다**. CI 변수 `MUPID_APP_ENV` 안에 base64(`ANDROID_KEYSTORE_BASE64`)로만 있다.
- 생성(최초 1회, [deploy.env.example](../../app/deploy.env.example) 에 명령 기재): `keytool -genkeypair -keystore mupid-release.jks -alias mupid -keyalg RSA -keysize 2048 -validity 10000` → `base64 -w0 mupid-release.jks`.
- CI 에서 `build-apk.sh` 가 `app/gen/android/mupid-release.jks` 와 `app/gen/android/keystore.properties`(`storeFile`/`storePassword`/`keyAlias`/`keyPassword`, store·key 비밀번호는 같은 값)를 만들고 job 종료 시 지운다.
- [app/build.gradle.kts](../../app/gen/android/app/build.gradle.kts) 는 `keystore.properties` 가 **있을 때만** release 서명을 건다. 없으면 서명 없는 APK(설치 불가)가 나온다.
- 커밋 차단: [app/.gitignore](../../app/.gitignore) (`/gen/android/*.jks`, `*.keystore`, `google-services.json`, `GoogleService-Info.plist`, `/.env`), [app/gen/android/.gitignore](../../app/gen/android/.gitignore) (`keystore.properties`, `key.properties`, `local.properties`).
- **원본 `.jks` 는 별도 백업한다.** 잃으면 같은 앱으로 업데이트할 수 없다(사용자가 삭제 후 재설치해야 함).
  > TODO: 팀 확인 — 원본 `.jks` 백업 보관 위치·담당자
- Firebase `google-services.json` 도 같은 방식이다. 로컬 빌드는 원본을 `app/gen/android/app/google-services.json` 에 두고(커밋 안 됨), CI 는 base64 로 복원한다.
  > TODO: 팀 확인 — Firebase 프로젝트 소유 계정

## 6. 배포 특이사항

- **전달 경로**: `deploy-apk.sh` 가 `ssh mupid-deploy-k8s@j15a204.p.ssafy.io "upload-apk <커밋> <sha256> <versionCode>"` 로 APK 를 stdin 전송 → 노드의 강제 명령 [ssh-entrypoint-k8s.sh](../../infra_k8s/deploy/ssh-entrypoint-k8s.sh) → `sudo /usr/local/sbin/mupid-receive-apk`([receive-apk.py](../../infra_k8s/deploy/receive-apk.py)).
- **수신기**: `/srv/mupid-k8s/downloads/` 에 `mupid-<커밋>.apk`, `mupid.apk`, `mupid.apk.sha256`, `native.json` 을 쓴다. 옛 APK 는 5개까지 보관, 크기 상한 300 MiB. `versionCode` 가 직전보다 **작으면 거부**(같으면 허용), `builtAt == versionCode*1000` 검사, `minSdk` 는 APK 바이트에서 읽는다.
- 수신기·sudoers 설치는 ansible 역할 [deploy_receivers](../../infra_k8s/ansible/roles/deploy_receivers/tasks/main.yml) 로 한다. sudoers 규칙이 없으면 `RECEIVER_NOT_PERMITTED`, 엔트리포인트가 옛것이면 `UPLOADER_UPGRADE_REQUIRED` 로 실패한다.
- **서빙**: 엣지 nginx [default.conf](../../infra_k8s/base/nginx/default.conf) 가 hostPath 마운트(`/srv/web/downloads`, [edge.yaml](../../infra_k8s/base/edge.yaml))에서 `/download/mupid.apk`·`.sha256`·`native.json`·`web.json`(no-store), `web-<n>.tar.gz`(immutable)를 내려준다. 배포 후 스크립트가 `.sha256` 과 `native.json` 의 `versionCode` 를 확인한다.
- `app-build`·`app-web-release` 는 `resource_group: mupid-downloads` 로 직렬화한다(같은 디렉터리 정리).
- APK 는 `app/**` 변경 시에만 새로 굽는다 — 새 APK 는 전 사용자에게 재설치 안내를 띄우기 때문. 프론트만 바뀌면 웹 번들 갈래만 돈다.
- 스탬프는 **커밋 시각** 기반이라 재시도해도 같은 값이 나온다. 번들 안 `app/build.json`·`web.json`·아카이브 이름의 스탬프가 어긋나면 앱이 번들을 계속 다시 받는다.
- 앱 내 APK 설치를 위해 `REQUEST_INSTALL_PACKAGES` 권한을 쓴다. 그 밖에 `INTERNET`, `FOREGROUND_SERVICE(_MEDIA_PLAYBACK)`, `POST_NOTIFICATIONS` ([AndroidManifest.xml](../../app/gen/android/app/src/main/AndroidManifest.xml)).
- CI 컨테이너 메모리 한도 때문에 Gradle 힙 `-Xmx1024m`, 데몬 끔, 워커 2 ([gradle.properties](../../app/gen/android/gradle.properties)). `app-build` 타임아웃 2h.
- 코드 생성물 `TauriActivity.kt` 의 `handleBackNavigation = false` 를 true 로 바꾸지 않는다(뒤로가기가 모달을 닫지 않게 됨) — [app/README.md](../../app/README.md).
- Tauri 버전을 올리면 `src/assets.rs` 가 기대는 `Context.assets`(비안정 API)가 깨질 수 있다. 실기기에서 `custom-protocol` 빌드로 디스크 번들 서빙을 확인한다.

## 7. 설정 파일 목록

| 파일 | 용도 | 커밋 |
| --- | --- | --- |
| [app/Cargo.toml](../../app/Cargo.toml) / [Cargo.lock](../../app/Cargo.lock) | Rust 크레이트·의존성 | O |
| [app/tauri.conf.json](../../app/tauri.conf.json) | 앱 ID·창·CSP·번들·`minSdkVersion`·프론트 빌드 명령 | O |
| [app/build.rs](../../app/build.rs) | 빌드 시 환경 변수 재컴파일 트리거 | O |
| [app/capabilities/default.json](../../app/capabilities/default.json) | Tauri 권한(`core:default`) | O |
| [app/.env.example](../../app/.env.example) | 로컬 빌드 환경 변수 키 목록 | O |
| [app/deploy.env.example](../../app/deploy.env.example) | CI 변수 `MUPID_APP_ENV` 템플릿(서명·Firebase) | O (값 비움) |
| [app/gen/android/app/build.gradle.kts](../../app/gen/android/app/build.gradle.kts) | SDK 버전·versionCode·서명 설정·Firebase | O |
| [app/gen/android/build.gradle.kts](../../app/gen/android/build.gradle.kts) | AGP·Kotlin·Firebase 플러그인 버전 | O |
| [app/gen/android/gradle.properties](../../app/gen/android/gradle.properties) | Gradle JVM·워커 설정 | O |
| [app/gen/android/app/src/main/AndroidManifest.xml](../../app/gen/android/app/src/main/AndroidManifest.xml) | 권한·서비스 | O |
| `app/gen/android/keystore.properties` | 서명 설정 (CI 가 생성) | X |
| `app/gen/android/mupid-release.jks` | 릴리스 키스토어 (CI 가 복원) | X |
| `app/gen/android/app/google-services.json` | Firebase 설정 (로컬 수동 배치 / CI 복원) | X |
| `app/.env` | 로컬 환경 변수 | X |
| [infra/ci/app.yml](../../infra/ci/app.yml) | 앱 CI job 정의 | O |
| [infra/ci/build-apk.sh](../../infra/ci/build-apk.sh) / [build-stamp.sh](../../infra/ci/build-stamp.sh) | APK 빌드 / 버전 스탬프 | O |
| [infra_k8s/ci/deploy-apk.sh](../../infra_k8s/ci/deploy-apk.sh) | APK 전송·검증 | O |
| [infra_k8s/deploy/receive-apk.py](../../infra_k8s/deploy/receive-apk.py) | 노드 측 APK 수신기 | O |
| [infra/runner/images/android.Dockerfile](../../infra/runner/images/android.Dockerfile) | CI 이미지(`mupid-ci-android:1`) | O |

## 8. 외부 서비스

- **Firebase Crashlytics** (SDK, `google-services.json` 필요)
- **GitLab CI** — 빌드·배포, 러너 태그 `mupid-ci` (self-hosted 러너, 이미지는 러너 로컬 도커에만 있음)
- 빌드 시 다운로드: Google Maven·Maven Central, `dl.google.com`(Android SDK), `sh.rustup.rs`, crates.io, `deb.nodesource.com`, `services.gradle.org`
