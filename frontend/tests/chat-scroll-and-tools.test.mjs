import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

/*
 * 대화에 새 내용이 오면 **화면이 실제로 움직이는가** (S15P21A204-409).
 *
 * ## 왜 이 시험이 필요한가
 *
 * 티켓의 본질은 "응답이 왔는데 화면이 안 움직인다" 였다. 그런데 이 저장소의 프론트
 * 시험은 대부분 **소스에 어떤 선언이 있는지**만 본다. 그것으로는 이 결함을 못 잡는다 —
 * 두 번 놓쳤다.
 *
 *   1) `chat.tsx` 의 효과가 `if (composing)` 에 매달려 있었다. `composing` 은 "지금
 *      입력 중인가" 이지 "새 말이 왔는가" 가 아니다. 입력칸에 커서를 두는 순간 `true` 가
 *      되고, 그 뒤로는 응답이 도착해도 값이 변하지 않아 **효과가 다시 돌지 않았다.**
 *   2) `use-mupid.ts` 는 `scrollIntoView({behavior:'smooth'})` 를 불렀는데, 실측에서
 *      이것은 즉시 아무것도 하지 않고 끝났다(3.5초를 기다려도 `scrollTop` 이 0).
 *      같은 파일 안에서 `auto` 는 546 으로 곧바로 움직였다.
 *
 * 그래서 여기서는 **선언이 아니라 동작**을 본다:
 *  · 효과가 응답 도착(`messages` 가 늘어남)에 반응해 **다시 도는가**
 *  · 그 효과가 **끝까지 내리는가**(`scrollTop = scrollHeight`)
 *  · `behavior:'smooth'` 에 다시 기대지 **않는가** — 이 환경에서 멈춘다
 *
 * ## 재는 방법
 *
 * 컴포넌트를 통째로 렌더하지 않는다(React·DOM 전체가 필요하다). 대신 **효과가 무엇에
 * 반응하고 무엇을 하는지**를 소스에서 뽑아 최소한의 실행으로 확인한다. 한계는 숨기지
 * 않고 아래에 적어 둔다 — 실제 좌표 검증은 브라우저에서 사람이/도구가 한다.
 */

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');

/**
 * 주석을 걷어낸 **코드만** 남긴다.
 *
 * 이 저장소는 주석을 한국어로 길게 남긴다. 409 의 원인 설명에는 `scrollIntoView` 나
 * `recommend_music` 같은 **금지하려는 낱말 자체**가 등장한다(그것이 결함이었으므로).
 * 주석을 안 걷어내면 "설명했다" 가 "쓰고 있다" 로 읽혀 시험이 거짓으로 깨진다 —
 * 실제로 여기서 한 번 걸렸다. 재는 것은 언제나 **코드**다.
 */
const code = (source) =>
  source
    .replace(/\/\*[\s\S]*?\*\//g, '')   // 블록 주석
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1'); // 줄 주석 (`http://` 는 건드리지 않는다)

const chatTsx = await read('components/mupid/chat.tsx');
const hookTs = await read('hooks/use-mupid.ts');
const css = await read('app/mupid.css');

// 코드만 보는 검사에 쓴다.
const chatTsxCode = code(chatTsx);
const hookTsCode = code(hookTs);

// ─────────────────────────────────────────────────────────────
// 1) 효과가 무엇에 반응하는가 — 의존성 배열
// ─────────────────────────────────────────────────────────────

/**
 * 스크롤을 담당하는 효과의 의존성 배열을 뽑는다.
 *
 * `scrollTop` 을 만지는 효과를 찾는다 — 그것이 스크롤을 내리는 유일한 자리여야 한다.
 */
const scrollEffects = (source) => {
  const out = [];
  const re = /useEffect\(\(\)\s*=>\s*\{([\s\S]*?)\}\s*,\s*\[([^\]]*)\]\s*\)/g;
  for (const m of source.matchAll(re)) {
    if (m[1].includes('scrollTop') || m[1].includes('scrollIntoView')) {
      out.push({ body: m[1], deps: m[2].split(',').map((d) => d.trim()).filter(Boolean) });
    }
  }
  return out;
};

