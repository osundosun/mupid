import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

// Compile only the adapter under test; no server, browser or real credentials required.
const source = await readFile(new URL('../lib/services/index.ts', import.meta.url), 'utf8');
const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  .replace("'../mupid-data'", JSON.stringify(new URL('../lib/mupid-data.ts', import.meta.url).href))
;
const { createDemoService, createService } = await import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'));
// 화면에 보일 곡은 서버에서 온다. 시험에서는 그 자리에 픽스처를 넣는다.
const { demoTracks } = await import(new URL('./fixtures/tracks.mjs', import.meta.url).href);

const memory = () => { const values = new Map(); return {
  getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key), values,
}; };

// 체험 로그인은 없앴다. 브라우저 저장만으로 서비스가 열리면 그건 인증이 아니다.
test('browser storage never grants a session', async () => {
  const storage = memory();
  const service = createDemoService(storage);
  await assert.rejects(service.login({ email: 'demo@naver.com', password: '1234' }), /서버/);
  assert.equal((await service.bootstrap()).session, null);
  // 예전 체험 로그인 흔적이 남아 있어도 세션으로 되살아나지 않는다.
  storage.setItem('mupid.demo-session.v1', 'active');
  assert.equal((await service.bootstrap()).session, null);
  assert.equal(storage.getItem('mupid.demo-session.v1'), null);
});

test('bootstrap returns independent snapshots of the server catalog', async () => {
  const service = createDemoService(memory());
  const first = await service.bootstrap(demoTracks);
  assert.equal(first.tracks.length, demoTracks.length);
  // 받은 쪽이 고쳐도 다음 호출과 원본 목록이 흔들리지 않는다.
  first.tracks.length = 0;
  first.preferences.moods.push('망가뜨리기');
  const second = await service.bootstrap(demoTracks);
  assert.equal(second.tracks.length, demoTracks.length);
  assert.deepEqual(second.preferences.moods, []);
  assert.equal(demoTracks.length, 8);
});

// 곡을 하나도 받지 못하면 화면에 보일 곡도 없다. 예시 곡으로 채우지 않는다.
test('bootstrap without a catalog has no songs', async () => {
  const { tracks } = await createDemoService(memory()).bootstrap();
  assert.deepEqual(tracks, []);
});
test('first visits require onboarding and completion survives reload independently of login', async () => {
  const service = createDemoService(memory());
  const first = await service.bootstrap();
  assert.equal(first.preferences.onboardingSubmittedAt, null);
  first.preferences.onboardingSubmittedAt = '2026-09-08T00:00:00.000Z';
  first.preferences.nickname = '방문자'; first.preferences.moods = ['잔잔한'];
  await service.savePreferences(first.preferences);
  await service.logout();
  const next = await service.bootstrap();
  assert.equal(next.preferences.onboardingSubmittedAt, first.preferences.onboardingSubmittedAt);
  assert.deepEqual(next.preferences.moods, ['잔잔한']);
});
test('existing preferences and empty playlists survive round trip', async () => {
  const service = createDemoService(memory()); const data = await service.bootstrap();
  data.preferences.nickname = '테스트'; data.preferences.playlists = [];
  await service.savePreferences(data.preferences);
  const restored = await service.bootstrap();
  assert.equal(restored.preferences.nickname, '테스트'); assert.deepEqual(restored.preferences.playlists, []);
});
test('corrupt storage recovers and invalid track references are removed', async () => {
  const storage = memory(); storage.setItem('mupid.sky.preferences.v1', '{broken');
  const service = createDemoService(storage);
  assert.equal((await service.bootstrap(demoTracks)).tracks.length, demoTracks.length);
  storage.setItem('mupid.sky.preferences.v1', JSON.stringify({ liked: [1, 999], playlists: [{id: 3, name: '목록', tracks: [null, {id: 999}]}] }));
  const data = await service.bootstrap(demoTracks); assert.deepEqual(data.preferences.liked, [1]); assert.deepEqual(data.preferences.playlists[0].tracks, []);
});
test('browser-only service never authenticates or stores credentials', async () => {
  const storage = memory(); const service = createDemoService(storage);
  await assert.rejects(service.login({email:'demo@example.test', password:'test-only'}), /서버/);
  await assert.rejects(service.signup({}), /서버/);
  await assert.rejects(service.requestEmailVerification('demo@example.test'), /서버/);
  assert.equal(storage.values.size, 0);
});
test('photo create/read/delete and storage failure propagate', async () => {
  const service = createDemoService(memory());
  await service.savePhoto('data:image/jpeg;base64,test'); assert.equal(await service.readPhoto(), 'data:image/jpeg;base64,test');
  await service.removePhoto(); assert.equal(await service.readPhoto(), '');
  const broken = createDemoService({ getItem(){throw Error('blocked');}, setItem(){throw Error('quota');}, removeItem(){throw Error('blocked');} });
  assert.equal((await broken.bootstrap(demoTracks)).tracks.length, demoTracks.length);
  await assert.rejects(broken.savePhoto('x'), /quota/);
});
test('API mode never silently falls back to demo', () => {
  assert.throws(() => createService('api'), /어댑터/);
  assert.throws(() => createService('api', createDemoService(memory())), /어댑터/);
});
