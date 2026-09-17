import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

// 화면 쪽만 떼어 검사한다. Tauri 런타임도 기기 도네트워크도 필요 없다 —
// `invoke` 자리에 기록기를 꽂아 두고 **무엇을 언제 부르는지**만 본다
// (`tests/media-session.test.mjs` 와 같은 방식).
const bridgeStub =
  'data:text/javascript;base64,' +
  Buffer.from(
    `export const isTauri = () => globalThis.__isTauri ?? true;
     export const invoke = (command, args) => globalThis.__invoke(command, args);`,
  ).toString('base64');

const source = await readFile(
  new URL('../lib/platform/update.ts', import.meta.url),
  'utf8',
);
const js = ts
  .transpileModule(source, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.ESNext,
    },
  })
  .outputText.replace(/'\.\/bridge'/g, JSON.stringify(bridgeStub));

const {
  checkForUpdates,
  checkForUpdatesNow,
  installNativeUpdate,
  noteUpdatePrompted,
  readUpdateState,
  shouldPromptUpdate,
  shouldSuggestNativeUpdate,
  UNKNOWN,
} = await import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'));

/** 원하는 상태를 돌려주도록 `invoke` 를 가로채고 호출을 기록한다. */
const stub = (responses = {}) => {
  const calls = [];
  globalThis.__isTauri = true;
  globalThis.__invoke = async (command, args) => {
    calls.push({ command, args });
    if (command in responses) {
      const value = responses[command];
      if (value instanceof Error) throw value;
      return typeof value === 'function' ? value() : value;
    }
    return null;
  };
  return calls;
};

const state = (overrides = {}) => ({
  localWebBuild: 1000,
  embeddedWebBuild: 1000,
  localNativeBuild: 1000,
  localVersionCode: 1,
  serverWebBuild: 1000,
  serverVersionCode: 1,
  webAvailable: false,
  nativeAvailable: false,
  webRejected: false,
  // 안드로이드 실기기 기준값. 데스크톱은 `canInstallNative: false` 다.
  canInstallNative: true,
  nativePromptDue: true,
  ...overrides,
});

// ─────────────────────────────────────────────────────────────
// 웹 갈래
// ─────────────────────────────────────────────────────────────

test('새 번들이 없으면 내려받지 않는다', async () => {
  const calls = stub({ update_status: state() });
  await checkForUpdates();
  // 상태만 두 번 읽고 `update_web` 은 부르지 않는다.
  assert.deepEqual(
    calls.map((call) => call.command),
    ['update_status', 'update_status'],
  );
});

test('새 번들이 있으면 내려받아 활성화한다', async () => {
  let seen = 0;
  const calls = stub({
    // 첫 호출은 "새 것 있음", 두 번째는 "적용됨" 을 돌려준다.
    update_status: () => {
      seen += 1;
      return seen === 1
        ? state({ webAvailable: true, serverWebBuild: 2000 })
        : state({ serverWebBuild: 2000 });
    },
    update_web: 2000,
  });

  const result = await checkForUpdates();
  assert.deepEqual(
    calls.map((call) => call.command),
    ['update_status', 'update_web', 'update_status'],
  );
  assert.equal(result.serverWebBuild, 2000);
});

test('거부 목록에 있는 값은 다시 내려받지 않는다', async () => {
  // 서버가 잘못된 번들을 올려 둔 상태다. 자동으로는 영영 다시 시도하지 않는다.
  const calls = stub({
    update_status: state({ webAvailable: true, webRejected: true }),
  });
  await checkForUpdates();
  assert.deepEqual(
    calls.map((call) => call.command),
    ['update_status', 'update_status'],
  );
});

test('사용자가 직접 누르면 거부 목록을 비우고 다시 시도한다', async () => {
  const calls = stub({
    update_status: state({ webAvailable: true, webRejected: true }),
    update_check_now: state({ serverWebBuild: 2000 }),
  });
  await checkForUpdatesNow();
  // 자동 경로와 달리 전용 커맨드를 쓴다 — Rust 가 거부 목록을 비우고
  // 내려받기·검증까지 한 번에 한다.
  assert.deepEqual(calls.map((call) => call.command), ['update_check_now']);
});

// ─────────────────────────────────────────────────────────────
// 실패는 조용해야 한다
// ─────────────────────────────────────────────────────────────

test('네트워크가 없으면 던지지 않고 null 을 준다', async () => {
  stub({ update_status: new Error('요청 실패') });
  // 던지면 화면에 오류가 뜬다. 업데이트 확인 실패는 사용자가 알 일이 아니다.
  assert.equal(await checkForUpdates(), null);
});

