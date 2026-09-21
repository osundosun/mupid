import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

/*
 * 화상 키보드가 입력란을 가리던 문제 (S15P21A204-389 → 411 재발).
 *
 * ## 이 시험이 411 에서 달라진 것
 *
 * 389 의 시험은 `visualViewport` 경로만 검사했다. **그 경로가 실기기에서 0 을
 * 준다는 것이 411 의 원인이었고, 그래서 389 는 재발했다.** 이제 재는 쪽은
 * 네이티브(`SafeArea.kt` 가 `Type.ime()` 로 잰 값)이고, `visualViewport` 는
 * 웹 폴백일 뿐이다. 그래서 여기서 **네이티브 경로를 먼저 검사한다.**
 *
 * 무엇을 어디에 적고, 어떤 선언·소비가 있어야 하는지는 여기서 본다. 키보드가
 * 실제로 올라오는 순간은 실기기에서만 나온다 — 그 한계는 숨기지 않고 아래
 * 마지막 절에 적어 둔다.
 *
 * React 는 이 파일에서 필요 없다. 재는 쪽만 떼어 검사한다.
 */
const reactStub =
  'data:text/javascript;base64,' +
  Buffer.from(
    `export const useEffect = () => {};
     export const useState = () => [0, () => {}];`,
  ).toString('base64');

/*
 * `use-keyboard-inset.ts` 는 `../lib/platform/safe-area` 를 import 한다.
 * 그 파일은 `window.__mupidSafeArea` 브리지를 읽는데, 여기서는 **브리지를 우리가
 * 세우고** 그 모듈도 같이 떼어내야 한다. 데이터 URL 모듈은 상대 경로를 풀지
 * 못하므로(ERR_INVALID_URL) 두 모듈을 각각 transpile 해서 import 문을 바꿔 끼운다.
 */
const bridgeStub = (bridgeJson) =>
  'data:text/javascript;base64,' +
  Buffer.from(
    `export const readNativeInsets = () => {
       const raw = ${JSON.stringify(bridgeJson)};
       if (raw === null) return null;
       const dpr = globalThis.window?.devicePixelRatio ?? 1;
       const scale = dpr > 0 ? dpr : 1;
       const num = (v) => (typeof v === 'number' && Number.isFinite(v) && v > 0 ? v : 0);
       const visible = raw.keyboardVisible === true;
       const height = visible ? num(raw.keyboardHeight) / scale : 0;
       return {
         safeArea: { top: num(raw.top)/scale, right: num(raw.right)/scale,
                     bottom: num(raw.bottom)/scale, left: num(raw.left)/scale },
         keyboard: { height, visible: height > 0 },
       };
     };
     export const readSafeArea = () => readNativeInsets()?.safeArea ?? null;`,
  ).toString('base64');

const source = await readFile(
  new URL('../hooks/use-keyboard-inset.ts', import.meta.url),
  'utf8',
);

/**
 * 재는 쪽 모듈을 세운다.
 *
 * `bridgeJson` 이 `null` 이면 "앱이 아니다"(`__mupidSafeArea` 없음)다 — 그때는
 * `readNativeInsets` 가 `null` 을 돌려주어 웹 폴백으로 내려간다.
 */
