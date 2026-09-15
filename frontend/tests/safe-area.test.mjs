import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

// 인셋을 건네는 쪽만 떼어 검사한다. 안드로이드도 웹뷰도 필요 없다 —
// `window` 자리에 가짜를 세워 두고 **무엇을 읽어 어디에 적는지** 만 본다.
const source = await readFile(
  new URL('../lib/platform/safe-area.ts', import.meta.url),
  'utf8',
);
const js = ts.transpileModule(source, {
  compilerOptions: {
    target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.ESNext,
  },
}).outputText;

const { applySafeArea, readSafeArea, toCssPixels, watchSafeArea } = await import(
  'data:text/javascript;base64,' + Buffer.from(js).toString('base64')
);

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');

/** `<html>` 의 인라인 스타일 흉내. 적힌 것만 들고 있으면 된다. */
const root = () => {
  const written = new Map();
  return {
    written,
    style: { setProperty: (name, value) => written.set(name, value) },
  };
};

/** 창 하나를 세운다. `bridge` 가 없으면 웹(또는 iOS)인 셈이다. */
const stage = ({ ratio = 1, bridge = null } = {}) => {
  const listeners = new Map();
  const element = root();
  globalThis.window = {
    devicePixelRatio: ratio,
    addEventListener: (event, handler) => {
      listeners.set(event, [...(listeners.get(event) ?? []), handler]);
    },
    removeEventListener: (event, handler) => {
      listeners.set(
        event,
        (listeners.get(event) ?? []).filter((one) => one !== handler),
      );
    },
  };
  if (bridge) globalThis.window.__mupidSafeArea = bridge;
  globalThis.document = { documentElement: element };
  return {
    element,
    fire: (event) => (listeners.get(event) ?? []).forEach((one) => one()),
    count: (event) => (listeners.get(event) ?? []).length,
    plug: (value) => {
      globalThis.window.__mupidSafeArea = value;
    },
  };
};

const bars = (insets) => ({ get: () => JSON.stringify(insets) });

// ─────────────────────────────────────────────────────────────
// 기기 픽셀 → CSS 픽셀
// ─────────────────────────────────────────────────────────────

void test('device pixels become CSS pixels at the webview ratio', () => {
  const css = toCssPixels({ top: 63, right: 0, bottom: 42, left: 0 }, 2.625);
  assert.equal(css.top, 24);
  assert.equal(css.bottom, 16);
});

void test('a missing ratio leaves the numbers alone rather than dividing by zero', () => {
  const css = toCssPixels({ top: 48, right: 0, bottom: 0, left: 0 }, 0);
  assert.equal(css.top, 48);
});

// ─────────────────────────────────────────────────────────────
// 읽기
// ─────────────────────────────────────────────────────────────

void test('the web build has no bridge, so nothing is read', () => {
  stage();
  assert.equal(readSafeArea(), null);
});

void test('android hands over four insets at once', () => {
  stage({ ratio: 3, bridge: bars({ top: 72, right: 0, bottom: 48, left: 0 }) });
  assert.deepEqual(readSafeArea(), { top: 24, right: 0, bottom: 16, left: 0 });
});

void test('garbage from the bridge falls back to no insets instead of throwing', () => {
  const warn = console.warn;
  console.warn = () => {};
  try {
    stage({ bridge: { get: () => 'not json' } });
    assert.equal(readSafeArea(), null);
  } finally {
    console.warn = warn;
  }
});

void test('missing or nonsense fields read as zero', () => {
  stage({ bridge: { get: () => '{"top":"48","bottom":-4}' } });
  assert.deepEqual(readSafeArea(), { top: 0, right: 0, bottom: 0, left: 0 });
});

// ─────────────────────────────────────────────────────────────
// 적기
// ─────────────────────────────────────────────────────────────

void test('all four custom properties land on the root element', () => {
  const element = root();
  applySafeArea(element, { top: 24, right: 1, bottom: 16, left: 2 });
  assert.equal(element.written.get('--safe-area-inset-top'), '24px');
  assert.equal(element.written.get('--safe-area-inset-right'), '1px');
  assert.equal(element.written.get('--safe-area-inset-bottom'), '16px');
  assert.equal(element.written.get('--safe-area-inset-left'), '2px');
});

