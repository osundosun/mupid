import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

// 화면 쪽만 떼어 검사한다. Tauri 런타임도 기기 도네트워크도 필요 없다 —
// `invoke` 자리에 기록기를 꽂아 두고 **무엇을 언제 부르는지** 만 본다
// (`tests/update.test.mjs` · `tests/media-session.test.mjs` 와 같은 방식).
//
// `./bridge` 는 확장자 없는 상대 경로라 `node --test` 가 해석하지 못한다.
// 그래서 컴파일한 결과에서 그 지정자를 data: URL 로 갈아 끼운다.
const bridgeStub =
  'data:text/javascript;base64,' +
  Buffer.from(
    `export const isTauri = () => globalThis.__isTauri ?? true;
     export const invoke = (command, args) => globalThis.__invoke(command, args);`,
  ).toString('base64');

const source = await readFile(
  new URL('../lib/platform/crash.ts', import.meta.url),
  'utf8',
);
const js = ts
  .transpileModule(source, {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.ESNext,
    },
  })
  .outputText.replace(/['"]\.\/bridge['"]/g, JSON.stringify(bridgeStub));

const {
  CRASH_MESSAGE_LIMIT,
  CRASH_SESSION_LIMIT,
  currentRoute,
  describe,
  reportCrash,
  watchCrashReports,
} = await import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'));

/**
 * 창 하나와 `invoke` 기록기를 세운다.
 *
 * `isTauri` 가 참이면 앱 셸 안이다. 거짓이면 웹 배포다 — 그때 이 모듈은
 * **아무 일도 하지 않아야** 한다.
 */
const stage = ({ tauri = true, route = '/report' } = {}) => {
  const calls = [];
  globalThis.__isTauri = tauri;
  globalThis.__invoke = (command, args) => {
    calls.push({ command, args });
    return Promise.resolve();
  };

  const listeners = new Map();
  globalThis.window = {
    location: { pathname: route },
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
  return {
    calls,
    /** 잡힌 처리기를 부른다. 이벤트 객체는 필요한 만큼만 흉내 낸다. */
    fire: (event, payload) =>
      (listeners.get(event) ?? []).forEach((handler) => handler(payload)),
    count: (event) => (listeners.get(event) ?? []).length,
  };
};

const crashCalls = (calls) => calls.filter((one) => one.command === 'crash_report');

test('잡히지 않은 예외를 셸로 넘긴다', () => {
  const world = stage();
  const stop = watchCrashReports();
  const stack = new Error('render failed').stack;

  world.fire('error', {
    message: 'render failed',
    error: { stack, message: 'render failed' },
    filename: 'app.js',
    lineno: 12,
    colno: 3,
  });

  const sent = crashCalls(world.calls);
  assert.equal(sent.length, 1, '예외 하나가 보고 하나여야 한다');
  assert.equal(sent[0].args.value.message, 'render failed');
  assert.equal(sent[0].args.value.stack, stack);
  // 화면 경로가 함께 실린다 — 스택만으로는 어느 화면인지 알 수 없다.
  assert.equal(sent[0].args.value.context, '/report');
  assert.ok(sent[0].args.value.atMs > 0, '발생 시각이 없다');
  stop();
});

test('처리되지 않은 Promise 거부를 셸로 넘긴다', () => {
  const world = stage();
  const stop = watchCrashReports();
  const reason = new Error('fetch failed');

  world.fire('unhandledrejection', { reason });

  const sent = crashCalls(world.calls);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].args.value.message, 'fetch failed');
  assert.equal(sent[0].args.value.stack, reason.stack);
  stop();
});

test('문자열로 거부해도 읽을 수 있는 한 줄이 된다', () => {
  const world = stage();
  const stop = watchCrashReports();

  world.fire('unhandledrejection', { reason: 'plain string' });

  const sent = crashCalls(world.calls);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].args.value.message, 'plain string');
  stop();
});

test('웹 배포에서는 아무것도 보내지 않는다', () => {
  const world = stage({ tauri: false });
  const stop = watchCrashReports();

  world.fire('error', { message: 'boom', error: new Error('boom') });
  world.fire('unhandledrejection', { reason: new Error('rejected') });

  assert.equal(world.calls.length, 0, '웹에서 셸 커맨드를 불렀다');
  stop();
});

test('상한을 넘으면 조용히 버린다', () => {
  // 렌더 루프에서 매 프레임 예외가 나면 그대로 IPC 폭주가 된다.
  const world = stage();
  const stop = watchCrashReports();

  for (let index = 0; index < CRASH_SESSION_LIMIT + 25; index += 1) {
    world.fire('error', { message: `boom ${index}`, error: new Error('boom') });
  }

  assert.equal(crashCalls(world.calls).length, CRASH_SESSION_LIMIT, '상한이 지켜지지 않았다');
  stop();
});

