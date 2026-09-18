import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("rust")
}

val tauriProperties = Properties().apply {
    val propFile = file("tauri.properties")
    if (propFile.exists()) {
        propFile.inputStream().use { load(it) }
    }
}

// ─────────────────────────────────────────────────────────────
// versionCode 를 **단조 증가**시킨다.
//
// 왜 필요한가: `tauri.properties` 는 빌드마다 `tauri.conf.json` 의
// `version: "0.1.0"` 에서 다시 만들어진다. 그래서 지금까지 배포된 APK 는
// 전부 versionCode=1000 이었다. 프론트만 바뀌어도 APK 가 재배포되는데
// (`.app-changes` 에 `frontend/**` 가 들어 있다), 앱 입장에서는 "새 APK 가
// 있는데 그게 내 것보다 새것인지 모르는" 상태가 된다 — 안드로이드는
// versionCode 로 업그레이드를 판정하므로 재설치 유도 자체가 성립하지 않는다.
//
// 그래서 빌드 타임스탬프를 버전으로 쓴다 (설계 6장). 웹 갈래와 네이티브
// 갈래가 **같은 축**을 쓰게 되어 "어느 쪽이 더 새로운가" 를 한 값으로 비교할
// 수 있다.
//
// 값의 출처는 `MUPID_NATIVE_BUILD` 환경변수다. CI(`infra/ci/build-apk.sh`)가
// `date +%s%3N`(밀리초)로 넣는다. **같은 변수를 Rust 가 컴파일 시점에
// `option_env!("MUPID_NATIVE_BUILD")` 로 읽어 1000 으로 나눈다**
// (`update.rs` 의 `local_version_code()`) — 한 값이 gradle 과 앱 안에서
// 어긋나지 않는다.
//
// 밀리초를 1000 으로 나누는 이유: 안드로이드 `versionCode` 상한이
// 2,100,000,000 이다. 밀리초를 그대로 쓰면 즉시 넘어 빌드가 거부되고,
// 초로 내리면 2036년에 상한에 닿는다.
//
// 웹 번들도 같은 변수 계열(`MUPID_WEB_BUILD`)을 같은 단위(밀리초)로 쓴다.
// 단위가 섞이면 "어느 쪽이 더 새로운가" 를 비교하는 자리에서 조용히 어긋난다.
//
// 없으면 `tauri.properties` 값으로 내려앉는다. 로컬 빌드가 죽지 않고,
// 개발자가 매번 변수를 넣지 않아도 된다. 다만 이때는 1000 이라 배포하면 안
// 된다 — CI 는 이 변수를 반드시 넣는다.
// ─────────────────────────────────────────────────────────────
val nativeBuildMs = System.getenv("MUPID_NATIVE_BUILD")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.toLongOrNull()
    ?.takeIf { it > 0 }
val versionCodeValue = (nativeBuildMs?.div(1000)
    ?: tauriProperties.getProperty("tauri.android.versionCode", "1").toLong()).toInt()

// 값이 뒤로 가면 안드로이드가 업그레이드로 보지 않는다. 조용히 지나가면
// "업데이트가 안 된다" 로만 보이므로 빌드 자체를 실패시킨다.
require(nativeBuildMs != null || providers.environmentVariable("CI").orNull == null) {
    "CI 빌드인데 MUPID_NATIVE_BUILD 가 없습니다 — versionCode 가 1000 으로 굳어 업데이트가 성립하지 않습니다."
}
// 안드로이드가 거부하는 값이면 여기서 멈춘다. 이 검사가 없으면 밀리초를
// 그대로 넣었을 때 빌드는 통과하고 설치는 실패한다.
require(versionCodeValue in 1..2_100_000_000) {
    "versionCode 가 안드로이드 상한을 벗어납니다: $versionCodeValue (MUPID_NATIVE_BUILD 단위를 확인하세요 — 밀리초여야 합니다)"
}

// 릴리스 서명. keystore.properties 는 커밋하지 않는다(.gitignore 에 있다).
// CI 가 MUPID_APP_ENV 에서 꺼내 만들고, 로컬에는 보통 없다.
//
// **없으면 서명 없이 간다.** 로컬에서 `cargo tauri android build` 를 돌리는
// 사람이 키스토어 때문에 막히면 안 되기 때문이다. 대신 서명 없는 APK 는
// 안드로이드가 설치를 거부하므로, 배포용은 CI 가 굽는다.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    compileSdk = 36
    namespace = "com.osundosun.mupid"
    defaultConfig {
        manifestPlaceholders["usesCleartextTraffic"] = "false"
        applicationId = "com.osundosun.mupid"
        minSdk = 24
        targetSdk = 36
        versionCode = versionCodeValue
        versionName = tauriProperties.getProperty("tauri.android.versionName", "1.0")
        // Kotlin·Java 쪽에서 읽을 수 있게 노출한다 (진단·표시용).
        buildConfigField("long", "NATIVE_BUILD", "${versionCodeValue}L")
    }
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        getByName("debug") {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            isDebuggable = true
            isJniDebuggable = true
            isMinifyEnabled = false
            packaging {                jniLibs.keepDebugSymbols.add("*/arm64-v8a/*.so")
                jniLibs.keepDebugSymbols.add("*/armeabi-v7a/*.so")
                jniLibs.keepDebugSymbols.add("*/x86/*.so")
                jniLibs.keepDebugSymbols.add("*/x86_64/*.so")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            proguardFiles(
                *fileTree(".") { include("**/*.pro") }
                    .plus(getDefaultProguardFile("proguard-android-optimize.txt"))
                    .toList().toTypedArray()
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        buildConfig = true
    }
}

rust {
    rootDirRel = "../../../"
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    // 상단 알림·잠금화면 플레이어(`MediaSessionCompat`, `MediaStyle`).
    implementation("androidx.media:media:1.7.0")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.0")
}

apply(from = "tauri.build.gradle.kts")