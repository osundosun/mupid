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

# 시스템 바 인셋을 건네는 통로.
#
# `SafeArea` 는 `addJavascriptInterface` 로 웹뷰에 심긴다. 자바스크립트가
# 이름으로 부르므로 R8 이 보기에는 아무도 안 부르는 메서드다. 이름이 바뀌면
# 릴리스 빌드에서만 인셋이 0 으로 떨어져 상단바가 상태바 밑으로 들어간다.
-keep class com.osundosun.mupid.SafeArea { *; }
-keepclassmembers class com.osundosun.mupid.SafeArea {
  @android.webkit.JavascriptInterface <methods>;
}
