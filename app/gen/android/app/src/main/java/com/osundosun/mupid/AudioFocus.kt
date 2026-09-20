package com.osundosun.mupid

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * 오디오 포커스 — 소리를 낼 자격을 시스템에 요청한다.
 *
 * ## 왜 필요한가
 *
 * 소리를 내는 것은 이 액티비티 웹뷰의 Web Audio 다(`frontend/lib/player/engine.ts`).
 * 그런데 **안드로이드 웹뷰는 오디오 포커스를 요청하지 않는다.** 그래서
 *
 * - 뮤피드가 재생 중일 때 다른 음악 앱(예: SoundCloud)을 켜면 그 앱이 뮤피드를
 *   멈추지 못하고,
 * - 다른 앱이 재생 중일 때 뮤피드에서 재생을 누르면 뮤피드가 그 앱을 멈추지 못한다.
 *
 * 둘이 그대로 겹쳐 울렸다. 안드로이드는 **포커스를 가진 앱 하나만 소리를 내게**
 * 하는 장치를 두고 있는데 이 앱은 그 대화에 아예 참여하지 않고 있었다
 * (`app/src` 전체에 `AUDIOFOCUS` 참조 0건 — 실측).
 *
 * ## 누가 부르는가
 *
 * 재생 여부를 아는 쪽은 화면이다. 화면은 곡과 함께 `playing` 을
 * `media_session_update` 로 올려보내고, 그 값이 곧 재생의 시작·정지다. 그래서
 * `MediaSessionPlugin.setState` 가 재생이 시작될 때 [acquire] 를 부른다.
 *
 * **웹뷰가 소리를 낸다고 포커스만 포기할 이유는 없다.** 오디오 포커스는 앱 단위
 * 개념이고, 웹뷰도 이 앱의 오디오 스택(AudioTrack)을 쓴다. 앱 전체를 대표해
 * 여기서 관리한다.
 *
 * ## 포커스를 잃으면
 *
 * 화면에 **알림·잠금화면 버튼과 똑같은 제어**를 보낸다(`NowPlayingSession.control`).
 * 새 통로를 만들지 않는다 — 화면은 이미 `useMediaSession` 한 곳에서 알림 버튼과
 * 같은 핸들러로 받고 있다. 잠시 뺏긴 것이면 되찾았을 때(`AUDIOFOCUS_GAIN`) 다시
 * 재생을 이어 준다.
 *
 * ## 왜 덕킹(볼륨만 줄이기)이 아니라 정지인가
 *
 * 안드로이드는 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` 요청에 대해 **시스템이 알아서
 * 볼륨을 줄여 주는** 길도 열어 둔다(`setWillPauseWhenDucked` 을 쓰지 않으면 그쪽이다).
 * 그런데 이 앱은 그 요청을 받아도 **볼륨을 직접 낮출 방법이 없다** — 소리를 내는
 * 것은 JS 의 Web Audio 그래프이고, 그 이득(gain)은 사용자가 화면에서 잡는 값이라
 * 네이티브에서 만질 통로가 없다. 그래서 `setWillPauseWhenDucked(true)` 로
 * **정지·재개 쪽을 택했다.** 시스템이 대신 줄여 주기를 기다리는 것보다, 멈췄다가
 * 되찾으면 이어 주는 편이 이 구조에서 확실하다.
 *
 * ## API 레벨
 *
 * 최소 지원은 API 24(`tauri.conf.json` 의 `minSdkVersion`, `build.gradle.kts` 의
 * `minSdk = 24`)다. `AudioFocusRequest` 는 **API 26** 부터라 그 아래는 옛
 * `requestAudioFocus(리스너, 스트림, 힌트)` 로 내려간다 — 이 저장소가 이미 다른
 * 곳에서 쓰는 방식이다(`PlaybackService`, `MediaSessionPlugin` 의 버전 분기).
 */
object AudioFocus {
  private const val TAG = "MupidMedia"

  /** 화면에 보내는 재생 제어. `NowPlayingSession` 의 `Control` 과 같은 낱말이다. */
  private const val ACTION_PLAY = "play"
  private const val ACTION_PAUSE = "pause"

  private var manager: AudioManager? = null

  /** API 26+ 의 포커스 요청. 한 번 만들어 **요청과 반납에 같은 것을 쓴다**(문서 규칙). */
  private var request: AudioFocusRequest? = null

  /** 지금 포커스를 쥐고 있는가. */
  @Volatile
  private var hasFocus = false

  /**
   * 되찾으면 이어 들어야 하는가.
   *
   * **포커스 때문에 멈춘 경우에만 참**이다. 사용자가 직접 멈춘 것은 이어 주면 안 된다.
   */
  @Volatile
  private var resumeOnGain = false

  /** 다른 앱이 포커스를 쥐고 있어 **나중에 받기로** 한 상태(`AUDIOFOCUS_REQUEST_DELAYED`). */
  @Volatile
  private var awaitingFocus = false

  private val listener = AudioManager.OnAudioFocusChangeListener { change -> changed(change) }

  /**
   * 소리를 내려 하니 포커스를 달라고 요청한다.
   *
   * 이미 쥐고 있으면 아무 일도 하지 않는다. 못 받으면 **소리를 내지 않는다** —
   * 화면이 이미 재생을 걸어 두었더라도 여기서 멈춘다. 받지도 못한 채 우겨서
   * 내보내면 두 앱이 겹쳐 우는 그 원래 문제로 돌아간다.
   *
   * 일시정지로는 반납하지 않는다([abandon] 참고). 그래서 이 함수는 재생이
   * **시작될 때**만 불린다.
   */
  fun acquire(context: Context) {
    if (hasFocus) return
    val audio = audioManager(context) ?: return

    val result = request(audio)
    when {
      result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
        hasFocus = true
        awaitingFocus = false
      }
      // 다른 앱이 포커스를 쥐고 있고, 그 앱이 놓을 때 주기로 했다. 그때까지는
      // 소리를 내면 안 된다. 놓으면 `AUDIOFOCUS_GAIN` 이 오고 거기서 이어 튼다.
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        result == AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
        awaitingFocus = true
        stopPlayback()
      }
      else -> {
        awaitingFocus = false
        Log.w(TAG, "오디오 포커스를 받지 못했습니다: $result")
        stopPlayback()
      }
    }
  }

  /**
   * 포커스를 놓는다. **재생을 닫았을 때만** 부른다(`clear`, 액티비티 파괴).
   *
   * 일시정지로는 놓지 않는다 — 안드로이드 문서의 규칙이다. 놓아 버리면 잠금화면의
   * 재생 버튼을 눌렀을 때 포커스를 처음부터 다시 요청해야 하고, 그 사이에 다른
   * 앱이 먼저 가져가면 이어 듣지 못한다.
   */
  fun abandon() {
    val audio = manager ?: return
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      request?.let { audio.abandonAudioFocusRequest(it) }
    } else {
      audio.abandonAudioFocus(listener)
    }
    hasFocus = false
    resumeOnGain = false
    awaitingFocus = false
  }

  /** API 레벨에 따라 갈라 요청한다. 돌려주는 값은 `AUDIOFOCUS_REQUEST_*` 다. */
  private fun request(audio: AudioManager): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val focusRequest = request ?: build(audio).also { request = it }
      audio.requestAudioFocus(focusRequest)
    } else {
      // 스트림 타입은 API 26 에서 폐기됐다. 그 아래에서는 이것이 유일한 길이다.
      audio.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
    }

  /**
   * 포커스 요청 한 벌.
   *
   * - **영구(`AUDIOFOCUS_GAIN`)** — 음악 재생이라 이쪽이다.
   * - `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC` — 안드로이드 12+ 가 다른 앱을 페이드아웃
   *   시킬지 판단하는 근거다(음성 콘텐츠는 덕킹 대상에서 빠진다).
   * - `setWillPauseWhenDucked(true)` — 위 클래스 주석의 이유로 덕킹 대신 정지·재개.
   *   이 값을 켜면 덕 요청도 콜백으로 들어온다.
   * - `setAcceptsDelayedFocusGain(true)` — 포커스가 잠겨 있으면 `DELAYED` 로 답이
   *   오고, 놓이는 순간 `GAIN` 으로 깨어난다. 이게 없으면 잠긴 동안 영영 못 튼다.
   */
  private fun build(audio: AudioManager): AudioFocusRequest {
    // `AudioAttributes` 자체는 API 21 부터다. 빌더만 26 가드를 탄다.
    val attributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_MEDIA)
      .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
      .build()
    return AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
      .setAudioAttributes(attributes)
      .setWillPauseWhenDucked(true)
      .setAcceptsDelayedFocusGain(true)
      .setOnAudioFocusChangeListener(listener)
      .build()
  }

  /**
   * 포커스가 바뀌었다.
   *
   * 문서의 정책을 그대로 따른다.
   *
   * - `GAIN` — 되찾았다. **포커스 때문에 멈춘 것이었으면** 이어 튼다.
   * - `LOSS` — 영구히 뺏겼다. 되찾는 콜백이 오지 않으므로 이어 듣지 않는다.
   *   다시 들으려면 사용자가 직접 눌러야 한다.
   * - `LOSS_TRANSIENT` / `LOSS_TRANSIENT_CAN_DUCK` — 잠시 뺏겼다. 멈췄다가
   *   되찾으면 이어 듣는다.
   */
  private fun changed(change: Int) {
    when (change) {
      AudioManager.AUDIOFOCUS_GAIN -> {
        hasFocus = true
        val resume = resumeOnGain || awaitingFocus
        resumeOnGain = false
        awaitingFocus = false
        // 멈춰 있을 때만 이어 튼다. 그 사이 사용자가 직접 멈췄다면(그것과 우리가
        // 멈춘 것을 구분할 신호가 없다) 이어 트는 쪽으로 기운다 — 잠깐 겹쳐
        // 우는 것보다 낫고, 다음 정지가 사용자 뜻대로 곧바로 먹는다.
        if (resume && NowPlayingSession.current?.playing != true) {
          NowPlayingSession.control(ACTION_PLAY)
        }
      }
      AudioManager.AUDIOFOCUS_LOSS -> {
        hasFocus = false
        resumeOnGain = false
        awaitingFocus = false
        stopPlayback()
      }
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
      AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
        hasFocus = false
        resumeOnGain = NowPlayingSession.current?.playing == true
        stopPlayback()
      }
    }
  }

  /**
   * 포커스를 잃었으니 재생을 멈춘다.
   *
   * **사용자가 직접 멈춘 것과 같은 제어를 보낸다.** 화면의 재생기가 그대로 서고
   * 상단 알림도 멈춤으로 바뀐다 — 알림 버튼을 누른 것과 같은 자리를 지나므로
   * 두 경로가 어긋날 일이 없다. 이미 멈춰 있으면 보낼 것이 없다.
   */
  private fun stopPlayback() {
    if (NowPlayingSession.current?.playing != true) return
    NowPlayingSession.control(ACTION_PAUSE)
  }

  /**
   * `AudioManager` 한 벌. 앱 컨텍스트를 쥐고 있으므로 액티비티가 사라져도 새지 않는다.
   */
  private fun audioManager(context: Context): AudioManager? {
    manager?.let { return it }
    val found = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    if (found == null) Log.w(TAG, "AudioManager 를 찾지 못했습니다")
    manager = found
    return found
  }
}
