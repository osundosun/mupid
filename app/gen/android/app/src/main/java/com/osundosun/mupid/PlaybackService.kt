package com.osundosun.mupid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.media.session.MediaButtonReceiver

/**
 * 재생 중임을 시스템에 알리는 전경 서비스.
 *
 * 두 가지 일을 한다.
 *
 * - **알림을 띄운다.** 상단 그늘과 잠금화면의 플레이어가 이 알림이다.
 * - **프로세스를 지킨다.** 소리는 웹뷰가 내는데, 앱이 뒤로 가면 안드로이드는
 *   그 프로세스를 언제든 정리할 수 있다. 전경 서비스가 붙어 있는 동안에는
 *   그러지 않는다 — 화면을 끄고도 노래가 이어지는 것이 이것 때문이다.
 *
 * 멈춰 있는 동안에도 전경에서 내려오지 않는다. 알림을 남겨 두어야 다시 누를
 * 자리가 있는데, **전경에서 내려온 뒤에는 잠금화면의 재생 버튼으로 다시 올라올
 * 수 없다** — 안드로이드 12 부터 뒤에 있는 앱은 전경 서비스를 시작하지 못하기
 * 때문이다. 대신 멈추면 알림을 `ongoing` 에서 풀어 쓸어 낼 수 있게 두고, 쓸어
 * 내면(`setDeleteIntent`) 재생을 닫으면서 서비스도 함께 내린다.
 *
 * **다만 프로세스를 지키는 데에는 한계가 있다.** 최근 앱 목록에서 앱을 밀어내면
 * 액티비티가 파괴되고, 그러면 tao 의 이벤트 루프가 끝나면서 `process::exit` 로
 * 앱이 스스로 나간다 (`tao/src/platform_impl/android/mod.rs`). 전경 서비스로
 * 막을 수 있는 종류가 아니다 — 안드로이드가 죽이는 것이 아니라 앱이 나가는
 * 것이기 때문이다. 그래서 그 경우에는 `MainActivity.onDestroy` 가 플레이어를
 * 먼저 거둔다.
 */
