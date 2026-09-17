import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

// 화면 쪽만 떼어 검사한다. Tauri 런타임도 기기 도네트워크도 필요 없다 —
// `invoke` 자리에 기록기를 꽂아 두고 **무엇을 언제 부르는지**만 본다
// (`tests/media-session.test.mjs` 와 같은 방식).
const bridgeStub =
  'data:text/javascript;base64,' +
  Buffer.from(
    `export const isTauri = () => globalThis.__isTauri ?? true;
     export const invoke = (command, args) => globalThis.__invoke(command, args);`,
  ).toString('base64');

const source = await readFile(
  new URL('../lib/platform/update.ts', import.meta.url),
  'utf8',
);
const js = ts
  .transpileModule(source, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.ESNext,
    },
  })
  .outputText.replace(/'\.\/bridge'/g, JSON.stringify(bridgeStub));

const {
  checkForUpdates,
  checkForUpdatesNow,
  installNativeUpdate,
  shouldSuggestNativeUpdate,
  UNKNOWN,
} = await import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'));

/** 원하는 상태를 돌려주도록 `invoke` 를 가로채고 호출을 기록한다. */
const stub = (responses = {}) => {
  const calls = [];
  globalThis.__isTauri = true;
  globalThis.__invoke = async (command, args) => {
    calls.push({ command, args });
    if (command in responses) {
      const value = responses[command];
      if (value instanceof Error) throw value;
      return typeof value === 'function' ? value() : value;
    }
    return null;
  };
  return calls;
};

const state = (overrides = {}) => ({
  localWebBuild: 1000,
  embeddedWebBuild: 1000,
  localNativeBuild: 1000,
  localVersionCode: 1,
  serverWebBuild: 1000,
  serverVersionCode: 1,
  webAvailable: false,
  nativeAvailable: false,
  webRejected: false,
  ...overrides,
});

// ─────────────────────────────────────────────────────────────
// 웹 갈래
// ─────────────────────────────────────────────────────────────

test('새 번들이 없으면 내려받지 않는다', async () => {
  const calls = stub({ update_status: state() });
  await checkForUpdates();
  // 상태만 두 번 읽고 `update_web` 은 부르지 않는다.
  assert.deepEqual(
    calls.map((call) => call.command),
    ['update_status', 'update_status'],
  );
});

test('새 번들이 있으면 내려받아 활성화한다', async () => {
  let seen = 0;
  const calls = stub({
    // 첫 호출은 "새 것 있음", 두 번째는 "적용됨" 을 돌려준다.
    update_status: () => {
      seen += 1;
      return seen === 1
        ? state({ webAvailable: true, serverWebBuild: 2000 })
        : state({ serverWebBuild: 2000 });
    },
    update_web: 2000,
  });

  const result = await checkForUpdates();
  assert.deepEqual(
    calls.map((call) => call.command),
    ['update_status', 'update_web', 'update_status'],
  );
  assert.equal(result.serverWebBuild, 2000);
});

test('거부 목록에 있는 값은 다시 내려받지 않는다', async () => {
  // 서버가 잘못된 번들을 올려 둔 상태다. 자동으로는 영영 다시 시도하지 않는다.
  const calls = stub({
    update_status: state({ webAvailable: true, webRejected: true }),
  });
  await checkForUpdates();
  assert.deepEqual(
    calls.map((call) => call.command),
    ['update_status', 'update_status'],
  );
});

test('사용자가 직접 누르면 거부 목록을 비우고 다시 시도한다', async () => {
  const calls = stub({
    update_status: state({ webAvailable: true, webRejected: true }),
    update_check_now: state({ serverWebBuild: 2000 }),
  });
  await checkForUpdatesNow();
  // 자동 경로와 달리 전용 커맨드를 쓴다 — Rust 가 거부 목록을 비우고
  // 내려받기·검증까지 한 번에 한다.
  assert.deepEqual(calls.map((call) => call.command), ['update_check_now']);
});

// ─────────────────────────────────────────────────────────────
// 실패는 조용해야 한다
// ─────────────────────────────────────────────────────────────

test('네트워크가 없으면 던지지 않고 null 을 준다', async () => {
  stub({ update_status: new Error('요청 실패') });
  // 던지면 화면에 오류가 뜬다. 업데이트 확인 실패는 사용자가 알 일이 아니다.
  assert.equal(await checkForUpdates(), null);
});

