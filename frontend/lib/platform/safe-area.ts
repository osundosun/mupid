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

/** 안드로이드가 건넨 JSON 한 줄을 읽는다. 앱이 아니면 `null`. */
export function readSafeArea(): SafeArea | null {
  const native = bridge();
  if (!native) return null;
  try {
    const raw = JSON.parse(native.get()) as Record<string, unknown>;
    return toCssPixels(
      {
        top: size(raw.top),
        right: size(raw.right),
        bottom: size(raw.bottom),
        left: size(raw.left),
      },
      window.devicePixelRatio,
    );
  } catch (error) {
    // 인셋 하나 못 읽은 것뿐이다. `env()` 기본값으로 그냥 두고 앱은 계속 돈다.
    console.warn('[safe-area]', error);
    return null;
  }
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