test('직접 확인도 실패하면 null 을 준다', async () => {
  stub({ update_check_now: new Error('요청 실패') });
  assert.equal(await checkForUpdatesNow(), null);
});

test('내려받기가 실패해도 던지지 않는다', async () => {
  const calls = stub({
    update_status: state({ webAvailable: true }),
    update_web: new Error('체크섬 불일치'),
  });
  const result = await checkForUpdates();
  assert.equal(result, null);
  // `update_web` 이 실패하면 두 번째 상태 조회로 가지 않는다.
  assert.deepEqual(
    calls.map((call) => call.command),
    ['update_status', 'update_web'],
  );
});

test('앱이 아니면 커맨드를 아예 부르지 않는다', async () => {
  const calls = stub({ update_status: state() });
  globalThis.__isTauri = false;
  assert.equal(await checkForUpdates(), null);
  assert.equal(await checkForUpdatesNow(), null);
  await installNativeUpdate();
  assert.equal(calls.length, 0, '웹에서는 아무 일도 일어나지 않아야 한다');
});

// ─────────────────────────────────────────────────────────────
// 네이티브 갈래
// ─────────────────────────────────────────────────────────────

test('설치 유도는 실패를 삼키지 않는다', async () => {
  // 사용자가 직접 누른 일이다. 아무 일도 일어나지 않으면 왜인지 알 수 없다.
  stub({ update_native: new Error('이 출처의 앱 설치를 허용해 주세요') });
  await assert.rejects(() => installNativeUpdate(), /허용해 주세요/);
});

test('설치 유도는 인자 없이 커맨드를 부른다', async () => {
  const calls = stub({ update_native: null });
  await installNativeUpdate();
  assert.deepEqual(calls, [{ command: 'update_native', args: undefined }]);
});

test('새 APK 가 있을 때만 안내한다', async () => {
  assert.equal(shouldSuggestNativeUpdate(state({ nativeAvailable: true })), true);
  assert.equal(shouldSuggestNativeUpdate(state({ nativeAvailable: false })), false);
  // 확인에 실패했으면 모르는 상태다. 안내하지 않는다.
  assert.equal(shouldSuggestNativeUpdate(null), false);
  assert.equal(shouldSuggestNativeUpdate(UNKNOWN), false);
});

// ─────────────────────────────────────────────────────────────
// Rust 와의 계약
// ─────────────────────────────────────────────────────────────

test('UpdateState 의 필드 이름이 Rust 와 짝을 이룬다', async () => {
  const rust = await readFile(
    new URL('../../app/src/update_net.rs', import.meta.url),
    'utf8',
  );
  const block = rust.slice(
    rust.indexOf('pub struct UpdateState'),
    rust.indexOf('\n}', rust.indexOf('pub struct UpdateState')),
  );

  // Rust 는 camelCase 로 내보낸다(`rename_all`). 화면이 읽는 이름과 같아야 한다.
  const expected = [
    'local_web_build',
    'embedded_web_build',
    'local_native_build',
    'local_version_code',
    'server_web_build',
    'server_version_code',
    'web_available',
    'native_available',
    'web_rejected',
  ];
  for (const field of expected) {
    assert.match(block, new RegExp(`pub ${field}:`), `Rust 에 ${field} 가 없다`);
  }

  // 화면 쪽 타입도 같은 이름(camelCase)을 갖고 있어야 한다.
  for (const field of expected) {
    const camel = field.replace(/_([a-z])/g, (_, c) => c.toUpperCase());
    assert.match(source, new RegExp(`\\b${camel}\\b`), `화면에 ${camel} 가 없다`);
  }
});

test('부르는 커맨드 이름이 Rust 에 실제로 등록돼 있다', async () => {
  const lib = await readFile(new URL('../../app/src/lib.rs', import.meta.url), 'utf8');
  for (const command of [
    'update_status',
    'update_web',
    'update_check_now',
    'update_native',
    'update_note_prompted',
  ]) {
    assert.match(
      lib,
      new RegExp(`commands::${command}\\b`),
      `${command} 가 invoke_handler 에 등록되지 않았다`,
    );
  }
  // 이 파일이 부르는 것들
  for (const command of ['update_status', 'update_web', 'update_check_now', 'update_native', 'update_note_prompted']) {
    assert.match(
      source,
      new RegExp(`invoke<[^>]*>\\('${command}'\\)`),
      `lib/platform/update.ts 가 ${command} 를 안 부른다`,
    );
  }
});

// ─────────────────────────────────────────────────────────────
// 안내 창을 띄울 조건 (모바일 전용)
// ─────────────────────────────────────────────────────────────