test('끊으면 더 이상 잡지 않는다', () => {
  const world = stage();
  const stop = watchCrashReports();
  world.fire('error', { message: 'one', error: new Error('one') });
  stop();
  world.fire('error', { message: 'two', error: new Error('two') });

  const sent = crashCalls(world.calls);
  assert.equal(sent.length, 1, '끊은 뒤에도 잡았다');
  assert.equal(sent[0].args.value.message, 'one');
  assert.equal(world.count('error'), 0, '처리기가 남아 있다');
});

test('전송이 실패해도 예외를 밖으로 던지지 않는다', () => {
  // 예외 처리기가 예외를 던지면 그 예외가 다시 처리기로 들어와 무한 고리가 된다.
  const world = stage();
  globalThis.__invoke = () => Promise.reject(new Error('no shell'));
  const stop = watchCrashReports();

  assert.doesNotThrow(() => {
    world.fire('error', { message: 'boom', error: new Error('boom') });
  });
  stop();
});

test('빈 메시지여도 자리를 잃지 않는다', () => {
  // 메시지가 비면 `filename:line:col` 로 내려앉는다 — 그마저도 없으면
  // "알 수 없는 오류" 다. **아무것도 안 보내면 그 예외는 영영 사라진다.**
  // (동기 예외의 fallback 이 있으므로 여기서 빈 보고가 나가는 것이 맞다.
  //  메시지 자체가 빈 문자열인 경우는 `reportCrash` 를 직접 부를 때뿐이다.)
  const world = stage();
  const stop = watchCrashReports();

  world.fire('error', { message: '', error: undefined, filename: 'app.js', lineno: 7, colno: 2 });
  const sent = crashCalls(world.calls);
  assert.equal(sent.length, 1, '자리까지 잃은 예외를 버렸다');
  assert.equal(sent[0].args.value.message, '알 수 없는 오류');
  assert.equal(sent[0].args.value.stack, 'app.js:7:2');

  // 아무 단서도 없으면 **알 수 없는 오류**로 남긴다.
  world.fire('error', { message: '', error: undefined });
  assert.equal(crashCalls(world.calls).length, 2);
  assert.equal(world.calls[1].args.value.message, '알 수 없는 오류');

  // 반대로 **직접 부를 때** 빈 메시지는 보내지 않는다.
  reportCrash({ message: '   ' });
  assert.equal(crashCalls(world.calls).length, 2, '빈 메시지를 직접 보냈다');
  stop();
});

test('긴 본문은 잘려서 나간다', () => {
  const world = stage();
  const stop = watchCrashReports();
  world.fire('error', { message: '가'.repeat(CRASH_MESSAGE_LIMIT * 2), error: undefined });

  const sent = crashCalls(world.calls);
  assert.equal(sent.length, 1);
  const message = sent[0].args.value.message;
  assert.ok(message.length <= CRASH_MESSAGE_LIMIT + 1, '본문이 잘리지 않았다');
  assert.ok(message.endsWith('…'), '잘린 표시가 없다');
  stop();
});

test('직접 부를 수도 있다 — 서버가 죽은 자리처럼', () => {
  // `lib/platform/update.ts` 의 `checkForUpdates` 가 이 통로를 쓴다.
  const world = stage();
  reportCrash({ message: '네트워크 요청에 실패했어요', context: 'checkForUpdates' });

  const sent = crashCalls(world.calls);
  assert.equal(sent.length, 1);
  assert.equal(sent[0].args.value.context, 'checkForUpdates');
});

test('순환 참조가 있는 값을 사람이 읽을 수 있게 만든다', () => {
  const circular = { name: 'loop' };
  circular.self = circular;
  // `JSON.stringify` 는 여기서 던진다. 던지면 위의 무한 고리다.
  assert.doesNotThrow(() => describe(circular));
  assert.equal(describe('그대로'), '그대로');
  assert.equal(describe(42), '42');
  assert.equal(describe(null), 'null');
  assert.ok(describe(circular).length > 0);
});

test('화면 경로를 못 읽어도 빈 문자열로 내려앉는다', () => {
  delete globalThis.window;
  assert.equal(currentRoute(), '');
});

test('update.ts 가 이 통로를 실제로 쓴다', async () => {
  // 배선이 끊기면 이 시험이 잡는다. 주석만 남고 코드가 없으면 아무것도 안 남는다.
  const update = await readFile(
    new URL('../lib/platform/update.ts', import.meta.url),
    'utf8',
  );
  assert.match(update, /from '\.\/crash'/, 'update.ts 가 crash 모듈을 부르지 않는다');
  assert.match(update, /reportCrash\(\{/, 'update.ts 가 reportCrash 를 부르지 않는다');
});

test('화면이 가장 바깥에서 처리기를 건다', async () => {
  const app = await readFile(
    new URL('../components/mupid-app.tsx', import.meta.url),
    'utf8',
  );
  assert.match(
    app,
    /useEffect\(\(\) => watchCrashReports\(\), \[\]\)/,
    'mupid-app.tsx 가 예외 처리기를 걸지 않는다',
  );
});
