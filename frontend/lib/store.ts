/**
 * 아주 작은 관찰 가능 스토어.
 *
 * 상태를 React 밖(플레이어 엔진, API 세션)에서 굴리고 화면은 구독만 하도록
 * 한다. 라이브러리를 늘리지 않으려고 `useSyncExternalStore` 만 쓴다
 * (docs/server-integration.md — 현 단계에서 라이브러리 구성은 바꾸지 않는다).
 */

import { useSyncExternalStore } from 'react';

export type Store<T> = {
  get(): T;
  /** 부분 갱신. 값이 그대로면 알리지 않는다. */
  set(patch: Partial<T> | ((current: T) => Partial<T>)): void;
  subscribe(listener: () => void): () => void;
};

export function createStore<T extends object>(initial: T): Store<T> {
  let state = initial;
  const listeners = new Set<() => void>();

  return {
    get: () => state,
    set(patch) {
      const next = typeof patch === 'function' ? patch(state) : patch;
      let changed = false;
      for (const key of Object.keys(next) as (keyof T)[]) {
        if (!Object.is(state[key], next[key])) {
          changed = true;
          break;
        }
      }
      if (!changed) return;
      state = { ...state, ...next };
      for (const listener of listeners) listener();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

/**
 * 스토어를 구독한다. `select` 로 필요한 부분만 고르면 그 값이 바뀔 때만
 * 다시 그린다. 서버 렌더에서는 현재 값을 그대로 쓴다(브라우저 전용 상태라
 * 초기값과 같다).
 */
export function useStore<T extends object, S>(
  store: Store<T>,
  select: (state: T) => S,
): S {
  return useSyncExternalStore(
    store.subscribe,
    () => select(store.get()),
    () => select(store.get()),
  );
}
