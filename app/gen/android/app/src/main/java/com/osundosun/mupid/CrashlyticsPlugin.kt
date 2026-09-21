package com.osundosun.mupid

import android.util.Log
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.Plugin
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Rust 가 넘기는 인자. 짝은 `app/src/crash.rs` 의 `CrashArgs`.
 *
 * `message` 는 필수고 나머지는 없을 수 있다. `Invoke.parseArgs` 가
 * `@InvokeArg` 클래스의 기본값으로 채우므로 null 이면 기본값이 온다.
 */
@InvokeArg
class CrashArgs {
  var message: String = ""
  var stack: String? = null
  var context: String? = null
}

/**
 * 예외 보고 — 안드로이드 쪽 (S15P21A204-383 2단계, B안: Firebase Crashlytics SDK).
 *
 * ## 왜 Kotlin 을 거치나
 *
 * Rust 에서 Crashlytics 를 직접 부를 수 없다. Firebase 의 공식 지원은
 * Android·iOS·Flutter·Unity 뿐이고(공식 Rust SDK 가 없다), Java 클래스를
 * Rust 에서 부르려면 JNI 를 손으로 짜야 한다. Tauri 의 안드로이드 플러그인
 * 통로(`PluginHandle.run_mobile_plugin`)를 쓰면 그 JNI 를 Tauri 가 대신
 * 해 준다 — `AppUpdatePlugin`·`MediaSessionPlugin` 이 이미 그렇게 한다.
 *
 * ## 두 커맨드로 나뉘어 있는 이유 (로그와 예외를 섞지 않는다)
 *
 * Crashlytics SDK 에는 통로가 둘 있다.
 *
 *  - [`recordException`][recordException] — **비정상 종료 이벤트**를 하나 만든다.
 *    대시보드의 비정상 종료율에 잡힌다.
 *  - [`log`][log] — 빵부스러기 한 줄. 그 자체로는 이벤트가 아니고 **다음
 *    예외에 붙어** 맥락이 된다.
 *
 * Rust 의 로그 브리지는 `log::warn!` 이상을 흘려보낸다. 그것을 전부
 * `recordException` 으로 올리면 **경고 한 줄마다 이벤트가 하나씩 생겨**
 * 비정상 종료율이 부풀고, 정작 봐야 할 예외가 그 속에 묻힌다.
 *
 * **어느 커맨드로 올지는 Kotlin 이 정하지 않는다.** Rust(`crash.rs` 의
 * `Kind::native_command`)가 정해서 커맨드 이름으로 넘긴다 — 두 곳에서
 * 판단하면 갈라지고, 갈라진 쪽이 대시보드를 오염시킨다. 여기서는 시킨 대로
 * 실행만 한다.
 *
 * ## 실패해도 앱을 깨뜨리지 않는다
 *
 * 이 앱은 오프라인에서도 재생돼야 한다. `FirebaseCrashlytics.getInstance()` 는
 * `google-services.json` 이 없거나 초기화가 실패하면 던질 수 있다 — 그때
 * `invoke.reject` 로 돌려보내고 끝낸다(Rust 가 그 실패를 삼킨다).
 * **여기서 예외를 밖으로 던지면 앱이 죽는다.**
 */
@TauriPlugin
class CrashlyticsPlugin(private val activity: android.app.Activity) : Plugin(activity) {

  /**
   * **예외**를 올린다. 패닉·JS `window.onerror`·`unhandledrejection` 이 온다.
   *
   * `stack` 이 있으면 그대로 넘긴다 — 심볼화된 스택트레이스가 이 경로의
   * 요점이다(1단계의 비공개 수집 통로와 달리 정공법이다).
   */
  @Command
  fun recordException(invoke: Invoke) {
    val args = invoke.parseArgs(CrashArgs::class.java)
    try {
      val crashlytics = FirebaseCrashlytics.getInstance()
      // 스택트레이스를 잃지 않는다. Crashlytics 는 `Throwable` 을 받으므로
      // 넘어온 스택텍스트를 메시지에 실어 **읽을 수 있게** 남긴다.
      crashlytics.recordException(
        ReportedException(compose(args))
      )
      invoke.resolve()
    } catch (error: Throwable) {
      // SDK 가 없거나 초기화되지 않은 빌드(예: `google-services.json` 없이
      // 구운 APK). **앱을 죽이지 않는다** — Rust 가 이 실패를 삼킨다.
      Log.w(TAG, "예외를 Crashlytics 로 올리지 못했습니다: ${error.message}")
      invoke.reject("Crashlytics 를 쓸 수 없습니다: ${error.message}")
    }
  }

