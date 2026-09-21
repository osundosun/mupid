import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

/*
 * 화상 키보드가 입력란을 가리던 문제 (S15P21A204-389).
 *
 * 이 시험은 **무엇을 어디에 적고, 어떤 선언이 있어야 하는지**만 본다. 키보드가
 * 실제로 올라오는 순간은 에뮬레이터/실기기에서만 나온다 — 그 한계는 숨기지 않고
 * 아래 마지막 절에 적어 둔다.
 *
 * React 는 이 파일에서 필요 없다. 재는 쪽(`watchKeyboardInset`)만 떼어 검사한다.
 */
const reactStub =
  'data:text/javascript;base64,' +
  Buffer.from(
    `export const useEffect = () => {};
     export const useState = () => [0, () => {}];`,
  ).toString('base64');

const source = await readFile(
  new URL('../hooks/use-keyboard-inset.ts', import.meta.url),
  'utf8',
);
const js = ts
  .transpileModule(source, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.ESNext,
    },
  })
  .outputText.replace(/'react'/, JSON.stringify(reactStub));

const { watchKeyboardInset } = await import(
  'data:text/javascript;base64,' + Buffer.from(js).toString('base64')
);

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');

/** `<html>`·`<body>` 의 인라인 스타일 흉내. 적힌 것만 들고 있으면 된다. */
const root = () => {
  const written = new Map();
  return {
    written,
    style: { setProperty: (name, value) => written.set(name, value) },
  };
};

/**
 * 창 하나를 세운다.
 *
 * `visualViewport` 가 없으면 웹/데스크톱인 셈이다. `height` 는 키보드가 올라온
 * 만큼 줄어든 "보이는 높이"다.
 */
const stage = ({ innerHeight = 800, viewport = null } = {}) => {
  const listeners = new Map();
  const html = root();
  const body = root();
  globalThis.window = {
    innerHeight,
    addEventListener: (event, handler) => {
      listeners.set(event, [...(listeners.get(event) ?? []), handler]);
    },
  };
  if (viewport) {
    globalThis.window.visualViewport = {
      ...viewport,
      addEventListener: (event, handler) => {
        listeners.set(event, [...(listeners.get(event) ?? []), handler]);
      },
      removeEventListener: () => {},
    };
  }
  globalThis.document = { documentElement: html, body };
  return {
    html,
    body,
    fire: (event) => (listeners.get(event) ?? []).forEach((one) => one()),
  };
};

/** 보이는 높이만 바꾼다. 키보드가 올라온 상황을 흉내 낸다. */
const keyboard = (view, height, offsetTop = 0) => {
  view.height = height;
  view.offsetTop = offsetTop;
};

// ─────────────────────────────────────────────────────────────
// 재기
// ─────────────────────────────────────────────────────────────

void test('a keyboard that covers the bottom is written to html and body', () => {
  const view = stage({ viewport: { height: 800, offsetTop: 0 } });
  watchKeyboardInset();
  assert.equal(view.html.written.get('--keyboard-inset'), '0px');

  // 800 중 300 이 키보드에 가렸다.
  keyboard(globalThis.window.visualViewport, 500);
  view.fire('resize');
  assert.equal(view.html.written.get('--keyboard-inset'), '300px');
  // 대화상자는 포털이라 `body` 아래에 붙는다. 여기 없으면 상속으로 못 받아
  // 늘 0 으로 읽는다 — 그래서 양쪽에 적는다.
  assert.equal(view.body.written.get('--keyboard-inset'), '300px');
});

void test('the chrome collapsing and expanding is not a keyboard', () => {
  const view = stage({ viewport: { height: 800, offsetTop: 0 } });
  watchKeyboardInset();
  // 주소창이 접히고 펴지는 몫은 수십 px 다. 그것까지 키보드로 세면 대화상자가
  // 스크롤할 때마다 흔들린다.
  keyboard(globalThis.window.visualViewport, 760);
  view.fire('resize');
  assert.equal(view.html.written.get('--keyboard-inset'), '0px');
});

