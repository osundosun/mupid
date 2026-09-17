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
