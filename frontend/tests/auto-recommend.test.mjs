/*
 * AI 이어듣기 — 목록을 다 들으면 추천 곡으로 큐를 채워 계속 튼다 (S15P21A204-439).
 *
 * 큐 담기·추천 API·플레이어는 각자의 시험이 이미 본다. 여기서 보는 것은 **그 셋을
 * 잇는 판정** 하나뿐이다 — 언제 채우고, 언제 채우지 않고, 늦게 온 답을 언제 버리는가.
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
  enabled: true,
  playback: 1,
  currentUid: null,
  queue: toQueueEntries([1, 2]),
  ...overrides,
});

test('꺼져 있으면 추천을 묻지도 않는다', async () => {
  let asked = 0;
  const result = await autoRecommendAtEnd(
    () => stage({ enabled: false }),
    () => {
      asked += 1;
      return Promise.resolve([{ id: 9 }]);
    },
    QUEUE_LIMIT,
  );
  assert.equal(asked, 0, '꺼져 있는데 추천 요청이 나갔다');
  assert.equal(result, null);
});

test('받은 곡을 순서대로 큐 맨 아래에 이어 붙인다', async () => {
  const now = stage();
  const result = await autoRecommendAtEnd(
    () => now,
    () => Promise.resolve([{ id: 9 }, { id: 10 }]),
    QUEUE_LIMIT,
  );
  assert.ok(result);
  assert.deepEqual(
    result.entries.map((entry) => entry.trackId),
    [1, 2, 9, 10],
    '맨 아래에 받은 순서대로 붙지 않았다',
  );
  // 목록이 이미 끝나 버린 자리에서는 **채운 첫 곡**을 튼다. 그 자리를 그대로 짚는다.
  assert.equal(result.tracks[0].id, 9);
  assert.equal(result.entries[2].uid, result.firstUid);
});

test('덜 와도 온 만큼만 붙인다 — 억지로 채우지 않는다', async () => {
  // 서버는 조건에 맞는 곡이 없으면 `shortfall` 을 채워 200 을 준다. 모자란 것은 오류가
  // 아니므로 받은 만큼만 쓴다 (`요구 3 · 자동재생 다음 곡`).
  // `stage` 를 그대로 넘기면 부를 때마다 새 객체라 "큐가 바뀌었다" 로 읽힌다.
  const now = stage();
  const result = await autoRecommendAtEnd(() => now, () => Promise.resolve([{ id: 9 }]), QUEUE_LIMIT);
  assert.equal(result.tracks.length, 1);
  assert.deepEqual(
    result.entries.map((entry) => entry.trackId),
    [1, 2, 9],
  );
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

test('그 사이 이어듣기를 껐으면 버린다', async () => {
  let now = stage();
  const result = await autoRecommendAtEnd(
    () => now,
    () => {
      now = stage({ enabled: false });
      return Promise.resolve([{ id: 9 }]);
    },
    QUEUE_LIMIT,
  );
  assert.equal(result, null, '끈 뒤에 도착한 추천을 이어 붙였다');
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
 * 아래는 훅의 배선이다. 위 판정이 옳아도 **부르는 자리가 틀리면** 전체 반복 중에도
 * 곡이 붙는다.
 *
 * **줄바꿈을 먼저 고른다.** 윈도우 체크아웃은 `core.autocrlf` 로 CRLF 가 되는데, 아래
 * 정규식은 여는 괄호 다음의 `\n` 을 짚는다 — 그대로 두면 리눅스에서만 통과하고 윈도우
 * 에서는 손대지도 않은 이 시험이 "새로 깨졌다" 로 뜬다.
 * `player-track-switch.test.mjs` 가 같은 이유로 같은 일을 한다.
 */
const hook = (
  await readFile(new URL('../hooks/use-mupid.ts', import.meta.url), 'utf8')
).replaceAll('\r\n', '\n');

