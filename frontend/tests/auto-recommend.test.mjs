/*
 * 재생 목록이 끝났을 때의 자동 추천 (JARA-439).
 *
 * 큐 담기·추천 API·플레이어는 각자의 시험이 이미 본다. 여기서 보는 것은 **그 셋을
 * 잇는 판정** 하나뿐이다 — 언제 묻고, 언제 묻지 않고, 늦게 온 답을 언제 버리는가.
 *
 * 판정은 `lib/queue.ts` 의 순수 함수에 있다. 훅이 안고 있으면 재생기와 React 를 통째로
 * 세워야 하는데, 그러면 이 시험이 보려는 것(판정)보다 세우는 장치가 커진다.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { autoRecommendAtEnd, toQueueEntries } from '../lib/queue.ts';

const QUEUE_LIMIT = 100;

/** 지금 상태를 들고 있는 최소한의 재생 화면. `read()` 가 매번 이것을 읽는다. */
const stage = (overrides = {}) => ({
  playback: 1,
  currentUid: null,
  queue: toQueueEntries([1, 2]),
  ...overrides,
});

test('추천 한 곡만 큐 맨 아래에 붙는다', async () => {
  const now = stage();
  const result = await autoRecommendAtEnd(
    () => now,
    () => Promise.resolve([{ id: 9 }, { id: 10 }]),
    QUEUE_LIMIT,
  );
  assert.ok(result);
  assert.equal(result.track.id, 9, '여러 곡이 와도 첫 곡 하나만 쓴다');
  assert.deepEqual(
    result.placement.entries.map((entry) => entry.trackId),
    [1, 2, 9],
    '맨 아래가 아니다',
  );
  // 재생은 방금 놓은 그 자리에서 이어진다 — 같은 곡이 큐에 또 있어도 헷갈리지 않는다.
  assert.equal(result.placement.entries.at(-1).uid, result.placement.uid);
});

test('사용자가 그 사이 다른 곡을 틀었으면 늦게 온 추천을 버린다', async () => {
  let now = stage();
  const result = await autoRecommendAtEnd(
    () => now,
    () => {
      // 응답을 기다리는 사이 사용자가 직접 재생을 걸었다.
      now = stage({ playback: 2, currentUid: now.queue[0].uid });
      return Promise.resolve([{ id: 9 }]);
    },
    QUEUE_LIMIT,
  );
  assert.equal(result, null, '늦게 온 추천이 사용자가 만든 상태를 덮었다');
});

test('그 사이 큐가 바뀌었으면 버린다', async () => {
  let now = stage();
  const result = await autoRecommendAtEnd(
    () => now,
    () => {
      // 응답을 기다리는 사이 사용자가 곡을 담았다.
      now = stage({ queue: toQueueEntries([1, 2, 3]) });
      return Promise.resolve([{ id: 9 }]);
    },
    QUEUE_LIMIT,
  );
  assert.equal(result, null, '큐가 바뀌었는데 이어 붙였다');
});

test('추천이 실패하거나 후보가 없으면 조용히 끝난다 — 재시도도 대체곡도 없다', async () => {
  const failed = await autoRecommendAtEnd(
    stage,
    () => Promise.reject(new Error('추천 서버 장애')),
    QUEUE_LIMIT,
  );
  assert.equal(failed, null);
  const empty = await autoRecommendAtEnd(stage, () => Promise.resolve([]), QUEUE_LIMIT);
  assert.equal(empty, null);
});

/*
 * 아래 둘은 훅의 배선이다. 위 판정이 옳아도 **부르는 자리가 틀리면** 셔플 한 바퀴나
 * 전체 반복에서도 곡이 붙는다.
 */
/*
 * **줄바꿈을 먼저 고른다.** 윈도우 체크아웃은 `core.autocrlf` 로 CRLF 가 되는데,
 * 아래 정규식은 여는 괄호 다음의 `\n` 을 짚는다 — 그대로 두면 리눅스에서만 통과하고
 * 윈도우에서는 손대지도 않은 이 시험이 "새로 깨졌다" 로 뜬다.
 * `player-track-switch.test.mjs` 가 같은 이유로 같은 일을 한다.
 */
const hook = (
  await readFile(new URL('../hooks/use-mupid.ts', import.meta.url), 'utf8')
).replaceAll('\r\n', '\n');

test('일반 전체 곡 재생의 마지막 자리에서만 묻는다', () => {
  const call = hook.match(/if \(\n\s+repeatMode === 'off'[\s\S]*?continueWithRecommendation[^\n]*/)?.[0];
  assert.ok(call, '자동 추천을 거는 자리를 찾지 못했다');
  // 전체·한곡 반복 중에는 목록이 끝나지 않는다.
  assert.ok(call.includes("repeatMode === 'off'"), '반복 설정을 보지 않는다');
  // 셔플 한 바퀴의 끝은 목록의 끝이 아니다.
  assert.ok(call.includes('!shuffle'), '셔플 한 바퀴 종료에도 끼어든다');
  // 목록 밖의 곡(저장한 순간 등)은 자리가 없다.
  assert.ok(call.includes('uid &&'), '목록 밖에서 튼 곡의 종료에도 끼어든다');
  // 빈 목록도 `index === pool.length - 1` 을 -1 로 통과한다. 따로 막아야 한다.
  assert.ok(call.includes('pool.length > 0'), '빈 목록에서도 추천을 잇는다');
  assert.ok(call.includes('index === pool.length - 1'), '마지막 자리인지 보지 않는다');
});

test("반복 버튼의 '반복 없음' 안내가 실제 동작과 같은 말을 한다", async () => {
  /*
   * 토글이 없어지면서 **반복을 끄는 것이 곧 멈춤이 아니게 됐다.** 예전 문구("목록이
   * 끝나면 멈춰요")를 그대로 두면 버튼이 거짓말을 한다 — 사용자가 재생을 끝내려고
   * 누르는 바로 그 버튼이라 더 그렇다. 둘이 갈라지면 여기서 잡는다.
   */
  const controls = await readFile(
    new URL('../components/mupid/primitives.tsx', import.meta.url),
    'utf8',
  );
  assert.ok(
    controls.includes("'반복 없음 · 목록이 끝나면 추천 곡을 이어 재생해요.'"),
    "'반복 없음' 안내가 아직 멈춘다고 말한다",
  );
});

test('한 번의 종료로 두 번 묻지 않는다', () => {
  const fn = hook.match(/const continueWithRecommendation = [\s\S]*?\n  \};/)?.[0];
  assert.ok(fn, 'continueWithRecommendation 을 찾지 못했다');
  assert.ok(fn.includes('if (autoRecommending.current) return;'), '중복 요청 가드가 없다');
  assert.ok(fn.includes('limit: 1'), '한 곡만 받지 않는다');
  assert.ok(fn.includes('excludeMusicIds'), '큐에 있는 곡을 제외하지 않는다');
});
