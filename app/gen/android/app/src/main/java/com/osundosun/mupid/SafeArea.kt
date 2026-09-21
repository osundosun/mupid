package com.osundosun.mupid

import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * 시스템 바와 **화상 키보드**가 가리는 자리를 웹뷰에 알려 준다.
 *
 * 화면은 상태바·내비게이션 바 밑까지 쓴다(`MainActivity` 의 `enableEdgeToEdge`).
 * 그만큼을 비워 두라고 CSS 에 일러 주는 것이 `env(safe-area-inset-*)` 인데,
 * **안드로이드 웹뷰는 그 값에 노치만 넣고 시스템 바는 넣지 않는다.** 노치 없는
 * 기기에서는 넷 다 0 이라, 비워 두라고 적어 둔 CSS 가 아무 일도 하지 않고
 * 시스템 바 밑으로 내용이 들어간다. 그래서 값을 여기서 재어 건넨다.
 *
 * 웹뷰가 읽어 가는 통로는 `window.__mupidSafeArea.get()` 하나다. 화면 쪽은 그것을
 * `--safe-area-inset-*` 사용자 정의 속성으로 받는다
 * (`frontend/lib/platform/safe-area.ts`). 웹과 iOS 에서는 그 속성이 `env()` 를
 * 그대로 물고 있어 이 통로가 없어도 된다.
 *
 * ## 화상 키보드는 **여기서 뷰를 줄여서** 해결한다 (S15P21A204-411)
 *
 * 키보드 높이를 웹에 건네 CSS 가 빼게 하는 길은 **네 번 실패했다**(아래 `install`
 * 의 표). 지금은 키보드가 뜨면 **웹뷰를 담은 컨테이너를 그만큼 줄인다.** 웹뷰의
 * 측정 크기가 줄면 `100dvh`·`innerHeight`·`visualViewport`·`position: fixed` 가
 * 전부 키보드를 뺀 값으로 저절로 맞는다 — 웹이 따로 뺄 것이 없다.
 *
 * 그래서 브리지의 `keyboardHeight` 는 **앱에서 늘 0** 이다. 웹이 보는 화면에서
 * 가려진 몫이 실제로 0 이기 때문이다.
 */
class SafeArea private constructor() {
  /**
   * 웹뷰가 읽어 가는 JSON 한 줄. **기기 픽셀**이고, CSS 픽셀로 나누는 일은
   * `devicePixelRatio` 를 아는 화면 쪽이 한다.
   *
   * `@JavascriptInterface` 는 웹뷰가 자기 스레드에서 부르고 값을 넣는 쪽은 UI
   * 스레드다. 값들을 따로 두면 그 사이에 반쯤 갱신된 것을 읽을 수 있어,
   * 통째로 갈아 끼우는 문자열 하나로 둔다.
   */
  @Volatile
  private var json =
    "{\"top\":0,\"right\":0,\"bottom\":0,\"left\":0," +
      "\"keyboardHeight\":0,\"keyboardVisible\":false,\"imeBottom\":0}"

  @JavascriptInterface
  fun get(): String = json

  /** 값이 실제로 바뀌었으면 true. */
  private fun set(
    top: Int,
    right: Int,
    bottom: Int,
    left: Int,
    keyboardVisible: Boolean,
    imeBottom: Int,
  ): Boolean {
    /*
     * `keyboardHeight` 는 **언제나 0** 이다. 빠뜨린 것이 아니다.
     *
     * 키보드가 뜨면 `install` 이 웹뷰를 담은 컨테이너를 IME 높이만큼 줄인다.
     * 웹뷰의 뷰포트 자체가 키보드 위에서 끝나므로 **웹이 보는 화면에서 가려진
     * 몫은 실제로 0 이다.** 여기에 IME 높이를 실어 보내면 웹이 `100dvh` 에서
     * 그것을 또 빼서 **두 번 줄어든다** — 대화상자가 화면 절반만 하게 선다.
     *
     * 필드를 지우지 않고 0 으로 두는 이유는 웹 쪽 타입이 이 이름을 알고 있고
     * (`frontend/lib/platform/safe-area.ts` 의 `KeyboardInset`), 브라우저에서는
     * `visualViewport` 폴백이 같은 자리를 채우기 때문이다. 계약을 깨지 않는다.
     *
     * 실제 IME 값은 `imeBottom` 으로 따로 싣는다 — **진단용이고 웹은 쓰지 않는다.**
     */
    val next =
      "{\"top\":$top,\"right\":$right,\"bottom\":$bottom,\"left\":$left," +
        "\"keyboardHeight\":0,\"keyboardVisible\":$keyboardVisible,\"imeBottom\":$imeBottom}"
    if (next == json) return false
    json = next
    return true
  }

