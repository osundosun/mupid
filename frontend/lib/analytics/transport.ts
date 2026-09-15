import type { ListenEvent } from './types';

/**
 * 배치 하나를 실제로 내보내는 방법. 실패하면 throw 해야 큐가 이벤트를 되돌려 놓는다.
 *
 * GA4 는 `navigator.sendBeacon` 을 쓰지만 우리는 기본이 `fetch(keepalive)` 다.
 * sendBeacon 은 커스텀 헤더를 붙일 수 없어 `Authorization` 을 실을 수 없기 때문이다.
 * keepalive 는 헤더를 허용하고 페이지가 닫히는 중에도 전송을 이어간다 — 대신 요청
 * 본문이 64KB 로 제한되므로 배치 크기를 작게 잡는다.
 */
export type Transport = (events: ListenEvent[], leaving: boolean) => Promise<void>;

/**
 * 다시 보내도 결과가 같은 실패. 큐는 이 배치를 **되돌리지 않고 버린다.**
 *
 * 되돌리면 같은 배치가 5초마다 영원히 다시 나가고, 그 앞에 막혀 뒤따르는 이벤트도
 * 전부 나가지 못한다. 큐가 상한(`STORE_LIMIT`)에 닿으면 오래된 것부터 밀려나므로
 * 결국 **고칠 수 없는 한 배치 때문에 고칠 수 있는 이벤트까지 잃는다.** 한 배치를
 * 포기하는 쪽이 싸다.
 *
 * `permanent` 필드가 큐와의 계약이다. `tracker.ts` 는 이 모듈에서 값을 import 하지
 * 않으므로(확장자 없는 상대 경로를 `node --test` 가 해석하지 못한다) `instanceof`
 * 가 아니라 이 필드를 본다.
 */
export class PermanentTransportError extends Error {
  readonly permanent = true;

  constructor(readonly status: number) {
    super(`이벤트 전송 실패(재시도하지 않음): ${status}`);
    this.name = 'PermanentTransportError';
  }
}

/**
 * 이 상태 코드는 되돌려 두고 다시 보낸다.
 *
 *   401  아직 토큰이 없거나 만료됐다. 다른 요청이 토큰을 갱신해 두면 다음 배치에서 나간다.
 *   408  요청 타임아웃.
 *   429  잠시 뒤에 다시 오라는 뜻이다.
 *
 * 나머지 4xx 는 우리가 보낸 것이 규약에 어긋난다는 뜻이라 다시 보내도 같은 답이 온다.
 * 5xx 는 서버 사정이므로 전부 재시도한다.
 */
const RETRYABLE = new Set([401, 408, 429]);

export type HttpTransportOptions = {
  url?: string;
  /**
   * 매 배치마다 호출한다. 액세스 토큰을 어디에 둘지가 아직 정해지지 않아
   * (knowledge/open-questions C1) SDK 는 보관 위치를 모른 채 값만 받는다.
   */
  authorization?: () => string | undefined;
};

export function createHttpTransport(options: HttpTransportOptions = {}): Transport {
  const url = options.url ?? '/api/v1/events';
  return async (events, leaving) => {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    const token = options.authorization?.();
    if (token) headers.Authorization = token;
    const response = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify({ events }),
      credentials: 'include',
      // 페이지를 떠나는 중이면 keepalive 로 넘긴다. 평소에는 붙이지 않는다 —
      // keepalive 요청은 브라우저가 전체 용량을 따로 제한해서 아껴 써야 한다.
      keepalive: leaving,
    });
    if (response.ok) return;
    // 네트워크 자체가 끊긴 경우는 fetch 가 던지므로 여기 오지 않는다 — 그건 재시도한다.
    if (response.status >= 400 && response.status < 500 && !RETRYABLE.has(response.status)) {
      throw new PermanentTransportError(response.status);
    }
    throw new Error(`이벤트 전송 실패: ${response.status}`);
  };
}
