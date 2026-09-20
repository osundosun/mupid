package com.osundosun.mupid

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log

/**
 * 지금 재생 중인 것.
 *
 * 소리는 웹뷰가 내므로 안드로이드는 무엇이 울리는지 알 길이 없다. 화면이
 * `media_session_update` 로 넘긴 값을 여기에 담아, 세션과 알림이 같은 것을 보게
 * 한다.
 */
data class NowPlaying(
  val title: String,
  val artist: String,
  val album: String,
  val durationMs: Long,
  val positionMs: Long,
  val playing: Boolean,
  /** Rust 가 받아 둔 앨범 아트 파일. 없으면 표지 없이 띄운다. */
  val artworkPath: String?,
  val canPrevious: Boolean,
  val canNext: Boolean,
)

/**
 * `MediaSession` 한 벌.
 *
 * 상단 알림과 잠금화면의 플레이어는 둘 다 이 세션에서 나온다. 안드로이드 11
 * 부터 잠금화면의 미디어 칸은 세션이 그리고, 그 아래 알림은 `PlaybackService`
 * 가 이 세션의 토큰을 물려 만든다 — 그래서 한 곳에서만 갱신하면 둘이 함께
 * 움직인다.
 *
 * 플러그인과 서비스가 같은 프로세스 안에 있으므로 바인더를 놓지 않고 객체
 * 하나를 함께 본다.
 */
object NowPlayingSession {
  private const val TAG = "MupidMedia"

  /** 앨범 아트를 줄일 한도. 알림과 잠금화면은 이보다 크게 그리지 않는다. */
  private const val MAX_ART_PX = 512

  private var session: MediaSessionCompat? = null
  private var artPath: String? = null
  private var art: Bitmap? = null

  /** 알림·잠금화면·이어폰 버튼을 누르면 이리로 온다. Rust 가 걸어 둔다. */
  @Volatile
  var onControl: ((action: String, positionMs: Long) -> Unit)? = null

  @Volatile
  var current: NowPlaying? = null
    private set

  val token: MediaSessionCompat.Token?
    get() = session?.sessionToken

  /** 알림을 만들 때 쓰는 표지. `update` 가 이미 풀어 둔 것을 그대로 준다. */
  val artwork: Bitmap?
    get() = art

  internal val mediaSession: MediaSessionCompat?
    get() = session

  fun update(context: Context, next: NowPlaying) {
    val app = context.applicationContext
    val session = ensure(app)
    artwork(next.artworkPath)
    session.setMetadata(metadataOf(next))
    session.setPlaybackState(stateOf(next))
    // 세션이 활성이어야 잠금화면과 이어폰 버튼이 이 앱을 찾는다.
    session.isActive = true
    current = next
    PlaybackService.sync(app)
  }

  fun clear(context: Context) {
    current = null
    session?.isActive = false
    art = null
    artPath = null
    PlaybackService.dismiss(context.applicationContext)
  }

  fun release() {
    session?.release()
    session = null
    current = null
    art = null
    artPath = null
  }

  private fun ensure(app: Context): MediaSessionCompat =
    session ?: MediaSessionCompat(app, "mupid").also {
      it.setCallback(callback)
      session = it
    }

  private val callback = object : MediaSessionCompat.Callback() {
    override fun onPlay() = emit("play")
    override fun onPause() = emit("pause")
    override fun onSkipToNext() = emit("next")
    override fun onSkipToPrevious() = emit("previous")
    override fun onStop() = emit("stop")
    override fun onSeekTo(pos: Long) = emit("seek", pos)
  }

  /**
   * 화면에 재생 제어를 보낸다. 알림·잠금화면 버튼과 **같은 통로**다.
   *
   * 오디오 포커스를 잃어 멈출 때와 되찾아 이어 들을 때(`AudioFocus`) 쓴다. 재생
   * 제어를 만들어 내보내는 자리가 여기 하나뿐이라, 앱 밖에서 온 정지와 알림 버튼의
   * 정지가 화면에서는 구분되지 않는다 — 둘 다 같은 자리를 지나 실제로 재생을 멈춘다.
   */
  fun control(action: String) = emit(action)

  private fun emit(action: String, positionMs: Long = -1) {
    val handler = onControl
    if (handler == null) {
      Log.w(TAG, "제어 통로가 아직 없습니다: $action")
      return
    }
    handler(action, positionMs)
  }

  private fun metadataOf(value: NowPlaying): MediaMetadataCompat {
    val builder = MediaMetadataCompat.Builder()
      .putString(MediaMetadataCompat.METADATA_KEY_TITLE, value.title)
      .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, value.artist)
      .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, value.album)
      // 길이가 있어야 잠금화면에 막대가 뜬다. 0 이면 버튼만 남는다.
      .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, value.durationMs)
    art?.let {
      builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
      builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
    }
    return builder.build()
  }

  private fun stateOf(value: NowPlaying): PlaybackStateCompat {
    var actions = PlaybackStateCompat.ACTION_PLAY or
      PlaybackStateCompat.ACTION_PAUSE or
      PlaybackStateCompat.ACTION_PLAY_PAUSE or
      PlaybackStateCompat.ACTION_STOP or
      PlaybackStateCompat.ACTION_SEEK_TO
    if (value.canNext) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_NEXT
    if (value.canPrevious) actions = actions or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS

    // 재생 속도를 함께 준다. 시스템은 이 값으로 **스스로 위치를 굴린다** — 그래서
    // 0.2초마다 상태를 올려보내지 않아도 잠금화면의 막대가 흐른다. 웹뷰가
    // 뒤로 가면 타이머가 늘어지는데, 그런 때에도 막대는 멀쩡하다.
    return PlaybackStateCompat.Builder()
      .setActions(actions)
      .setState(
        if (value.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
        value.positionMs,
        if (value.playing) 1f else 0f,
        SystemClock.elapsedRealtime(),
      )
      .build()
  }

  /** 표지를 풀어 둔다. 같은 파일이면 다시 풀지 않는다. */
  private fun artwork(path: String?) {
    if (path == null) {
      art = null
      artPath = null
      return
    }
    if (path == artPath && art != null) return
    art = decode(path)
    artPath = if (art == null) null else path
  }

  private fun decode(path: String): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    // 원본은 앨범 표지라 1000픽셀을 넘기 쉽다. 알림에 넣을 크기까지 줄여서
    // 푼다 — 통째로 풀면 곡을 넘길 때마다 수 MB 를 잡았다 놓는다.
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > MAX_ART_PX) sample *= 2
    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
  } catch (error: Throwable) {
    Log.w(TAG, "앨범 아트를 읽지 못했습니다: $path", error)
    null
  }
}