test('직접 확인도 실패하면 null 을 준다', async () => {
  stub({ update_check_now: new Error('요청 실패') });
  assert.equal(await checkForUpdatesNow(), null);
});

test('내려받기가 실패해도 던지지 않는다', async () => {
  const calls = stub({
    update_status: state({ webAvailable: true }),
    update_web: new Error('체크섬 불일치'),
  });
  const result = await checkForUpdates();
  assert.equal(result, null);
  // `update_web` 이 실패하면 두 번째 상태 조회로 가지 않는다.
  assert.deepEqual(
    calls.map((call) => call.command),
    ['update_status', 'update_web'],
  );
});

test('앱이 아니면 커맨드를 아예 부르지 않는다', async () => {
  const calls = stub({ update_status: state() });
  globalThis.__isTauri = false;
  assert.equal(await checkForUpdates(), null);
  assert.equal(await checkForUpdatesNow(), null);
  await installNativeUpdate();
  assert.equal(calls.length, 0, '웹에서는 아무 일도 일어나지 않아야 한다');
});

// ─────────────────────────────────────────────────────────────
// 네이티브 갈래
// ─────────────────────────────────────────────────────────────

test('설치 유도는 실패를 삼키지 않는다', async () => {
  // 사용자가 직접 누른 일이다. 아무 일도 일어나지 않으면 왜인지 알 수 없다.
  stub({ update_native: new Error('이 출처의 앱 설치를 허용해 주세요') });
  await assert.rejects(() => installNativeUpdate(), /허용해 주세요/);
});

test('설치 유도는 인자 없이 커맨드를 부른다', async () => {
  const calls = stub({ update_native: null });
  await installNativeUpdate();
  assert.deepEqual(calls, [{ command: 'update_native', args: undefined }]);
});

test('새 APK 가 있을 때만 안내한다', async () => {
  assert.equal(shouldSuggestNativeUpdate(state({ nativeAvailable: true })), true);
  assert.equal(shouldSuggestNativeUpdate(state({ nativeAvailable: false })), false);
  // 확인에 실패했으면 모르는 상태다. 안내하지 않는다.
  assert.equal(shouldSuggestNativeUpdate(null), false);
  assert.equal(shouldSuggestNativeUpdate(UNKNOWN), false);
});

// ─────────────────────────────────────────────────────────────
// Rust 와의 계약
// ─────────────────────────────────────────────────────────────

test('UpdateState 의 필드 이름이 Rust 와 짝을 이룬다', async () => {
  const rust = await readFile(
    new URL('../../app/src/update_net.rs', import.meta.url),
    'utf8',
  );
  const block = rust.slice(
    rust.indexOf('pub struct UpdateState'),
    rust.indexOf('\n}', rust.indexOf('pub struct UpdateState')),
  );

  // Rust 는 camelCase 로 내보낸다(`rename_all`). 화면이 읽는 이름과 같아야 한다.
  const expected = [
    'local_web_build',
    'embedded_web_build',
    'local_native_build',
    'local_version_code',
    'server_web_build',
    'server_version_code',
    'web_available',
    'native_available',
    'web_rejected',
  ];
  for (const field of expected) {
    assert.match(block, new RegExp(`pub ${field}:`), `Rust 에 ${field} 가 없다`);
  }

  // 화면 쪽 타입도 같은 이름(camelCase)을 갖고 있어야 한다.
  for (const field of expected) {
    const camel = field.replace(/_([a-z])/g, (_, c) => c.toUpperCase());
    assert.match(source, new RegExp(`\\b${camel}\\b`), `화면에 ${camel} 가 없다`);
  }
});

test('부르는 커맨드 이름이 Rust 에 실제로 등록돼 있다', async () => {
  const lib = await readFile(new URL('../../app/src/lib.rs', import.meta.url), 'utf8');
  for (const command of ['update_status', 'update_web', 'update_check_now', 'update_native']) {
    assert.match(
      lib,
      new RegExp(`commands::${command}\\b`),
      `${command} 가 invoke_handler 에 등록되지 않았다`,
    );
    assert.match(source, new RegExp(`invoke<[^>]*>\\('${command}'\\)`), `화면이 ${command} 를 안 부른다`);
  }
});
