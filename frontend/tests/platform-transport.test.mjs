import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

// 앱 전송 계층만 떼어 검사한다. Tauri 런타임도 실제 서버도 필요 없다 —
// `invoke` 자리에 우리가 만든 응답을 꽂아 두고, `Transport` 규약을 지키는지만 본다.
const bridgeStub =
  'data:text/javascript;base64,' +
  Buffer.from(
    `export const isTauri = () => true;
     export const invoke = (command, args) => globalThis.__invoke(command, args);`,
  ).toString('base64');

const source = await readFile(
  new URL('../lib/platform/transport.ts', import.meta.url),
  'utf8',
);
const js = ts
  .transpileModule(source, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.ESNext,
    },
  })
  .outputText.replace(
    "'../api/transport'",
    JSON.stringify(new URL('../lib/api/transport.ts', import.meta.url).href),
  )
  .replace(/'\.\/bridge'/g, JSON.stringify(bridgeStub));

const { createTauriTransport } = await import(
  'data:text/javascript;base64,' + Buffer.from(js).toString('base64')
);
const { TransportError } = await import(
  new URL('../lib/api/transport.ts', import.meta.url).href
);

/** Rust 가 돌려주는 모양: `[4바이트 길이][메타 JSON][본문]`. */
const frame = (status, headers, body) => {
  const meta = new TextEncoder().encode(JSON.stringify({ status, headers }));
  const payload = typeof body === 'string' ? new TextEncoder().encode(body) : body;
  const out = new Uint8Array(4 + meta.length + payload.length);
  new DataView(out.buffer).setUint32(0, meta.length, true);
  out.set(meta, 4);
  out.set(payload, 4 + meta.length);
  return out.buffer;
};

/** `invoke` 를 가로채고, 받은 요청을 기록해 돌려준다. */
const stub = (respond) => {
  const calls = [];
  globalThis.__invoke = async (command, args) => {
    calls.push({ command, args });
    return respond(command, args);
  };
  return calls;
};

const request = (overrides = {}) => ({
  method: 'GET',
  url: '/api/v1/user',
  headers: {},
  responseType: 'json',
  ...overrides,
});

test('framed responses become status, lowercase headers and parsed body', async () => {
  stub(() =>
    frame(200, { 'x-mupid-error': '' }, JSON.stringify({ data: { user_id: 7 } })),
  );
  const response = await createTauriTransport().send(request());
  assert.equal(response.status, 200);
  assert.deepEqual(response.headers, { 'x-mupid-error': '' });
  assert.deepEqual(response.data, { data: { user_id: 7 } });
});

// 오류 본문에 화면이 보여 줄 메시지가 들어 있다. 선언한 형식이 무엇이든 읽어야 한다.
test('error bodies are parsed as JSON even when the caller asked for bytes', async () => {
  stub(() =>
    frame(
      401,
      { 'x-mupid-error': 'MUPID_UNAUTHORIZED' },
      JSON.stringify({ code: 'MUPID_UNAUTHORIZED', message: '로그인이 필요해요.' }),
    ),
  );
  const response = await createTauriTransport().send(
    request({ responseType: 'arraybuffer' }),
  );
  assert.equal(response.status, 401);
  assert.equal(response.data.message, '로그인이 필요해요.');
});

test('successful binary responses stay bytes', async () => {
  const chunk = new Uint8Array([1, 2, 3, 4]);
  stub(() => frame(200, {}, chunk));
  const response = await createTauriTransport().send(
    request({ responseType: 'arraybuffer' }),
  );
  assert.deepEqual(new Uint8Array(response.data), chunk);
});

test('204 carries no body whatever the caller asked for', async () => {
  stub(() => frame(204, {}, ''));
  const response = await createTauriTransport().send(request());
  assert.equal(response.data, undefined);
});

