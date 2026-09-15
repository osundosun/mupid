import test from 'node:test';
import assert from 'node:assert/strict';
import { createTracker } from '../lib/analytics/tracker.ts';

/** 시계를 고정하고 전송을 가로챈 트래커. 저장소는 쓰지 않는다. */
function harness() {
  const sent = [];
  let clock = 1_700_000_000_000;
  const tracker = createTracker({
    storage: null,
    now: () => clock,
    transport: async (events) => {
      sent.push(events);
    },
  });
  return { tracker, sent, tick: (delta) => (clock += delta), at: () => clock };
}

const types = (tracker) => tracker.peek().map((e) => e.event_type);

test('하트비트는 직전 포인트부터 현재 위치까지를 연속 구간으로 보고한다', async () => {
  const { tracker, tick } = harness();
  tracker.startPlay({ trackId: 1001, entryPath: 'RECOMMEND' });
  tick(5000);
  tracker.heartbeat(5000);
  tick(5000);
  tracker.heartbeat(10000);

  const progress = tracker.peek().filter((e) => e.event_type === 'PLAY_PROGRESS');
  assert.deepEqual(
    progress.map((e) => [e.from_ms, e.position_ms]),
    [
      [0, 5000],
      [5000, 10000],
    ],
  );
  await tracker.flush();
});

test('추적하지 못한 점프는 버리되 포인터는 옮긴다 — 안 들은 구간을 들었다고 세면 안 된다', async () => {
  const { tracker, tick } = harness();
  tracker.startPlay({ trackId: 1001, entryPath: 'SEARCH' });
  tick(5000);
  tracker.heartbeat(600_000); // 5초 사이에 10분을 건너뛸 수는 없다
  assert.equal(types(tracker).filter((t) => t === 'PLAY_PROGRESS').length, 0);

  tick(5000);
  tracker.heartbeat(605_000);
  const progress = tracker.peek().filter((e) => e.event_type === 'PLAY_PROGRESS');
  assert.deepEqual(
    progress.map((e) => [e.from_ms, e.position_ms]),
    [[600_000, 605_000]],
  );
  await tracker.flush();
});

test('seek 은 건너뛰기 전까지 들은 구간을 먼저 확정하고 방향을 구분한다', async () => {
  const { tracker, tick } = harness();
  tracker.startPlay({ trackId: 1001, entryPath: 'PLAYLIST' });
  tick(5000);
  tracker.heartbeat(5000);

  tick(2000);
  tracker.seek(7000, 2000);
  tick(3000);
  tracker.heartbeat(5000);

  assert.deepEqual(
    tracker.peek().map((e) => [e.event_type, e.from_ms ?? null, e.position_ms ?? null]),
    [
      ['PLAY_START', null, 0],
      ['PLAY_PROGRESS', 0, 5000],
      ['PLAY_PROGRESS', 5000, 7000], // 되감기 직전까지 들은 구간
      ['SEEK_BACK', 7000, 2000],
      ['PLAY_PROGRESS', 2000, 5000], // 되감은 뒤 다시 들은 구간
    ],
  );
  await tracker.flush();
});

test('스킵은 원시 신호만 남긴다 — 분류도 가중치도 붙이지 않는다', async () => {
  const { tracker, tick } = harness();
  tracker.startPlay({ trackId: 1001, entryPath: 'RECOMMEND' });
  tick(3000);
  tracker.skip(3000, 'NEXT_BUTTON');

  const skip = tracker.peek().at(-1);
  assert.equal(skip.event_type, 'TRACK_SKIP');
  assert.equal(skip.skip_trigger, 'NEXT_BUTTON');
  assert.equal(skip.position_ms, 3000);
  for (const key of ['weight', 'skip_type', 'skip_class', 'section_uid', 'user_id']) {
    assert.equal(key in skip, false, `${key} 는 수집 단계에 없어야 한다`);
  }
  assert.equal(tracker.playId, null);
  await tracker.flush();
});

