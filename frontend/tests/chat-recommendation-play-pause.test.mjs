import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

/*
 * 챗봇이 권한 곡 줄의 재생 버튼이 **진짜 토글인가** (S15P21A204-422).
 *
 * ## 무엇이 고장나 있었나
 *
 * 추천 곡 줄은 구간 시작점에서 틀려고 `m.play(track, true)` 로 부른다. 두 번째
 * 인자가 `fromMoment` 다 — **늘 true** 다. 그런데 `use-mupid.ts` 의 `play()` 는
 * 일시정지·재개 분기를 둘 다 `!fromMoment` 로 막고 있었다. 그래서 그 줄은 토글
 * 경로에 영영 들어가지 못했고, 울리는 곡을 다시 눌러도 멈추는 대신 `previewEnd`
 * 를 다시 걸고 구간 처음부터 다시 틀었다. 버튼 아이콘(`TrackRow` 의
 * `m.playing && m.active?.id === track.id`)은 Pause 모양이었으므로 **보이는 것과
 * 하는 일이 어긋났다.**
 *
 * ## 재는 방법
 *
 * 소스에 무슨 낱말이 있는지 보는 것으로는 이 결함을 못 잡는다 — 고장난 코드에도
 * `player.pause()` 는 멀쩡히 적혀 있었다. 닿지 못하는 자리에 있었을 뿐이다.
 * 그래서 여기서는 `play()` **함수 본문을 그대로 떼어 내** 가짜 재생기·큐를 물리고
 * 실제로 돌린다. 훅 전체(React·오디오 엔진)는 필요 없다.
 *
 * 한계: 떼어 내는 경계는 소스의 `const play = (` ~ `const next = (` 다. 그 모양이
 * 바뀌면 아래 추출이 먼저 깨지므로, 조용히 통과하지는 않는다.
 */

/*
 * 줄끝은 **읽을 때 LF 로 맞춘다.** 아래의 추출 경계가 `\n` 으로 적혀 있어,
 * CRLF 로 체크아웃되는 윈도우에서는 경계를 못 찾고 시험이 통째로 멈췄다
 * (`core.autocrlf` 가 기본인 환경). 잘라 내는 위치는 바뀌지 않는다.
 */
const read = (path) =>
  readFile(new URL('../' + path, import.meta.url), 'utf8').then((text) =>
    text.replace(/\r\n/g, '\n'),
  );

const hook = await read('hooks/use-mupid.ts');
const chatTsx = await read('components/mupid/chat.tsx');

/** 주석을 걷어낸 **코드만** 남긴다 (`tests/chat-scroll-and-tools.test.mjs` 와 같은 방법). */
const code = (source) =>
  source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1');

// ─────────────────────────────────────────────────────────────
// play() 를 떼어 내 돌릴 수 있게 만든다
// ─────────────────────────────────────────────────────────────

const START = '  const play = (\n    track: Track | null = activeRef.current,';
const END = '\n  const next = (direction = 1) => {';
const from = hook.indexOf(START);
const to = hook.indexOf(END);
assert.ok(from >= 0, 'use-mupid.ts 에서 play() 의 시작을 찾지 못했다');
assert.ok(to > from, 'use-mupid.ts 에서 play() 의 끝(next 선언)을 찾지 못했다');
const playSource = hook.slice(from, to);
assert.ok(playSource.includes('startPlayback('), '떼어 낸 것이 play() 가 아니다');

const wrapper = `
type Track = any;
export const makePlay = (deps: any) => {
  const {
    activeRef, previewEnd, queue, getPlayer, momentSequence, setSavedMomentPlayback,
    placePlayedTrack, currentUidRef, setCurrentUid, analytics, positionMs, startPlayback,
  } = deps;
${playSource}
  return play;
};
`;

const { makePlay } = await import(
  'data:text/javascript;base64,' +
    Buffer.from(
      ts.transpileModule(wrapper, {
        compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
      }).outputText,
    ).toString('base64')
);

/** 추천 구간이 달린 곡 한 줄. 챗봇이 내려 주는 모양 그대로다. */
const recommendedTrack = (id = 1) => ({
  id,
  musicId: 100 + id,
  title: `곡 ${id}`,
  moment: 12,
  recommended: { start: 41, end: 73, sectionLabel: '후렴', reason: '이유', rank: 1 },
});

/**
 * 가짜 재생기·큐를 물린 `play()` 한 벌.
 *
 * `status` 는 재생기가 지금 무엇을 하고 있는가다. `'idle'` 은 **이 곡을 아직 들고
 * 있지 않다** 는 뜻으로 쓴다(앱을 막 열어 `active` 이기만 한 경우가 이것이다).
 */
