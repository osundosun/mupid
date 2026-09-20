import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile, readdir } from 'node:fs/promises';
import ts from 'typescript';

/**
 * 세션 이어받기. S15P21A204-375 의 프론트 절반이다.
 *
 * <h2>무엇이 문제였나</h2>
 *
 * 서버는 응답에 `session_id` 를 실어 대화를 `ai_chat_sessions`·`ai_chat_messages` 에
 * 남긴다. 그런데 `requestChat` 이 그 값을 **읽지도 보내지도 않았다.** 서버 입장에서는
 * 매 요청이 `session_id` 없는 새 대화라 **턴마다 새 세션 행**이 생기고, 한 대화가 여러
 * 세션으로 갈라졌다. 저장은 되는데 이어지지 않는 상태다.
 *
 * <h2>고친 뒤 못박는 것</h2>
 *
 *  - 응답의 `session_id` 가 `ChatAnswer.sessionId` 로 올라온다
 *  - 두 번째 턴의 요청 본문에 그 번호가 `session_id` 로 실린다
 *  - 세션이 없으면(첫 턴) 키 자체가 빠진다 — `null` 이 나가면 서버 검증에 걸린다
 *  - 화면 코드가 응답의 세션 번호를 들고 다음 턴에 넘긴다
 *
 * 서버 호출만 가짜로 바꾸고 나머지는 실제 코드 그대로 돌린다 — `ai-chat.test.mjs` 와
 * 같은 방식이다.
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

const { requestChat } = await import(inline(js));

const catalog = [
  { id: 1002, musicId: 2, title: '곡', artist: '테스트', album: '테스트', genre: '', mood: '',
    moment: 30, duration: 120, match: 100, reason: '', image: '/a.webp', tags: [], sections: [] },
];

/** 서버 본문을 그대로 옮긴 것. `session_id` 를 포함한다. */
const replyWith = (sessionId, extra = {}) => ({
  reply: '네', session_id: sessionId, recommendation_id: null, tracks: [], ...extra,
});

const setReply = (reply) => {
  globalThis.__aiCalls = [];
  globalThis.__aiReply = reply;
};

/** 실제로 나가는 바이트를 본다. `undefined` 키가 지워지는 것은 직렬화가 정한다. */
const wireBody = (index) => JSON.parse(JSON.stringify(globalThis.__aiCalls[index]));

test('응답의 session_id 를 읽어 ChatAnswer 로 올린다', async () => {
  setReply(replyWith(42));
  const answer = await requestChat({ prompt: '추천해 줘', current: null, catalog, history: [] });
  assert.equal(answer.sessionId, 42);
});

test('두 번째 턴은 같은 세션으로 나간다 — 턴마다 새 세션이 열리지 않는다', async () => {
  // 1턴: 세션이 없다. 서버가 42 를 발급한다.
  setReply(replyWith(42));
  const first = await requestChat({ prompt: '잔잔하게', current: null, catalog, history: [] });
  assert.equal(first.sessionId, 42);
  // 첫 턴에는 실을 번호가 없다. 키가 빠져야 서버가 새 세션을 연다.
  assert.equal('session_id' in wireBody(0), false, '첫 턴에 session_id 가 나가면 안 된다');

  // 2턴: 1턴이 받은 번호를 그대로 되돌려 보낸다. 이것이 재현의 핵심이다.
  globalThis.__aiCalls = [];
  setReply(replyWith(42));
  const second = await requestChat({
    prompt: '조금 더 빠르게', current: null, catalog, history: [],
    sessionId: first.sessionId,
  });

  assert.equal(wireBody(0).session_id, 42, '두 번째 턴이 같은 세션으로 가야 한다');
  assert.equal(second.sessionId, 42);

  // 3턴도 같은 번호로 이어진다 — 한 대화가 한 세션으로 유지된다.
  globalThis.__aiCalls = [];
  setReply(replyWith(42));
  await requestChat({
    prompt: '다른 것도', current: null, catalog, history: [], sessionId: second.sessionId,
  });
  assert.equal(wireBody(0).session_id, 42);
});

test('세션이 없으면(null) 키를 보내지 않는다 — null 은 서버 검증에 걸린다', async () => {
  setReply(replyWith(null));
  const answer = await requestChat({
    prompt: '추천', current: null, catalog, history: [], sessionId: null,
  });

  assert.equal('session_id' in wireBody(0), false);
  // 저장에 실패한 응답이다. 답변은 정상이고 번호만 없다.
  assert.equal(answer.sessionId, null);
  assert.equal(answer.text, '네');
});

test('session_id 가 아예 없는 응답(구 서버)에도 터지지 않는다', async () => {
  // 배포 순서가 어긋나면 화면이 새 서버보다 먼저 뜰 수 있다.
  setReply({ reply: '네', recommendation_id: null, tracks: [] });
  const answer = await requestChat({ prompt: '추천', current: null, catalog, history: [] });
  assert.equal(answer.sessionId, null);
  assert.equal(answer.text, '네');
});

// ─────────────────────────────────────────────────────────────── 화면(hook) 쪽

const hook = await readFile(new URL('../hooks/use-mupid.ts', import.meta.url), 'utf8');

test('화면이 응답의 세션 번호를 들고 다음 턴에 넘긴다', () => {
  // 화면을 렌더하지 않고 소스로 확인한다. 이 저장소의 나머지 시험과 같은 방식이다
  // (훅은 React 상태·타이머에 얽혀 있어 단위로 띄우지 않는다).
  assert.match(hook, /chatSessionRef/,
    '세션을 들고 있을 자리가 있어야 한다');
  assert.match(hook, /chatSessionRef\.current = answer\.sessionId/,
    '응답의 세션 번호를 저장해야 한다');
  assert.match(hook, /sessionId: chatSessionRef\.current/,
    '다음 요청에 그 번호를 실어야 한다');
});

test('새 대화가 세션을 끊는다 — 안 끊으면 지운 대화에 이어 붙는다', () => {
  assert.match(hook, /const startNewChat = \(\) => \{/);
  // 새 대화 본문 안에서 세션을 비운다.
  const body = hook.slice(hook.indexOf('const startNewChat = () => {'));
  const cut = body.slice(0, body.indexOf('\n  };'));
  assert.match(cut, /chatSessionRef\.current = null/,
    '새 대화는 세션을 비워야 한다');
  assert.match(cut, /setMessages\(/, '대화 목록도 인사 한 마디로 되돌려야 한다');
  // 진행 중인 요청을 끊지 않으면 지운 뒤 도착한 답이 새 대화에 섞인다.
  assert.match(cut, /chatAbort\.current\?\.abort\(\)/);
});

test('새 대화 진입점이 화면에 노출돼 있다', async () => {
  const files = await readdir(new URL('../components/mupid', import.meta.url));
  let exposed = false;
  for (const file of files) {
    const text = await readFile(new URL(`../components/mupid/${file}`, import.meta.url), 'utf8');
    if (text.includes('m.startNewChat')) exposed = true;
  }
  assert.equal(exposed, true, '새 대화를 시작할 수 있는 경로가 화면에 있어야 한다');
});
