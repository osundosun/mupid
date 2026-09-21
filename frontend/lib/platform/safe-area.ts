/**
 * 시스템 바가 가리는 자리를 CSS 에 건넨다.
 *
 * 화면 CSS 는 `env(safe-area-inset-*)` 대신 `--safe-area-inset-*` 를 쓴다.
 * 안드로이드 웹뷰의 `env()` 가 **노치만 셈에 넣고 상태바·내비게이션 바는
 * 빼먹기** 때문이다 — 노치 없는 기기에서는 넷 다 0 이라, 비워 두라고 적어 둔
 * CSS 가 아무 일도 하지 않고 상단바가 상태바 밑으로 들어간다.
 *
 * 앱에서는 안드로이드가 잰 값(`gen/android/.../SafeArea.kt`)을 여기서 받아
 * `<html>` 에 적는다. 웹과 iOS 에서는 이 파일이 아무 일도 하지 않고, 같은 이름의
 * 속성이 `app/globals.css` 에서 `env()` 를 그대로 물고 있다.
 */

export type SafeArea = {
  top: number;
  right: number;
  bottom: number;
  left: number;
};

/**
 * 화상 키보드가 **웹이 보는 화면에서** 가리고 있는 높이(CSS px).
 *
 * ## 앱에서 `height` 는 늘 0 이다 (S15P21A204-411)
 *
 * 키보드가 안 떴다는 뜻이 아니다. 키보드가 뜨면 `SafeArea.kt` 가 웹뷰를 담은
 * 컨테이너를 IME 만큼 줄여, 웹뷰 뷰포트가 키보드 위에서 끝난다. **그래서 웹이 보는
 * 화면에서 가려진 몫이 실제로 0 이다.** 여기에 IME 높이가 실려 오면 웹이 `100dvh`
 * 에서 그것을 또 빼 두 번 줄어든다.
 *
 * 키보드가 실제로 떠 있는지는 `visible` 로 본다 — `height` 로 판단하지 마라.
 */
export type KeyboardInset = {
  /** 웹이 더 빼야 할 몫. 앱에서는 늘 0, 브라우저에서는 `visualViewport` 로 잰 값. */
  height: number;
  /** 키보드가 지금 떠 있는가. 네이티브가 그대로 알려 준다. */
  visible: boolean;
};

/** 안드로이드가 심어 주는 전역. `SafeArea.kt` 와 같아야 한다. */
const BRIDGE = '__mupidSafeArea';

/** 값이 바뀌었다고 안드로이드가 던지는 이벤트. `SafeArea.kt` 와 같아야 한다. */
const CHANGED = 'mupid:safe-area';

type Bridge = { get(): string };

const bridge = (): Bridge | null => {
  if (typeof window === 'undefined') return null;
  const found = (window as unknown as Record<string, unknown>)[BRIDGE] as
    | Bridge
    | undefined;
  return typeof found?.get === 'function' ? found : null;
};

/**
 * 안드로이드는 **기기 픽셀** 로 잰다. CSS 픽셀로 바꾸는 일은 여기서 한다 —
 * `devicePixelRatio` 는 기기 밀도뿐 아니라 웹뷰가 잡은 배율까지 함께 담고 있어,
 * 네이티브 쪽의 `displayMetrics.density` 보다 이쪽이 정확하다.
 */
export function toCssPixels(insets: SafeArea, ratio: number): SafeArea {
  const scale = ratio > 0 ? ratio : 1;
  return {
    top: insets.top / scale,
    right: insets.right / scale,
    bottom: insets.bottom / scale,
    left: insets.left / scale,
  };
}

const size = (value: unknown): number =>
  typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : 0;

/** `readSafeArea` 가 돌려주는 것 — 시스템 바와 키보드를 한 번에 읽는다. */
export type NativeInsets = {
  safeArea: SafeArea;
  keyboard: KeyboardInset;
};

/**
 * 안드로이드가 건넨 JSON 한 줄을 읽는다. 앱이 아니면 `null`.
 *
 * 시스템 바와 키보드를 **한 번에** 읽는다. 통로가 하나뿐이라 두 번 읽으면 그 사이에
 * 값이 바뀌어 서로 다른 시점의 것을 섞을 수 있다.
 */
export function readNativeInsets(): NativeInsets | null {
  const native = bridge();
  if (!native) return null;
  try {
    const raw = JSON.parse(native.get()) as Record<string, unknown>;
    const ratio = window.devicePixelRatio;
    const scale = ratio > 0 ? ratio : 1;
    /*
     * `keyboardVisible` 이 거짓이면 높이를 0 으로 쓴다. 기기가 키보드를 내린 뒤에도
     * 마지막 인셋을 남기는 경우가 있어(제조사별 차이), 보이는가를 따로 본다.
     *
     * `visible` 은 **높이에서 유도하지 않는다.** 앱에서 높이는 늘 0 이라
     * (`KeyboardInset` 주석) `height > 0` 으로 정하면 키보드가 떠 있어도 늘 거짓이 된다.
     */
    const visible = raw.keyboardVisible === true;
    const height = visible ? size(raw.keyboardHeight) / scale : 0;
    return {
      safeArea: toCssPixels(
        {
          top: size(raw.top),
          right: size(raw.right),
          bottom: size(raw.bottom),
          left: size(raw.left),
        },
        ratio,
      ),
      keyboard: { height, visible },
    };
  } catch (error) {
    // 인셋 하나 못 읽은 것뿐이다. `env()` 기본값으로 그냥 두고 앱은 계속 돈다.
    console.warn('[safe-area]', error);
    return null;
  }
}

/** 시스템 바만 필요할 때. 없으면 `null`. */
export function readSafeArea(): SafeArea | null {
  return readNativeInsets()?.safeArea ?? null;
}

/** `<html>` 인라인 스타일로 적는다. `:root` 규칙보다 우선한다. */
export function applySafeArea(root: HTMLElement, insets: SafeArea): void {
  root.style.setProperty('--safe-area-inset-top', `${insets.top}px`);
  root.style.setProperty('--safe-area-inset-right', `${insets.right}px`);
  root.style.setProperty('--safe-area-inset-bottom', `${insets.bottom}px`);
  root.style.setProperty('--safe-area-inset-left', `${insets.left}px`);
}

/**
 * 값을 한 번 읽어 적고, 그 뒤 바뀔 때마다 다시 적는다. 돌려주는 함수를 부르면
 * 끊는다.
 *
 * 앱이 아니면 아무 일도 하지 않는다. 그래도 **구독은 걸어 둔다** — 웹뷰가
 * 화면보다 늦게 통로를 심는 경우에도 첫 인셋이 도착하는 순간 따라붙는다.
 * 화면 회전은 `resize` 로도 들어오는데, 그때는 `devicePixelRatio` 가 함께
 * 바뀌어 있을 수 있어 다시 읽는 편이 맞다.
 */
export function watchSafeArea(): () => void {
  if (typeof window === 'undefined') return () => {};
  const apply = () => {
    const insets = readSafeArea();
    if (insets) applySafeArea(document.documentElement, insets);
  };
  apply();
  window.addEventListener(CHANGED, apply);
  window.addEventListener('resize', apply);
  return () => {
    window.removeEventListener(CHANGED, apply);
    window.removeEventListener('resize', apply);
  };
}
