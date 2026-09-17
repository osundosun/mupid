import test from 'node:test';
import assert from 'node:assert/strict';
import { autoScrollDelta, AUTO_SCROLL_EDGE, AUTO_SCROLL_MAX_STEP } from '../lib/queue.ts';

// 끌어서 순서를 바꿀 때 목록이 저절로 따라 움직이는 규칙.
// 100곡을 담아 두면 화면에는 서너 줄만 보여서, 첫 줄을 맨 끝으로 보내려면
// 목록이 대신 밀려 줘야 한다.

const TOP = 200;
const BOTTOM = 600;
const delta = (y) => autoScrollDelta(y, TOP, BOTTOM);

test('가운데에서는 움직이지 않는다', () => {
  assert.equal(delta((TOP + BOTTOM) / 2), 0);
  assert.equal(delta(TOP + AUTO_SCROLL_EDGE + 1), 0);
  assert.equal(delta(BOTTOM - AUTO_SCROLL_EDGE - 1), 0);
});

test('아래 가장자리에 닿으면 아래로 민다', () => {
  assert.ok(delta(BOTTOM - 10) > 0);
});

test('위 가장자리에 닿으면 위로 민다', () => {
  assert.ok(delta(TOP + 10) < 0);
});

test('가장자리로 깊이 들어갈수록 빨라진다', () => {
  assert.ok(delta(BOTTOM - 5) > delta(BOTTOM - 40));
  assert.ok(delta(TOP + 5) < delta(TOP + 40));
});

test('아무리 멀리 벗어나도 한 프레임의 걸음은 정해져 있다', () => {
  // 손이 창 밖까지 나가도 목록이 순간이동하면 어디를 겨누는지 놓친다.
  assert.equal(delta(BOTTOM + 5000), AUTO_SCROLL_MAX_STEP);
  assert.equal(delta(TOP - 5000), -AUTO_SCROLL_MAX_STEP);
});