test('목록을 다 들었을 때만 이어 붙인다 — 셔플 한 바퀴도 센다', () => {
  const call = hook.match(
    /if \(\n\s+autoRecommendRef\.current[\s\S]*?fillQueueWithRecommendations[^\n]*/,
  )?.[0];
  assert.ok(call, '이어 붙이는 자리를 찾지 못했다');
  // 꺼져 있으면 요청 자체가 나가지 않는다.
  assert.ok(call.includes('autoRecommendRef.current'), '이어듣기 설정을 보지 않는다');
  // 전체·한곡 반복 중에는 목록이 끝나지 않는다.
  assert.ok(call.includes("repeatMode === 'off'"), '반복 설정을 보지 않는다');
  // 섞어 듣든 순서대로 듣든 "다 들었다" 는 같다. 그 판정은 `finished` 가 들고 있다.
  assert.ok(call.includes('finished'), '목록을 다 들었는지 보지 않는다');
  assert.ok(!call.includes('!shuffle'), '셔플이면 아직도 건너뛴다');
  // 목록 밖의 곡(저장한 순간 등)은 자리가 없다.
  assert.ok(call.includes('uid &&'), '목록 밖에서 튼 곡의 종료에도 끼어든다');
  // 셔플일 때 빈 목록은 remaining 이 0 이라 finished 를 통과한다. 따로 막아야 한다.
  assert.ok(call.includes('pool.length > 0'), '빈 목록에서도 이어 붙인다');
});

test('뒤에 남은 곡이 얼마 없으면 미리 채운다 — 끝나고 묻지 않는다', () => {
  /*
   * 곡이 끝난 뒤에 물으면 추천 선정(수 초)에 더해 오디오까지 새로 받아야 해서 목록 끝
   * 마다 소리가 끊긴다. 미리 채워 두면 재생기의 기존 미리 받기(다음 3곡)가 그대로
   * 데워 주고, 스킵도 그냥 다음 곡이라 기다림이 없다.
   */
  const effect = hook.match(/const remaining = pool\.length - 1 - index;[\s\S]*?\n  \}, \[[^\]]*\]/)?.[0];
  assert.ok(effect, '미리 채우는 자리를 찾지 못했다');
  assert.ok(effect.includes('remaining > AUTO_QUEUE_MIN'), '남은 곡 수를 보지 않는다');
  assert.ok(effect.includes('fillQueueWithRecommendations({ remaining })'), '미리 채우지 않는다');
  // 서버가 이 쓰임을 전제로 만들어졌다 — `limit` 기본 5 (`요구 3 · 자동재생 다음 곡`).
  assert.match(hook, /const AUTO_QUEUE_TARGET = 5;/);
  assert.match(hook, /const AUTO_QUEUE_MIN = 3;/);
});

test('요청이 겹치지 않고, 큐에 있는 곡은 빼고 묻는다', () => {
  const fn = hook.match(/const fillQueueWithRecommendations = [\s\S]*?\n  \};/)?.[0];
  assert.ok(fn, 'fillQueueWithRecommendations 를 찾지 못했다');
  assert.ok(fn.includes('if (autoFilling.current) return;'), '중복 요청 가드가 없다');
  assert.ok(fn.includes('excludeMusicIds'), '큐에 있는 곡을 제외하지 않는다');
  // 목표치에서 남은 곡을 뺀 만큼만 받는다.
  assert.ok(fn.includes('AUTO_QUEUE_TARGET - remaining'), '필요한 만큼만 받지 않는다');
});

test('이어 붙인 곡은 추천 구간이 아니라 곡 처음부터 튼다', () => {
  /*
   * 추천 응답에는 권하는 구간(`recommended.start`)이 실려 온다. 화면에서 **눌러서** 트는
   * 길(`play()`)은 그 구간을 들려주려고 누른 것이라 거기서 시작하지만, 여기는 목록을
   * 끝까지 듣고 그대로 이어지는 자리다 — 고르지도 않았는데 후렴 한가운데서 소리가
   * 나면 곡이 끊긴 것처럼 들린다.
   */
  const fn = hook.match(/const fillQueueWithRecommendations = [\s\S]*?\n  \};/)?.[0];
  assert.ok(fn.includes("startPlayback(found[0], 0, firstUid, 'RECOMMEND')"), '0 초에서 틀지 않는다');
  assert.ok(!/recommended\?\.start/.test(fn), '추천 구간에서 시작한다');
});

