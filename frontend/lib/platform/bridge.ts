/**
 * 앱 셸(Tauri)과 이야기하는 통로.
 *
 * 웹 빌드에서도 이 파일은 함께 묶이지만 `@tauri-apps/api` 는 **부를 때만**
 * 불러온다. 정적으로 import 하면 브라우저에서도 내려받게 되고, 그 안의
 * `window.__TAURI_INTERNALS__` 접근이 없는 환경에서 먼저 터진다.
 */

type InvokeFn = <T>(
  command: string,
  args?: Record<string, unknown>,
) => Promise<T>;

type ListenFn = <T>(
  event: string,
  handler: (message: { payload: T }) => void,
) => Promise<() => void>;

/**
 * 지금 앱 셸 안에서 도는가.
 *
 * Tauri 는 웹뷰가 뜰 때 이 전역을 먼저 심는다. 사용자 에이전트 문자열로
 * 알아내려 하면 안드로이드 크롬과 구분되지 않는다.
 */
export const isTauri = (): boolean =>
  typeof window !== 'undefined' && '__TAURI_INTERNALS__' in window;

let loading: Promise<InvokeFn> | null = null;

const load = (): Promise<InvokeFn> => {
  loading ??= import('@tauri-apps/api/core').then(
    (module) => module.invoke as InvokeFn,
  );
  return loading;
};

export async function invoke<T>(
  command: string,
  args?: Record<string, unknown>,
): Promise<T> {
  const fn = await load();
  return fn<T>(command, args);
}

let listening: Promise<ListenFn> | null = null;

/**
 * Rust 가 웹뷰로 보내는 이벤트를 받는다. 돌려주는 함수를 부르면 끊는다.
 *
 * `invoke` 와 달리 **Rust 가 먼저 말을 거는** 통로다. 알림·잠금화면의 버튼처럼
 * 화면 밖에서 벌어진 일이 이리로 들어온다.
 */
export async function listen<T>(
  event: string,
  handler: (payload: T) => void,
): Promise<() => void> {
  listening ??= import('@tauri-apps/api/event').then(
    (module) => module.listen as ListenFn,
  );
  const fn = await listening;
  return fn<T>(event, (message) => handler(message.payload));
}

/**
 * 안드로이드 뒤로가기(소프트버튼·제스처)를 받는다 (S15P21A204-410).
 *
 * 돌려주는 함수를 부르면 **리스너를 뗀다.** 떼는 것이 핵심이다 — 앱 셸의
 * `AppPlugin` 은 리스너의 유무로 갈려서, 없으면 웹뷰 히스토리 뒤로가기(그
 * 바닥에서는 앱 종료)로 내려가고 있으면 이 통로로 웹에 넘긴다.
 * (`lib/back-button.ts` 가 "열려 있는 오버레이가 있을 때만" 붙이는 이유다.)
 *
 * **앱 셸 밖에서는 아무 일도 하지 않는다.** 이벤트를 붙이려 들면
 * `__TAURI_INTERNALS__` 가 없는 브라우저에서 그대로 터지므로, 웹·데스크톱은
 * 빈 정리 함수를 받는다 — 부르는 쪽이 분기할 필요가 없다.
 *
 * 정적 import 를 피하는 이유는 아래 통로들과 같다: 웹 번들에도 함께 묶이는
 * 파일이라, 최상단에서 불러오면 브라우저에서도 내려받게 된다.
 */
export async function onBackButtonPress(
  handler: () => void,
): Promise<() => void> {
  if (!isTauri()) return () => {};
  try {
    const { onBackButtonPress: attach } = await import('@tauri-apps/api/app');
    const listener = await attach(() => handler());
    return () => {
      void listener.unregister().catch(() => {});
    };
  } catch {
    // 붙이지 못했으면 아무것도 하지 않는다 — 뒤로가기는 네이티브 기본
    // 동작(히스토리 뒤로가기, 바닥이면 앱 종료)으로 남는다. 웹 회귀가 없다.
    return () => {};
  }
}
