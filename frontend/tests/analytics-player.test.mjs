import test from 'node:test';
import assert from 'node:assert/strict';
import { createTracker } from '../lib/analytics/tracker.ts';
import { bindPlayer } from '../lib/analytics/player.ts';

/**
 * 재생기 스토어를 흉내 낸다. `lib/store.ts` 와 같은 의미 — 값이 바뀔 때만 알리고
 * 구독자를 등록 순서대로 부른다.
 */
function fakePlayer(initial = { status: 'idle', musicId: null, positionSec: 0, error: null }) {
  let state = initial;
  const listeners = new Set();
  return {
    store: {
      get: () => state,
      subscribe: (fn) => {
        listeners.add(fn);
        return () => listeners.delete(fn);
      },
    },
    move(patch) {
      state = { ...state, ...patch };
      for (const fn of listeners) fn();
    },
  };
}

const harness = () => {
  const tracker = createTracker({ storage: null, transport: async () => {} });
  const player = fakePlayer();
  const stop = bindPlayer(player, tracker);
  return { tracker, player, stop, types: () => tracker.peek().map((e) => e.event_type) };
};

test('첫 재생은 재개가 아니다 — 한 번 멈춘 뒤부터 PLAY_RESUME 이다', () => {
  const { tracker, player, stop, types } = harness();
  tracker.startPlay({ trackId: 1001, entryPath: 'RECOMMEND' });
  player.move({ status: 'loading', musicId: 1001 });
  player.move({ status: 'buffering' });
  player.move({ status: 'playing' });
  assert.deepEqual(types(), ['PLAY_START']);

  player.move({ status: 'paused', positionSec: 12 });
  player.move({ status: 'playing' });
  assert.deepEqual(types(), ['PLAY_START', 'PLAY_PAUSE', 'PLAY_RESUME']);
  assert.equal(tracker.peek()[1].position_ms, 12_000);
  stop();
});

test('재생 중에 버퍼가 떨어진 것만 PLAY_STALL 이다 — 곡을 새로 걸 때는 아니다', () => {
  const { tracker, player, stop, types } = harness();
  tracker.startPlay({ trackId: 1001, entryPath: 'SEARCH' });
  player.move({ status: 'loading', musicId: 1001 });
  player.move({ status: 'buffering' });
  assert.deepEqual(types(), ['PLAY_START']);

  player.move({ status: 'playing' });
  player.move({ status: 'buffering', positionSec: 30 });
  assert.deepEqual(types(), ['PLAY_START', 'PLAY_STALL']);
  stop();
});

test('끝까지 들은 것은 PLAY_END 이고 한 곡에 한 번만 남는다', () => {
  const { tracker, player, stop, types } = harness();
  tracker.startPlay({ trackId: 1001, entryPath: 'PLAYLIST' });
  player.move({ status: 'playing', musicId: 1001 });
  player.move({ status: 'ended', positionSec: 180 });
  // 엔진이 종료 뒤에 멈춤 상태를 한 번 더 흘려도 PLAY_PAUSE 로 세지 않는다.
  player.move({ status: 'paused' });
  player.move({ status: 'ended' });

  assert.deepEqual(types(), ['PLAY_START', 'PLAY_END']);
  assert.equal(tracker.peek()[1].position_ms, 180_000);
  assert.equal(tracker.playId, null);
  stop();
});

test('재생 실패는 이유와 함께 남는다 — 안 들린 것을 안 좋아한 것으로 세지 않기 위해서다', () => {
  const { tracker, player, stop } = harness();
  tracker.startPlay({ trackId: 1001, entryPath: 'SEARCH' });
  player.move({ status: 'playing', musicId: 1001 });
  player.move({ status: 'error', positionSec: 5, error: '음원을 불러오지 못했어요.' });

  const failure = tracker.peek().at(-1);
  assert.equal(failure.event_type, 'PLAY_ERROR');
  assert.equal(failure.position_ms, 5000);
  assert.equal(failure.reason, '음원을 불러오지 못했어요.');
  stop();
});

test('구독을 끊으면 하트비트도 이벤트도 더는 남지 않는다', () => {
  const { tracker, player, stop, types } = harness();
  tracker.startPlay({ trackId: 1001, entryPath: 'SEARCH' });
  player.move({ status: 'playing', musicId: 1001 });
  stop();
  player.move({ status: 'paused', positionSec: 9 });
  assert.deepEqual(types(), ['PLAY_START']);
});
