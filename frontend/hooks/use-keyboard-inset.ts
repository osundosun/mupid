import { useEffect, useState } from 'react';

import { readNativeInsets } from '../lib/platform/safe-area';

/**
 * 화상 키보드가 아래에서 가린 높이(CSS px). 키보드가 없으면 0.
 *
 * ## 앱에서는 **언제나 0** 이다 (S15P21A204-411)
 *
 * 놀랄 값이 아니다. 앱에서는 키보드가 뜨는 순간 **네이티브가 웹뷰를 담은 컨테이너를
 * 그만큼 줄인다**(`SafeArea.kt`). 웹뷰의 뷰포트가 키보드 위에서 끝나므로
 * `100dvh`·`innerHeight`·`visualViewport` 가 전부 이미 줄어든 값이고, **웹이 보는
 * 화면에서 가려진 몫은 실제로 0 이다.** 여기서 또 빼면 두 번 줄어든다.
 *
 * 실기기 실측(2026-09-21, Galaxy S23 / Android 16 / WebView 152). 키보드가 뜰 때
 * 웹뷰가 실제로 줄어든다:
 *
 *     WebView        [0,0][1080,2316] → [0,0][1080,1308]
 *     비밀번호 입력란  [109,1490][970,1628] → [109,1099][942,1240]   (IME 상단 1308)
 *
 * ## 그럼 이 값은 누구를 위한 것인가 — **브라우저**
 *
 * 앱이 아니면 네이티브 통로가 없다. 브라우저에서는 `visualViewport` 가 실제로
 * 줄어들므로 그것으로 잰다. 그 경로만이 이 값을 양수로 만든다.
 *
 * **389 는 이 둘을 구분하지 못해 재발했다.** 브라우저에서 되는 방법을 앱에도
 * 쓴다고 보고 `visualViewport` 로 쟀는데, 앱은 `enableEdgeToEdge()` 로
 * `decorFitsSystemWindows=false` 라 프레임워크가 창을 줄여 주지 않아 늘 0 이었다.
 * 게다가 `layout.tsx` 의 `interactiveWidget: 'overlays-content'` 가 **"어떤 뷰포트도
 * 줄이지 말라"** 고 못박아 계측 경로 자체를 껐다.
 */
export function useKeyboardInset(): number {
  const [inset, setInset] = useState(0);
  useEffect(() => {
    const measure = () => setInset(measureKeyboardInset());
    measure();
    // 네이티브 값은 `SafeArea.kt` 가 바뀔 때마다 이 이벤트를 던진다.
    window.addEventListener('mupid:safe-area', measure);
    const viewport = window.visualViewport;
    viewport?.addEventListener('resize', measure);
    viewport?.addEventListener('scroll', measure);
    return () => {
      window.removeEventListener('mupid:safe-area', measure);
      viewport?.removeEventListener('resize', measure);
      viewport?.removeEventListener('scroll', measure);
    };
  }, []);
  return inset;
}

/**
 * 지금 키보드가 가린 높이(CSS px).
 *
 * **브리지가 있으면 그 값만 쓴다 — `visualViewport` 로 내려가지 않는다.** 앱에서는
 * 네이티브가 뷰를 줄여 이미 처리했고 그 값이 0 이다. 여기서 "0 이니 웹 방식으로 한 번
 * 더 재 보자" 고 내려가면 그 순간 이중 차감이 된다.
 *
 * `useKeyboardInset`(한 컴포넌트)와 `watchKeyboardInset`(뿌리에 한 번)이 **같은 값을
 * 쓰도록** 여기 모아 둔다. 두 곳이 각자 재면 한쪽만 고쳐지는 일이 생긴다.
 */
export function measureKeyboardInset(): number {
  const native = readNativeInsets();
  if (native) {
    return native.keyboard.height;
  }
  return measureKeyboardInsetFromViewport();
}

/**
 * `visualViewport` 로 잰다. **브라우저 전용이다** — 앱에서는 이 경로로 오지 않는다.
 *
 * `visualViewport` 는 키보드가 올라오면 보이는 영역만큼 줄어든다. 페이지 높이
 * (`innerHeight`)와의 차이가 곧 가린 높이다. `offsetTop` 은 iOS 가 입력칸을 보이려고
 * 페이지를 밀어 올린 몫이다 — 그만큼은 위로 사라진 것이니 아래 가림에서 뺀다.
 */
