import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

import { aiChat } from './fixtures/ai-chat-module.mjs';

/*
 * "3곡 추천했다" 고 말하면서 2곡만 그리는 화면 (S15P21A204-421).
 *
 * ## 무엇이 문제였나
 *
 * 챗봇은 "3곡 골라뒀어요" 라고 답하고, 툴 호출 기록 줄에도 `곡 3건` 이 찍혔다. 그런데
 * 곡 카드는 2장이었다. 곡이 **말없이** 사라지는 자리가 둘이다.
 *
 *   1) `lib/ai-chat.ts` 의 `resolve()` — 아직 재생할 수 없는 곡(`playable` false)을
 *      버린다. 눌러도 재생되지 않는 줄을 만들지 않으려는 것이라 **판단 자체는 옳다.**
 *   2) `components/mupid/chat.tsx` 의 곡 목록 — 답변이 온 뒤 사용자가 '관심 없음' 으로
 *      표시한 곡을 가린다.
 *
 * 세 번째 자리가 있었다: 화면 카탈로그(앞쪽 100곡)에 없어서 버리던 것. 서버가 곡을
 * 통째로 실어 주게 되면서 **없어졌다** (S15P21A204-424).
 *
 * 둘 다 사용자에게는 똑같이 보인다: 한 곡이 아무 말 없이 없어졌다. 이 저장소가 반복해
 * 겪은 **조용한 실패**다(`chat-tool-calls.tsx`·`ai-chat.ts` 주석에 이력이 있다).
 *
 * ## 여기서 고정하는 것
 *
 *  · 추천 3건 중 1건이 카탈로그에 없으면 화면이 그 차이를 **말한다**
 *  · 추천이 0건이거나 툴 호출이 아예 없던 턴에는 **아무 줄도 더하지 않는다**
 *  · 실패한 호출 위에 경고를 **겹쳐 얹지 않는다** — 기록 줄이 이미 사유를 보여 준다
 *  · 같은 도구를 여러 번 부른 턴에 건수를 **더하지 않는다** (새로운 거짓말이 된다)
 *  · 문구에 내부 이름(`recommend_music`·`music_id`·`catalog`)이 **새지 않는다**
 *
 * ## 재는 방법
 *
 * 세는 규칙은 `lib/ai-chat.ts` 의 `missingTracksNotice` 라 **실제로 실행해서** 본다
 * (`ai-chat.test.mjs` 와 같은 방식 — 서버 호출만 가짜로 바꾼다). 화면에 실제로 놓였는지는
 * 소스 구조로 본다(`chat-scroll-and-tools.test.mjs` 와 같은 방식) — 컴포넌트를 통째로
 * 렌더하려면 React·DOM 전체가 필요하다.
 */

const { missingTracksNotice } = aiChat;

/** 화면에 그려진 곡 한 줄. 세는 데 필요한 것은 `id` 뿐이다. */
const track = (id) => ({ id, musicId: id, title: `곡 ${id}`, artist: '테스트' });

/** 성공한 추천 호출 한 건. `trackCount` 가 "서버가 추천했다고 말한 수" 다. */
const call = (trackCount, extra = {}) => ({
  name: 'recommend_music',
  label: '음악 추천',
  arguments: { query: '비 오는 날' },
  succeeded: true,
  trackCount,
  failureReason: null,
  ...extra,
});

// ─────────────────────────────────────────────────────────────
// 1) 이 티켓의 본체
// ─────────────────────────────────────────────────────────────

void test('추천 3건 중 1건이 재생 불가로 빠지면 화면이 그 차이를 말한다', () => {
  // `resolve()` 가 하나를 버려 화면에는 2곡만 남은 상태.
  const notice = missingTracksNotice([call(3)], [track(1), track(2)], []);
  assert.ok(notice, '한 곡이 말없이 사라졌는데 화면이 아무 말도 하지 않는다');
  // 몇 곡을 추천받았고 몇 곡이 빠졌는지가 둘 다 읽혀야 한다.
  assert.match(notice, /3곡/, '추천 건수가 문구에 없다');
  assert.match(notice, /1곡/, '빠진 곡 수가 문구에 없다');
});

void test('답변이 온 뒤 관심 없음으로 표시한 곡도 사유가 남는다', () => {
  // 3곡이 다 화면까지 왔는데, 그중 하나를 사용자가 숨겼다.
  const notice = missingTracksNotice(
    [call(3)],
    [track(1), track(2), track(3)],
    [2],
  );
  assert.ok(notice);
  assert.match(notice, /관심 없음/, '가려진 이유를 말하지 않는다');
});

