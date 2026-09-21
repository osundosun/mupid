#!/bin/sh
# Tauri 안드로이드 APK 를 굽는다. mupid-ci-android 이미지 안에서 돈다.
#
#   sh infra/ci/build-apk.sh
#
# 결과: .release/app/mupid.apk
#
# 설정은 MUPID_APP_ENV 하나에서 온다 (File 형식·보호된 CI 변수). .env 와 같은
# KEY=VALUE 형식이고, music_preprocessing 쪽과 같은 방식이다.
#
#   MUPID_API_ORIGIN=https://j15a204.p.ssafy.io
#   ANDROID_KEY_ALIAS=mupid
#   ANDROID_KEYSTORE_PASSWORD=...
#   ANDROID_KEYSTORE_BASE64=...        # keytool 로 만든 .jks 를 base64 로
#
# 아래 둘은 선택이다. 넣지 않으면 예외 보고만 조용히 꺼지고 앱은 정상 동작한다
# (app/src/crash.rs). 값이 **컴파일 시점에 박히므로**(option_env!) 나중에 바꾸려면
# APK 를 다시 구워야 한다 — 런타임 환경변수로는 바뀌지 않는다.
#
#   MUPID_CRASHLYTICS_ENDPOINT=...     # 수집기 HTTPS 엔드포인트
#   MUPID_CRASHLYTICS_API_KEY=...      # 그 엔드포인트의 인증 키
#
# ⚠️ SDK 경로(google-services.json + Crashlytics Gradle 플러그인)로 가면 이 둘은
#    필요 없다. 그 길은 app/gen/android/** 와 Android 빌드를 건드린다.
set -eu
: "${MUPID_APP_ENV:?GitLab file variable required}"
: "${ANDROID_HOME:?안드로이드 이미지 안에서 돌려야 한다}"
: "${NDK_HOME:?안드로이드 이미지 안에서 돌려야 한다}"

# File 형식은 경로를, 일반 Variable 은 값을 준다. 둘 다 받는다.
if [ -f "$MUPID_APP_ENV" ]; then
  envfile=$MUPID_APP_ENV
else
  envfile=$(mktemp)
  printf '%s\n' "$MUPID_APP_ENV" > "$envfile"
fi

# 값을 뽑아 쓴다. **source 하지 않는다** — 그러면 파일 안의 $(...) 가 실행되고,
# 붙여 넣기 실수 하나가 job 에서 임의 명령이 된다.
#
# CR 을 걷어내는 것이 핵심이다. 윈도우에서 만든 내용을 붙여 넣으면 줄 끝에 \r 이
# 남는다. 그러면 비밀번호와 base64 **값 끝에 \r 이 붙어 서명이 조용히 깨진다.**
# 실제로 그렇게 실패했다 — dash 가 빈 줄의 \r 을 명령으로 읽어 터졌고, 값이
# 들어갔더라도 키스토어를 열지 못했을 것이다.
read_value() {
  tr -d '\r' < "$envfile" \
    | sed -n "s/^[[:space:]]*$1=//p" \
    | head -1 \
    | sed -e "s/^['\"]//" -e "s/['\"]\$//"
}

MUPID_API_ORIGIN=$(read_value MUPID_API_ORIGIN)
ANDROID_KEY_ALIAS=$(read_value ANDROID_KEY_ALIAS)
ANDROID_KEYSTORE_PASSWORD=$(read_value ANDROID_KEYSTORE_PASSWORD)
ANDROID_KEYSTORE_BASE64=$(read_value ANDROID_KEYSTORE_BASE64)
# 앱이 붙을 주소는 컴파일 시점에 박힌다(app/src/config.rs 의 option_env!).
[ -n "$MUPID_API_ORIGIN" ] && export MUPID_API_ORIGIN

# 예외 보고(Firebase Crashlytics, S15P21A204-383). 위와 같은 이유로 **컴파일 시점에**
# 박힌다(app/src/crash.rs 가 option_env! 로 읽는다).
#
# **export 를 빠뜨리면 조용히 꺼진 채로 구워진다.** crash.rs 는 키가 없으면 큐에
# 아무것도 쌓지 않고 정상 기동하도록 설계돼 있어(그게 의도다) 실패가 드러나지
# 않는다 — APK 를 풀어 문자열을 찾기 전까지는 알 수 없다.
#
# **둘 다 있어야 켜진다.** 하나만 있으면 crash.rs 가 꺼진 상태로 남는다. 그래서
# 한쪽만 온 경우를 여기서 경고한다 — 그 상태는 앱에서도 구분되지 않는다.
MUPID_CRASHLYTICS_ENDPOINT=$(read_value MUPID_CRASHLYTICS_ENDPOINT)
MUPID_CRASHLYTICS_API_KEY=$(read_value MUPID_CRASHLYTICS_API_KEY)
if [ -n "$MUPID_CRASHLYTICS_ENDPOINT" ] && [ -n "$MUPID_CRASHLYTICS_API_KEY" ]; then
  export MUPID_CRASHLYTICS_ENDPOINT MUPID_CRASHLYTICS_API_KEY
  printf '예외 보고를 켜고 굽는다 (MUPID_CRASHLYTICS_* 둘 다 있음)\n'
