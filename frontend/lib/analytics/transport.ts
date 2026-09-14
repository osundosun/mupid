import { createPlatformTransport } from '../platform';
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
    if (!response.ok) throw new Error(`이벤트 전송 실패: ${response.status}`);
  };
}

/**
 * 앱용 전송.
 *
 * 앱에서는 HTTP 가 전부 Rust 에서 돈다. `keepalive` 는 브라우저가 페이지를
 * 버리는 순간에도 요청을 살려 두려고 쓰는 장치인데, 앱 웹뷰는 그런 식으로
 * 사라지지 않고 Rust 쪽 요청은 화면과 수명이 다르다. 그래서 `leaving` 을 보지
 * 않는다 — 64KB 본문 제한도 함께 없어진다.
 */
export function createNativeTransport(options: HttpTransportOptions = {}): Transport {
  const url = options.url ?? '/api/v1/events';
  const transport = createPlatformTransport();
  return async (events) => {
    const headers: Record<string, string> = { 'Content-Type': 'application/json' };
    const token = options.authorization?.();
    if (token) headers.Authorization = token;
    const response = await transport.send({
      method: 'POST',
      url,
      headers,
      body: JSON.stringify({ events }),
      responseType: 'none',
    });
    if (response.status < 200 || response.status >= 300) {
      throw new Error(`이벤트 전송 실패: ${response.status}`);
    }
  };
}
