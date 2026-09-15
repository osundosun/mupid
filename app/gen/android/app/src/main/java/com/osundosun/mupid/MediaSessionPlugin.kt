package com.osundosun.mupid

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin

@InvokeArg
class NowPlayingArgs {
  var title: String = ""
  var artist: String = ""
  var album: String = ""
  var durationMs: Long = 0
  var positionMs: Long = 0
  var playing: Boolean = false
  var artworkPath: String? = null
  var canPrevious: Boolean = false
  var canNext: Boolean = false
}

/**
 * 상단 알림·잠금화면 플레이어의 안드로이드 쪽.
 *
 * Rust(`app/src/media.rs`)가 곡이 바뀌거나 재생 상태가 바뀔 때 `setState` 를
 * 부르고, 버튼을 누르면 `control` 이벤트로 되돌려 받는다. 커맨드는 Tauri 가
 * 메인 스레드에서 부르므로 `MediaSession` 을 여기서 바로 만져도 된다.
 */
@TauriPlugin
class MediaSessionPlugin(private val activity: Activity) : Plugin(activity) {
  /** 알림 권한을 한 번만 묻는다. 거절해도 재생은 그대로 이어진다. */
  private var askedForNotifications = false

  @Command
  fun setState(invoke: Invoke) {
    val args = invoke.parseArgs(NowPlayingArgs::class.java)
    NowPlayingSession.onControl = { action, positionMs -> send(action, positionMs) }
    requestNotificationPermission()
    NowPlayingSession.update(
      activity,
      NowPlaying(
        title = args.title,
        artist = args.artist,
        album = args.album,
        durationMs = args.durationMs,
        positionMs = args.positionMs,
        playing = args.playing,
        artworkPath = args.artworkPath,
        canPrevious = args.canPrevious,
        canNext = args.canNext,
      ),
    )
    invoke.resolve()
  }

  @Command
  fun clear(invoke: Invoke) {
    NowPlayingSession.clear(activity)
    invoke.resolve()
  }

  override fun onDestroy(activity: AppCompatActivity) {
    NowPlayingSession.onControl = null
    NowPlayingSession.release()
  }

  private fun send(action: String, positionMs: Long) {
    trigger("control", JSObject().put("action", action).put("positionMs", positionMs))
  }

  /**
   * 안드로이드 13 부터는 허락을 받아야 알림이 보인다.
   *
   * 앱이 뜨자마자 묻지 않고 **처음 재생할 때** 묻는다 — 그때가 알림이 왜
   * 필요한지 가장 분명한 순간이다. 답은 기다리지 않는다. 허락하면 다음 상태
   * 갱신이나 `onResume` 에서 알림이 뜬다.
   */
  private fun requestNotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    if (askedForNotifications) return
    val granted = ContextCompat.checkSelfPermission(
      activity,
      Manifest.permission.POST_NOTIFICATIONS,
    ) == PackageManager.PERMISSION_GRANTED
    if (granted) return
    askedForNotifications = true
    ActivityCompat.requestPermissions(
      activity,
      arrayOf(Manifest.permission.POST_NOTIFICATIONS),
      NOTIFICATION_PERMISSION_REQUEST,
    )
  }

  /**
   * 권한 대화상자에서 돌아오면 알림을 다시 그린다.
   *
   * 거절한 뒤 설정에서 켜고 돌아오는 경우도 여기로 온다. 재생 중일 때만
   * 건드리므로 멈춰 있는 앱이 알림을 되살리지는 않는다.
   */
  override fun onResume() {
    if (NowPlayingSession.current != null) PlaybackService.sync(activity)
  }

  private companion object {
    const val NOTIFICATION_PERMISSION_REQUEST = 0x4D55
  }
}