const load = async (bridgeJson) => {
  const js = ts
    .transpileModule(source, {
      compilerOptions: {
        target: ts.ScriptTarget.ES2022,
        module: ts.ModuleKind.ESNext,
      },
    })
    .outputText.replace(/'react'/, JSON.stringify(reactStub))
    // 상대 import 를 스텁 데이터 URL 로 바꾼다.
    .replace(/from\s+['"]\.\.\/lib\/platform\/safe-area['"]/, `from '${bridgeStub(bridgeJson)}'`);
  const url = 'data:text/javascript;base64,' + Buffer.from(js).toString('base64');
  return import(url + '#' + Math.random());
};

const noBridge = await load(null);

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');

/** `<html>`·`<body>` 의 인라인 스타일 흉내. 적힌 것만 들고 있으면 된다. */
const root = () => {
  const written = new Map();
  return {
    written,
    style: {
      setProperty: (name, value) => written.set(name, value),
      // 인셋이 0 이면 웹이 변수를 **지운다**(폴백이 살아나야 한다).
      removeProperty: (name) => written.delete(name),
    },
  };
};

/**
 * 창 하나를 세운다.
 *
 * `visualViewport` 가 없으면 웹/데스크톱인 셈이다. `height` 는 키보드가 올라온
 * 만큼 줄어든 "보이는 높이"다.
 */
const stage = ({ innerHeight = 800, viewport = null, bridge = false } = {}) => {
  const listeners = new Map();
  const html = root();
  const body = root();
  globalThis.window = {
    innerHeight,
    devicePixelRatio: 1,
    addEventListener: (event, handler) => {
      listeners.set(event, [...(listeners.get(event) ?? []), handler]);
    },
    removeEventListener: () => {},
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
    bridge,
    fire: (event) => (listeners.get(event) ?? []).forEach((one) => one()),
  };
};

/** 보이는 높이만 바꾼다. 키보드가 올라온 상황을 흉내 낸다. */
const keyboard = (view, height, offsetTop = 0) => {
  view.height = height;
  view.offsetTop = offsetTop;
};

// ─────────────────────────────────────────────────────────────
// 네이티브 경로 — 411 의 본론
// ─────────────────────────────────────────────────────────────

void test('in the app the web subtracts nothing, because the native side already shrank the view', async () => {
  /*
   * **411 의 회귀 시험이다.**
   *
   * 앱에서는 키보드가 뜨는 순간 `SafeArea.kt` 가 웹뷰를 담은 컨테이너를 IME 만큼
   * 줄인다. 실기기에서 웹뷰가 `[0,0][1080,2316]` → `[0,0][1080,1308]` 로 실제로
   * 줄어드는 것을 확인했다. 그러면 `100dvh` 가 이미 키보드 위에서 끝나므로 웹이
   * 뺄 것이 **없다**.
   *
   * 그래서 브리지는 `keyboardVisible: true` 를 보내면서도 `keyboardHeight: 0` 을
   * 보낸다. 여기서 양수가 나오면 **두 번 줄어든다** — 대화상자가 화면 절반만 하게
   * 선다.
   */
  const module = await load({
    top: 0, right: 0, bottom: 0, left: 0,
    // 실기기가 실제로 보내는 모양: 키보드는 떠 있고(visible), 가려진 몫은 0.
    keyboardHeight: 0, keyboardVisible: true, imeBottom: 1008,
  });
  // 네이티브가 줄인 뒤라 창 높이도 이미 1308 이다.
  const view = stage({ innerHeight: 1308, viewport: { height: 1308, offsetTop: 0 } });

  module.watchKeyboardInset();
  assert.equal(
    view.html.written.get('--keyboard-inset'),
    '0px',
    '네이티브가 뷰를 줄였는데 웹이 또 빼면 두 번 줄어든다',
  );
  assert.equal(view.body.written.get('--keyboard-inset'), '0px');
});

void test('the app never falls back to the viewport guess, even when the native height is zero', async () => {
  /*
   * 브리지가 있으면 그 값이 0 이어도 `visualViewport` 로 **내려가지 않아야** 한다.
   *
   * 앱에서 0 은 "못 쟀다"가 아니라 "가려진 것이 없다"는 뜻이다. 여기서 "0 이니
   * 웹 방식으로 한 번 더 재 보자"고 내려가면, 뷰가 줄어드는 과도기에 잡힌
   * 어긋난 값이 그대로 이중 차감이 된다.
   */
  const module = await load({
    top: 0, right: 0, bottom: 0, left: 0,
    keyboardHeight: 0, keyboardVisible: true, imeBottom: 1008,
  });
  // `visualViewport` 만 보면 300px 이 가린 것처럼 보이는 상황을 일부러 만든다.
  stage({ innerHeight: 800, viewport: { height: 500, offsetTop: 0 } });
  assert.equal(module.measureKeyboardInset(), 0);
});

void test('a hidden keyboard is zero even if the native height lingers', async () => {
  // 기기에 따라 키보드가 내려간 뒤에도 마지막 인셋을 남긴다. `keyboardVisible`
  // 을 따로 보지 않으면 대화상자가 영영 줄어든 채로 선다.
  const module = await load({
    top: 0, right: 0, bottom: 0, left: 0,
    keyboardHeight: 1008, keyboardVisible: false,
  });
  assert.equal(module.measureKeyboardInset(), 0);
});

// ─────────────────────────────────────────────────────────────
// 웹 폴백 — 앱이 아닐 때만
// ─────────────────────────────────────────────────────────────

void test('a keyboard that covers the bottom is written to html and body', () => {
  const view = stage({ viewport: { height: 800, offsetTop: 0 } });
  noBridge.watchKeyboardInset();
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
  noBridge.watchKeyboardInset();
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
  noBridge.watchKeyboardInset();
  keyboard(globalThis.window.visualViewport, 500, 60);
  view.fire('resize');
  assert.equal(view.html.written.get('--keyboard-inset'), '240px');
});

void test('the web build without a visual viewport writes nothing', () => {
  const view = stage();
  noBridge.watchKeyboardInset();
  // 키가 아예 없으면 CSS 의 `var(--keyboard-inset, 0px)` 가 기본값으로 내려간다.
  assert.equal(view.html.written.has('--keyboard-inset'), false);
});

// ─────────────────────────────────────────────────────────────
// 선언과 소비 — 여기가 실제로 어긋났던 자리다
// ─────────────────────────────────────────────────────────────

const [manifest, layout, dialogCss, loginCss, login, app, safeAreaKt, readability] =
  await Promise.all([
    read('../app/gen/android/app/src/main/AndroidManifest.xml'),
    read('app/layout.tsx'),
    read('app/dialog-viewport.css'),
    read('app/demo-login.css'),
    read('components/mupid/demo-login.tsx'),
    read('components/mupid-app.tsx'),
    read('../app/gen/android/app/src/main/java/com/osundosun/mupid/SafeArea.kt'),
    read('app/readability.css'),
  ]);

void test('the native side reads the IME inset, not just the system bars', () => {
  /*
   * **411 의 핵심이다.** `enableEdgeToEdge()` 로 `decorFitsSystemWindows=false` 인
   * 이상, Android 15+ 에서 IME 인셋은 `Type.ime()` 로 직접 읽어야 한다.
   * `systemBars() or displayCutout()` 만 읽으면 키보드 높이가 영영 0 이라
   * 웹이 무엇을 하든 입력란이 덮인다.
   */
  assert.match(
    safeAreaKt,
    /WindowInsetsCompat\.Type\.ime\(\)/,
    'SafeArea.kt 가 IME 인셋을 읽지 않으면 --keyboard-inset 이 늘 0 이다',
  );
  // 키보드가 내려간 뒤 마지막 높이를 남기는 기기를 위해 보이는가를 따로 본다.
  assert.match(safeAreaKt, /isVisible\(WindowInsetsCompat\.Type\.ime\(\)\)/);
  // 값이 브리지 JSON 에 실려 나가야 한다.
  assert.match(safeAreaKt, /keyboardHeight/);
  assert.match(safeAreaKt, /keyboardVisible/);
});

void test('the native side pads the container that holds the webview, not the webview', () => {
  /*
   * **411 의 본체이자, 앞선 네 번이 모두 걸려 넘어진 자리다.**
   *
   * 웹뷰 **자신**에게 패딩을 주면 아무 일도 일어나지 않는다 — WebView 는 자기 패딩
   * 영역에도 콘텐츠를 그려 측정 크기가 바뀌지 않는다. 담고 있는 컨테이너
   * (`android.R.id.content`)에 줘야 웹뷰가 함께 줄어든다.
   */
  assert.match(
    safeAreaKt,
    /android\.R\.id\.content/,
    '웹뷰를 담은 컨테이너를 찾지 않으면 패딩을 줄 곳을 못 정한다',
  );
  assert.match(
    safeAreaKt,
    /container\.setPadding\(/,
    '웹뷰가 아니라 컨테이너에 패딩을 줘야 웹뷰의 측정 크기가 줄어든다',
  );
});

void test('the container is resolved after the webview is attached, not at install time', () => {
  /*
   * **재발을 막는 시험이다.**
   *
   * wry 는 `setWebView`(→ `onWebViewCreate` → `SafeArea.install`)를
   * `setContentView` **보다 먼저** 부른다. 그 순간 웹뷰는 아무 데도 안 붙어 있어
   * `View.getRootView()` 가 **자기 자신**을 돌려준다. 4번째 시도가 그래서 3번째
   * 시도와 같은 코드가 됐고, 로그에는 `root padding applied` 가 찍히는데 화면은
   * 한 픽셀도 안 움직였다.
   *
   * 그러니 `rootView` 를 붙잡아 두는 코드가 **다시 들어오면 안 된다.**
   *
   * 주석은 걷어내고 **코드만** 본다 — 위의 그 설명 자체가 `rootView` 를 말하고
   * 있어서, 낱말만 세면 설명을 지워야 통과하는 시험이 된다.
   */
  const code = safeAreaKt.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/.*/g, '');
  assert.equal(
    /\brootView\b/.test(code),
    false,
    'rootView 는 install 시점에 웹뷰 자신이다 — 컨테이너는 인셋이 올 때 찾아야 한다',
  );
  // 그리고 컨테이너를 찾는 일이 인셋 리스너 **안**에서 일어나야 한다.
  const listener = code.slice(code.indexOf('setOnApplyWindowInsetsListener'));
  assert.match(
    listener,
    /contentContainer\(/,
    '컨테이너를 인셋이 도착할 때 찾지 않으면 웹뷰가 아직 안 붙어 있을 수 있다',
  );
});

void test('the bridge reports a zero keyboard height so the web cannot subtract it twice', () => {
  /*
   * 네이티브가 뷰를 줄였으므로 웹이 보는 화면에서 가려진 몫은 0 이다. 실제 IME
   * 값을 실어 보내면 웹이 `100dvh` 에서 한 번 더 빼서 두 번 줄어든다.
   *
   * **초기값이 아니라 `set` 이 만드는 문자열을 본다.** 예전 시험은
   * `/keyboardHeight\\":0/` 로만 봤는데, 그 무늬는 필드 초기값에도 있어서
   * `set` 이 `imeBottom` 을 싣고 있어도 **조용히 통과했다.** 실제로 그랬다.
   */
  const setter = safeAreaKt.slice(safeAreaKt.indexOf('val next ='));
  assert.ok(setter.length > 0, 'set() 의 JSON 조립부를 찾지 못했다');
  assert.match(
    setter.slice(0, 400),
    /keyboardHeight\\":0,/,
    'set() 이 keyboardHeight 로 실제 IME 값을 실으면 웹이 두 번 뺀다',
  );
  // 진단 통로 — 실측값 자체는 따로 남아야 나중에 확인할 수 있다.
  assert.match(safeAreaKt, /imeBottom/, 'IME 실측값을 진단용으로 남겨야 한다');
});

void test('the native side logs the webview size, not just that a setting was applied', () => {
  /*
   * 389 와 411 의 앞선 시도들은 "패딩을 줬다"만 로그로 남기고 "화면이 바뀌었나"를
   * 남기지 않아, **로그가 성공을 말하는데 화면은 그대로**인 상태를 네 번 반복했다.
   * 크기를 함께 적으면 그 착각이 불가능해진다.
   */
  assert.match(
    safeAreaKt,
    /\$\{node\.width\}x\$\{node\.height\}/,
    '뷰 크기를 로그에 남기지 않으면 "적용됐다"와 "줄어들었다"를 구분할 수 없다',
  );
});

void test('the activity resizes the window instead of panning it', () => {
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

void test('the viewport keeps the path that actually measures the keyboard in a browser', () => {
  /*
   * 389 는 여기에 `overlays-content` 를 박았다. 그 값은 스펙상 **"어떤 뷰포트도
   * 줄이지 않는다"** 는 뜻이라, `visualViewport` 가 줄어드는 경로 — 즉 브라우저에서
   * 키보드를 재는 **유일한 통로** — 를 스스로 껐다.
   *
   * 앱에서는 네이티브가 뷰를 줄이므로 이 선언이 무엇이든 상관없다. 그러니 브라우저가
   * 잴 수 있는 쪽으로 둔다.
   */
  assert.match(layout, /interactiveWidget:\s*'resizes-visual'/);
  assert.equal(
    /interactiveWidget:\s*'overlays-content'/.test(layout),
    false,
    'overlays-content 는 브라우저의 계측 경로를 끈다',
  );
});

void test('the centred dialog moves its bottom edge, not just its height', () => {
  /*
   * **389 가 `max-height` 만 넣고 닫힌 자리다.**
   *
   * 이 대화상자는 `inset: 16px; margin: auto` 로 가운데 정렬이라, 높이만 줄이면
   * 가운데를 중심으로 줄어들 뿐 **아래 끝이 키보드 위로 올라오지 않는다.**
   * `bottom` 을 올려야 `margin: auto` 가 그 줄어든 상자 안에서 다시 가운데를 잡는다.
   */
  assert.match(
    dialogCss,
    /bottom:\s*calc\(\s*16px\s*\+\s*var\(--keyboard-inset,\s*0px\)\s*\)\s*!important/,
    '아래 끝을 올리지 않으면 가운데 정렬 대화상자는 높이를 줄여도 안 올라온다',
  );
  // 높이 제한도 함께 있어야 한다 — 올리기만 하면 위로 삐져나간다.
  assert.match(
    dialogCss,
    /max-height:\s*calc\(\s*100dvh\s*-\s*32px\s*-\s*var\(--keyboard-inset,\s*0px\)\s*\)\s*!important/,
    '대화상자가 키보드 몫을 빼지 않으면 위로 삐져나간다',
  );
  // `bottom` 은 `inset` 뒤에 와야 이긴다 (같은 !important 끼리는 나중 선언이 이긴다).
  assert.ok(
    dialogCss.indexOf('inset: 16px !important') <
      dialogCss.indexOf('bottom: calc(16px + var(--keyboard-inset, 0px)) !important'),
    'bottom 이 inset 보다 앞서면 inset 의 bottom: 16px 에 덮인다',
  );
  // `100vh` 는 키보드가 열리든 말든 늘 "가장 큰 높이"다. 이 자리에서 쓰면
  // 보이는 영역보다 큰 값이 나와 아래가 잘린다.
  for (const sheet of [dialogCss, loginCss]) {
    assert.equal(sheet.includes('100vh'), false, '뷰포트 단위는 dvh 여야 한다');
  }
});

void test('the bottom-anchored sheet lifts its floor off the keyboard', () => {
  /*
   * 389 는 일반 `.sky-dialog` 만 고쳤고 **최하단 고정 시트를 빠뜨렸다.**
   * `track-menu-sheet` 는 `bottom: 0` 으로 붙고 안에 입력란이 있어
   * (`track-menu.tsx` 의 `#new-track-playlist`) 키보드에 정통으로 덮인다.
   *
   * **바닥에 붙은 시트는 `max-height` 로는 절대 안 올라온다** — 높이를 줄여도
   * `bottom: 0` 이라 아래 끝이 그대로다. 두 규칙 모두 바닥을 올려야 하고,
   * **같은 값이어야 한다** — 하나만 고치면 어느 쪽이 이기는지에 따라 조용히 덮인다.
   */
  for (const [name, sheet] of [
    ['dialog-viewport.css', dialogCss],
    ['readability.css', readability],
  ]) {
    assert.match(
      sheet,
      /\.track-menu-sheet[\s\S]{0,2000}?bottom:\s*var\(--keyboard-inset,\s*0px\)\s*!important/,
      `${name} 의 track-menu-sheet 가 바닥을 키보드 위로 올리지 않는다`,
    );
  }
  assert.match(
    dialogCss,
    /\.sky-dialog\.track-menu-sheet[\s\S]{0,2000}?max-height:\s*calc\(\s*100dvh\s*-\s*24px\s*-\s*var\(--keyboard-inset,\s*0px\)\s*\)\s*!important/,
    'dialog-viewport.css 의 track-menu-sheet 가 키보드 몫을 빼지 않는다',
  );
  assert.match(
    readability,
    /\.track-menu-sheet[\s\S]{0,2000}?max-height:\s*calc\(\s*85dvh\s*-\s*var\(--keyboard-inset,\s*0px\)\s*\)/,
    'readability.css 의 track-menu-sheet 가 키보드 몫을 빼지 않는다',
  );
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
