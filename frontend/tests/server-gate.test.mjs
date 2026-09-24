import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { isServerUp } from '../lib/server-health.ts';

/*
 * 서버에 닿지 않으면 서비스를 열지 않는다 (S15P21A204-444).
 * 판정 규칙과, 그 판정이 화면 전체를 감싸는지 본다.
 */
const read = (path) => readFile(new URL(`../${path}`, import.meta.url), 'utf8');

test('UP 일 때만 서버가 살아 있다고 본다', async () => {
  assert.equal(await isServerUp(async () => ({ status: 'UP' })), true);
  assert.equal(await isServerUp(async () => ({ status: 'DOWN' })), false);
  assert.equal(await isServerUp(async () => ({ status: 'OUT_OF_SERVICE' })), false);
  assert.equal(await isServerUp(async () => null), false);
});

test('닿지 않거나 503 이면 던지지 않고 거짓이다', async () => {
  assert.equal(await isServerUp(async () => { throw new TypeError('Failed to fetch'); }), false);
  const unavailable = Object.assign(new Error('요청에 실패했어요. (503)'), { status: 503 });
  assert.equal(await isServerUp(async () => { throw unavailable; }), false);
});

test('루트 레이아웃이 화면 전체를 게이트 안에 둔다', async () => {
  const layout = await read('app/layout.tsx');
  assert.match(layout, /<ServerGate>\{children\}<\/ServerGate>/);
});

test('게이트는 통과하기 전에 자식을 그리지 않고, 닫는 길이 없다', async () => {
  const gate = await read('components/mupid/server-gate.tsx');
  assert.match(gate, /if \(state === 'up'\) return <>\{children\}<\/>;/);
  assert.match(gate, /onOpenChange=\{\(\) => \{\}\}/);
  assert.doesNotMatch(gate, /AlertDialogCancel/);
});

test('앱이 기기에 남긴 서버 응답으로 화면을 채우는 길이 없다', async () => {
  for (const path of ['lib/stream-catalog.ts', 'lib/music-analysis.ts', 'lib/platform/index.ts']) {
    assert.doesNotMatch(await read(path), /withCache/, path);
  }
});