test('안내는 폰 폭이고 안드로이드일 때만 뜬다', () => {
  const ready = state({ nativeAvailable: true, canInstallNative: true, nativePromptDue: true });
  assert.equal(shouldPromptUpdate({ isMobile: true, state: ready }), true);
  // 폭이 넓으면 안 뜬다 — 데스크톱 브라우저로 같은 화면을 열었을 때.
  assert.equal(shouldPromptUpdate({ isMobile: false, state: ready }), false);
  // 폰 폭이어도 설치할 수 있는 플랫폼이 아니면 안 뜬다. 이게 없으면
  // "눌러도 아무 일도 안 일어나는 창" 이 뜬다.
  assert.equal(
    shouldPromptUpdate({
      isMobile: true,
      state: state({ nativeAvailable: true, canInstallNative: false, nativePromptDue: true }),
    }),
    false,
  );
});

test('새 APK 가 없으면 안내하지 않는다', () => {
  assert.equal(
    shouldPromptUpdate({
      isMobile: true,
      state: state({ nativeAvailable: false, canInstallNative: true, nativePromptDue: true }),
    }),
    false,
  );
});

test('오늘 이미 보여 줬으면 조용히 있는다', () => {
  // 앱을 켤 때마다 창이 뜨면 삭제로 이어진다. 기록은 Rust 가 DB 에 남긴다.
  assert.equal(
    shouldPromptUpdate({
      isMobile: true,
      state: state({ nativeAvailable: true, canInstallNative: true, nativePromptDue: false }),
    }),
    false,
  );
});

test('상태를 모르면 안내하지 않는다', () => {
  assert.equal(shouldPromptUpdate({ isMobile: true, state: null }), false);
  assert.equal(shouldPromptUpdate({ isMobile: true, state: UNKNOWN }), false);
});

test('상태 읽기는 내려받지 않는다', async () => {
  // 기동 훅이 이미 확인·내려받기를 했다. 안내 화면이 또 내려받으면 느린
  // 회선에서 서로를 방해한다 — `update_status` 만 불러야 한다.
  const calls = stub({ update_status: state() });
  await readUpdateState();
  assert.deepEqual(calls.map((call) => call.command), ['update_status']);
});

test('안내를 띄웠다고 기록한다', async () => {
  const calls = stub({ update_note_prompted: null });
  await noteUpdatePrompted();
  assert.deepEqual(calls.map((call) => call.command), ['update_note_prompted']);
});

test('기록에 실패해도 던지지 않는다', async () => {
  // 기록을 못 남긴 것 때문에 창을 막을 이유는 없다. 다음 기동에 한 번 더
  // 뜨는 정도의 대가다.
  stub({ update_note_prompted: new Error('db 잠김') });
  await assert.doesNotReject(() => noteUpdatePrompted());
});

// ─────────────────────────────────────────────────────────────
// 화면이 실제로 붙어 있는가
// ─────────────────────────────────────────────────────────────

test('안내 컴포넌트가 앱에 붙어 있다', async () => {
  const app = await readFile(new URL('../components/mupid-app.tsx', import.meta.url), 'utf8');
  assert.match(app, /<UpdatePrompt\s*\/>/, 'UpdatePrompt 가 렌더되지 않는다');
  assert.match(
    app,
    /import \{ UpdatePrompt \} from '@\/components\/mupid\/update-prompt'/,
    'import 가 없다',
  );
});