  /**
   * **빵부스러기**를 남긴다. 로그 브리지가 흘려보낸 경고·오류가 온다.
   *
   * 이벤트를 만들지 않는다 — 다음 예외에 붙어 맥락이 되는 것이 목적이다.
   */
  @Command
  fun log(invoke: Invoke) {
    val args = invoke.parseArgs(CrashArgs::class.java)
    try {
      FirebaseCrashlytics.getInstance().log(compose(args))
      invoke.resolve()
    } catch (error: Throwable) {
      Log.w(TAG, "로그를 Crashlytics 에 남기지 못했습니다: ${error.message}")
      invoke.reject("Crashlytics 를 쓸 수 없습니다: ${error.message}")
    }
  }

  /**
   * 지금 이 기기에서 Crashlytics 를 쓸 수 있는가.
   *
   * `crash_report_status` 가 읽는 값의 **안드로이드 쪽 절반**이다. Rust 는
   * 이 커맨드가 등록됐는지만 알지 "SDK 가 실제로 초기화됐는지" 는 모른다 —
   * 그래서 화면·진단에서 확실히 확인하려면 이 커맨드를 부르면 된다.
   *
   * `isCrashlyticsCollectionEnabled` 는 **수집 켜짐 여부**지 초기화 성공
   * 여부가 아니다(둘은 다르다). 그래서 `getInstance()` 가 던지지 않는지를
   * 함께 본다 — 던지면 초기화가 안 된 것이다.
   */
  @Command
  fun status(invoke: Invoke) {
    try {
      val crashlytics = FirebaseCrashlytics.getInstance()
      val result = app.tauri.plugin.JSObject()
      result.put("available", true)
      result.put("collectionEnabled", crashlytics.isCrashlyticsCollectionEnabled)
      invoke.resolve(result)
    } catch (error: Throwable) {
      val result = app.tauri.plugin.JSObject()
      result.put("available", false)
      result.put("reason", error.message ?: "알 수 없는 이유")
      invoke.resolve(result)
    }
  }

  /**
   * 두 커맨드가 같은 본문을 만든다.
   *
   * 메시지·스택·자리를 한 덩어리로 붙인다 — Crashlytics 의 `log()` 는 한 줄을
   * 받고 `recordException` 은 `Throwable` 을 받으므로, 어느 쪽이든 **잃지 않고**
   * 실어 보내려면 문자열 하나로 합치는 편이 안전하다.
   */
  private fun compose(args: CrashArgs): String {
    val parts = mutableListOf(args.message)
    args.context?.takeIf { it.isNotBlank() }?.let { parts.add("context=$it") }
    args.stack?.takeIf { it.isNotBlank() }?.let { parts.add("stack=$it") }
    return parts.joinToString("\n")
  }

  /**
   * 서버가 준 스택텍스트를 `Throwable` 로 감싼다.
   *
   * `recordException` 이 `Throwable` 을 요구하기 때문이다. 스택을 **덮어쓰지
   * 않고** 메시지에 실어 보내는 이유는, 여기서 만든 스택은 JNI 경계라
   * 의미가 없고 진짜 단서는 넘어온 텍스트뿐이기 때문이다.
   */
  private class ReportedException(message: String) : Exception(message)

  private companion object {
    /** logcat 에서 이 플러그인의 줄만 고르는 태그. */
    const val TAG = "MupidCrashlytics"
  }
}