  companion object {
    /** 로그 태그. `adb logcat -s MupidSafeArea` 로 본다. */
    private const val TAG = "MupidSafeArea"

    /** 웹뷰에 심는 전역 이름. `frontend/lib/platform/safe-area.ts` 와 같아야 한다. */
    private const val BRIDGE = "__mupidSafeArea"

    /** 값이 바뀌었을 때 웹뷰에 던지는 이벤트. 화면 쪽과 같아야 한다. */
    private const val CHANGED = "mupid:safe-area"

    /**
     * 웹뷰를 담은 컨테이너를 찾는다. 없으면 `null`.
     *
     * ## 왜 `webView.rootView` 를 쓰면 안 되는가 — **411 의 진짜 원인**
     *
     * wry(0.55.1)는 웹뷰를 이 순서로 붙인다(`wry/src/android/main_pipe.rs`):
     *
     *     activity.setWebView(webview)   // ← 여기서 onWebViewCreate → SafeArea.install
     *     ...
     *     activity.setContentView(webview)
     *
     * **`install` 이 불리는 순간 웹뷰는 아직 아무 데도 붙어 있지 않다.** 그때
     * `View.getRootView()` 는 조상이 없으므로 **자기 자신을 돌려준다**(AOSP:
     * `mAttachInfo` 가 null 이고 부모가 없으면 `return this`).
     *
     * 그래서 "루트 뷰에 패딩을 준다"던 4번째 시도는 **웹뷰 자신에게 패딩을 준
     * 3번째 시도와 글자만 다른 같은 코드**였다. 로그에 `root padding applied:
     * bottom=1008` 이 찍히는데 화면이 한 픽셀도 안 움직인 이유가 이것이다 —
     * WebView 는 자기 패딩 영역에도 콘텐츠를 그려 **측정 크기가 바뀌지 않는다.**
     *
     * ## 그래서 **붙은 뒤에, 매번** 찾는다
     *
     * 인셋이 도착하는 시점에는 이미 `setContentView` 가 끝나 계층이 서 있다.
     * 실기기에서 잰 계층은 이렇다(uiautomator, Galaxy S23 / Android 16):
     *
     *     FrameLayout (DecorView)              [0,0][1080,2316]
     *       LinearLayout                       [0,0][1080,2316]
     *         FrameLayout                      [0,0][1080,2316]
     *           LinearLayout action_bar_root   [0,0][1080,2316]
     *             FrameLayout android:id/content   ← 이 자리
     *               WebView                    [0,0][1080,2316]
     *
     * `android.R.id.content` 를 고르는 이유는 그것이 **웹뷰를 직접 담는
     * `FrameLayout`** 이고, 웹뷰가 `MATCH_PARENT` 로 들어 있어 패딩이 곧 웹뷰의
     * 크기가 되기 때문이다(`FrameLayout.onMeasure` 가 패딩을 빼고 자식을 잰다).
     * 더 위(DecorView)로 올라가면 창 장식까지 함께 줄어든다.
     *
     * 못 찾으면 **바로 위 부모**로 내려앉는다. 그것도 없으면 `null` 이고, 그때는
     * 아무것도 하지 않고 로그에 남긴다 — 웹뷰 자신에게 패딩을 주어 "됐다"고
     * 착각하느니 아무 일도 안 하는 편이 낫다. 그 착각이 411 이었다.
     */
    private fun contentContainer(webView: WebView): ViewGroup? {
      var nearest: ViewGroup? = null
      var node = webView.parent
      while (node is ViewGroup) {
        if (nearest == null) nearest = node
        if (node.id == android.R.id.content) return node
        node = node.parent
      }
      return nearest
    }

    /**
     * 계층을 한 줄로 적는다. **어느 층에서 끊겼는지 로그만 보고 알 수 있어야 한다.**
     *
     * 앞선 네 번은 "설정이 적용됐다"만 찍고 "화면이 바뀌었나"를 찍지 않아서
     * 로그가 성공을 말하는데 화면은 그대로인 상태를 네 번 반복했다. 크기를 같이
     * 적으면 그 착각이 불가능해진다.
     */
    private fun chain(webView: WebView): String {
      val parts = mutableListOf<String>()
      var node: View? = webView
      var depth = 0
      while (node != null && depth < 12) {
        val tag = if (node.id == android.R.id.content) "#content" else ""
        parts += "${node.javaClass.simpleName}$tag(${node.width}x${node.height},pb=${node.paddingBottom})"
        node = node.parent as? View
        depth += 1
      }
      return parts.joinToString(" < ")
    }

    /**
     * 웹뷰가 만들어질 때 한 번 부른다.
     *
     * 값을 밀어 넣지 않고 **읽어 갈 자리만** 심는 이유는, 인셋이 처음 도착하는
     * 시점과 화면이 뜨는 시점의 순서를 보장할 수 없기 때문이다. 밀어 넣기만
     * 하면 아직 빈 문서에 적히고 사라진다. 화면은 뜰 때 스스로 한 번 읽고,
     * 그 뒤로는 이 이벤트를 듣는다.
     *
     * ## 키보드를 여기서 처리한다 — 앞선 네 번이 실패한 자리 (S15P21A204-411)
     *
     * | # | 한 일 | 결과 |
     * |---|---|---|
     * | 1 | 웹이 `visualViewport` 로 재어 CSS 로 뺀다(389) | 실기기에서 늘 0 |
     * | 2 | IME 인셋 → JS → CSS `max-height` 차감 | `max-height` 는 **상한**일 뿐이라 대화상자의 자연 높이가 더 작으면 아무 일도 안 일어난다 |
     * | 3 | `webView.setPadding(..., imeBottom)` | 로그만 찍히고 화면 그대로 |
     * | 4 | `webView.rootView.setPadding(..., imeBottom)` | 3번과 **같은 코드였다**(위 `contentContainer` 주석) |
     *
     * 1번이 0 인 이유는 `enableEdgeToEdge()` 로 `decorFitsSystemWindows=false` 라
     * 프레임워크가 창을 줄여 주지 않기 때문이다. 매니페스트에 `adjustResize` 가
     * 있고 실기기에서 `STATE_UNSPECIFIED|ADJUST_RESIZE` 로 **적용되는 것까지
     * 확인했는데도** 웹뷰 레이아웃에는 도달하지 않는다.
     *
     * 그래서 **우리가 직접 줄인다.** IME 인셋만큼 컨테이너에 아래쪽 패딩을 주면
     * 웹뷰가 그만큼 작게 측정되고, 웹뷰 뷰포트가 키보드 위에서 끝난다.
     */
    fun install(webView: WebView) {
      val safeArea = SafeArea()
      webView.addJavascriptInterface(safeArea, BRIDGE)
      ViewCompat.setOnApplyWindowInsetsListener(webView) { view, windowInsets ->
        val bars = windowInsets.getInsets(
          WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
        val keyboardVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
        val imeBottom = if (keyboardVisible) ime.bottom else 0

        /*
         * 컨테이너를 **지금** 찾는다. `install` 때 찾아 두면 웹뷰가 아직 안 붙어
         * 있어 빗나간다 — 그것이 411 의 원인이었다(`contentContainer` 주석).
         */
        val webViewNode = view as WebView
        val container = contentContainer(webViewNode)

        /*
         * 측정값을 로그로 남긴다.
         *
         * 릴리스 빌드는 웹뷰 원격 디버깅이 막혀 있어(`not debuggable`) 웹 쪽 값을
         * 볼 수 없다. 그래서 **네이티브가 본 것을 여기서 말한다** — 인셋이 오는가,
         * 어느 뷰에 먹였는가, 그래서 **웹뷰의 높이가 실제로 줄었는가.**
         *
         * 마지막 하나가 핵심이다. `chain=` 의 웹뷰 크기가 줄지 않으면 **실패다.**
         *
         * `adb logcat -s MupidSafeArea` 로 본다.
         */
        Log.i(
          TAG,
          "insets: imeVisible=$keyboardVisible imeBottom=$imeBottom " +
            "bars(top=${bars.top} bottom=${bars.bottom}) " +
            "target=${container?.javaClass?.simpleName ?: "(없음)"} " +
            "chain=${chain(webViewNode)}",
        )

        /*
         * 컨테이너에 키보드 몫을 준다. 값이 그대로면 건드리지 않는다 — 레이아웃마다
         * 불리므로 매번 `setPadding` 하면 재배치가 끝없이 돈다.
         */
        if (container == null) {
          Log.w(TAG, "컨테이너를 못 찾았다 — 키보드 몫을 줄 곳이 없다")
        } else if (container.paddingBottom != imeBottom) {
          container.setPadding(
            container.paddingLeft,
            container.paddingTop,
            container.paddingRight,
            imeBottom,
          )
          Log.i(TAG, "padding applied to ${container.javaClass.simpleName}: bottom=$imeBottom")
        }

        /*
         * 키보드가 떠 있는 동안 **내비게이션 바 몫은 웹에 넘기지 않는다.**
         *
         * 인셋은 창을 기준으로 재어 오지만, 위에서 컨테이너를 줄였으므로 웹뷰의
         * 아래 끝은 이미 키보드 위(= 내비게이션 바보다 위)다. 그 상태에서
         * `--safe-area-inset-bottom` 을 그대로 넘기면 웹이 쓸 수 있는 자리 아래에
         * 아무것도 없는 띠가 한 겹 더 생긴다.
         */
        val barsBottom = (bars.bottom - imeBottom).coerceAtLeast(0)

        // 레이아웃마다 불린다. 값이 그대로면 스크립트를 돌리지 않는다.
        if (safeArea.set(
            bars.top, bars.right, barsBottom, bars.left,
            keyboardVisible, imeBottom,
          )
        ) {
          webViewNode.evaluateJavascript(
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
