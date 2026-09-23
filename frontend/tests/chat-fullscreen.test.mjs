import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';

/*
 * 폰에서 AI 음악 탐색(파생곡 추천) 패널이 화면 전체를 채우고, X 한 번에 닫힌다
 * (S15P21A204-438).
 *
 * X 가 한 번에 안 닫히던 원인은 **입력칸 포커스에 따라 패널의 틀이 바뀐 것**이다.
 * 패널은 열리자마자 입력칸에 커서를 두어 `.composing` 으로 뜬다. X 를 누르면
 * `mousedown` 이 포커스를 빼앗아 `.composing` 이 풀리고 패널이 147px 내려앉는다.
 * 이어지는 `mouseup`·`click` 은 X 가 아니라 그 자리에 들어온 뒤쪽 화면에 떨어졌다.
 * 헤드리스 크롬(384x823, 터치 에뮬레이션)에서 이 순서를 그대로 확인했다:
 *
 *   mousedown:X → focusout:입력칸 → mouseup:'내 계정 메뉴' → click:배경
 *
 * 탭 한 번이 실제로 닫히는지는 브라우저가 있어야 보인다. 여기서는 그 원인이
 * 되돌아오지 않았는지 — **폰에서 `.composing` 이 틀을 따로 갖지 않는지** — 를 본다.
 */
const read = (path) => readFileSync(new URL('../' + path, import.meta.url), 'utf8');
const sheet = read('app/chat-fullscreen.css');
const mobileQuery = '@media (max-width: 767px), (pointer: coarse) and (max-height: 520px)';

/** 폰 규칙 본문. 선택자와 선언을 함께 돌려준다. */
const mobileRule = () => {
  const media = sheet.slice(sheet.indexOf(mobileQuery));
  assert.ok(sheet.includes(mobileQuery), '폰(세로·눕힌 터치 기기) 미디어 쿼리가 없다');
  const match = /\{\s*([^{}]*?)\s*\{([^}]*)\}/.exec(media.slice(mobileQuery.length));
  assert.ok(match, '폰 규칙을 찾지 못했다');
  return { selector: match[1], body: match[2] };
};

void test('globals.css loads the fullscreen sheet after every other chat-panel rule', () => {
  const globals = read('app/globals.css');
  const imports = [...globals.matchAll(/@import '\.\/([^']+)';/g)].map((m) => m[1]);
  const at = imports.indexOf('chat-fullscreen.css');
  assert.ok(at >= 0, 'chat-fullscreen.css 를 불러오지 않는다');
  // 패널 자리를 정하는 파일들보다 뒤여야 같은 우선순위에서 이긴다.
  for (const other of ['mupid.css', 'desktop-home.css', 'playback-details.css', 'responsive-polish.css']) {
    assert.ok(imports.indexOf(other) < at, `${other} 가 chat-fullscreen.css 보다 뒤에 온다`);
  }
});

void test('on phones the panel fills the viewport and pads by the safe-area insets', () => {
  const { body } = mobileRule();
  for (const side of ['top', 'right', 'bottom', 'left']) {
    assert.match(body, new RegExp(`${side}: 0 !important`), `${side} 가 화면 끝에 붙지 않는다`);
  }
  assert.match(body, /height: auto !important/);
  assert.match(body, /max-width: none !important/);
  // 네 방향 인셋을 안쪽 여백으로 비운다. `env()` 가 아니라 변수를 거친다.
  const padding = /padding:([^;]+);/.exec(body)?.[1] ?? '';
  for (const side of ['top', 'right', 'bottom', 'left']) {
    assert.ok(padding.includes(`var(--safe-area-inset-${side}, 0px)`), `padding 에 ${side} 인셋이 없다`);
  }
  // 키보드와 제스처바는 더하지 않고 겹친다(dialog-viewport.css 와 같은 이유).
  assert.match(padding, /max\(var\(--safe-area-inset-bottom, 0px\), var\(--keyboard-inset, 0px\)\)/);
  assert.ok(!sheet.includes('env(safe-area-inset'), 'env() 를 직접 부른다');
});

void test('typing does not change the panel frame on phones, so the X stays under the finger', () => {
  // 같은 규칙이 `.composing` 까지 덮어야 포커스가 빠져도 틀이 그대로다.
  const { selector } = mobileRule();
  assert.ok(selector.includes('.chat-panel.composing'), '.composing 이 폰 규칙에서 빠졌다 — X 첫 탭이 다시 뒤쪽 화면에 떨어진다');

  // 다른 파일이 폰에서 `.composing` 에 따로 자리를 주던 규칙이 되살아나면 안 된다.
  const composingFrame = /\.chat-panel\.composing\s*\{[^}]*\b(top|bottom|height|max-height)\s*:/;
  for (const name of readdirSync(new URL('../app/', import.meta.url))) {
    if (!name.endsWith('.css') || name === 'chat-fullscreen.css') continue;
    assert.ok(!composingFrame.test(read('app/' + name)), `app/${name} 가 .composing 에 틀을 따로 준다`);
  }
});

void test('the close button still closes the panel directly', () => {
  const chat = read('components/mupid/chat.tsx');
  assert.match(chat, /aria-label="AI 음악 탐색 닫기"\s*onClick=\{m\.closeChat\}/);
});
