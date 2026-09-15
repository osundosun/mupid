package com.osundosun.mupid

import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

class MainActivity : TauriActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    // 화면을 시스템 바 밑까지 쓴다. 웹뷰는 `--safe-area-inset-*` 로 그만큼을
    // 비워 두므로(`app/mobile-navigation.css` 등) 내용이 가려지지는 않는다.
    // 그 값을 건네는 쪽은 `SafeArea` 다.
    //
    // 스타일을 직접 지정하는 이유는 두 가지다.
    //
    //  - `light` 는 "배경이 밝다"는 뜻이라 시스템이 아이콘을 어둡게 그린다.
    //    기본값으로 두면 밝은 하늘 배경 위에 흰 아이콘이 올라와 시각·배터리가
    //    보이지 않는다.
    //  - 스크림을 투명으로 두면 3버튼 내비게이션 뒤에 시스템이 깔던 회색 띠가
    //    사라지고 앱 배경이 그대로 이어진다.
    val transparent = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = transparent, navigationBarStyle = transparent)
    super.onCreate(savedInstanceState)
  }

  /**
   * 웹뷰가 만들어지는 자리. 주소를 불러오기 전이라 여기서 심어야 첫 화면부터
   * 값을 읽을 수 있다 — `addJavascriptInterface` 는 다음 문서부터 적용된다.
   */
  override fun onWebViewCreate(webView: WebView) {
    SafeArea.install(webView)
  }

  /**
   * 화면이 사라지면 상단·잠금화면 플레이어도 함께 거둔다.
   *
   * **최근 앱 목록에서 밀어내면 재생도 끝난다. 그렇게 하기로 정했다.**
   *
   * 소리를 내는 것은 이 액티비티의 웹뷰다. 액티비티가 파괴되면 웹뷰도 함께
   * 사라져 재생은 그 자리에서 끝난다 — 게다가 그 정리 과정에서 안드로이드의
   * `libhwui` 가 죽는다(`WebViewFunctorManager::onContextDestroyed` 에서
   * `pthread_mutex_lock called on a destroyed mutex`). 전경 서비스는 안드로이드가
   * 프로세스를 거둬 가려 할 때 막아 주는 장치지, 액티비티를 지켜 주지도 크래시를
   * 막아 주지도 않는다. 재생이 살아남게 하려면 디코딩과 스케줄링을 웹뷰 밖으로
   * 옮겨야 하고, 그것은 이 파일에서 할 수 있는 일이 아니다 (README).
   *
   * 그러니 여기서 거두지 않으면 **눌러도 아무 일이 없는 플레이어**가 알림
   * 그늘에 남는다. 실제로 그랬다 — 밀어내면 노래는 멈추는데 플레이어만 남았다.
   *
   * 홈 버튼이나 화면 끄기는 여기까지 오지 않는다(`onStop` 에서 멈춘다). 그쪽
   * 배경 재생은 그대로다 — 실제로 쓰이는 경로는 대개 이쪽이다.
   */
  override fun onDestroy() {
    NowPlayingSession.clear(this)
    NowPlayingSession.release()
    super.onDestroy()
  }
}
