/*
 * 취소는 본문을 다 받을 때까지 유효해야 한다 (S15P21A204-434).
 *
 * `fetch` 는 헤더가 오면 곧바로 반환하고 본문은 그 뒤에 흐른다. 청크 오디오는
 * 100KB 남짓이라 **대부분의 시간이 본문 구간**인데, 취소 연결과 타임아웃이
 * 헤더까지만 걸려 있었다. 그래서 곡을 바꿔 `abort()` 를 불러도 듣던 곡의 청크는
 * 끝까지 내려받혔고, 그 응답이 곡이 바뀐 뒤에 도착해 플레이어를 헷갈리게 했다.
 */
import test from 'node:test';
import assert from 'node:assert/strict';

const { createFetchTransport } = await import(
  new URL('../lib/api/transport.ts', import.meta.url).href
);

/**
 * 헤더는 곧바로 주고 본문은 붙잡아 두는 fetch. 진짜 fetch 처럼 **넘겨받은
 * 신호가 끊기면 본문 읽기가 거절된다.**
 */
function slowBodyFetch() {
  const state = {};
  globalThis.fetch = async (_url, init) => {
    state.signal = init.signal;
    return {
      ok: true,
      status: 200,
      headers: { forEach() {} },
      arrayBuffer: () =>
        new Promise((_resolve, reject) => {
          init.signal.addEventListener(
            'abort',
            () => reject(new DOMException('Aborted', 'AbortError')),
            { once: true },
          );
        }),
    };
  };
  return state;
}

// 고장 나면 **영영 끝나지 않는** 요청이므로 시험에 한도를 둔다.
test('본문을 받는 중에 취소하면 요청이 그 자리에서 끊긴다', { timeout: 5000 }, async () => {
  const original = globalThis.fetch;
  try {
    slowBodyFetch();
    const controller = new AbortController();
    const sent = createFetchTransport().send({
      method: 'GET',
      url: 'https://example.test/api/v1/chunk/10/0',
      headers: {},
      responseType: 'arraybuffer',
      signal: controller.signal,
      timeoutMs: 30000,
    });
    // 헤더는 이미 왔고 본문만 남은 상태에서 곡이 바뀐다.
    await new Promise((resolve) => setTimeout(resolve, 0));
    controller.abort();
    await assert.rejects(sent, (error) => error?.name === 'AbortError');
  } finally {
    globalThis.fetch = original;
  }
});

test('본문이 오지 않으면 타임아웃이 요청을 끊는다', { timeout: 5000 }, async () => {
  const original = globalThis.fetch;
  try {
    slowBodyFetch();
    const sent = createFetchTransport().send({
      method: 'GET',
      url: 'https://example.test/api/v1/chunk/10/0',
      headers: {},
      responseType: 'arraybuffer',
      timeoutMs: 20,
    });
    await assert.rejects(sent, (error) => error?.name === 'AbortError');
  } finally {
    globalThis.fetch = original;
  }
});
