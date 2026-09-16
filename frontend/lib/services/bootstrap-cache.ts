/**
 * 부트스트랩 결과를 React 트리 **밖**에 한 벌 둔다.
 *
 * 화면(`components/mupid-app.tsx`)이 다시 마운트돼도 "음악을 준비하고 있어요…"
 * 가 다시 뜨면 안 된다. 그 화면이 뜨는 동안 아래 `LoadedMupidApp` 이 통째로
 * 언마운트되고, 그때 현재 곡·재생 위치·재생 목록·셔플·반복이 전부 처음 값으로
 * 돌아간다. 값을 모듈 스코프에 두면 마운트·언마운트와 무관하게 살아 있으므로
 * 두 번째 마운트는 받아 둔 값을 곧바로 쓰고 카탈로그를 다시 받지 않는다.
 * (`lib/player` 의 엔진과 `lib/api/session` 의 세션이 이미 같은 자리를 쓴다.)
 *
 * **계정 번호를 열쇠로 한 벌만** 들고 있는다. 로그아웃하거나 계정을 바꾸면
 * 열쇠가 달라져 없는 것으로 치므로 앞사람의 좋아요·감상 기록이 다음 사람에게
 * 넘어가지 않고, 다음 부트스트랩이 이 자리를 덮어써 앞 계정의 값도 남지 않는다.
 * 비로그인은 0 번이다 (`components/mupid-app.tsx` 의 `accountId`).
 *
 * 서버 렌더에서는 쓰이지 않는다 — 쓰는 쪽이 `useEffect` 안에서만 채우기 때문에
 * 브라우저에서만 값이 들어간다.
 */

import { createStore } from '../store';
import type { Bootstrap } from './contracts';

type BootstrapCache = {
  /** 이 값이 어느 계정 몫인가. 아직 아무것도 받지 않았으면 null. */
  accountId: number | null;
  value: Bootstrap | null;
};

export const bootstrapCache = createStore<BootstrapCache>({
  accountId: null,
  value: null,
});

/** 이 계정 몫으로 받아 둔 값. 없거나 다른 계정 것이면 null. */
export function readBootstrap(accountId: number): Bootstrap | null {
  const cached = bootstrapCache.get();
  return cached.accountId === accountId ? cached.value : null;
}

export function writeBootstrap(accountId: number, value: Bootstrap): void {
  bootstrapCache.set({ accountId, value });
}

/** 받아 둔 값을 버린다. "다시 불러오기" 는 캐시를 지나쳐 서버로 가야 한다. */
export function clearBootstrap(): void {
  bootstrapCache.set({ accountId: null, value: null });
}
