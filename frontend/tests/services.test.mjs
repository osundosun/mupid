import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import ts from 'typescript';

// Compile only the adapter under test; no server, browser or real credentials required.
const source = await readFile(new URL('../lib/services/index.ts', import.meta.url), 'utf8');
const js = ts.transpileModule(source, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  .replace("'../mupid-data'", JSON.stringify(new URL('../lib/mupid-data.ts', import.meta.url).href));
const { createDemoService, createService } = await import('data:text/javascript;base64,' + Buffer.from(js).toString('base64'));
const memory = () => { const values = new Map(); return {
  getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key), values,
}; };

test('public demo account accepts only the configured credentials', async () => {
  const service = createDemoService(memory());
  const session = await service.login({ email:'demo@naver.com', password:'1234' });
  assert.equal(session.kind, 'demo'); assert.equal(session.userId, -1);
  assert.equal(session.onboardingSubmittedAt, null);
  assert.equal((await service.bootstrap()).session.kind, 'demo');
  await service.logout();
  assert.equal((await service.bootstrap()).session, null);
  await assert.rejects(service.login({email:'demo@naver.com', password:'wrong'}));
  await assert.rejects(service.login({email:'other@naver.com', password:'1234'}));
});

test('bootstrap returns independent demo snapshots', async () => {
  const service = createDemoService(memory());
  const first = await service.bootstrap(); first.tracks.length = 0; first.preferences.playlists.length = 0;
  const second = await service.bootstrap();
  assert.equal(second.tracks.length, 8); assert.ok(second.preferences.playlists.length);
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
  const service = createDemoService(storage); assert.equal((await service.bootstrap()).tracks.length, 8);
  storage.setItem('mupid.sky.preferences.v1', JSON.stringify({ liked: [1, 999], playlists: [{id: 3, name: '목록', tracks: [null, {id: 999}]}] }));
  const data = await service.bootstrap(); assert.deepEqual(data.preferences.liked, [1]); assert.deepEqual(data.preferences.playlists[0].tracks, []);
});
test('invalid demo login and unconnected signup never authenticate or store credentials', async () => {
  const storage = memory(); const service = createDemoService(storage);
  await assert.rejects(service.login({email:'demo@example.test', password:'test-only'}), /데모 이메일 또는 비밀번호/);
  await assert.rejects(service.signup({}), /서버/);
  await assert.rejects(service.requestEmailVerification('demo@example.test'), /서버/);
  assert.equal(storage.values.size, 0);
});
test('photo create/read/delete and storage failure propagate', async () => {
  const service = createDemoService(memory());
  await service.savePhoto('data:image/jpeg;base64,test'); assert.equal(await service.readPhoto(), 'data:image/jpeg;base64,test');
  await service.removePhoto(); assert.equal(await service.readPhoto(), '');
  const broken = createDemoService({ getItem(){throw Error('blocked');}, setItem(){throw Error('quota');}, removeItem(){throw Error('blocked');} });
  assert.equal((await broken.bootstrap()).tracks.length, 8);
  await assert.rejects(broken.savePhoto('x'), /quota/);
});
test('API mode never silently falls back to demo', () => {
  assert.throws(() => createService('api'), /어댑터/);
  assert.throws(() => createService('api', createDemoService(memory())), /어댑터/);
});
