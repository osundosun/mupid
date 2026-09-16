import test from 'node:test';
import assert from 'node:assert/strict';
import {
  queueEntry,
  queueIndexOf,
  removeQueueEntries,
  removeQueueEntry,
  reorderQueue,
  stepQueue,
  toQueueEntries,
  upcomingQueue,
} from '../lib/queue.ts';

// 화면이 보는 목록. 큐에서 건너뛴 곡을 걸러 낸 결과다.
const visible = (entries, ignored = []) =>
  entries.filter((entry) => !ignored.includes(entry.trackId));

test('같은 곡을 두 번 담으면 두 자리가 되고 자리 번호는 서로 다르다', () => {
  const queue = [...toQueueEntries([1, 2]), queueEntry(1)];
  assert.equal(queue.length, 3);
  assert.deepEqual(
    queue.map((entry) => entry.trackId),
    [1, 2, 1],
  );
  assert.equal(new Set(queue.map((entry) => entry.uid)).size, 3);
});

test('중복 중 한 자리만 빼면 나머지 자리는 남는다', () => {
  const queue = toQueueEntries([1, 2, 1]);
  const left = removeQueueEntry(queue, queue[2].uid);
  assert.deepEqual(
    left.map((entry) => entry.trackId),
    [1, 2],
  );
  assert.equal(left[0].uid, queue[0].uid);
  // 고르기 화면은 곡 단위로 체크하므로 그쪽에서는 두 자리가 함께 빠진다.
  const uids = queue.filter((entry) => entry.trackId === 1).map((entry) => entry.uid);
  assert.deepEqual(
    removeQueueEntries(queue, uids).map((entry) => entry.trackId),
    [2],
  );
});

test('재정렬하면 큐 순서가 요청한 순서와 정확히 같다', () => {
  const queue = toQueueEntries([10, 20, 30, 40]);
  const asked = [queue[2].uid, queue[0].uid, queue[3].uid, queue[1].uid];
  assert.deepEqual(
    reorderQueue(queue, asked).map((entry) => entry.uid),
    asked,
  );
});

test('건너뛴 곡이 섞여 있어도 보이는 줄만 요청한 순서로 놓이고, 감춘 자리는 제자리에 남는다', () => {
  const queue = toQueueEntries([10, 20, 30, 40]);
  const ignored = [20];
  const shown = visible(queue, ignored);
  // 화면에서 30 을 맨 위로 끌어 올린다.
  const asked = [shown[1].uid, shown[0].uid, shown[2].uid];
  const next = reorderQueue(queue, asked);
  assert.deepEqual(
    visible(next, ignored).map((entry) => entry.uid),
    asked,
  );
  // 건너뛴 곡은 큐 맨 뒤로 밀려나지 않는다. 2번째 칸 그대로다.
  assert.equal(next[1].trackId, 20);
  assert.equal(next.length, queue.length);
});

test('중복이 있어도 다음 자리 계산이 앞으로 되감기지 않는다', () => {
  const queue = toQueueEntries([1, 2, 1, 3]);
  // 뒤쪽 중복(3번째 칸)을 듣는 중이면 다음은 4번째 칸이다.
  assert.equal(queueIndexOf(queue, queue[2].uid), 2);
  assert.equal(stepQueue(queue, queue[2].uid, 1).uid, queue[3].uid);
  assert.equal(stepQueue(queue, queue[2].uid, -1).uid, queue[1].uid);
  // 앞쪽 중복을 듣는 중일 때만 2번째 칸으로 간다.
  assert.equal(stepQueue(queue, queue[0].uid, 1).uid, queue[1].uid);
  // 목록 끝에서는 한 바퀴 돌아 맨 앞으로.
  assert.equal(stepQueue(queue, queue[3].uid, 1).uid, queue[0].uid);
  // 목록 밖의 곡을 듣는 중이면 맨 앞에서 이어받는다.
  assert.equal(stepQueue(queue, null, 1).uid, queue[0].uid);
  assert.equal(stepQueue([], queue[0].uid, 1), null);
});

// 화면(QueueList)이 손잡이로 한 줄을 옮길 때 만드는 순서 그대로.
const moveVisible = (shown, uid, to) => {
  const uids = shown.map((entry) => entry.uid);
  const from = uids.indexOf(uid);
  const target = Math.max(0, Math.min(uids.length - 1, to));
  uids.splice(from, 1);
  uids.splice(target, 0, uid);
  return uids;
};

test('순서를 바꾸면 화면도 다음 곡도 바뀐 순서를 따른다', () => {
  const ignored = [20];
  let queue = toQueueEntries([10, 20, 30, 40, 50]);
  const shown = visible(queue, ignored);
  // 50(보이는 목록의 4번째)을 맨 위로 끌어 올린다.
  queue = reorderQueue(queue, moveVisible(shown, shown[3].uid, 0));
  const after = visible(queue, ignored);
  assert.deepEqual(
    after.map((entry) => entry.trackId),
    [50, 10, 30, 40],
  );
  // 50 을 듣는 중이라면 다음은 바뀐 순서대로 10 이다. 건너뛴 20 은 끼어들지 않는다.
  assert.equal(stepQueue(after, after[0].uid, 1).trackId, 10);
  assert.deepEqual(
    upcomingQueue(after, after[0].uid).map((entry) => entry.trackId),
    [10, 30, 40],
  );
});

test('미리 받아 둘 곡은 듣고 있는 자리 다음부터 센다', () => {
  const queue = toQueueEntries([1, 2, 3, 4]);
  assert.deepEqual(
    upcomingQueue(queue, queue[2].uid).map((entry) => entry.trackId),
    [4, 1, 2],
  );
  // 자리를 모르면 맨 앞부터 — 예전 동작 그대로다.
  assert.deepEqual(
    upcomingQueue(queue, null).map((entry) => entry.trackId),
    [1, 2, 3, 4],
  );
});