class PlaybackService : Service() {
  companion object {
    private const val TAG = "MupidMedia"
    private const val CHANNEL_ID = "mupid.playback"
    private const val NOTIFICATION_ID = 1

    /** 지금 상태로 알림을 다시 그린다. 서비스가 없으면 띄운다. */
    fun sync(context: Context) {
      val intent = Intent(context, PlaybackService::class.java)
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (error: Throwable) {
        // 안드로이드 12+ 는 앱이 뒤에 있는 동안의 전경 서비스 시작을 막는다.
        // 재생은 사용자가 화면에서 누를 때 시작하므로 정상 흐름에서는 걸리지
        // 않지만, 걸리더라도 소리까지 끊을 이유는 없다.
        Log.w(TAG, "재생 알림을 띄우지 못했습니다", error)
      }
    }

    fun stop(context: Context) {
      context.stopService(Intent(context, PlaybackService::class.java))
    }

    /**
     * 알림을 **지금 당장** 거둔다.
     *
     * 두 가지를 순서대로 해야 한다.
     *
     * 1. **전경에서 먼저 내려온다.** 전경 서비스는 알림을 반드시 하나 들고
     *    있어야 해서, 올라서 있는 동안 `cancel` 을 불러도 시스템이 도로 띄운다.
     * 2. 그 다음에 알림을 지운다.
     *
     * 둘 다 **동기로** 해야 한다. `stopService` 는 비동기라 알림을 지우는 자리
     * (서비스의 `onDestroy`)가 돌기 전에 프로세스가 먼저 사라진다. 최근 앱에서
     * 밀어낼 때는 WebView 정리 중에 hwui 가 죽으면서(`FORTIFY: pthread_mutex_lock
     * called on a destroyed mutex`) 그 틈이 더 짧다. 그래서 돌고 있는 서비스를
     * 직접 붙잡아 손으로 내린다 — 밀어낸 뒤 눌러도 아무 일이 없는 플레이어가
     * 남던 것이 이것 때문이었다.
     */
    fun dismiss(context: Context) {
      try {
        running?.let { ServiceCompat.stopForeground(it, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
      } catch (error: Throwable) {
        Log.w(TAG, "재생 알림을 거두지 못했습니다", error)
      }
      stop(context)
    }

    /**
     * 돌고 있는 서비스. `dismiss` 가 전경에서 내리려면 인스턴스가 있어야 한다.
     *
     * 같은 프로세스 안이고 `onDestroy` 에서 비우므로 새지 않는다.
     */
    private var running: PlaybackService? = null
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    // 이어폰·블루투스 버튼은 이 인텐트로 들어온다. 세션 콜백으로 넘긴다.
    MediaButtonReceiver.handleIntent(NowPlayingSession.mediaSession, intent)

    val now = NowPlayingSession.current
    if (now == null) {
      stopSelf()
      return START_NOT_STICKY
    }

    // `startForegroundService` 로 깨워 놓고 `startForeground` 를 부르지 않으면
    // 안드로이드가 앱을 죽인다. 멈춰 있어도 반드시 부른다.
    ServiceCompat.startForeground(
      this,
      NOTIFICATION_ID,
      build(now),
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
      } else {
        0
      },
    )
    return START_NOT_STICKY
  }

  override fun onCreate() {
    super.onCreate()
    running = this
  }

  override fun onDestroy() {
    running = null
    ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
    super.onDestroy()
  }

  private fun build(now: NowPlaying): Notification {
    ensureChannel()

    // 플레이어를 누르면 앱으로 돌아온다.
    //
    // **앱 아이콘을 누른 것과 똑같은 인텐트**로 만든다(`ACTION_MAIN` +
    // `CATEGORY_LAUNCHER`). 그냥 `Intent(this, MainActivity)` 로 띄우면 이미
    // 열려 있던 화면 위에 **새 태스크가 하나 더** 생겨서, 돌아온 뒤 뒤로
    // 가기를 누르면 같은 앱이 두 번 나온다. 최근 앱에서 밀어낸 뒤라면 이
    // 인텐트가 앱을 처음부터 띄운다.
    val open = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java).apply {
        action = Intent.ACTION_MAIN
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    val builder = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_stat_mupid)
      .setContentTitle(now.title)
      .setContentText(now.artist)
      .setSubText(now.album.ifBlank { null })
      .setLargeIcon(NowPlayingSession.artwork)
      .setContentIntent(open)
      .setDeleteIntent(button(PlaybackStateCompat.ACTION_STOP))
      // 잠금화면에 곡 이름까지 그대로 보인다. 감출 만한 내용이 아니다.
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
      // 곡이 바뀔 때마다 소리와 진동을 내지 않는다.
      .setOnlyAlertOnce(true)
      .setShowWhen(false)
      .setOngoing(now.playing)

    // 좁은 칸에 보일 버튼 자리는 **넣은 순서**로 센다. 이전·다음이 상황에 따라
    // 빠지므로 번호를 박아 두면 엉뚱한 버튼이 접힌다.
    val compact = mutableListOf<Int>()
    var index = 0
    if (now.canPrevious) {
      builder.addAction(
        android.R.drawable.ic_media_previous,
        getString(R.string.playback_previous),
        button(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS),
      )
      compact.add(index++)
    }
    if (now.playing) {
      builder.addAction(
        android.R.drawable.ic_media_pause,
        getString(R.string.playback_pause),
        button(PlaybackStateCompat.ACTION_PAUSE),
      )
    } else {
      builder.addAction(
        android.R.drawable.ic_media_play,
        getString(R.string.playback_play),
        button(PlaybackStateCompat.ACTION_PLAY),
      )
    }
    compact.add(index++)
    if (now.canNext) {
      builder.addAction(
        android.R.drawable.ic_media_next,
        getString(R.string.playback_next),
        button(PlaybackStateCompat.ACTION_SKIP_TO_NEXT),
      )
      compact.add(index)
    }

    val style = androidx.media.app.NotificationCompat.MediaStyle()
      .setShowActionsInCompactView(*compact.toIntArray())
    // 토큰을 물려야 안드로이드 11+ 의 잠금화면 미디어 칸이 이 알림을 집는다.
    NowPlayingSession.token?.let { style.setMediaSession(it) }
    builder.setStyle(style)

    return builder.build()
  }

  private fun button(action: Long): PendingIntent? =
    MediaButtonReceiver.buildMediaButtonPendingIntent(this, action)

  private fun ensureChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = getSystemService(NotificationManager::class.java) ?: return
    if (manager.getNotificationChannel(CHANNEL_ID) != null) return
    val channel = NotificationChannel(
      CHANNEL_ID,
      getString(R.string.playback_channel_name),
      // 재생 알림은 소리도 진동도 내지 않는다. 상태를 보여 줄 뿐이다.
      NotificationManager.IMPORTANCE_LOW,
    ).apply {
      description = getString(R.string.playback_channel_description)
      setShowBadge(false)
      lockscreenVisibility = Notification.VISIBILITY_PUBLIC
    }
    manager.createNotificationChannel(channel)
  }
}