void test('대화 스크롤은 composing 이 아니라 "무엇이 늘었는가"에 반응한다', () => {
  const effects = scrollEffects(chatTsx);
  assert.ok(effects.length > 0, 'chat.tsx 에서 스크롤 효과를 찾지 못했다');
  const [effect] = effects;

  // 곡 카드까지 포함해 새 내용이 생기면 반응해야 한다.
  assert.ok(
    effect.deps.some((d) => d.includes('messages')),
    `메시지 변화에 반응하지 않는다: [${effect.deps.join(', ')}]`,
  );
  // `composing` 하나에만 매달리면 409 가 그대로 재현된다.
  assert.notDeepEqual(effect.deps, ['composing'], 'composing 에만 매달려 있다 — 409 그대로다');
  // 로딩이 끝나는 순간(말풍선이 실제로 그려진 뒤)도 반응해야 한다.
  assert.ok(
    effect.deps.some((d) => d.includes('chatLoading')),
    `로딩 상태 변화에 반응하지 않는다: [${effect.deps.join(', ')}]`,
  );
});

void test('끝까지 내리는 쪽 효과가 하나 이상 있다 — sentinel 에만 기대지 않는다', () => {
  // 실측: sentinel 을 `block:'end'` 로 내리면 546, 컨테이너를 끝까지 내리면 564.
  // 곡 카드가 온전히 보이는 것은 후자다.
  const scrollsContainer = /scrollTop\s*=\s*[A-Za-z_$][\w.$]*\.scrollHeight/.test(chatTsx)
    || /scrollTop\s*=\s*[A-Za-z_$][\w.$]*\.scrollHeight/.test(hookTs);
  assert.ok(scrollsContainer, '컨테이너를 끝까지 내리는(`scrollTop = ….scrollHeight`) 자리가 없다');
});

// ─────────────────────────────────────────────────────────────
// 2) 멈추는 방식으로 다시 돌아가지 않았는가
// ─────────────────────────────────────────────────────────────

