buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.11.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.25")
        // ── Firebase Crashlytics (S15P21A204-383 2단계, B안: SDK 정공법) ──────
        //
        // 여기에 두는 이유: 이 저장소는 `settings.gradle`(Groovy) 에
        // `pluginManagement { repositories { ... } }` 가 없다. 그래서
        // `app/build.gradle.kts` 의 `plugins { id("com.google.firebase.crashlytics") }`
        // 는 플러그인 마커를 저장소에서 찾을 수 없다 — **buildscript
        // 클래스패스에 올려 두면 버전 없이 id 로 쓸 수 있다**
        // (지금 `com.android.application`·`kotlin.android` 가 그렇게 동작한다).
        // Groovy DSL(`settings.gradle`) 을 Kotlin DSL 로 바꾸지 않고도 되는 길이다.
        //
        // 버전을 **고정**한다. `+`·`latest.release` 를 쓰면 어제 되던 빌드가
        // 오늘 깨지고, 그 원인이 이 파일에 안 남는다. 근거는 각 줄에 적는다.
        //
        //   google-services 4.5.0
        //     Crashlytics Gradle 플러그인 3.x 가 **4.4.1 이상**을 요구한다
        //     (플러그인 jar 안의 문자열로 실측:
        //      "The Crashlytics Gradle plugin 3 requires Google-Services 4.4.1
        //       and above"). 4.5.0 은 그 계열의 최신 안정판이다.
        //     이 플러그인은 `google-services.json` 을 읽어 리소스를 만든다.
        //
        //   firebase-crashlytics-gradle 3.0.7
        //     **3.0.8 이 아니라 3.0.7 을 쓴다.** 근거가 있다 —
        //     이 Gradle 은 8.14.3 이고 그 배포본에 들어 있는 Kotlin 은
        //     2.0.21 이다(`gradle-8.14.3/lib/kotlin-compiler-embeddable-2.0.21.jar`).
        //     플러그인 class 의 `kotlin.Metadata` 를 javap 로 실측하면
        //       3.0.7 → mv=[2,0,0], kotlin-stdlib 2.0.21
        //       3.0.8 → mv=[2,1,0], kotlin-stdlib 2.1.21
        //     다. 3.0.8 은 Gradle 이 들고 있는 2.0.21 컴파일러가 읽을 수 없는
        //     메타데이터라 빌드 스크립트 자체가 "compiled by a newer Kotlin" 으로
        //     죽는다. 3.0.7 은 정확히 맞는다.
        //     플러그인 3.x 의 요구(플러그인 jar 안 문자열로 실측): Gradle 8.0 이상,
        //     `com.android.application` 8.1 이상 → 이 저장소는 8.14.3 / 8.11.0 이라
        //     둘 다 만족한다.
        classpath("com.google.gms:google-services:4.5.0")
        classpath("com.google.firebase:firebase-crashlytics-gradle:3.0.7")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

tasks.register("clean").configure {
    delete("build")
}