test('마지막 자리에서 다음을 누르면 맨 앞으로 되감지 않는다', () => {
  /*
   * `stepQueue` 는 나머지 연산으로 순환한다. 목록 끝이 곧 "AI 가 고른 곡" 이 된 지금,
   * 거기서 스킵하면 1번 곡이 다시 울렸다 — 의도한 사람이 아무도 없는 동작이다.
   * 평소에는 미리 채워 둔 곡이 뒤에 있어 이 갈래로 오지 않는다. 채우기가 실패했거나
   * 아직 도착하지 않았을 때의 대비다.
   */
  const fn = hook.match(/const next = \(direction = 1\)[\s\S]*?\n  \};/)?.[0];
  assert.ok(fn, 'next 를 찾지 못했다');
  assert.ok(fn.includes('atLastSlot'), '마지막 자리인지 보지 않는다');
  assert.ok(fn.includes('direction > 0'), '이전 버튼까지 가로챈다');
  assert.ok(fn.includes('fillQueueWithRecommendations({ play: true })'), '새 추천을 받지 않는다');
  // 셔플은 무작위로 고르는 중이라 '마지막 자리' 개념이 없다.
  assert.ok(fn.includes('!shuffle'), '셔플 중에도 가로챈다');
  // 끝까지 듣지 않고 떠난 것이므로 스킵 기록은 그대로 남아야 한다.
  assert.ok(fn.includes("analytics.skip(positionMs(), 'NEXT_BUTTON')"), '스킵을 기록하지 않는다');
});

test('AI 이어듣기와 반복은 상호 배제다 — 한쪽을 켜면 다른 쪽이 꺼진다', () => {
  /*
   * 둘 다 "목록이 끝나면 무엇을 하는가" 에 답한다. 동시에 켜지면 답이 둘이 되는데,
   * 특히 **전체 반복 중에는 목록이 끝나지 않아** 이어듣기가 영영 돌지 않는다 — 켰는데
   * 아무 일도 일어나지 않는 상태를 사용자가 보게 된다.
   */
  const setAuto = hook.match(/const setAutoRecommend = \([\s\S]*?\n  \};/)?.[0];
  const setRepeat = hook.match(/const setRepeatMode = \([\s\S]*?\n  \};/)?.[0];
  assert.ok(setAuto && setRepeat, '두 설정 함수를 찾지 못했다');
  assert.ok(setAuto.includes("setRepeatModeState('off')"), '이어듣기를 켜도 반복이 남는다');
  assert.ok(setRepeat.includes('setAutoRecommend(false)'), '반복을 켜도 이어듣기가 남는다');
});

test('반복 버튼은 목록 끝 이야기를 하지 않는다 — 그건 이어듣기 버튼 몫이다', async () => {
  /*
   * 상호 배제라 "반복 없음" 은 멈출 수도(이어듣기 꺼짐) 이어질 수도(켜짐) 있다.
   * 한쪽을 단정하면 다른 쪽에서 거짓말이 된다.
   */
  const controls = await readFile(
    new URL('../components/mupid/primitives.tsx', import.meta.url),
    'utf8',
  );
  assert.ok(
    controls.includes("'반복 없음 · 목록을 한 번만 재생해요.'"),
    "'반복 없음' 안내가 목록 끝을 단정한다",
  );
  /*
   * 셔플·반복과 같은 `toggle-control` 버튼이다. 유튜브식 알약 스위치는 아이콘 버튼들
   * 사이에서 혼자 커서 여섯 칸이 한 줄에 설 때 재생 버튼이 가운데를 잃었다.
   */
  const button = controls.match(/export function AutoContinueButton[\s\S]*?\n}/)?.[0];
  assert.ok(button, 'AutoContinueButton 을 찾지 못했다');
  assert.ok(button.includes('icon-button toggle-control'), '다른 토글과 같은 모양이 아니다');
  assert.ok(button.includes('aria-pressed={on}'), '누름 상태를 알리지 않는다');
  // 반짝임은 '추천 이유' 가 이미 쓴다. 같은 화면에서 두 가지를 가리키면 안 된다.
  assert.ok(!button.includes('Sparkles'), 'Sparkles 를 다시 쓴다');
});