elif [ -n "$MUPID_CRASHLYTICS_ENDPOINT" ] || [ -n "$MUPID_CRASHLYTICS_API_KEY" ]; then
  printf '경고: MUPID_CRASHLYTICS_* 가 한쪽만 있다 — 예외 보고는 꺼진 채로 구워진다\n' >&2
  printf '      (둘 다 있어야 켜진다. app/src/crash.rs 참고)\n' >&2
else
  printf 'MUPID_CRASHLYTICS_* 가 없다 — 예외 보고 없이 굽는다(비활성이 기본이다)\n'
fi

# ── google-services.json 을 CI 에서 만든다 ────────────────────────────
#
# 왜 필요한가: `383` 2단계에서 Firebase Crashlytics SDK 를 붙였다
# (`app/gen/android/**` 의 google-services 플러그인, MR !200). 그 플러그인은
# **`app/gen/android/app/google-services.json` 을 요구하고, 없으면 기본 전략이
# ERROR 라 빌드를 실패시킨다.**
#
# 그런데 그 파일은 `app/.gitignore:29` 가 막는다 — 자격증명(프로젝트 번호·API 키·
# 앱 ID)이라 커밋할 수 없다. 그래서 **CI 체크아웃에는 존재하지 않는다.**
# 이 주입이 없으면 master 배포의 `app-build` job 이 그대로 죽는다.
#
# **키스토어와 완전히 같은 패턴을 쓴다**(아래 `ANDROID_KEYSTORE_BASE64` 참고).
# 값을 base64 로 `MUPID_APP_ENV` 에 넣으면 여기서 파일로 되돌린다.
#   만드는 법: `base64 -w0 app/gen/android/app/google-services.json`
#
# **없으면 조용히 넘어가지 않는다.** 넘어가면 google-services 플러그인이 ERROR 로
# 잡아 주긴 하지만, 그 오류는 "파일이 없다" 이지 "왜 없는지" 를 말해 주지 않는다.
# 여기서 먼저 짚어 준다.
GOOGLE_SERVICES_JSON_BASE64=$(read_value GOOGLE_SERVICES_JSON_BASE64)
if [ -n "$GOOGLE_SERVICES_JSON_BASE64" ]; then
  umask 077
  printf '%s' "$GOOGLE_SERVICES_JSON_BASE64" | base64 -d > app/gen/android/app/google-services.json
  printf 'google-services.json 을 MUPID_APP_ENV 에서 복원했다 (Crashlytics SDK 활성)\n'
else
  printf '경고: MUPID_APP_ENV 에 GOOGLE_SERVICES_JSON_BASE64 가 없다\n' >&2
  printf '      google-services 플러그인이 파일을 못 찾아 빌드를 실패시킨다(기본 전략 ERROR).\n' >&2
  printf '      base64 -w0 app/gen/android/app/google-services.json 의 값을 넣어라.\n' >&2
fi

# ── 빌드 타임스탬프가 곧 버전이다 ──────────────────────────────────
# semver 를 쓰지 않는다(`tauri.conf.json` 의 version 은 0.1.0 그대로 둔다).
# 두 소비자가 **같은 값**을 봐야 한다.
#
#   gradle  `build.gradle.kts` 가 `versionCode` 를 이 값에서 유도한다(/1000).
#   Rust    `option_env!("MUPID_NATIVE_BUILD")` 로 읽어
#           `local_version_code()` 와 `local_web_build()` 를 만든다.
#
# 한쪽만 새 값이면 "gradle 이 아는 버전" 과 "앱이 아는 버전" 이 어긋나고,
# 증상은 "업데이트 안내가 안 뜬다" 뿐이다. 한 번 정해 양쪽에 넘긴다.
#
# 값을 만드는 규칙은 `build-stamp.sh` 한 곳에 있다 — 웹 번들 job 이 **같은
# 스크립트**를 부른다. 두 곳에 복사해 두면 언젠가 어긋난다(설계 12장 10번).
#
# 두 변수를 **둘 다** export 한다. 웹 쪽(`web-archive.mjs` · `update.rs` 의
# `embedded_web_build`)이 보는 이름이 `MUPID_WEB_BUILD` 이고, 값의 출처는
# 하나여야 한다(설계 6.2).
MUPID_NATIVE_BUILD=${MUPID_NATIVE_BUILD:-$(sh infra/ci/build-stamp.sh)}
case "$MUPID_NATIVE_BUILD" in
  ''|*[!0-9]*) echo "MUPID_NATIVE_BUILD 가 숫자가 아니다: $MUPID_NATIVE_BUILD" >&2; exit 64 ;;
