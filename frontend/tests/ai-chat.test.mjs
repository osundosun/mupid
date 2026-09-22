import test from 'node:test';
import assert from 'node:assert/strict';

import { aiChat, ApiError, recommendedTrack } from './fixtures/ai-chat-module.mjs';

/**
 * AI 대화 어댑터. **서버 호출만** 가짜로 바꾸고 나머지는 실제 코드 그대로 돌린다.
 *
 * 여기서 잡고 싶은 것은 두 가지다.
 *  - 요청 모양: 역할 이름(USER/AI)과 이전 대화 개수. 어긋나면 서버가 400·404 를 낸다.
 *  - 응답 처리: **서버가 준 곡으로** 줄을 만드는 것. 예전에는 화면이 들고 있는
 *    카탈로그에서 다시 찾아 못 찾으면 버렸는데, 그 카탈로그가 앞쪽 100곡뿐이라
 *    추천이 통째로 사라졌다 (S15P21A204-424).
 */

const { requestChat, chatErrorMessage } = aiChat;

const setReply = (reply) => {
  globalThis.__aiCalls = [];
  globalThis.__aiReply = reply;
};

test('보내는 요청은 서버 규약대로 역할을 대문자로 옮기고 이전 대화를 자른다', async () => {
  setReply({ reply: '네', tracks: [] });
  const history = Array.from({ length: 15 }, (_, i) => ({
    id: i, role: i % 2 ? 'assistant' : 'user', text: `말 ${i}`,
  }));

  await requestChat({ prompt: '조금 더 잔잔하게', current: { musicId: 2 }, history });

  const [body] = globalThis.__aiCalls;
  assert.equal(body.message, '조금 더 잔잔하게');
  assert.equal(body.music_id, 2);
  // 서버가 받는 상한(ai.max-history)보다 적게 보낸다. 최근 것부터 남긴다.
  assert.equal(body.history.length, 12);
  assert.equal(body.history.at(-1).text, undefined);
  assert.equal(body.history.at(-1).content, '말 14');
  assert.deepEqual([...new Set(body.history.map((turn) => turn.role))].sort(), ['AI', 'USER']);
});

test('듣는 곡이 없으면 music_id 를 보내지 않는다', async () => {
  setReply({ reply: '네', tracks: [] });
  await requestChat({ prompt: '추천해 줘', current: null, history: [] });
  assert.equal('music_id' in JSON.parse(JSON.stringify(globalThis.__aiCalls[0])), false);
});

test('카탈로그를 서버로 보내지 않는다 — 곡은 서버가 안다', async () => {
  setReply({ reply: '네', tracks: [] });
  await requestChat({ prompt: '추천해 줘', current: null, history: [] });
  // `undefined` 인 칸은 JSON 에서 빠진다. 실제로 나가는 모양으로 본다.
  const body = JSON.parse(JSON.stringify(globalThis.__aiCalls[0]));
  assert.equal('catalog' in body, false);
  assert.deepEqual(Object.keys(body).sort(), ['history', 'message']);
});

test('서버가 준 곡으로 줄을 만든다 — 화면 카탈로그를 뒤지지 않는다', async () => {
  setReply({
    reply: '이런 결은 어떠세요?',
    tracks: [recommendedTrack({
      musicId: 3, title: '모노 테스트 곡', score: 0.87, reason: '여운이 긴 곡이에요.',
    })],
  });

  const answer = await requestChat({ prompt: '잔잔하게', current: null, history: [] });

  assert.equal(answer.text, '이런 결은 어떠세요?');
  assert.equal(answer.tracks.length, 1);
  const [track] = answer.tracks;
  // 곡 변환은 카탈로그와 같은 것(`stream-catalog.toTrack`)이라 `id` 규칙도 같다.
  assert.equal(track.id, 1003);
  assert.equal(track.musicId, 3);
  assert.equal(track.title, '모노 테스트 곡');
  assert.equal(track.duration, 120);
  assert.equal(track.match, 87);
  assert.equal(track.reason, '여운이 긴 곡이에요.');
});

test('앞쪽 100곡 밖의 곡도 그대로 살아남는다 — 424 가 고친 것이 이것이다', async () => {
  // 예전 화면 카탈로그(`CATALOG_SIZE = 100`)에는 절대 없을 번호다. 예전 코드였다면
  // 세 곡 모두 말없이 버려져 "3곡 중 3곡은 …없어 빼뒀어요" 만 남았다.
  setReply({
    reply: '세 곡 골라뒀어요',
    tracks: [
      recommendedTrack({ musicId: 9001, title: '멀리 있는 곡 1' }),
      recommendedTrack({ musicId: 9002, title: '멀리 있는 곡 2' }),
      recommendedTrack({ musicId: 9003, title: '멀리 있는 곡 3' }),
    ],
  });

  const answer = await requestChat({ prompt: '추천', current: null, history: [] });

  assert.deepEqual(answer.tracks.map((t) => t.musicId), [9001, 9002, 9003]);
  assert.deepEqual(answer.tracks.map((t) => t.title),
    ['멀리 있는 곡 1', '멀리 있는 곡 2', '멀리 있는 곡 3']);
});

test('아직 재생할 수 없는 곡은 버린다 — 눌러도 재생되지 않는 줄을 만들지 않는다', async () => {
  setReply({
    reply: '한 곡은 아직이에요',
    tracks: [
      recommendedTrack({ musicId: 2, playable: false, durationMs: null }),
      recommendedTrack({ musicId: 3 }),
    ],
  });

  const answer = await requestChat({ prompt: '추천', current: null, history: [] });

  // 버리는 판단은 이제 "카탈로그에 있는가" 가 아니라 **서버가 말해 주는 실제 신호**다.
  assert.deepEqual(answer.tracks.map((track) => track.musicId), [3]);
});

test('추천 구간이 곡 길이 안일 때만 순간을 옮긴다', async () => {
  setReply({ reply: '', tracks: [recommendedTrack({ musicId: 2, startMs: 45_000 })] });
  let answer = await requestChat({ prompt: '추천', current: null, history: [] });
  assert.equal(answer.tracks[0].moment, 45);

  // 곡 길이(120초) 밖이면 믿지 않고 곡 자신의 순간으로 되돌아간다.
  setReply({ reply: '', tracks: [recommendedTrack({ musicId: 2, startMs: 999_000 })] });
  answer = await requestChat({ prompt: '추천', current: null, history: [] });
  assert.equal(answer.tracks[0].moment, 36);
});

test('구간 이름은 이어듣기와 같은 표로 옮긴다 — 화면마다 다른 이름이 생기지 않는다', async () => {
  setReply({ reply: '', tracks: [recommendedTrack({ musicId: 2, sectionLabel: 'CHORUS' })] });
  const answer = await requestChat({ prompt: '추천', current: null, history: [] });
  assert.equal(answer.tracks[0].recommended.sectionLabel, '코러스');
});

test('서버가 알려 준 실패 사유를 그대로 보여 준다', () => {
  assert.equal(
    chatErrorMessage(new ApiError(503, 'MUPID_AI_NOT_CONFIGURED', 'AI 대화 기능이 설정되지 않았습니다.')),
    'AI 대화 기능이 설정되지 않았습니다.',
  );
  assert.match(chatErrorMessage(new Error('socket hang up')), /다시 요청해 주세요/);
});
