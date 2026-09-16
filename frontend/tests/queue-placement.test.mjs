import test from 'node:test';
import assert from 'node:assert/strict';
import { placeInQueue, toQueueEntries } from '../lib/queue.ts';

// 큐의 규칙은 decision/0022 에 있다. 여기서 고정하는 것은 회의로 정한 세 가지다 —
// 중복 금지(다시 담으면 옮기기), 담을 자리를 사용자가 고르는 것, 그리고
// '재생 중인 곡 다음' 인데 듣는 곡이 없을 때의 대체 동작.

const ids = (placement) => placement.entries.map((entry) => entry.trackId);

test('맨 아래가 기본 자리다', () => {
  const queue = toQueueEntries([1, 2, 3]);
  assert.deepEqual(ids(placeInQueue(queue, 9, 'bottom', null, 100)), [1, 2, 3, 9]);
});

test('맨 위로도 담을 수 있다', () => {
  const queue = toQueueEntries([1, 2, 3]);
  assert.deepEqual(ids(placeInQueue(queue, 9, 'top', null, 100)), [9, 1, 2, 3]);
});

test('재생 중인 곡 다음에 담는다', () => {
  const queue = toQueueEntries([1, 2, 3]);
  const current = queue[1].uid; // 2번 곡을 듣는 중
  assert.deepEqual(ids(placeInQueue(queue, 9, 'afterCurrent', current, 100)), [1, 2, 9, 3]);
});

test('재생 중인 곡이 없으면 맨 아래로 내려가고 그 사실을 알린다', () => {
  const queue = toQueueEntries([1, 2, 3]);
  const placed = placeInQueue(queue, 9, 'afterCurrent', null, 100);

  assert.equal(placed.fellBack, true, '대체 동작이 일어났음을 알려야 화면이 안내할 수 있다');
  assert.deepEqual(ids(placed), [1, 2, 3, 9]);
});

test('듣던 자리가 큐에서 빠진 뒤여도 맨 아래로 내려간다', () => {
  // 재생 중인 곡을 목록에서 빼면 자리 번호만 남고 그 자리는 없다.
  const queue = toQueueEntries([1, 2, 3]);
  const placed = placeInQueue(queue, 9, 'afterCurrent', 'q-사라진-자리', 100);

  assert.equal(placed.fellBack, true);
  assert.deepEqual(ids(placed), [1, 2, 3, 9]);
});

test('같은 곡을 두 번 담지 않는다 — 대신 새 자리로 옮긴다', () => {
  const queue = toQueueEntries([1, 2, 3]);
  const placed = placeInQueue(queue, 1, 'bottom', null, 100);

  assert.equal(placed.moved, true, '옮긴 것임을 알려야 안내 문구를 가를 수 있다');
  assert.deepEqual(ids(placed), [2, 3, 1]);
  assert.equal(new Set(ids(placed)).size, ids(placed).length, '큐에 같은 곡이 두 번 있으면 안 된다');
});

test('옮길 때도 고른 자리를 따른다', () => {
  const queue = toQueueEntries([1, 2, 3]);
  assert.deepEqual(ids(placeInQueue(queue, 3, 'top', null, 100)), [3, 1, 2]);
});

test('옮긴 자리는 새 자리 번호를 받는다', () => {
  const queue = toQueueEntries([1, 2, 3]);
  const before = queue[0].uid;
  const placed = placeInQueue(queue, 1, 'bottom', null, 100);

  assert.notEqual(placed.uid, before, '자리 번호가 그대로면 화면이 줄을 옮기지 않고 놔둔다');
  assert.equal(placed.entries.at(-1).uid, placed.uid);
});

test('맨 아래로 담아 가득 차면 맨 위가 밀려난다', () => {
  const queue = toQueueEntries([1, 2, 3]);
  const placed = placeInQueue(queue, 9, 'bottom', null, 3);

  assert.equal(placed.evictedTrackId, 1, '아래로 쌓으면 위쪽이 가장 오래 묵는다');
  assert.deepEqual(ids(placed), [2, 3, 9]);
});

test('맨 위로 담아 가득 차면 맨 아래가 밀려난다', () => {
  const queue = toQueueEntries([1, 2, 3]);
  const placed = placeInQueue(queue, 9, 'top', null, 3);

  assert.equal(placed.evictedTrackId, 3);
  assert.deepEqual(ids(placed), [9, 1, 2]);
});

test('재생 중인 곡은 밀려나지 않는다', () => {
  const queue = toQueueEntries([1, 2, 3]);
  const current = queue[0].uid; // 밀려날 자리인 맨 위를 듣는 중
  const placed = placeInQueue(queue, 9, 'bottom', current, 3);

  assert.equal(placed.evictedTrackId, 2, '듣고 있는 곡 대신 그 다음으로 오래된 곡을 뺀다');
  assert.deepEqual(ids(placed), [1, 3, 9]);
});

test('방금 담은 곡이 도로 밀려나지 않는다', () => {
  // 맨 위로 담으면 퇴출은 아래쪽에서 오는데, 방금 놓은 자리가 거기 있으면 안 된다.
  const queue = toQueueEntries([1, 2, 3]);
  const current = queue[2].uid;
  const placed = placeInQueue(queue, 9, 'top', current, 3);

  assert.notEqual(placed.evictedTrackId, 9, '담자마자 빠지면 담기 버튼이 헛도는 것처럼 보인다');
  assert.ok(ids(placed).includes(9));
});

test('가득 찬 큐에서 이미 담긴 곡을 옮기면 아무도 밀려나지 않는다', () => {
  // 자리를 옮기는 것뿐이라 곡 수가 늘지 않는다.
  const queue = toQueueEntries([1, 2, 3]);
  const placed = placeInQueue(queue, 1, 'bottom', null, 3);

  assert.equal(placed.evictedTrackId, null);
  assert.deepEqual(ids(placed), [2, 3, 1]);
});

test('원래 배열을 건드리지 않는다', () => {
  const queue = toQueueEntries([1, 2, 3]);
  placeInQueue(queue, 9, 'top', null, 3);
  assert.deepEqual(queue.map((e) => e.trackId), [1, 2, 3]);
});