test('binary and multipart bodies cross as base64, not number arrays', async () => {
  const calls = stub(() => frame(200, {}, '{}'));
  const transport = createTauriTransport();

  await transport.send(
    request({ method: 'POST', body: new Uint8Array([104, 105]) }),
  );
  assert.deepEqual(calls[0].args.request.body, {
    kind: 'binary',
    dataBase64: 'aGk=',
  });

  const form = new FormData();
  form.append('imageFile', new File([new Uint8Array([104, 105])], 'me.jpg', {
    type: 'image/jpeg',
  }));
  form.append('note', '안녕');
  await transport.send(request({ method: 'POST', body: form }));
  const parts = calls[1].args.request.body.parts;
  assert.equal(calls[1].args.request.body.kind, 'multipart');
  assert.deepEqual(parts[0], {
    name: 'imageFile',
    fileName: 'me.jpg',
    contentType: 'image/jpeg',
    dataBase64: 'aGk=',
  });
  // 파일이 아닌 필드에는 파일 이름을 붙이지 않는다. 붙이면 서버가 첨부로 읽는다.
  assert.equal(parts[1].fileName, undefined);
  assert.equal(
    Buffer.from(parts[1].dataBase64, 'base64').toString('utf8'),
    '안녕',
  );
});

test('an already aborted signal never reaches the shell', async () => {
  const calls = stub(() => frame(200, {}, '{}'));
  const controller = new AbortController();
  controller.abort();
  await assert.rejects(
    createTauriTransport().send(request({ signal: controller.signal })),
    (error) => error.name === 'AbortError',
  );
  assert.equal(calls.length, 0);
});

// 신호가 붙은 요청에만 식별자를 만든다. Rust 는 이 값이 없으면 취소 통로를 열지 않는다.
test('only cancellable requests carry an id', async () => {
  const calls = stub(() => frame(200, {}, '{}'));
  const transport = createTauriTransport();
  await transport.send(request());
  assert.equal(calls[0].args.request.requestId, undefined);

  await transport.send(request({ signal: new AbortController().signal }));
  assert.equal(typeof calls[1].args.request.requestId, 'string');
});

test('aborting mid-flight asks the shell to stop and reports a cancellation', async () => {
  const controller = new AbortController();
  const calls = [];
  let started = () => {};
  let failSend = () => {};
  const sent = new Promise((resolve) => {
    started = resolve;
  });
  globalThis.__invoke = (command) => {
    calls.push(command);
    if (command === 'http_abort') {
      // 셸이 요청을 끊으면 `http_send` 는 오류 문자열로 끝난다.
      failSend('요청이 취소됐어요.');
      return Promise.resolve();
    }
    return new Promise((_, fail) => {
      failSend = fail;
      started();
    });
  };
  const pending = createTauriTransport().send(request({ signal: controller.signal }));
  await sent;
  controller.abort();
  await assert.rejects(pending, (error) => error.name === 'AbortError');
  assert.ok(calls.includes('http_abort'));
});

// 본문을 만드는 동안 끊기면 요청은 나가지 않아야 한다. 이미 끊긴 신호에는
// `abort` 이벤트가 다시 오지 않으므로, 놓치면 아무도 못 멈추는 요청이 남는다.
test('a signal that fires while the body is being built stops the request', async () => {
  const calls = stub(() => frame(200, {}, '{}'));
  const controller = new AbortController();
  const form = new FormData();
  form.append('imageFile', new File([new Uint8Array(1024)], 'me.jpg'));

  const pending = createTauriTransport().send(
    request({ method: 'POST', body: form, signal: controller.signal }),
  );
  controller.abort();
  await assert.rejects(pending, (error) => error.name === 'AbortError');
  assert.equal(calls.length, 0);
});

test('a failing command surfaces as a transport error, not a raw string', async () => {
  stub(() => Promise.reject('네트워크 요청에 실패했어요: dns error'));
  await assert.rejects(
    createTauriTransport().send(request()),
    (error) =>
      error instanceof TransportError && /dns error/.test(error.message),
  );
});

test('a truncated frame is reported instead of being read as an empty body', async () => {
  stub(() => new Uint8Array([1, 2]).buffer);
  await assert.rejects(
    createTauriTransport().send(request()),
    (error) => error instanceof TransportError,
  );
});