const harness = ({ status = 'idle', active = null, queue = [], previewEnd = null } = {}) => {
  const calls = { pause: 0, resume: 0, startPlayback: [], lift: [], skip: [] };
  const state = { status };
  const player = {
    store: { get: () => ({ status: state.status }) },
    pause: () => {
      calls.pause += 1;
      state.status = 'paused';
    },
    play: async () => {
      calls.resume += 1;
      state.status = 'playing';
    },
  };
  const refs = {
    activeRef: { current: active },
    previewEnd: { current: previewEnd },
    currentUidRef: { current: queue[0]?.uid ?? null },
    momentSequence: { current: { trackId: 999, ranges: [], index: 0, mode: 'single' } },
  };
  let nextUid = 900;
  const deps = {
    ...refs,
    queue,
    getPlayer: () => player,
    setSavedMomentPlayback: () => {},
    placePlayedTrack: (track) => {
      calls.lift.push(track.id);
      const uid = `u${(nextUid += 1)}`;
      return { uid, entries: [{ uid, trackId: track.id }, ...queue] };
    },
    setCurrentUid: (uid) => {
      refs.currentUidRef.current = uid;
    },
    analytics: { skip: (ms, reason) => calls.skip.push(reason) },
    positionMs: () => 0,
    startPlayback: (track, start, uid) => {
      calls.startPlayback.push({ id: track.id, start, uid });
      state.status = 'playing';
    },
  };
  return { play: makePlay(deps), calls, state, refs };
};

// ─────────────────────────────────────────────────────────────
// 기대 동작 1 — 울리는 중에 누르면 멈춘다
// ─────────────────────────────────────────────────────────────

void test('추천 곡이 울리는 중에 그 줄을 다시 누르면 멈춘다 — 다시 틀지 않는다', () => {
  const track = recommendedTrack();
  const { play, calls, state } = harness({
    status: 'playing',
    active: track,
    queue: [{ uid: 'u1', trackId: track.id }],
    previewEnd: track.recommended.end,
  });

  play(track, true); // 챗봇 추천 줄은 늘 fromMoment=true 로 부른다

  assert.equal(calls.pause, 1, '멈추지 않았다 — 422 가 그대로다');
  assert.deepEqual(calls.startPlayback, [], '멈추라고 눌렀는데 다시 틀었다');
  assert.equal(state.status, 'paused');
});

void test('버퍼링 중에 눌러도 멈춘다 — 막 누른 곡을 취소할 수 있어야 한다', () => {
  const track = recommendedTrack();
  const { play, calls } = harness({
    status: 'buffering',
    active: track,
    queue: [{ uid: 'u1', trackId: track.id }],
  });

  play(track, true);

  assert.equal(calls.pause, 1);
  assert.deepEqual(calls.startPlayback, []);
});

// ─────────────────────────────────────────────────────────────
// 기대 동작 2 — 멈춘 곡은 멈춘 자리에서 이어서
// ─────────────────────────────────────────────────────────────

void test('멈춰 둔 추천 곡을 다시 누르면 멈춘 자리에서 이어 튼다 — 구간 처음으로 되감지 않는다', () => {
  const track = recommendedTrack();
  const { play, calls, state } = harness({
    status: 'paused',
    active: track,
    queue: [{ uid: 'u1', trackId: track.id }],
    previewEnd: track.recommended.end,
  });

  play(track, true);

  assert.equal(calls.resume, 1, '이어 틀지 않았다');
  assert.deepEqual(
    calls.startPlayback,
    [],
    'startPlayback 을 다시 불렀다 — 구간 시작점으로 되감긴다',
  );
  assert.equal(state.status, 'playing');
});

void test('멈췄다 이어 틀어도 추천 구간의 끝은 그대로다 — 미리듣기가 풀리지 않는다', () => {
  const track = recommendedTrack();
  const { play, refs } = harness({
    status: 'paused',
    active: track,
    queue: [{ uid: 'u1', trackId: track.id }],
    previewEnd: track.recommended.end,
  });

  play(track, true);

  assert.equal(
    refs.previewEnd.current,
    track.recommended.end,
    '이어 트는 것뿐인데 구간 끝을 잃었다 — 곡이 끝까지 흘러간다',
  );
});

void test('멈췄다 트는 것만으로 재생 목록 순서가 바뀌지 않는다', () => {
  const track = recommendedTrack(2);
  const { play, calls } = harness({
    status: 'playing',
    active: track,
    queue: [
      { uid: 'u1', trackId: 1 },
      { uid: 'u2', trackId: 2 },
    ],
  });

  play(track, true);

  assert.deepEqual(calls.lift, [], '멈추기만 했는데 큐에서 자리를 걷어 올렸다');
});

// ─────────────────────────────────────────────────────────────
// 기대 동작 3 — 아직 들고 있지 않은 곡은 기존대로 구간 시작점에서
// ─────────────────────────────────────────────────────────────

void test('다른 곡을 듣던 중 추천 줄을 누르면 추천 구간 시작점에서 튼다', () => {
  const other = { id: 9, musicId: 109, title: '듣던 곡', moment: 5 };
  const track = recommendedTrack();
  const { play, calls, refs } = harness({ status: 'playing', active: other, queue: [] });

  play(track, true);

  assert.equal(calls.startPlayback.length, 1);
  assert.equal(calls.startPlayback[0].id, track.id);
  assert.equal(
    calls.startPlayback[0].start,
    track.recommended.start,
    '추천 구간이 아니라 다른 자리에서 시작했다',
  );
  assert.equal(
    refs.previewEnd.current,
    track.recommended.end,
    '구간 끝을 걸지 않았다 — 다음 구간까지 흘러간다',
  );
  assert.deepEqual(calls.lift, [track.id], '목록 밖에서 튼 곡이 큐에 자리를 얻지 못했다');
  assert.deepEqual(calls.skip, ['OTHER_TRACK']);
  assert.equal(calls.pause, 0);
});

