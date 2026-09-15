package com.osundosun.mupid

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 시스템 바가 가리는 자리를 웹뷰에 알려 준다.
 *
 * 화면은 상태바·내비게이션 바 밑까지 쓴다(`MainActivity` 의 `enableEdgeToEdge`).
 * 그만큼을 비워 두라고 CSS 에 일러 주는 것이 `env(safe-area-inset-*)` 인데,
 * **안드로이드 웹뷰는 그 값에 노치만 넣고 시스템 바는 넣지 않는다.** 노치 없는
 * 기기에서는 넷 다 0 이라, 비워 두라고 적어 둔 CSS 가 아무 일도 하지 않고
 * 상단바가 상태바 밑으로 들어간다.
 *
 * 그래서 안드로이드가 잰 값을 여기서 직접 건네고, 화면은 그것을
 * `--safe-area-inset-*` 사용자 정의 속성으로 받는다
 * (`frontend/lib/platform/safe-area.ts`). 웹과 iOS 에서는 그 속성이 `env()` 를
 * 그대로 물고 있어 이 통로가 없어도 된다.
 */
class SafeArea private constructor() {
  /**
   * 화면이 읽어 가는 값. **기기 픽셀 단위다** — CSS 픽셀로 바꾸는 일은
   * `devicePixelRatio` 를 아는 화면 쪽이 한다.
   *
   * `@JavascriptInterface` 는 웹뷰가 자기 스레드에서 부르고 값을 넣는 쪽은 UI
   * 스레드다. 네 숫자를 따로 두면 그 사이에 반쯤 갱신된 값을 읽을 수 있어,
   * 통째로 갈아 끼우는 문자열 하나로 둔다.
   */
  @Volatile
  private var json = "{\"top\":0,\"right\":0,\"bottom\":0,\"left\":0}"

  @JavascriptInterface
  fun get(): String = json

  /** 값이 실제로 바뀌었으면 true. */
  private fun set(top: Int, right: Int, bottom: Int, left: Int): Boolean {
    val next = "{\"top\":$top,\"right\":$right,\"bottom\":$bottom,\"left\":$left}"
    if (next == json) return false
    json = next
    return true
  }

  companion object {
    /** 웹뷰에 심는 전역 이름. `frontend/lib/platform/safe-area.ts` 와 같아야 한다. */
    private const val BRIDGE = "__mupidSafeArea"

    /** 값이 바뀌었을 때 웹뷰에 던지는 이벤트. 화면 쪽과 같아야 한다. */
    private const val CHANGED = "mupid:safe-area"

    /**
     * 웹뷰가 만들어질 때 한 번 부른다.
     *
     * 값을 밀어 넣지 않고 **읽어 갈 자리만** 심는 이유는, 인셋이 처음 도착하는
     * 시점과 화면이 뜨는 시점의 순서를 보장할 수 없기 때문이다. 밀어 넣기만
     * 하면 아직 빈 문서에 적히고 사라진다. 화면은 뜰 때 스스로 한 번 읽고,
     * 그 뒤로는 이 이벤트를 듣는다.
     */
    fun install(webView: WebView) {
      val safeArea = SafeArea()
      webView.addJavascriptInterface(safeArea, BRIDGE)
      ViewCompat.setOnApplyWindowInsetsListener(webView) { view, windowInsets ->
        val bars = windowInsets.getInsets(
          WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        // 레이아웃마다 불린다. 값이 그대로면 스크립트를 돌리지 않는다.
        if (safeArea.set(bars.top, bars.right, bars.bottom, bars.left)) {
          (view as WebView).evaluateJavascript(
            "window.dispatchEvent(new Event('$CHANGED'))",
            null,
          )
        }
        // 소비하지 않고 그대로 넘긴다 — 뒤에 오는 뷰도 같은 값을 봐야 한다.
        windowInsets
      }
    }
  }
}
