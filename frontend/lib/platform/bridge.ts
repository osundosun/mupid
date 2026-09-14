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
