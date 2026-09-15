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
}
