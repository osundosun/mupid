import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

/*
 * 안드로이드 소프트버튼이 **최상단 오버레이를 닫는가** (S15P21A204-410).
 *
 * 원래는 모달이 씹히고 페이지 히스토리가 뒤로 갔다. 원인이 앱 셸(Tauri)에
 * 있었으므로 웹 계층의 규칙 — "무엇을 먼저 닫는가" 와 "언제 리스너를 다는가" —
 * 는 React 를 세우지 않고 여기서 검사할 수 있다. 실기기에서 눈으로 보는 것과
 * 별개로, 이 규칙은 순수 로직이라 여기가 정본이다.
 *
 * `react` 는 이 파일에서 필요 없다. `use-dismissable-overlay` 의 알맹이
 * (`registerOverlay`)와 `back-button` 의 어댑터만 떼어 검사한다.
 */
const reactStub =
  'data:text/javascript;base64,' +
  Buffer.from(
    `export const useEffect = () => {};
     export const useRef = (value) => ({ current: value });`,
  ).toString('base64');

/** `@/...` 별칭을 실제 파일 URL 로 바꾼다. 시험은 번들러 없이 돈다. */
const loadTs = async (path) => {
  const source = await readFile(new URL('../' + path, import.meta.url), 'utf8');
  const js = ts
    .transpileModule(source, {
      compilerOptions: {
        target: ts.ScriptTarget.ES2022,
        module: ts.ModuleKind.ESNext,
      },
    })
    .outputText.replace(/'react'/g, JSON.stringify(reactStub))
    // 형제 모듈은 상대 경로라 `data:` URL 에서 못 푼다. 절대 URL 로 바꾼다.
    .replace(
      /'\.\/overlay-stack'/g,
      JSON.stringify(new URL('../lib/overlay-stack.ts', import.meta.url).href),
    )
    .replace(
      /'@\/lib\/overlay-stack'/g,
      JSON.stringify(new URL('../lib/overlay-stack.ts', import.meta.url).href),
    );
  return import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'));
};

const { registerOverlay, closeTopOverlay, overlayDepth, subscribeOverlayDepth, resetOverlays } =
  await import(new URL('../lib/overlay-stack.ts', import.meta.url).href);
const { connectBackButton } = await loadTs('lib/back-button.ts');

/** 시험마다 스택을 비운다. 앞 시험이 남긴 것이 다음 시험을 어지럽히지 않게. */
const clean = () => resetOverlays();

// ─────────────────────────────────────────────────────────────
// 우선순위 — 가장 위(최근에 연 것)를 닫는다
// ─────────────────────────────────────────────────────────────

void test('뒤로가기는 가장 위에 쌓인 오버레이를 닫는다', () => {
  clean();
  const closed = [];
  const a = registerOverlay(() => closed.push('a'));
  const b = registerOverlay(() => closed.push('b'));
  const c = registerOverlay(() => closed.push('c'));

  assert.equal(overlayDepth(), 3);
  assert.equal(closeTopOverlay(), true);
  // 마지막에 연 것부터.
  assert.deepEqual(closed, ['c']);
  assert.equal(overlayDepth(), 2);

  assert.equal(closeTopOverlay(), true);
  assert.deepEqual(closed, ['c', 'b']);
  assert.equal(closeTopOverlay(), true);
  assert.deepEqual(closed, ['c', 'b', 'a']);

  // 다 닫힌 뒤에는 닫을 것이 없다 — 그때 히스토리로 내려간다.
  assert.equal(closeTopOverlay(), false);
  assert.equal(overlayDepth(), 0);

  a(); b(); c();
  assert.equal(overlayDepth(), 0);
});

void test('오버레이를 닫으면 저절로 스택에서 빠진다', () => {
  clean();
  const off = registerOverlay(() => {});
  assert.equal(overlayDepth(), 1);
  // 화면이 스스로 닫은 경우(폼 제출·X 버튼) — 등록 해제가 효과에서 돌아온다.
  off();
  assert.equal(overlayDepth(), 0);
  assert.equal(closeTopOverlay(), false);
  // 두 번 불러도 스택이 흔들리지 않는다.
  off();
  assert.equal(overlayDepth(), 0);
});

