# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# 상단 알림·잠금화면 플레이어.
#
# `MediaSessionPlugin` 은 Rust 가 이름으로 찾아 세운다(`app/src/media.rs` 의
# `register_android_plugin`). 코드 어디에서도 부르지 않으므로 R8 이 보기에는
# 죽은 코드다. tauri-android 의 규칙이 `@TauriPlugin` 을 이미 남기지만,
# 이름으로 찾는다는 사실을 여기에도 적어 둔다 — 규칙이 사라지면 릴리스
# 빌드에서만 알림이 뜨지 않고, 그 원인을 찾기까지 오래 걸린다.
-keep class com.osundosun.mupid.MediaSessionPlugin { *; }
-keep class com.osundosun.mupid.NowPlayingArgs { *; }

# 시스템 바 인셋을 건네는 통로.
#
# `SafeArea` 는 `addJavascriptInterface` 로 웹뷰에 심긴다. 자바스크립트가
# 이름으로 부르므로 R8 이 보기에는 아무도 안 부르는 메서드다. 이름이 바뀌면
# 릴리스 빌드에서만 인셋이 0 으로 떨어져 상단바가 상태바 밑으로 들어간다.
-keep class com.osundosun.mupid.SafeArea { *; }
-keepclassmembers class com.osundosun.mupid.SafeArea {
  @android.webkit.JavascriptInterface <methods>;
}