void test('the first read happens on subscribe, before any event arrives', () => {
  const { element } = stage({
    bridge: bars({ top: 48, right: 0, bottom: 0, left: 0 }),
  });
  watchSafeArea();
  assert.equal(element.written.get('--safe-area-inset-top'), '48px');
});

void test('a later change is picked up', () => {
  const view = stage({ bridge: bars({ top: 48, right: 0, bottom: 0, left: 0 }) });
  watchSafeArea();
  view.plug(bars({ top: 0, right: 0, bottom: 0, left: 0 }));
  view.fire('mupid:safe-area');
  assert.equal(view.element.written.get('--safe-area-inset-top'), '0px');
});

// 웹뷰가 화면보다 늦게 통로를 심어도 첫 인셋이 도착하면 따라붙어야 한다.
// 구독을 앱에서만 걸면 그 경우 영영 0 으로 남는다.
void test('a bridge that appears after mount still gets picked up', () => {
  const view = stage();
  watchSafeArea();
  assert.equal(view.element.written.size, 0);
  view.plug(bars({ top: 48, right: 0, bottom: 0, left: 0 }));
  view.fire('mupid:safe-area');
  assert.equal(view.element.written.get('--safe-area-inset-top'), '48px');
});

void test('unsubscribing drops both listeners', () => {
  const view = stage({ bridge: bars({ top: 48, right: 0, bottom: 0, left: 0 }) });
  watchSafeArea()();
  assert.equal(view.count('mupid:safe-area'), 0);
  assert.equal(view.count('resize'), 0);
});

// ─────────────────────────────────────────────────────────────
// CSS 쪽 약속
// ─────────────────────────────────────────────────────────────

const sheets = [
  'app/globals.css',
  'app/dialog-viewport.css',
  'app/mobile-navigation.css',
  'app/mupid.css',
  'app/nav-account.css',
  'app/readability.css',
  'app/responsive-polish.css',
  'app/welcome-redesign.css',
];
const css = Object.fromEntries(
  await Promise.all(sheets.map(async (path) => [path, await read(path)])),
);

void test('globals.css keeps the env() fallback the web and iOS still need', () => {
  for (const side of ['top', 'right', 'bottom', 'left']) {
    assert.ok(
      css['app/globals.css'].includes(
        `--safe-area-inset-${side}: env(safe-area-inset-${side}, 0px);`,
      ),
      side,
    );
  }
});

// 안드로이드 웹뷰의 `env()` 는 노치만 세고 시스템 바는 빼먹는다. 한 군데라도
// 직접 부르면 그 자리만 조용히 0 으로 떨어진다.
void test('no rule reaches for env(safe-area-inset-*) directly', () => {
  for (const [path, text] of Object.entries(css)) {
    if (path === 'app/globals.css') continue;
    assert.equal(
      text.includes('env(safe-area-inset'),
      false,
      `${path} still calls env() instead of var(--safe-area-inset-*)`,
    );
  }
});

// 상태바 몫은 하늘 위(셸 바깥 여백)에서 비운다. 카드 안쪽 헤더가 한 번 더
// 비우면 두 번 세어 로고가 내려앉는다.
void test('the mobile shell clears the status bar, and the header no longer does', () => {
  assert.ok(
    css['app/responsive-polish.css'].includes(
      '.mupid-shell:not(.page-welcome) { padding:calc(14px + var(--safe-area-inset-top))',
    ),
  );
  // 좁은 화면의 헤더 규칙은 카드 안쪽에 있다. 여기서 또 비우면 두 번 센다.
  for (const path of ['app/nav-account.css', 'app/mobile-navigation.css']) {
    const mobile = css[path].slice(css[path].indexOf('@media (max-width: 767px)'));
    for (const rule of mobile.split('}')) {
      if (!rule.includes('.app-header')) continue;
      assert.equal(rule.includes('--safe-area-inset-top'), false, path);
    }
  }
});