esac
export MUPID_NATIVE_BUILD
# 웹 갈래가 보는 이름. **같은 값**이다 — APK 에 임베드되는 번들의 나이가 곧
# 이 APK 의 나이다. 다르면 새 APK 를 설치한 직후 그 앱이 자기 번들을 다시 받는다.
MUPID_WEB_BUILD=$MUPID_NATIVE_BUILD
export MUPID_WEB_BUILD

# ── 값의 출처를 **파일로도** 남긴다 ────────────────────────────────
# `export` 는 **이 스크립트의 자식**에게만 간다. 부모 셸(CI)에는 오지 않으므로,
# 다음 줄에서 도는 `deploy-apk.sh` 는 이 값을 볼 수 없다.
#
# 이걸 두 번 밟았다.
#   1) CI 가 두 줄로 나눠 돌려서 (5ee8c4 로 `&&` 연결)
#   2) 그래도 안 됐다 — `sh build-apk.sh` 자체가 **자식 프로세스**라
#      그 안의 export 가 부모로 올라오지 않는다. `&&` 는 두 번째 문제를 못 고쳤다.
#
# 그래서 CI 가 읽을 수 있는 자리에 파일로 남긴다. CI 줄은
#   `MUPID_NATIVE_BUILD=$(cat .release/app/build-stamp)` 로 값을 얻는다.
# 파일 이름을 바꾸면 `infra_k8s/tests` 의 `BuildStampHandoffTest` 가 깨진다.
mkdir -p .release/app
printf '%s\n' "$MUPID_NATIVE_BUILD" > .release/app/build-stamp
printf '빌드 스탬프 %s (versionCode %s)\n' \
  "$MUPID_NATIVE_BUILD" "$((MUPID_NATIVE_BUILD / 1000))"

: "${ANDROID_KEYSTORE_BASE64:?MUPID_APP_ENV 에 ANDROID_KEYSTORE_BASE64 가 없다}"
: "${ANDROID_KEYSTORE_PASSWORD:?MUPID_APP_ENV 에 ANDROID_KEYSTORE_PASSWORD 가 없다}"
: "${ANDROID_KEY_ALIAS:?MUPID_APP_ENV 에 ANDROID_KEY_ALIAS 가 없다}"

# 서명 재료를 gradle 이 보는 자리에 놓는다. app/gen/android/.gitignore 에
# keystore.properties 가 들어 있어 커밋되지 않는다. build.gradle.kts 는 이
# 파일이 있을 때만 signingConfig 를 건다 — 없으면 로컬 빌드가 그대로 돈다.
umask 077
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 -d > app/gen/android/mupid-release.jks
cat > app/gen/android/keystore.properties <<PROPS
storeFile=mupid-release.jks
storePassword=$ANDROID_KEYSTORE_PASSWORD
keyAlias=$ANDROID_KEY_ALIAS
keyPassword=$ANDROID_KEYSTORE_PASSWORD
PROPS
trap 'rm -f app/gen/android/mupid-release.jks app/gen/android/keystore.properties' EXIT

# 화면. beforeBuildCommand 가 이것을 돌리지만, npm ci 를 먼저 해 두지 않으면
# 거기서 실패한다.
npm --prefix frontend ci --prefer-offline --no-audit --no-fund

# aarch64 하나만 굽는다. 요즘 안드로이드 기기는 대부분 arm64 이고, 4개 ABI 를
# 다 담으면 APK 가 서너 배가 된다. 32비트 기기까지 받아야 하면 --target 을
# 늘린다 (armv7 · i686 · x86_64).
cd app
cargo tauri android build --apk --target aarch64
cd ..

# tauri 가 어디에 떨어뜨렸는지는 버전마다 달라서 찾는다. 서명되지 않은 것을
# 집어 가면 기기가 설치를 거부하므로 -unsigned 는 제외한다.
found=$(find app/gen/android/app/build/outputs/apk -name '*.apk' \
          ! -name '*unsigned*' -print | sort | tail -1)
[ -n "$found" ] || {
  echo 'APK 를 찾지 못했다. 아래에 무엇이 있는지 본다:' >&2
  find app/gen/android/app/build/outputs -name '*.apk' >&2 || true
  exit 65
}

mkdir -p .release/app
cp "$found" .release/app/mupid.apk
printf '구운 APK: %s -> .release/app/mupid.apk (%s 바이트)\n' \
  "$found" "$(wc -c < .release/app/mupid.apk)"

# 서명이 실제로 붙었는지 확인한다. 서명 없는 APK 는 안드로이드가 설치를
# 거부하는데, 그것은 받는 사람이 겪는다 — 여기서 걸러야 한다.
apksigner=$(find "$ANDROID_HOME/build-tools" -name apksigner | sort | tail -1)
if [ -n "$apksigner" ]; then
  "$apksigner" verify --print-certs .release/app/mupid.apk | grep -E 'Signer #1 certificate SHA-256'
else
  echo '경고: apksigner 를 찾지 못해 서명을 확인하지 못했다' >&2
fi