void test('앱을 막 열어 active 이기만 한 곡도 추천 구간 시작점에서 튼다 — 토글이 아니다', () => {
  // `active` 는 마지막에 듣던 곡으로 복원돼 있지만 재생기는 아직 아무것도 안 들고 있다.
  const track = recommendedTrack();
  const { play, calls, refs } = harness({ status: 'idle', active: track, queue: [] });

  play(track, true);

  assert.equal(calls.pause, 0, '울리지도 않는 곡을 멈췄다');
  assert.equal(calls.resume, 0);
  assert.equal(calls.startPlayback.length, 1);
  assert.equal(calls.startPlayback[0].start, track.recommended.start);
  assert.equal(refs.previewEnd.current, track.recommended.end);
});

// ─────────────────────────────────────────────────────────────
// 기존 호출자들의 동작은 그대로다
// ─────────────────────────────────────────────────────────────

void test('하단 플레이어(fromMoment 없음)의 일시정지·재개는 그대로다', () => {
  const track = { id: 3, musicId: 103, title: '곡 3', moment: 8 };
  const playingOne = harness({
    status: 'playing',
    active: track,
    queue: [{ uid: 'u1', trackId: track.id }],
  });
  playingOne.play(); // 인자 없이 = 지금 곡
  assert.equal(playingOne.calls.pause, 1);
  assert.deepEqual(playingOne.calls.startPlayback, []);

  const pausedOne = harness({
    status: 'paused',
    active: track,
    queue: [{ uid: 'u1', trackId: track.id }],
  });
  pausedOne.play();
  assert.equal(pausedOne.calls.resume, 1);
  assert.deepEqual(pausedOne.calls.startPlayback, []);
});

void test('재생 목록에서 고른 줄은 그 자리를 그대로 튼다 — 자리를 옮기지 않는다', () => {
  const other = { id: 9, musicId: 109, title: '듣던 곡', moment: 5 };
  const track = { id: 4, musicId: 104, title: '곡 4', moment: 8 };
  const { play, calls } = harness({
    status: 'playing',
    active: other,
    queue: [
      { uid: 'u1', trackId: 9 },
      { uid: 'u2', trackId: 4 },
    ],
  });

  play(track, false, 'u2'); // queue-list.tsx 가 부르는 모양

  assert.deepEqual(calls.lift, [], '목록 안에서 눌렀는데 자리를 옮겼다');
  assert.deepEqual(calls.startPlayback, [{ id: 4, start: undefined, uid: 'u2' }]);
});

void test('추천 구간이 없는 곡을 fromMoment 로 부르면 그 곡의 moment 에서 튼다', () => {
  const other = { id: 9, musicId: 109, title: '듣던 곡', moment: 5 };
  const track = { id: 5, musicId: 105, title: '곡 5', moment: 31 };
  const { play, calls, refs } = harness({ status: 'playing', active: other, queue: [] });

  play(track, true);

  assert.equal(calls.startPlayback[0].start, track.moment);
  assert.equal(refs.previewEnd.current, null);
});

void test('새 재생을 시작하는 길은 저장 순간 시퀀스를 끝낸다 — 토글만 보존한다', () => {
  const other = { id: 9, musicId: 109, title: '듣던 곡', moment: 5 };
  const track = recommendedTrack();

  const starting = harness({ status: 'playing', active: other, queue: [] });
  starting.play(track, true);
  assert.equal(starting.refs.momentSequence.current, null, '새로 트는데 시퀀스가 남았다');

  const toggling = harness({
    status: 'playing',
    active: track,
    queue: [{ uid: 'u1', trackId: track.id }],
  });
  toggling.play(track, true);
  assert.notEqual(toggling.refs.momentSequence.current, null, '멈추기만 했는데 시퀀스를 버렸다');
});

// ─────────────────────────────────────────────────────────────
// 호출부 계약
// ─────────────────────────────────────────────────────────────

void test('챗봇 추천 줄은 여전히 추천 구간으로 부른다 — 구간을 버려서 고치지 않았다', () => {
  // 곡이 아니라 **이 구간**을 권한 것이므로 0초부터 틀면 안 된다. 토글은
  // `play()` 안에서 갈린다.
  assert.match(code(chatTsx), /onPlay=\{\(\) => m\.play\(\s*track,\s*true\s*\)\}/);
});

void test('토글 조건이 fromMoment 에 다시 매달리지 않는다', () => {
  const body = code(playSource);
  const toggle = /const willToggle =([\s\S]*?);/.exec(body);
  assert.ok(toggle, 'willToggle 선언을 찾지 못했다');
  assert.equal(
    /fromMoment/.test(toggle[1]),
    false,
    'willToggle 이 다시 fromMoment 를 본다 — 422 가 되살아난다',
  );
});