void test('대화 스크롤에 behavior:smooth 를 다시 쓰지 않는다', () => {
  /*
   * 실측: 같은 문서에서 `scrollIntoView({block:'nearest'})` 는 546 으로 즉시 움직였는데
   * `behavior:'smooth'` 를 붙이면 0 에 머물렀다(3.5초 관찰). `chat.tsx` 와
   * `use-mupid.ts` 두 곳이 모두 `smooth` 였고, 그래서 "응답이 왔는데 화면이 안 움직였다".
   *
   * 화면 어디에도 smooth 스크롤이 없어야 한다는 뜻은 아니다. **대화 패널 안에서는**
   * 안 된다는 것이다 — 여기서 도착 지점이 확정되지 않으면 사용자는 곡을 못 본다.
   */
  for (const [name, source] of [['chat.tsx', chatTsxCode], ['use-mupid.ts', hookTsCode]]) {
    for (const effect of scrollEffects(source)) {
      assert.equal(
        effect.body.includes("behavior: 'smooth'"),
        false,
        `${name} 의 대화 스크롤에 behavior:'smooth' 가 남아 있다 — 이 환경에서 멈춘다`,
      );
    }
    // `scrollIntoView` 로 대화를 내리는 자리 자체가 남아 있지 않아야 한다.
    assert.equal(
      /scrollIntoView\(/.test(source),
      false,
      `${name} 이 아직 scrollIntoView 로 대화를 내린다`,
    );
  }
});

void test('곡 카드가 스크롤 영역 안에 들어온다 — 대상이 컨테이너 자신이다', () => {
  // sentinel(`messageEndRef`)은 카드보다 뒤에 있어도 끝까지 가지 못한다. 실제로 내려야
  // 하는 것은 스크롤 영역(`.chat-messages`)이다.
  assert.match(chatTsxCode, /className="chat-messages"[\s\S]{0,120}ref=\{chatBoxRef\}/);
  assert.match(hookTsCode, /const chatBoxRef = useRef/);
  // 그 참조가 모델로 나가야 컴포넌트가 붙일 수 있다.
  assert.match(hookTsCode, /chatBoxRef,/);
});

// ─────────────────────────────────────────────────────────────
// 3) 툴 호출 기록 (결함 B)
// ─────────────────────────────────────────────────────────────

const toolCalls = await read('components/mupid/chat-tool-calls.tsx');
const aiChat = await read('lib/ai-chat.ts');
const endpoints = await read('lib/api/endpoints.ts');

void test('서버가 준 tool_calls 가 화면 모델까지 온다 — 버리지 않는다', () => {
  // 378 커밋 fcefb33 이 `result.session_id` 를 버렸던 전례가 있다. 같은 실수를 막는다.
  assert.match(endpoints, /tool_calls:\s*AiToolCallRecord\[\]/);
  assert.match(aiChat, /toolCalls:\s*toToolCalls\(result\?\.tool_calls\)/);
  assert.match(hookTs, /toolCalls:\s*answer\.toolCalls\.length\s*\?\s*answer\.toolCalls\s*:\s*undefined/);
});

void test('툴 호출이 없던 턴에는 아무것도 그리지 않는다', () => {
  // 그냥 대화만 한 턴에 "도구 호출" 줄을 띄우면 거짓말이 된다.
  assert.match(chatTsx, /message\.toolCalls\s*&&\s*message\.toolCalls\.length\s*>\s*0/);
  assert.match(hookTs, /answer\.toolCalls\.length\s*\?\s*answer\.toolCalls\s*:\s*undefined/);
});

void test('내부 툴 이름을 사용자 화면에 그대로 노출하지 않는다', () => {
  // `recommend_music` 은 개발자용 식별자다. 화면은 서버가 옮긴 `label`(`음악 추천`)을 쓴다.
  const source = code(toolCalls);
  assert.match(source, /\{call\.label\}/);
  assert.equal(
    /recommend_music/.test(source),
    false,
    '화면 코드가 내부 툴 이름을 알고 있다 — 문구는 서버가 정해야 한다',
  );
  // 내부 이름은 시험용 훅에만 남는다.
  assert.match(source, /data-tool=\{call\.name\}/);
});

void test('실패한 툴 호출은 사유까지 보인다 — 조용한 실패로 돌아가지 않는다', () => {
  // 접히는 것은 성공한 호출뿐이다.
  assert.match(toolCalls, /!call\.succeeded\s*\|\|\s*open/);
  assert.match(toolCalls, /chat-tool-failure/);
  // 요약 줄에도 사유가 들어간다.
  assert.match(toolCalls, /실패 — \$\{call\.failureReason\}/);
  // 비활성 버튼으로 두면 눌러도 안 펼쳐진다 — 사유가 화면에 남아야 하므로 그 자체가
  // 문제가 되지 않는다는 것을 명시적으로 남긴다.
  assert.match(toolCalls, /disabled=\{!call\.succeeded\}/);
});

void test('여러 번 불린 툴을 접지 않는다 — 몇 번 불렀는지가 정보다', () => {
  // 서버가 호출 순서대로 전부 준다. 화면도 그대로 그린다(합치지 않는다).
  assert.match(toolCalls, /calls\.map\(\(call, index\)/);
  assert.match(toolCalls, /\$\{index \+ 1\}번째/);
});

void test('기록 줄이 기존 말풍선 구조 안에 들어간다 — 디자인을 깨지 않는다', () => {
  // `.message-body` 안, 곡 목록보다 **위**. 카드 아래면 곡을 다 훑고 나서야 보인다.
  const bodyIndex = chatTsx.indexOf('<div className="message-body">');
  const callsIndex = chatTsx.indexOf('<ChatToolCalls');
  const tracksIndex = chatTsx.indexOf('<div className="chat-tracks">');
  assert.ok(bodyIndex > -1 && callsIndex > -1 && tracksIndex > -1);
  assert.ok(callsIndex > bodyIndex, '기록이 말풍선 밖에 있다');
  assert.ok(callsIndex < tracksIndex, '기록이 곡 목록 아래에 있다 — 근거가 시야 밖으로 밀린다');
  // CSS 가 있어야 실제로 그려진다.
  assert.match(css, /\.chat-tools\s*\{/);
  assert.match(css, /\.chat-tool-failure\s*\{/);
});

/*
 * ─────────────────────────────────────────────────────────────
 * 이 시험이 보지 못하는 것 (숨기지 않는다)
 * ─────────────────────────────────────────────────────────────
 *
 * 여기서 재는 것은 **효과가 무엇에 반응해 무엇을 하는지**까지다. 실제로 화면이
 * 움직였는지는 이 파일에서 알 수 없다 — 브라우저가 있어야 한다.
 *
 * 실측 좌표는 브라우저에서 따로 쟀고(티켓 보고에 값이 있다), 앞으로도 그 자리에서
 * 재야 한다:
 *
 *   const m = document.querySelector('.chat-messages');
 *   const card = document.querySelector('.chat-track');
 *   const visible = Math.max(0,
 *     Math.min(card.getBoundingClientRect().bottom, m.getBoundingClientRect().bottom)
 *     - Math.max(card.getBoundingClientRect().top, m.getBoundingClientRect().top));
 *
 * `innerText` 로 보면 안 된다. DOM 에 있고 글로 잡히는데 화면에는 없는 상태를
 * "정상" 이라고 잘못 보고하게 된다 — 실제로 그렇게 한 번 잘못 보고했다.
 */
