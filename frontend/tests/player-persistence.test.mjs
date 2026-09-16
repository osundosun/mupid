import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

const read = (file) => readFile(new URL('../' + file, import.meta.url), 'utf8');

/*
 * 캐시만 떼어 불러온다. 확장자 없는 상대 import 는 Node 가 그대로 풀지 못하므로
 * `tests/services.test.mjs` 와 같은 방법으로 옮겨 붙인다.
 */
const js = ts
  .transpileModule(await read('lib/services/bootstrap-cache.ts'), {
    compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext },
  })
  .outputText.replace(
    "'../store'",
    JSON.stringify(new URL('../lib/store.ts', import.meta.url).href),
  );
const { bootstrapCache, clearBootstrap, readBootstrap, writeBootstrap } = await import(
  'data:text/javascript;base64,' + Buffer.from(js).toString('base64')
);

/*
 * 메뉴를 옮기면 재생이 끊기던 문제를 지키는 시험이다. 두 갈래가 함께 있어야 한다.
 *
 *  1. 정적 배포에서 화면 이동이 전체 새로고침으로 떨어지지 않을 것 — 그러면
 *     모듈 스코프든 React 상태든 전부 함께 사라진다.
 *  2. 그 위에서, 화면이 다시 마운트돼도 부트스트랩을 다시 받지 않을 것.
 */

const sample = (id) => ({
  tracks: [{ id, title: `곡 ${id}` }],
  preferences: { nickname: `사람 ${id}` },
  session: null,
});

test('받아 둔 부트스트랩은 같은 계정의 다음 마운트가 그대로 쓴다', () => {
  clearBootstrap();
  assert.equal(readBootstrap(7), null);
  const first = sample(7);
  writeBootstrap(7, first);
  // 두 번째 마운트. 같은 값을 돌려주므로 화면은 로딩을 거치지 않는다.
  assert.equal(readBootstrap(7), first);
  clearBootstrap();
});

test('계정이 다르면 없는 것으로 친다 — 앞사람의 컬렉션이 넘어가지 않는다', () => {
  clearBootstrap();
  writeBootstrap(7, sample(7));
  // 로그아웃(비로그인은 0번)과 계정 전환 모두 여기서 걸러진다.
  assert.equal(readBootstrap(0), null);
  assert.equal(readBootstrap(8), null);
  // 다음 부트스트랩이 이 자리를 덮어쓰므로 앞 계정의 값도 남지 않는다.
  writeBootstrap(0, sample(0));
  assert.equal(readBootstrap(7), null);
  assert.equal(bootstrapCache.get().accountId, 0);
  clearBootstrap();
});

test('"다시 불러오기" 는 캐시를 버리고 서버로 간다', () => {
  clearBootstrap();
  writeBootstrap(7, sample(7));
  clearBootstrap();
  assert.equal(readBootstrap(7), null);
  assert.equal(bootstrapCache.get().value, null);
});

// 스토어는 React 밖에 있다. 화면이 사라져도 값이 남아야 의미가 있다.
test('부트스트랩 캐시는 모듈 스코프에 있다 — React 를 쓰지 않는다', async () => {
  const source = await read('lib/services/bootstrap-cache.ts');
  assert.ok(source.includes("from '../store'"));
  // React 를 부르지 않는다. 부르는 순간 값의 수명이 화면에 묶인다.
  assert.ok(!/from ['"]react['"]/.test(source));
  assert.ok(!/\buse[A-Z]\w*\(/.test(source));
});

test('MupidApp 은 받아 둔 값으로 시작하고, 없을 때만 새로 받는다', async () => {
  const app = await read('components/mupid-app.tsx');
  // 첫 렌더부터 캐시를 본다. 로딩 화면을 한 번 거치면 그 아래가 통째로 사라진다.
  assert.ok(app.includes('useState<Bootstrap | null>(() => readBootstrap(accountId))'));
  // 이미 있으면 네트워크로 가지 않는다.
  assert.match(app, /const cached = readBootstrap\(accountId\);\s*\n\s*if \(cached\) \{ setData\(cached\); return; \}/);
  // 받아 온 값은 다음 마운트를 위해 남긴다.
  assert.ok(app.includes('writeBootstrap(accountId, value)'));
  // 계정이 바뀌면 다시 받는 길은 그대로 둔다.
  assert.ok(app.includes('}, [attempt, accountId]);'));
  // 다시 불러오기는 캐시를 지나친다.
  assert.ok(app.includes("setError(''); clearBootstrap(); setAttempt("));
});

test('공통 레이아웃은 화면마다 다시 만들어지지 않는다', async () => {
  const layout = await read('app/(music)/layout.tsx');
  // 페이지가 레이아웃을 감싸면 이동할 때마다 새로 만들어진다. 지금 모양을 지킨다.
  assert.ok(layout.includes('<MupidApp />'));
  assert.ok(layout.includes('{children}'));
  for (const page of ['home', 'explore', 'player', 'playlists', 'report', 'profile', 'moments', 'similar']) {
    const source = await read(`app/(music)/${page}/page.tsx`);
    assert.match(source, /return null;/);
  }
});

/*
 * 정적 배포(`ec2`)의 nginx 는 내보낸 파일을 그대로 돌려줄 뿐이라
 * `X-Vinext-RSC-Compatibility-Id` 를 붙이지 못한다. 번들에 식별자가 박혀 있으면
 * 라우터는 매 이동마다 값이 다르다고 보고 문서를 통째로 다시 읽는다 — 그것이
 * 재생이 끊기고 상태가 초기화되던 원인이다.
 */
test('ec2 빌드는 RSC 호환성 식별자 검사를 끈다', async () => {
  const config = await read('vite.config.ts');
  assert.ok(
    config.includes("pinRscCompatibilityId(target === 'app' ? RSC_COMPATIBILITY_ID : '')"),
    'ec2 에는 빈 값을, app 에는 고정 값을 박아야 한다',
  );
  // 앱 셸(`app/src/lib.rs`)과 맞춘 고정 값은 그대로 남아 있어야 한다.
  assert.match(config, /RSC_COMPATIBILITY_ID = '[^']+'/);
});
