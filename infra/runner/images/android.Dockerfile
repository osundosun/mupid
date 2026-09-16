# app-build 의 job 이미지. Tauri 안드로이드 APK 를 굽는 도구 사슬이다.
#
# **linux/amd64 로 만든다.** 안드로이드 NDK 는 리눅스용이 x86_64 바이너리만
# 나온다 — aarch64 리눅스 NDK 가 없다. 러너는 Apple Silicon 이지만 Docker
# Desktop 의 Rosetta 2 가 amd64 를 거의 네이티브 속도로 돌린다(gzip 실측
# arm64 0.86s / amd64 0.84s). 그래서 에뮬레이션 손해 없이 NDK 를 쓸 수 있다.
#
# 굽는 법 (러너 호스트에서):
#   sh infra/runner/build-images.sh
#
# 크다(수 GB). 레지스트리에 올리지 않고 러너의 도커 데몬에만 둔다 —
# job 이 pull_policy: if-not-present 로 집어 간다.
FROM --platform=linux/amd64 debian:bookworm-slim

# 버전은 app/gen/android 가 요구하는 것에 맞춘다.
#   AGP 8.11.0 · Kotlin 1.9.25 · Gradle 8.14.3 (wrapper 가 직접 받는다)
#   compileSdk 36 · targetSdk 36 · minSdk 24
# AGP 8.11 은 JDK 17 이상을 요구한다.
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    NDK_VERSION=27.2.12479018 \
    DEBIAN_FRONTEND=noninteractive \
    PATH=/usr/local/cargo/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:$PATH
ENV NDK_HOME=/opt/android-sdk/ndk/${NDK_VERSION} \
    ANDROID_NDK_ROOT=/opt/android-sdk/ndk/${NDK_VERSION}

RUN apt-get update && apt-get install -y --no-install-recommends \
      openjdk-17-jdk-headless curl unzip git ca-certificates \
      build-essential pkg-config libssl-dev file \
 && rm -rf /var/lib/apt/lists/*

# Node. beforeBuildCommand 가 `npm --prefix frontend run build:static` 이라
# APK 안에 들어갈 화면을 여기서 굽는다.
RUN curl -fsSL https://deb.nodesource.com/setup_22.x | bash - \
 && apt-get install -y --no-install-recommends nodejs \
 && rm -rf /var/lib/apt/lists/*

# Android SDK. cmdline-tools 는 latest 경로에 있어야 sdkmanager 가 자기 위치를
# 알아본다.
RUN mkdir -p /opt/android-sdk/cmdline-tools \
 && curl -fsSL -o /tmp/tools.zip \
      https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
 && unzip -q /tmp/tools.zip -d /opt/android-sdk/cmdline-tools \
 && mv /opt/android-sdk/cmdline-tools/cmdline-tools /opt/android-sdk/cmdline-tools/latest \
 && rm /tmp/tools.zip \
 && yes | sdkmanager --licenses > /dev/null \
 && sdkmanager --install \
      "platform-tools" \
      "platforms;android-36" "build-tools;36.0.0" \
      "platforms;android-35" "build-tools;35.0.0" \
      "ndk;${NDK_VERSION}" > /dev/null \
 && rm -rf /root/.android

# Rust. CARGO_HOME 을 /usr/local/cargo 로 두되 tauri-cli 는 --root /usr/local 로
# 깔아 /usr/local/bin 에 둔다. 그래야 job 이 CARGO_HOME 을 /cache 로 돌려
# 레지스트리를 캐시해도 cargo-tauri 가 사라지지 않는다.
ENV CARGO_HOME=/usr/local/cargo RUSTUP_HOME=/usr/local/rustup
RUN curl -fsSL https://sh.rustup.rs | sh -s -- -y --no-modify-path --profile minimal \
 && rustup target add \
      aarch64-linux-android armv7-linux-androideabi \
      i686-linux-android x86_64-linux-android \
 && cargo install tauri-cli --version "^2" --locked --root /usr/local \
 && rm -rf /usr/local/cargo/registry /usr/local/cargo/git \
 && chmod -R a+rX /usr/local/cargo /usr/local/rustup

# job 이 무엇을 들고 있는지 로그 첫 줄에서 보이게 한다.
RUN printf '%s\n' \
      "jdk=$(java -version 2>&1 | head -1)" \
      "node=$(node --version)" \
      "rustc=$(rustc --version)" \
      "tauri=$(cargo-tauri --version 2>/dev/null || echo '?')" \
      "ndk=${NDK_VERSION}" > /opt/toolchain.txt \
 && cat /opt/toolchain.txt