void test('스택을 통해 닫힌 뒤의 등록 해제는 아래 것을 건드리지 않는다', () => {
  clean();
  const closed = [];
  registerOverlay(() => closed.push('아래'));
  const off = registerOverlay(() => closed.push('위'));

  // 뒤로가기: '위' 가 닫힌다 → 컴포넌트가 상태를 비우며 off() 를 부른다.
  closeTopOverlay();
  off();
  assert.deepEqual(closed, ['위']);

  // 이제 아래 것이 최상단이다. 위 것을 또 닫으려 해서는 안 된다.
  closeTopOverlay();
  assert.deepEqual(closed, ['위', '아래']);
});

void test('깊이가 바뀌면 구독자에게 알린다', () => {
  clean();
  const seen = [];
  const stop = subscribeOverlayDepth((depth) => seen.push(depth));

  const off = registerOverlay(() => {});
  closeTopOverlay();
  off();
  stop();

  assert.deepEqual(seen, [1, 0]);
  // 구독을 끊은 뒤에는 알리지 않는다.
  registerOverlay(() => {})();
  assert.deepEqual(seen, [1, 0]);
});

// ─────────────────────────────────────────────────────────────
// 어댑터 — 열려 있을 때만 네이티브 리스너를 단다
// ─────────────────────────────────────────────────────────────

/** 붙임/뗌을 기록하는 가짜 어댑터. */
const fakeAdapter = () => {
  const state = { attached: 0, detached: 0, unhandled: 0, handle: null };
  return {
    state,
    attach: async (handler) => {
      state.attached += 1;
      state.handle = handler;
      return () => { state.detached += 1; };
    },
    onUnhandled: () => { state.unhandled += 1; },
  };
};

/** `attach` 가 돌려주는 프라미스가 풀릴 때까지 기다린다. */
const settle = () => new Promise((resolve) => setImmediate(resolve));

void test('열려 있는 오버레이가 없으면 리스너를 달지 않는다', async () => {
  clean();
  const adapter = fakeAdapter();
  const stop = connectBackButton(adapter);
  await settle();

  // 안 달아야 네이티브 기본 경로(히스토리 뒤로가기 → 바닥에서 앱 종료)가 산다.
  assert.equal(adapter.state.attached, 0);
  stop();
  await settle();
  assert.equal(adapter.state.detached, 0);
});

void test('오버레이가 열리면 달고, 다 닫히면 뗀다', async () => {
  clean();
  const adapter = fakeAdapter();
  const stop = connectBackButton(adapter);
  await settle();

  const off = registerOverlay(() => {});
  await settle();
  assert.equal(adapter.state.attached, 1);

  off();
  await settle();
  // 떼야 그 뒤로가기가 앱 셸 기본 경로로 내려간다.
  assert.equal(adapter.state.detached, 1);

  // 다시 열면 다시 단다.
  const off2 = registerOverlay(() => {});
  await settle();
  assert.equal(adapter.state.attached, 2);
  off2(); stop();
  await settle();
});

void test('리스너가 붙어 있는 동안의 뒤로가기는 최상단을 닫는다', async () => {
  clean();
  const adapter = fakeAdapter();
  const closed = [];
  const stop = connectBackButton(adapter);
  registerOverlay(() => closed.push('아래'));
  registerOverlay(() => closed.push('위'));
  await settle();

  adapter.state.handle();
  assert.deepEqual(closed, ['위']);
  // 웹이 닫았으므로 히스토리로 내려가서는 안 된다.
  assert.equal(adapter.state.unhandled, 0);

  adapter.state.handle();
  assert.deepEqual(closed, ['위', '아래']);
  assert.equal(adapter.state.unhandled, 0);

  // 이제 닫을 것이 없다 → 그때만 히스토리 뒤로가기로 내려간다.
  adapter.state.handle();
  assert.equal(adapter.state.unhandled, 1);

  stop();
  await settle();
});