void test('추천이 전부 사라진 턴에도 말한다 — 가장 헷갈리는 경우다', () => {
  /*
   * `use-mupid.ts` 는 곡이 하나도 없으면 `tracks` 를 아예 넣지 않는다(`undefined`).
   * 그래서 곡 목록 블록이 통째로 빠지고, 예전에는 답변만 덩그러니 남았다.
   */
  const notice = missingTracksNotice([call(2)], undefined, []);
  assert.ok(notice, '추천이 전부 사라졌는데 화면이 침묵한다');
  assert.match(notice, /2곡/);
});

// ─────────────────────────────────────────────────────────────
// 2) 없던 줄을 만들어 내지 않는가
// ─────────────────────────────────────────────────────────────

void test('그냥 대화만 한 턴에는 아무 줄도 더하지 않는다', () => {
  assert.equal(missingTracksNotice(undefined, undefined, []), null);
  assert.equal(missingTracksNotice([], undefined, []), null);
});

void test('추천이 원래 0건인 턴에는 아무 줄도 더하지 않는다', () => {
  // 기록 줄이 이미 `조건에 맞는 곡 없음` 을 보여 준다. 여기서 또 말하면 군더더기다.
  assert.equal(missingTracksNotice([call(0)], undefined, []), null);
});

void test('빠진 곡이 없으면 아무 줄도 더하지 않는다', () => {
  const notice = missingTracksNotice([call(2)], [track(1), track(2)], []);
  assert.equal(notice, null, '멀쩡한 답변 밑에 군더더기 한 줄이 붙는다');
});

void test('실패한 호출 위에 경고를 겹쳐 얹지 않는다', () => {
  /*
   * 실패 사유는 접힌 기록 줄에 이미 그대로 있다(`chat-tool-calls.tsx` 의 `resultText`).
   * 여기서 한 번 더 말하면 같은 사건이 두 줄이 되고, 무엇을 봐야 할지 흐려진다.
   */
  const failed = call(0, { succeeded: false, failureReason: '곡 추천 서버가 응답하지 않습니다.' });
  assert.equal(missingTracksNotice([failed], undefined, []), null);
});

// ─────────────────────────────────────────────────────────────
// 3) 새로운 거짓말을 만들지 않는가
// ─────────────────────────────────────────────────────────────

void test('같은 도구를 여러 번 부른 턴에 건수를 더하지 않는다', () => {
  /*
   * 모델은 한 턴에 같은 도구를 여러 번 부른다(`ai.max-tool-rounds`) — 말을 조금 바꿔
   * 다시 부르는 것이 실측으로 확인돼 있다(`chat-tool-calls.tsx` 주석). 3번을 더해
   * 9곡으로 세면 화면은 "9곡 중 7곡이 빠졌어요" 라는 **새로운 거짓말**을 하게 된다.
   */
  const notice = missingTracksNotice([call(3), call(3), call(3)], [track(1), track(2)], []);
  assert.ok(notice);
  assert.match(notice, /3곡 중/, '여러 번 부른 건수를 더해 세고 있다');
  assert.equal(/9곡/.test(notice), false, '호출 수만큼 추천 건수가 부풀었다');
});

void test('그린 곡이 추천 건수보다 많아도 음수를 말하지 않는다', () => {
  // 같은 곡이 두 번 추천되는 등으로 뒤집힐 수 있다. 그때는 할 말이 없다.
  const notice = missingTracksNotice([call(1)], [track(1), track(2)], []);
  assert.equal(notice, null);
  assert.equal(/-\d/.test(String(notice)), false);
});

void test('문구는 사용자용이다 — 내부 이름이 새지 않는다', () => {
  const notices = [
    missingTracksNotice([call(3)], [track(1), track(2)], []),
    missingTracksNotice([call(3)], [track(1), track(2), track(3)], [3]),
    missingTracksNotice([call(3)], [track(1), track(2)], [2]),
  ];
  for (const notice of notices) {
    assert.ok(notice);
    for (const internal of ['recommend_music', 'music_id', 'catalog', 'trackCount', 'null']) {
      assert.equal(
        notice.includes(internal),
        false,
        `내부 이름이 화면 문구에 새어 나왔다: ${notice}`,
      );
    }
  }
});

void test('두 이유가 겹치면 한 줄에 둘 다 적는다', () => {
  // 3건 추천 → 하나는 아직 재생할 수 없어 버려지고, 남은 둘 중 하나는 숨긴 곡이다.
  const notice = missingTracksNotice([call(3)], [track(1), track(2)], [2]);
  assert.ok(notice);
  assert.match(notice, /재생할 수 없어/);
  assert.match(notice, /관심 없음/);
});

// ─────────────────────────────────────────────────────────────
// 4) 실제로 화면에 놓였는가
// ─────────────────────────────────────────────────────────────

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');

