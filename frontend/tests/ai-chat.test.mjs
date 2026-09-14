import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

/**
 * AI 대화 어댑터. 서버 호출만 가짜로 바꾸고 나머지는 실제 코드 그대로 돌린다.
 *
 * 여기서 잡고 싶은 것은 두 가지다.
 *  - 요청 모양: 역할 이름(USER/AI)과 이전 대화 개수. 어긋나면 서버가 400·404 를 낸다.
 *  - 응답 처리: 카탈로그에 **없는** 추천 곡을 목록에 남기지 않는 것. 남기면 눌러도
 *    재생되지 않는 줄이 생긴다.
 */

const inline = (source) =>
  'data:text/javascript;base64,' + Buffer.from(source).toString('base64');

const sessionStub = inline(`
  export const api = {
    ai: {
      chat: async (body) => {
        globalThis.__aiCalls.push(body);
        return globalThis.__aiReply;
      },
    },
  };
`);

const clientStub = inline(`
  export class ApiError extends Error {
    constructor(status, code, message) { super(message); this.status = status; this.code = code; }
  }
`);

const source = await readFile(new URL('../lib/ai-chat.ts', import.meta.url), 'utf8');
const js = ts
  .transpileModule(source, {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  })
  .outputText.replace("'./api/session'", JSON.stringify(sessionStub))
  .replace("'./api/client'", JSON.stringify(clientStub));

const { requestChat, chatErrorMessage } = await import(inline(js));
const { ApiError } = await import(clientStub);

const catalog = [
  { id: 1002, musicId: 2, title: '첫 번째 곡', artist: '테스트', album: '테스트', genre: '', mood: '',
    moment: 30, duration: 120, match: 100, reason: '', image: '/a.webp', tags: [], sections: [] },
  { id: 1003, musicId: 3, title: '모노 테스트 곡', artist: '테스트', album: '테스트', genre: '', mood: '',
    moment: 30, duration: 120, match: 100, reason: '', image: '/b.webp', tags: [], sections: [] },
];

const recommended = (musicId, extra = {}) => ({
  rank_order: 1, music_id: musicId, title: '아무 곡', artist: '아무개', album: '앨범',
  image_url: null, music_section_id: null, section_label: null,
  start_time_ms: 0, end_time_ms: 0, score: 0.91, reason_code: 'COLD_START',
  reason: '잔향이 길게 남는 곡이에요.', ...extra,
});

const setReply = (reply) => {
  globalThis.__aiCalls = [];
  globalThis.__aiReply = reply;
};

test('보내는 요청은 서버 규약대로 역할을 대문자로 옮기고 이전 대화를 자른다', async () => {
  setReply({ reply: '네', tracks: [] });
  const history = Array.from({ length: 15 }, (_, i) => ({
    id: i, role: i % 2 ? 'assistant' : 'user', text: `말 ${i}`,
  }));

  await requestChat({ prompt: '조금 더 잔잔하게', current: catalog[0], catalog, history });

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
  await requestChat({ prompt: '추천해 줘', current: null, catalog, history: [] });
  assert.equal('music_id' in JSON.parse(JSON.stringify(globalThis.__aiCalls[0])), false);
});

test('추천 곡은 카탈로그의 곡에 사유와 일치율만 얹는다', async () => {
  setReply({
    reply: '이런 결은 어떠세요?',
    tracks: [recommended(3, { score: 0.87, reason: '여운이 긴 곡이에요.' })],
  });

  const answer = await requestChat({ prompt: '잔잔하게', current: catalog[0], catalog, history: [] });

  assert.equal(answer.text, '이런 결은 어떠세요?');
  assert.equal(answer.tracks.length, 1);
  const [track] = answer.tracks;
  // 재생에 필요한 값은 카탈로그의 것이어야 한다. 서버가 준 제목으로 갈아치우지 않는다.
  assert.equal(track.id, 1003);
  assert.equal(track.musicId, 3);
  assert.equal(track.title, '모노 테스트 곡');
  assert.equal(track.match, 87);
  assert.equal(track.reason, '여운이 긴 곡이에요.');
});

test('카탈로그에 없는 곡은 버린다 — 눌러도 재생되지 않는 줄을 만들지 않는다', async () => {
  setReply({ reply: '없는 곡이에요', tracks: [recommended(9001), recommended(2)] });
  const answer = await requestChat({ prompt: '추천', current: null, catalog, history: [] });
  assert.deepEqual(answer.tracks.map((track) => track.musicId), [2]);
});

test('추천 구간이 곡 길이 안일 때만 순간을 옮긴다', async () => {
  setReply({ reply: '', tracks: [recommended(2, { start_time_ms: 45_000 })] });
  let answer = await requestChat({ prompt: '추천', current: null, catalog, history: [] });
  assert.equal(answer.tracks[0].moment, 45);

  setReply({ reply: '', tracks: [recommended(2, { start_time_ms: 999_000 })] });
  answer = await requestChat({ prompt: '추천', current: null, catalog, history: [] });
  assert.equal(answer.tracks[0].moment, 30);
});

test('서버가 알려 준 실패 사유를 그대로 보여 준다', () => {
  assert.equal(
    chatErrorMessage(new ApiError(503, 'MUPID_AI_NOT_CONFIGURED', 'AI 대화 기능이 설정되지 않았습니다.')),
    'AI 대화 기능이 설정되지 않았습니다.',
  );
  assert.match(chatErrorMessage(new Error('socket hang up')), /다시 요청해 주세요/);
});