export function measureKeyboardInsetFromViewport(): number {
  if (typeof window === 'undefined') return 0;
  const viewport = window.visualViewport;
  if (!viewport) return 0;
  const covered = window.innerHeight - viewport.height - viewport.offsetTop;
  // 주소창이 접히고 펴지는 몫(수십 px)은 키보드가 아니다.
  return covered > 80 ? Math.round(covered) : 0;
}

/**
 * 가려진 높이를 CSS 변수로 `<html>` 과 `<body>` 에 적는다.
 *
 * 훅(`useKeyboardInset`)은 **한 컴포넌트 안에서만** 쓸 수 있다. 그런데 이 값을
 * 필요로 하는 자리는 하나가 아니다 — 로그인·회원가입 대화상자와 채팅 패널이
 * 각각 다른 나무에 있고, 대화상자는 포털로 `body` 아래에 따로 붙는다. 그래서
 * 각자 재는 대신 한 곳에서 잰 값을 뿌리에 적고, CSS 가
 * `var(--keyboard-inset, 0px)` 로 읽는다.
 *
 * 이 일은 **앱이 뜰 때 한 번** 붙으면 된다 — `MupidApp` 이 부른다.
 *
 * ## 높이가 아니라 **자리**를 옮겨야 한다 (S15P21A204-411)
 *
 * 한때 여기서 `--keyboard-safe-height`(= `innerHeight - inset`)도 같이 적어
 * 대화상자의 **높이**를 확정하려 했다. **그것으로는 대화상자가 키보드 위로 올라오지
 * 않는다.** 대화상자는 `inset: 16px; margin: auto` 로 **가운데 정렬**이라, 높이를
 * 줄이면 가운데를 중심으로 줄어들 뿐 아래 끝이 키보드 위로 올라오지 않는다.
 * `max-height` 를 낮춘 것도 같은 이유로 소용이 없었다.
 *
 * 그래서 CSS 쪽에서 **`bottom` 을 키보드만큼 올린다**(`dialog-viewport.css`).
 * `margin: auto` 가 그 줄어든 상자 안에서 다시 가운데를 잡는다. 변수 하나
 * (`--keyboard-inset`)면 충분해서 `--keyboard-safe-height` 는 지웠다.
 */
export function watchKeyboardInset(): void {
  if (typeof window === 'undefined') return;

  /*
   * 잴 수단이 있는가 — 안드로이드 브리지 또는 `visualViewport`.
   *
   * **둘 다 없으면 아무것도 적지 않는다.** CSS 의 `var(--keyboard-inset, 0px)` 가
   * 기본값으로 내려가므로 결과는 같고, `0px` 를 명시적으로 박으면 나중에 다른
   * 곳이 그 변수를 세우더라도 덮어쓴다. 389 가 세운 계약이고 그대로 지킨다.
   */
  const hasSource = () => readNativeInsets() !== null || Boolean(window.visualViewport);

  const apply = () => {
    if (!hasSource()) return;
    const inset = measureKeyboardInset();
    /*
     * `html` 과 `body` 양쪽에 적는다. 대화상자는 포털이라 `body` 아래에 붙는데,
     * `body` 에 변수가 없으면 상속으로 못 받아 늘 0 으로 읽는다.
     */
    const value = `${inset}px`;
    document.documentElement.style.setProperty('--keyboard-inset', value);
    document.body?.style.setProperty('--keyboard-inset', value);
  };

  apply();
  /*
   * 구독은 **잴 수단이 없어도 걸어 둔다.** 안드로이드 브리지는 웹뷰가 화면보다 늦게
   * 심을 수 있고, 그때 첫 인셋이 도착하는 순간 따라붙어야 한다.
   */
  // 네이티브(안드로이드) — 키보드가 오르내릴 때마다 온다.
  window.addEventListener('mupid:safe-area', apply);
  // 웹 — visualViewport 가 실제로 줄어드는 환경에서.
  const viewport = window.visualViewport;
  viewport?.addEventListener('resize', apply);
  viewport?.addEventListener('scroll', apply);
}
