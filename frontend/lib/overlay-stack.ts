/**
 * 지금 열려 있는 오버레이(모달·팝업·독)의 스택 (S15P21A204-410).
 *
 * ## 왜 필요한가
 *
 * 안드로이드 소프트버튼(뒤로가기)이 모달을 닫지 않고 웹뷰 히스토리를 뒤로
 * 보냈다. 원인은 앱 셸 쪽에 있었다 — 웹이 `back-button` 리스너를 등록하지
 * 않으면 Tauri 의 `AppPlugin` 이 **무조건** `webView.goBack()` 으로 보낸다
 * (`tauri 2.11.5` 의 `mobile/android/src/main/java/app/tauri/AppPlugin.kt`).
 * 그런데 이 앱의 모달은 전부 React `useState` 라 히스토리와 아무 관계가 없고,
 * 그래서 뒤로가기가 되돌릴 주체가 아예 없었다.
 *
 * 그래서 "지금 무엇이 열려 있는가"만 여기에 모은다. **열림 상태 자체는 옮기지
 * 않는다** — 30개가 넘는 오버레이의 state 관리 방식을 한꺼번에 바꾸면 회귀가
 * 통제 불능이 된다. 각자 쓰던 `useState` 그대로 두고, 열려 있는 동안에만
 * "내가 열려 있다"고 등록한다(`hooks/use-dismissable-overlay.ts`).
 *
 * ## 규칙
 *
 * 뒤로가기는 **가장 위(최근에 연 것)** 를 닫는다. `closeTopOverlay()` 가
 * 아무것도 닫지 못했으면 그때만 히스토리로 내려간다(`lib/back-button.ts`).
 *
 * ## 스택 등록을 네이티브가 보는 이유
 *
 * 등록된 것이 하나도 없으면 네이티브 리스너를 **떼어 둔다.** 그래야
 * `AppPlugin` 이 자기 기본 경로 — 웹뷰 히스토리 뒤로가기, 그 바닥에서는
 * `activity.onBackPressed()`(앱 종료) — 로 내려간다. 리스너를 항상 달아 두면
 * 앱을 웹에서 닫아야 하는데, 그 통로는 ACL 로 막혀 있다(`app` 플러그인의
 * `generate_handler` 에 `exit` 이 없고 `@tauri-apps/api` 에도 `exit` 이 없다).
 */

/** 등록된 오버레이 하나. `close` 는 그 오버레이를 닫는 함수다. */
type Entry = {
  close: () => void;
};

/** 가장 아래가 먼저, 가장 위(최근에 연 것)가 마지막. */
let stack: Entry[] = [];

const depthListeners = new Set<(depth: number) => void>();

const notify = () => {
  for (const listener of depthListeners) listener(stack.length);
};

/**
 * 오버레이 하나를 스택에 올린다. 돌려주는 함수를 부르면 내린다.
 *
 * 등록은 **열려 있는 동안에만** 한다. 열림/닫힘을 그대로 반영하므로 화면이
 * 스스로 닫히든(폼 제출 뒤) 스택을 통해 닫히든 결과가 같다.
 *
 * 같은 오버레이가 다시 등록되면 스택에 두 번 쌓인다 — 화면 코드에서 열림
 * 전이(`open` 이 false→true)에만 부르므로 정상 흐름에서는 생기지 않는다.
 */
export function registerOverlay(close: () => void): () => void {
  const entry: Entry = { close };
  stack = [...stack, entry];
  notify();

  let live = true;
  return () => {
    if (!live) return;
    live = false;
    const next = stack.filter((one) => one !== entry);
    // 이미 빠졌으면(스택을 통해 닫혔다) 알릴 것이 없다.
    if (next.length === stack.length) return;
    stack = next;
    notify();
  };
}

/**
 * 가장 위의 오버레이를 닫는다. 닫았으면 true.
 *
 * **스택에서 먼저 뺀 뒤 닫는다.** 닫는 함수가 상태를 바꾸면 등록 해제가
 * 이어지는데, 그때 이미 빠져 있어야 아래 것을 잘못 닫지 않는다.
 */
export function closeTopOverlay(): boolean {
  const top = stack[stack.length - 1];
  if (!top) return false;
  stack = stack.slice(0, -1);
  notify();
  top.close();
  return true;
}

/** 지금 열려 있는 오버레이 개수. */
export const overlayDepth = (): number => stack.length;

/**
 * 스택 깊이가 바뀔 때마다 알린다.
 *
 * 네이티브 리스너를 붙일지 뗄지가 여기에 달려 있다
 * (`lib/back-button.ts`). 첫 값은 부르는 쪽이 `overlayDepth()` 로 한 번 읽는다 —
 * 이미 열려 있는 오버레이를 지나치지 않게.
 */
export function subscribeOverlayDepth(
  listener: (depth: number) => void,
): () => void {
  depthListeners.add(listener);
  return () => depthListeners.delete(listener);
}

/** 시험용. 앱 흐름에서는 부르지 않는다. */
export function resetOverlays(): void {
  stack = [];
  notify();
}