test('재생 맥락은 그 재생의 모든 이벤트에 실리고, 검색에는 실리지 않는다', async () => {
  const { tracker } = harness();
  tracker.startPlay({
    trackId: 1001,
    entryPath: 'RECOMMEND',
    rec: { id: 'rec-0001', type: 'SECTION', rank: 2 },
  });
  tracker.like(15_000, 'SECTION');
  tracker.search('푸른 오후');

  const [start, like, search] = tracker.peek();
  assert.equal(start.rec_id, 'rec-0001');
  assert.equal(start.rec_type, 'SECTION');
  assert.equal(start.rec_rank, 2);
  assert.equal(like.entry_path, 'RECOMMEND');
  assert.equal(like.track_id, 1001);
  assert.equal(like.play_id, start.play_id);
  assert.ok(start.play_id);
  // 검색은 재생 밖의 행동이다.
  assert.equal(search.play_id, undefined);
  assert.equal(search.track_id, undefined);
  assert.equal(search.search_query, '푸른 오후');
  await tracker.flush();
});

test('설명 없이 끊긴 재생은 스킵이 아니라 PLAYBACK_ABANDON 이다', async () => {
  const { tracker, tick } = harness();
  tracker.startPlay({ trackId: 1001, entryPath: 'SEARCH' });
  tick(5000);
  tracker.heartbeat(5000);
  tracker.startPlay({ trackId: 2002, entryPath: 'SEARCH' });

  assert.deepEqual(types(tracker), [
    'PLAY_START',
    'PLAY_PROGRESS',
    'PLAYBACK_ABANDON',
    'PLAY_START',
  ]);
  assert.equal(tracker.peek()[2].track_id, 1001);
  await tracker.flush();
});

test('구간 지정은 범위와 함께 시작점을 position_ms 로 보낸다', async () => {
  const { tracker } = harness();
  tracker.startPlay({ trackId: 1001, entryPath: 'RECOMMEND' });
  tracker.markSection(30_000, 60_000);

  const mark = tracker.peek().at(-1);
  assert.deepEqual(
    [mark.event_type, mark.start_ms, mark.end_ms, mark.position_ms],
    ['SECTION_MARK', 30_000, 60_000, 30_000],
  );
  // 백엔드 구간 매핑이 position_ms 한 점으로 dictGet 하고 position_ms > 0 을 요구한다.
  // 이 값이 빠지면 가장 강한 긍정 신호(+1.5)가 조용히 사라진다.
  assert.ok(mark.position_ms > 0);
  await tracker.flush();
});

test('occurred_at 은 전송 시각이 아니라 발생 시각이다', async () => {
  const { tracker, tick, sent } = harness();
  tracker.startPlay({ trackId: 1001, entryPath: 'SEARCH' });
  const born = tracker.peek()[0].occurred_at;
  tick(600_000); // 10분 뒤에야 네트워크가 살아났다
  await tracker.flush();
  assert.equal(sent[0][0].occurred_at, born);
  assert.equal(born, new Date(1_700_000_000_000).toISOString());
});

test('전송에 실패한 배치는 큐 앞으로 되돌아온다', async () => {
  let fail = true;
  const tracker = createTracker({
    storage: null,
    transport: async () => {
      if (fail) throw new Error('offline');
    },
  });
  tracker.search('네트워크 없음');
  await tracker.flush();
  assert.deepEqual(types(tracker), ['SEARCH']);

  fail = false;
  await tracker.flush();
  assert.deepEqual(tracker.peek(), []);
});

test('다시 보내도 소용없는 실패(4xx)는 큐를 막지 않고 버려진다', async () => {
  const attempts = [];
  const tracker = createTracker({
    storage: null,
    transport: async (events) => {
      attempts.push(events.length);
      // 서버가 규약 위반으로 400 을 준 상황.
      const error = new Error('bad request');
      error.permanent = true;
      throw error;
    },
  });
  tracker.search('규약을 어긴 배치');
  await tracker.flush();

  // 되돌렸다면 이 배치가 큐 앞에 남아 영원히 다시 나간다.
  assert.deepEqual(tracker.peek(), []);
  assert.deepEqual(attempts, [1]);
});

test('401 은 버리지 않는다 — 로그인하면 그대로 나가야 한다', async () => {
  let signedIn = false;
  const sent = [];
  const tracker = createTracker({
    storage: null,
    transport: async (events) => {
      if (!signedIn) throw new Error('이벤트 전송 실패: 401');
      sent.push(...events);
    },
  });
  tracker.search('로그인 전 검색');
  await tracker.flush();
  assert.deepEqual(types(tracker), ['SEARCH']);

  signedIn = true;
  await tracker.flush();
  assert.equal(sent.length, 1);
  assert.equal(sent[0].search_query, '로그인 전 검색');
});
