package com.osundosun.mupid

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.Plugin
import java.io.File

/** Rust 가 넘기는 인자. 짝은 `app/src/update_native.rs` 의 `InstallRequest`. */
@InvokeArg
class InstallArgs {
  var path: String = ""
}

/**
 * APK 설치 유도 — 안드로이드 쪽.
 *
 * Rust(`app/src/update_native.rs`)가 검증을 마친 APK 의 **앱 내부 경로**를
 * 넘기면, 여기서 시스템 설치기에 넘긴다.
 *
 * ## 왜 FileProvider 를 거치나
 *
 * 안드로이드 7(API 24)부터 앱 사이에 `file://` 경로를 그대로 넘기면
 * `FileUriExposedException` 으로 죽는다. 다른 앱(설치기)은 우리 앱 내부
 * 저장소를 읽을 권한이 없기 때문이고, `content://` URI 로 감싸 읽기 권한을
 * **그 인텐트에 한해** 넘겨야 한다.
 *
 * `cache-path` 가 `res/xml/file_paths.xml` 에 이미 있어 설정을 새로 만들지
 * 않았다 — APK 는 `app_cache_dir()/update/` 에 놓인다(`commands.rs`).
 *
 * ## 권한
 *
 * - `REQUEST_INSTALL_PACKAGES` (매니페스트) — 앱이 APK 를 설치하려면 필요하다.
 * - "이 출처의 앱 설치" (사용자 설정) — 사용자가 직접 허용해야 한다. 허용되지
 *   않았으면 **설정 화면으로 보낸다.** 여기서 할 수 있는 일이 그것뿐이고,
 *   그냥 실패시키면 사용자는 왜 안 되는지 알 수 없다.
 *
 * 앱이 조용히 제 패키지를 갈아 끼울 수는 없다 — 그건 안드로이드가 막는 일이고,
 * 그래서 이 흐름은 "설치를 유도" 이지 "설치" 가 아니다.
 */
@TauriPlugin
class AppUpdatePlugin(private val activity: Activity) : Plugin(activity) {

  /**
   * 검증된 APK 를 시스템 설치기로 넘긴다.
   *
   * **결과를 기다리지 않는다.** 설치기는 별도 화면이고, 사용자가 거기서
   * 무엇을 하든(설치·취소·뒤로) 우리 앱은 이미 백그라운드다. `invoke.resolve`
   * 는 인텐트를 띄운 직후 부른다 — 설치가 끝날 때까지 매달리면 안 된다.
   */
  @Command
  fun install(invoke: Invoke) {
    val args = invoke.parseArgs(InstallArgs::class.java)

    // Rust 가 이미 `.apk` 확장자와 크기·체크섬을 확인했다. 여기서는 경로가
    // 우리 캐시 안인지만 한 번 더 본다 — 넘어온 경로를 그대로 믿고
    // FileProvider 에 주면, 그 경로가 무엇이든 다른 앱에 열어 주게 된다.
    val file = File(args.path)
    val cacheRoot = activity.cacheDir.canonicalFile
    val canonical = try {
      file.canonicalFile
    } catch (error: Exception) {
      invoke.reject("APK 경로를 읽지 못했습니다: ${error.message}")
      return
    }
    if (!canonical.path.startsWith(cacheRoot.path + File.separator)) {
      invoke.reject("캐시 밖의 파일은 설치할 수 없습니다")
      return
    }
    if (!canonical.isFile) {
      invoke.reject("APK 파일이 없습니다: ${canonical.path}")
      return
    }

    // Android 8+ 는 이 권한이 있어야 APK 를 설치할 수 있다.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      if (!activity.packageManager.canRequestPackageInstalls()) {
        // 아직 허용되지 않았다. **설정 화면으로 보낸다** — 그냥 실패시키면
        // 사용자는 왜 안 되는지 알 방법이 없다.
        openInstallPermissionSettings()
        invoke.reject("이 출처의 앱 설치를 허용해 주세요")
        return
      }
    }

    val uri: Uri = FileProvider.getUriForFile(
      activity,
      "${activity.packageName}.fileprovider",
      canonical,
    )

    val intent = Intent(Intent.ACTION_VIEW).apply {
      setDataAndType(uri, "application/vnd.android.package-archive")
      // 읽기 권한을 **이 인텐트에 한해** 넘긴다. 플래그가 없으면 설치기가
      // content URI 를 열지 못해 조용히 아무 일도 일어나지 않는다.
      addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    try {
      activity.startActivity(intent)
      invoke.resolve()
    } catch (error: Exception) {
      // 설치기가 없는 기기(드묾)나 인텐트를 못 여는 경우. 이건 사용자가
      // 알아야 하는 실패다 — 조용히 넘기면 "업데이트가 안 된다" 만 남는다.
      invoke.reject("설치 화면을 열지 못했습니다: ${error.message}")
    }
  }

  /**
   * "이 출처의 앱 설치" 설정 화면을 연다.
   *
   * 되돌아오면 사용자가 설치를 다시 시도할 수 있다. 이 화면을 여는 것 말고는
   * 앱이 할 수 있는 일이 없다 — 권한은 사용자만 줄 수 있다.
   */
  private fun openInstallPermissionSettings() {
    try {
      val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
        data = Uri.parse("package:${activity.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      activity.startActivity(intent)
    } catch (error: Exception) {
      // 설정 화면이 없는 기기도 있다. 그 경우는 위의 reject 만 남는다.
      // `android.util.Log` 를 직접 쓴다 — Tauri 의 `Plugin` 기반 클래스에는
      // `log()` 가 없다 (MediaSessionPlugin 도 로그를 쓰지 않는다).
      Log.w(TAG, "설치 권한 설정 화면을 열지 못했습니다: ${error.message}")
    }
  }

  /** 이 출처의 앱 설치가 허용돼 있는가. 화면이 미리 물어볼 때 쓸 수 있다. */
  private fun canInstallPackages(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
      activity.packageManager.canRequestPackageInstalls()

  private companion object {
    /** logcat 에서 이 플러그인의 줄만 고르는 태그. */
    const val TAG = "MupidAppUpdate"
  }
}