void test('붙이는 동안 마지막 오버레이가 닫히면 리스너를 남기지 않는다', async () => {
  clean();
  const adapter = fakeAdapter();
  const stop = connectBackButton(adapter);

  // 붙이는 일은 `invoke` 왕복이라 비동기다. 그 사이에 닫히는 경우를 흉내 낸다.
  const off = registerOverlay(() => {});
  off();
  await settle();

  // 달았더라도 곧바로 떼야 한다. 남으면 히스토리 바닥에서 뒤로가기가
  // 아무 일도 하지 않는다(웹이 앱을 닫을 통로가 ACL 로 막혀 있다).
  assert.equal(adapter.state.attached, 1);
  assert.equal(adapter.state.detached, 1);

  // 그래서 지금은 웹 쪽 핸들러가 **없다** — 네이티브가 리스너를 보지 못하고
  // 자기 기본 경로로 내려간다. 새로 달지도 않는다.
  assert.equal(adapter.state.detached, adapter.state.attached);
  assert.equal(adapter.state.attached, 1);

  // 다시 열면 그때 새로 단다.
  const again = registerOverlay(() => {});
  await settle();
  assert.equal(adapter.state.attached, 2);
  again(); stop();
  await settle();
});

void test('리스너를 붙이지 못해도 조용히 기본 동작으로 남는다', async () => {
  clean();
  const logged = [];
  const stop = connectBackButton({
    // 웹·데스크톱에서는 붙일 것이 없다 — 래퍼가 빈 함수를 돌려주지만, 여기서는
    // 아예 실패하는 쪽까지 본다.
    attach: async () => { throw new Error('앱 셸이 아니다'); },
    onUnhandled: () => {},
    log: (message) => logged.push(message),
  });
  registerOverlay(() => {});
  await settle();

  assert.equal(logged.length, 1);
  assert.match(logged[0], /뒤로가기 리스너를 등록하지 못했다/);
  stop();
  await settle();
});

// ─────────────────────────────────────────────────────────────
// 웹 회귀 없음 — 브리지 래퍼가 앱이 아니면 no-op 인가
// ─────────────────────────────────────────────────────────────

const bridge = await readFile(
  new URL('../lib/platform/bridge.ts', import.meta.url),
  'utf8',
);

void test('브리지는 앱이 아니면 붙이지 않고 빈 정리 함수를 돌려준다', () => {
  // `isTauri()` 로 먼저 막는다. 이 가드가 없으면 `__TAURI_INTERNALS__` 없는
  // 브라우저에서 이벤트 등록이 그대로 터진다.
  assert.match(bridge, /export async function onBackButtonPress/);
  assert.match(bridge, /if \(!isTauri\(\)\) return \(\) => \{\};/);
  // 정적 import 금지 — 웹 번들에도 함께 묶이는 파일이다.
  assert.equal(
    /^import .*@tauri-apps\/api/m.test(bridge),
    false,
    '최상단에서 @tauri-apps/api 를 불러오면 브라우저에서도 내려받는다',
  );
  assert.match(bridge, /await import\('@tauri-apps\/api\/app'\)/);
  // 붙이지 못했을 때도 조용히 지나가야 한다(웹 회귀 없음).
  assert.match(bridge, /catch \{[\s\S]*?return \(\) => \{\};[\s\S]*?\}/);
});

// ─────────────────────────────────────────────────────────────
// 배선 — 루트 셸에 한 번만 걸려 있는가
// ─────────────────────────────────────────────────────────────

const app = await readFile(
  new URL('../components/mupid-app.tsx', import.meta.url),
  'utf8',
);

void test('브리지는 루트 셸에 한 번만 걸린다', () => {
  // 오버레이마다 걸면 한 번의 뒤로가기에 여러 개가 함께 닫힌다.
  const uses = app.match(/useBackButtonBridge\(/g) ?? [];
  assert.equal(uses.length, 1);
  // 아무것도 닫지 못했을 때는 히스토리 뒤로가기로 내려간다.
  assert.match(app, /useBackButtonBridge\(m\.goBack\)/);
});

void test('화면 헤더의 뒤로가기도 같은 규칙을 따른다', async () => {
  const source = await readFile(
    new URL('../hooks/use-mupid.ts', import.meta.url),
    'utf8',
  );
  // 열려 있는 오버레이가 있으면 화면 이동보다 그것을 먼저 닫는다.
  const goBack = source.slice(
    source.indexOf('const goBack = () => {'),
    source.indexOf('const go = (next: Page) => {'),
  );
  assert.match(goBack, /if \(closeTopOverlay\(\)\) return;/);
});