/** 주석을 걷어낸 **코드만** 남긴다 (`chat-scroll-and-tools.test.mjs` 와 같은 이유다). */
const code = (text) =>
  text.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/[^\n]*/g, '$1');

const chatTsx = await read('components/mupid/chat.tsx');
const noteTsx = await read('components/mupid/chat-tracks-note.tsx');
const css = await read('app/mupid.css');

void test('빠진 곡 줄이 곡 목록 자리에, 곡 카드 아래에 그려진다', () => {
  const chat = code(chatTsx);
  const tracksIndex = chat.indexOf('className="chat-tracks"');
  const noteIndex = chat.indexOf('<ChatTracksNote');
  const bodyEnd = chat.indexOf('{m.chatLoading &&');
  assert.ok(noteIndex > -1, '빠진 곡 줄이 화면에 붙어 있지 않다');
  assert.ok(noteIndex > tracksIndex, '곡 카드보다 위에 있다 — 묻기 전에 먼저 답하는 꼴이다');
  assert.ok(noteIndex < bodyEnd, '말풍선 밖으로 나갔다 — 어느 답변의 이야기인지 모른다');
  /*
   * 곡 목록 블록 **밖**이어야 한다. 추천이 전부 사라지면 `message.tracks` 가
   * `undefined` 라 그 블록이 통째로 빠지고, 안에 있으면 이 줄도 함께 사라진다.
   *
   * 들여쓰기로 형제 관계를 잰다 — 같은 깊이면 `{message.tracks && …}` 안이 아니다.
   */
  const indentOf = (needle) => /^[ \t]*/.exec(
    chatTsx.slice(chatTsx.lastIndexOf('\n', chatTsx.indexOf(needle)) + 1),
  )[0].length;
  assert.equal(
    indentOf('<ChatTracksNote'),
    indentOf('{message.tracks && ('),
    '곡 목록 블록 안에 있다 — 추천이 전부 사라진 턴에 함께 사라진다',
  );
  // 세는 근거는 이미 화면에 들어와 있는 툴 호출 기록이다. 서버를 새로 부르지 않는다.
  assert.match(chat, /calls=\{message\.toolCalls\}/);
});

void test('세는 규칙은 한 곳에만 있다 — 화면마다 다른 문구가 생기지 않는다', () => {
  // 컴포넌트는 `lib/ai-chat.ts` 의 판단을 놓기만 한다.
  assert.match(code(noteTsx), /import \{ missingTracksNotice \} from '@\/lib\/ai-chat'/);
  // 문구를 컴포넌트가 따로 짜 두면 곧 두 벌이 된다.
  assert.equal(/곡 중/.test(code(noteTsx)), false, '컴포넌트가 문구를 따로 짓고 있다');
  assert.equal(/곡 중/.test(code(chatTsx)), false, 'chat.tsx 가 문구를 따로 짓고 있다');
});

void test('빠진 곡 줄에 CSS 가 있다 — 실패처럼 붉게 칠하지 않는다', () => {
  assert.match(css, /\.chat-tracks-note\s*\{/);
  const rule = /\.chat-tracks-note\s*\{([^}]*)\}/.exec(css)[1];
  // 실패 사유 줄의 붉은 계열을 쓰면 요청이 정상으로 끝난 턴이 장애로 읽힌다.
  const failure = /\.chat-tool-failure\s*\{([^}]*)\}/.exec(css)[1];
  const colorOf = (block) => /color:\s*([^;]+);/.exec(block)?.[1]?.trim();
  assert.ok(colorOf(rule), '색이 지정돼 있지 않다');
  assert.notEqual(colorOf(rule), colorOf(failure), '실패 사유와 같은 색이다 — 장애로 읽힌다');
});

/*
 * ─────────────────────────────────────────────────────────────
 * 이 시험이 보지 못하는 것 (숨기지 않는다)
 * ─────────────────────────────────────────────────────────────
 *
 * 세는 규칙은 실제로 돌려서 보지만, **그 줄이 눈에 보이는지**는 여기서 알 수 없다 —
 * 브라우저가 있어야 한다. 곡 카드가 많은 턴에서 이 줄이 대화 영역 밖으로 밀려나지
 * 않는지는 `chat-scroll-and-tools.test.mjs` 의 끝 주석에 적힌 방식으로 브라우저에서
 * 재야 한다(`innerText` 로 보면 안 된다 — DOM 에 있고 화면에는 없는 상태를 "정상" 으로
 * 잘못 보고하게 된다).
 *
 * 추천 건수의 근거는 서버가 센 `track_count` 다. 서버가 그 숫자를 틀리게 세면 이 줄도
 * 함께 틀린다. 그것은 화면에서 고칠 수 있는 문제가 아니다.
 */