test('안내 컴포넌트가 폭과 플랫폼을 모두 본다', async () => {
  const prompt = await readFile(
    new URL('../components/mupid/update-prompt.tsx', import.meta.url),
    'utf8',
  );
  // 창 너비만 보면 태블릿·가로 화면에서 설치할 수 없는 창이 뜬다.
  assert.match(prompt, /useIsMobile\(\)/, '폭을 안 본다');
  // 조건 판단이 컴포넌트 안에 흩어져 있으면 창을 띄워 보지 않고는 확인할 수 없다.
  assert.match(prompt, /shouldPromptUpdate\(/, '순수 함수를 안 쓴다');
  // 닫을 방법이 없는 창은 없는 것만 못하다.
  assert.match(prompt, /AlertDialogCancel/, '"나중에" 가 없다');
  // 띄운 사실을 안 남기면 앱을 켤 때마다 같은 창이 다시 뜬다.
  assert.match(prompt, /noteUpdatePrompted\(/, '띄웠다고 기록하지 않는다');
});

test('띄울 때 한 번만 기록한다', async () => {
  const prompt = await readFile(
    new URL('../components/mupid/update-prompt.tsx', import.meta.url),
    'utf8',
  );
  // 다시 그려질 때마다 부르면 창이 열려 있는 동안 계속 DB 를 두드린다.
  assert.match(prompt, /noted\s*=\s*useRef\(false\)/, '한 번만 기록하는 장치가 없다');
  assert.match(prompt, /noted\.current/, 'ref 를 안 쓴다');
});

// ─────────────────────────────────────────────────────────────
// 보이는 모습 (실기기에서 실제로 어긋났던 것들)
// ─────────────────────────────────────────────────────────────

test('제목·설명을 가운데로 되돌린다', async () => {
  const css = await readFile(new URL('../app/update-prompt.css', import.meta.url), 'utf8');
  const viewport = await readFile(new URL('../app/dialog-viewport.css', import.meta.url), 'utf8');

  // 공통 규칙이 헤더 오른쪽에 닫기 버튼 자리(52px)를 비운다. AlertDialog 에는
  // 닫기 버튼이 없어서(`AlertDialogCancel` 은 footer) 그대로 두면 내용이
  // 왼쪽으로 밀린다 — 실기기에서 눈에 띄게 어긋났다.
  assert.match(
    viewport,
    /\.sky-dialog \[data-slot='alert-dialog-header'\][\s\S]{0,200}padding-right: 52px/,
    '공통 규칙이 바뀌었다 — 이 시험의 전제를 다시 확인해야 한다',
  );
  assert.match(
    css,
    /\.update-dialog \[data-slot='alert-dialog-header'\]\s*\{\s*padding-right: 0;/,
    '닫기 버튼 자리를 되돌리지 않는다',
  );
  assert.match(
    css,
    /\.update-dialog \[data-slot='alert-dialog-title'\],\s*\.update-dialog \[data-slot='alert-dialog-description'\]\s*\{\s*text-align: center;/,
    '가운데 정렬이 없다',
  );
});

test('설명문은 두 줄로 끊어 읽기 쉽게 둔다', async () => {
  const prompt = await readFile(
    new URL('../components/mupid/update-prompt.tsx', import.meta.url),
    'utf8',
  );
  // 한 줄로 두면 폰 폭에서 어절이 임의로 끊겨 두 문장의 경계가 흐려진다.
  // 문장 단위로 끊어 주면 눈이 한 번에 한 줄씩 읽는다.
  assert.match(
    prompt,
    /돌아왔어요\.\s*<br\s*\/>\s*설치하면/,
    '설명문에 의도적인 개행이 없다',
  );
});

test('설명문 줄 간격이 답답하지 않다', async () => {
  const css = await readFile(new URL('../app/update-prompt.css', import.meta.url), 'utf8');
  // 개행을 넣었으니 줄 간격이 좁으면 두 줄이 한 덩어리로 뭉쳐 보인다.
  const block = css.slice(css.indexOf("[data-slot='alert-dialog-description']"));
  const lineHeight = block.match(/line-height:\s*([\d.]+)/);
  assert.ok(lineHeight, '줄 간격이 없다');
  assert.ok(
    Number(lineHeight[1]) >= 1.5,
    `줄 간격이 좁다 (${lineHeight[1]}) — 두 줄이 뭉쳐 보인다`,
  );
});

test('주 버튼은 브랜드 파랑이다 (붉은색이 아니다)', async () => {
  const css = await readFile(new URL('../app/update-prompt.css', import.meta.url), 'utf8');
  const polish = await readFile(new URL('../app/responsive-polish.css', import.meta.url), 'utf8');

  // 공통 규칙이 `.sky-dialog` 의 action 버튼을 전부 붉게 칠한다(삭제·초기화용).
  // 설치에는 뜻이 어긋나므로 되돌린다.
  assert.match(
    polish,
    /\.sky-dialog \[data-slot='alert-dialog-action'\]\s*\{\s*border: 0;\s*background: #c9636e;/,
    '공통 규칙이 바뀌었다 — 이 시험의 전제를 다시 확인해야 한다',
  );
  assert.match(
    css,
    /\.update-dialog \[data-slot='alert-dialog-action'\]\s*\{[\s\S]*?background: linear-gradient\(110deg, #6b97dc, #507dcc\)/,
    '브랜드 파랑으로 되돌리지 않는다',
  );
  // 호버도 함께 되돌려야 한다. 안 그러면 마우스를 올리는 순간 붉어진다.
  assert.match(
    css,
    /\.update-dialog \[data-slot='alert-dialog-action'\]:hover:not\(:disabled\)/,
    '호버 색을 되돌리지 않는다',
  );
  // 브랜드 색은 앱의 주 버튼과 같은 값이어야 한다.
  const mupid = await readFile(new URL('../app/mupid.css', import.meta.url), 'utf8');
  assert.match(
    mupid,
    /\.primary-button \{\s*color: white;\s*background: linear-gradient\(110deg, #6b97dc, #507dcc\);/,
    '브랜드 주 버튼 색이 바뀌었다 — 위 값과 맞춰야 한다',
  );
});
