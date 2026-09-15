import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

// 알림·잠금화면에 올려보내는 쪽만 떼어 검사한다. Tauri 런타임도 안드로이드도
// 필요 없다 — `invoke` 자리에 기록기를 꽂아 두고 **무엇을 언제 보내는지**만 본다.
const bridgeStub =
  'data:text/javascript;base64,' +
  Buffer.from(
    `export const isTauri = () => true;
     export const invoke = (command, args) => globalThis.__invoke(command, args);
     export const listen = async (event, handler) => {
       globalThis.__listen = { event, handler };
       return () => { globalThis.__listen = null; };
     };`,
  ).toString('base64');

const source = await readFile(
  new URL('../lib/platform/media-session.ts', import.meta.url),
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

const { createMediaSession, onMediaControl, shouldPush } = await import(
  'data:text/javascript;base64,' + Buffer.from(js).toString('base64')
);

/** `invoke` 를 가로채고 받은 호출을 기록한다. */
const stub = () => {
  const calls = [];
  globalThis.__invoke = async (command, args) => {
    calls.push({ command, args });
  };
  return calls;
};

const playing = (overrides = {}) => ({
  title: '별 헤는 밤',
  artist: '오순도순',
  album: '첫 번째 밤',
  durationSec: 214,
  positionSec: 0,
  playing: true,
  artwork: '/api/v1/storage/image/ALBUM/1',
  canPrevious: true,
  canNext: true,
  ...overrides,
});

// ─────────────────────────────────────────────────────────────
// 무엇을 보내는가
// ─────────────────────────────────────────────────────────────

test('first update reaches the shell as a media_session_update', async () => {
  const calls = stub();
  const session = createMediaSession({ now: () => 0 });
  await session.update(playing());
  assert.equal(calls.length, 1);
  assert.equal(calls[0].command, 'media_session_update');
  assert.equal(calls[0].args.state.title, '별 헤는 밤');
  // 앨범 아트는 서버가 준 주소 그대로 넘긴다. `mupidimg` 변환은 웹뷰 전용이다.
  assert.equal(calls[0].args.state.artwork, '/api/v1/storage/image/ALBUM/1');
});

test('clearing only speaks up when something was shown', async () => {
  const calls = stub();
  const session = createMediaSession({ now: () => 0 });
  await session.clear();
  assert.equal(calls.length, 0, '보여 준 적이 없으면 거둘 것도 없다');

  await session.update(playing());
  await session.clear();
  assert.deepEqual(
    calls.map((call) => call.command),
    ['media_session_update', 'media_session_clear'],
  );
});

// ─────────────────────────────────────────────────────────────
// 언제 보내는가 — 안드로이드가 스스로 굴리는 위치를 다시 보내지 않는다
// ─────────────────────────────────────────────────────────────

test('a position that matches the extrapolated one is not sent again', async () => {
  const calls = stub();
  let clock = 0;
  const session = createMediaSession({ now: () => clock });
  await session.update(playing({ positionSec: 10 }));

  // 3초가 흐르고 재생 위치도 딱 3초 늘었다. 시스템이 이미 같은 값을 그리고 있다.
  clock = 3000;
  await session.update(playing({ positionSec: 13 }));
  assert.equal(calls.length, 1);
});

test('a position that drifted past the tolerance is corrected', async () => {
  const calls = stub();
  let clock = 0;
  const session = createMediaSession({ now: () => clock });
  await session.update(playing({ positionSec: 10 }));

  // 버퍼를 기다리느라 3초 동안 위치가 0.5초밖에 못 갔다. 막대를 고쳐 준다.
  clock = 3000;
  await session.update(playing({ positionSec: 10.5 }));
  assert.equal(calls.length, 2);
  assert.equal(calls[1].args.state.positionSec, 10.5);
});

test('a paused player does not get its position extrapolated', () => {
  const previous = { state: playing({ playing: false, positionSec: 10 }), at: 0 };
  // 멈춰 있는 동안에는 시계가 흘러도 위치가 그대로다. 그 상태로 다시 보내지 않는다.
  assert.equal(shouldPush(previous, playing({ playing: false, positionSec: 10 }), 9000), false);
  assert.equal(shouldPush(previous, playing({ playing: false, positionSec: 40 }), 9000), true);
});

test('pausing, skipping and losing a neighbour all go through', () => {
  const previous = { state: playing({ positionSec: 10 }), at: 0 };
  const at = 0;
  assert.equal(shouldPush(previous, playing({ positionSec: 10, playing: false }), at), true);
  assert.equal(shouldPush(previous, playing({ positionSec: 10, title: '다른 곡' }), at), true);
  assert.equal(shouldPush(previous, playing({ positionSec: 10, artwork: null }), at), true);
  assert.equal(shouldPush(previous, playing({ positionSec: 10, canNext: false }), at), true);
  assert.equal(shouldPush(previous, playing({ positionSec: 10 }), at), false);
});

// ─────────────────────────────────────────────────────────────
// 돌아오는 방향
// ─────────────────────────────────────────────────────────────

test('buttons arrive as an action with the seek target in seconds', async () => {
  const seen = [];
  const stop = onMediaControl((control) => seen.push(control));
  // `listen` 은 비동기라 구독이 붙기를 한 박자 기다린다.
  await Promise.resolve();
  await Promise.resolve();

  assert.equal(globalThis.__listen.event, 'media-session://control');
  globalThis.__listen.handler({ action: 'seek', positionMs: 96500 });
  globalThis.__listen.handler({ action: 'next', positionMs: -1 });
  assert.deepEqual(seen, [
    { action: 'seek', positionSec: 96.5 },
    { action: 'next', positionSec: 0 },
  ]);

  stop();
  assert.equal(globalThis.__listen, null);
});