void test('the part iOS scrolled away is not counted as covered', () => {
  // iOS 가 입력칸을 보이려고 페이지를 60px 밀어 올렸다. 그만큼은 위로 사라진
  // 것이라 아래 가림에서 뺀다 — 안 빼면 키보드 높이를 60px 과다 계산한다.
  const view = stage({ viewport: { height: 800, offsetTop: 0 } });
  watchKeyboardInset();
  keyboard(globalThis.window.visualViewport, 500, 60);
  view.fire('resize');
  assert.equal(view.html.written.get('--keyboard-inset'), '240px');
});

void test('the web build without a visual viewport writes nothing', () => {
  const view = stage();
  watchKeyboardInset();
  // 키가 아예 없으면 CSS 의 `var(--keyboard-inset, 0px)` 가 기본값으로 내려간다.
  // 웹에서는 그래서 `max-height` 가 늘 쓰던 값 그대로다.
  assert.equal(view.html.written.has('--keyboard-inset'), false);
});

// ─────────────────────────────────────────────────────────────
// 선언과 소비 — 여기가 실제로 어긋났던 자리다
// ─────────────────────────────────────────────────────────────

const [manifest, layout, dialogCss, loginCss, login, app] = await Promise.all([
  read('../app/gen/android/app/src/main/AndroidManifest.xml'),
  read('app/layout.tsx'),
  read('app/dialog-viewport.css'),
  read('app/demo-login.css'),
  read('components/mupid/demo-login.tsx'),
  read('components/mupid-app.tsx'),
]);

void test('the activity resizes the window instead of panning it', () => {
  // `windowSoftInputMode` 가 없으면 기본값 `adjustUnspecified` 라 시스템이
  // 마음대로 정한다 — 실기기에서는 키보드를 **덮는 쪽**으로 정해졌다.
  assert.match(manifest, /android:windowSoftInputMode="adjustResize"/);
  // `adjustPan` 은 화면을 통째로 밀어 올린다. 시스템 바 여백과 어긋나므로 쓰지 않는다.
  // 주석에도 이 낱말이 나오므로 **속성값만** 본다.
  const modes = [...manifest.matchAll(/android:windowSoftInputMode="([^"]+)"/g)].map(
    (one) => one[1],
  );
  assert.ok(modes.length > 0, 'windowSoftInputMode 속성을 찾지 못했다');
  for (const mode of modes) {
    assert.equal(mode.includes('adjustPan'), false, mode);
  }
});

void test('the viewport lets the keyboard overlay without resizing the layout', () => {
  // 이 앱이 `100dvh` 로 자리를 잡는 이상, 키보드가 `dvh` 안에 들어와야 한다.
  // 웹뷰에서 `resizes-content` 는 실제로 레이아웃을 줄이지 않아 어긋났다.
  assert.match(layout, /interactiveWidget:\s*'overlays-content'/);
});

void test('the dialog shortens itself by the height the keyboard covers', () => {
  assert.match(
    dialogCss,
    /max-height:\s*calc\(\s*100dvh\s*-\s*32px\s*-\s*var\(--keyboard-inset,\s*0px\)\s*\)\s*!important/,
    '대화상자가 키보드 몫을 빼지 않으면 아래쪽 입력란이 그대로 덮인다',
  );
  // `100vh` 는 키보드가 열리든 말든 늘 "가장 큰 높이"다. 이 자리에서 쓰면
  // 보이는 영역보다 큰 값이 나와 아래가 잘린다.
  for (const sheet of [dialogCss, loginCss]) {
    assert.equal(sheet.includes('100vh'), false, '뷰포트 단위는 dvh 여야 한다');
  }
});

void test('the login and signup form re-reveals whatever field took focus', () => {
  // 입력란마다 붙이지 않고 폼 하나에서 받는다 — 회원가입 입력란을 하나 빠뜨리면
  // 그 자리만 조용히 키보드 뒤로 들어간다.
  assert.match(login, /onFocusCapture=/);
  assert.match(login, /scrollIntoView\(\{\s*block:\s*'nearest'\s*\}\)/);
});

void test('the inset is measured once for the whole app', () => {
  // 대화상자는 포털로 `body` 아래에 붙어 `MupidApp` 나무 밖이다. 각자 재게 하면
  // 같은 값을 두세 번 재고, 빠뜨린 자리는 조용히 가려진다.
  assert.ok(app.includes('watchKeyboardInset'), '키보드 인셋을 재는 쪽이 붙어 있지 않다');
  assert.ok(app.includes("from '@/hooks/use-keyboard-inset'"));
});
