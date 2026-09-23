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

test('bootstrap returns independent snapshots of preferences', async () => {
  const service = createDemoService(memory());
  const first = await service.bootstrap();
  // 받은 쪽이 고쳐도 다음 호출이 흔들리지 않는다.
  first.preferences.moods.push('망가뜨리기');
  const second = await service.bootstrap();
  assert.deepEqual(second.preferences.moods, []);
});

// 곡 목록(카탈로그)을 들고 오지 않는다 (S15P21A204-446). 곡은 저장된 값이 가리키는
// 것만 찾아 오고, 그마저 찾는 방법(resolve)이 없으면 묻지 않는다. 예시 곡으로 채우지 않는다.
test('bootstrap carries no song list, only tracks the stored values point to', async () => {
  const { knownTracks } = await createDemoService(memory()).bootstrap();
  assert.deepEqual(knownTracks, []);
  const asked = [];
  const result = await createDemoService(memory()).bootstrap(async (ids) => { asked.push(...ids); return []; });
  assert.deepEqual(result.knownTracks, []);
  assert.deepEqual(asked, [], '가리키는 곡이 없으면 묻지도 않는다');
});
test('first visits require onboarding and completion survives reload independently of login', async () => {
  const service = createDemoService(memory());
  const first = await service.bootstrap();
  assert.equal(first.preferences.onboardingSubmitted, false);
  first.preferences.onboardingSubmitted = true;
  first.preferences.nickname = '방문자'; first.preferences.moods = ['잔잔한'];
  await service.savePreferences(first.preferences);
  await service.logout();
  const next = await service.bootstrap();
  assert.equal(next.preferences.onboardingSubmitted, true);
  assert.deepEqual(next.preferences.moods, ['잔잔한']);
});
// 예전에는 완료 **시각**을 저장했다. 화면이 시각을 쓰지 않아 불리언으로 바꿨는데,
// 이미 저장된 기기가 "안 했다"로 읽혀 설문을 다시 보면 안 된다.
test('legacy onboarding timestamps still read as completed', async () => {
  const storage = memory();
  storage.setItem('mupid.sky.preferences.v1', JSON.stringify({ onboardingSubmittedAt: '2026-09-08T00:00:00.000Z' }));
  const { preferences } = await createDemoService(storage).bootstrap();
  assert.equal(preferences.onboardingSubmitted, true);
});
test('existing preferences and empty playlists survive round trip', async () => {
  const service = createDemoService(memory()); const data = await service.bootstrap();
  data.preferences.nickname = '테스트'; data.preferences.playlists = [];
  await service.savePreferences(data.preferences);
  const restored = await service.bootstrap();
  assert.equal(restored.preferences.nickname, '테스트'); assert.deepEqual(restored.preferences.playlists, []);
});
test('corrupt storage recovers and malformed track references are removed', async () => {
  const storage = memory(); storage.setItem('mupid.sky.preferences.v1', '{broken');
  const service = createDemoService(storage);
  assert.deepEqual((await service.bootstrap()).preferences.liked, []);
  // 곡 번호 목록은 번호만 본다 — 곡 목록으로 거르지 않는다. 번호가 아닌 것만 버린다.
  storage.setItem('mupid.sky.preferences.v1', JSON.stringify({ liked: [1, 1999, 'x', 1.5, null], playlists: [{id: 3, name: '목록', tracks: [null, {id: 999}]}] }));
  const data = await service.bootstrap(); assert.deepEqual(data.preferences.liked, [1, 1999]); assert.deepEqual(data.preferences.playlists, []);
});
// 기기에 남은 플레이리스트는 되살리지도, 다시 적지도 않는다. 이 저장소는 계정별로
// 나뉘어 있지 않아 — 신규 가입자가 앞사람의(그리고 예전 빌드의 데모) 목록을 넘겨받았다.
test('device-stored playlists are never restored and get purged on save', async () => {
  const storage = memory();
  storage.setItem('mupid.sky.preferences.v1', JSON.stringify({ nickname: '새 사람', playlists: [{ id: 1, name: '규원의 Mupid Mix', tracks: [] }] }));
  const service = createDemoService(storage);
  const data = await service.bootstrap();
  assert.deepEqual(data.preferences.playlists, []);
  await service.savePreferences({ ...data.preferences, playlists: [{ id: 1, name: '규원의 Mupid Mix', tracks: [] }] });
  assert.deepEqual(JSON.parse(storage.getItem('mupid.sky.preferences.v1')).playlists, []);
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
  assert.deepEqual((await broken.bootstrap()).knownTracks, []);
  await assert.rejects(broken.savePhoto('x'), /quota/);
});
test('API mode never silently falls back to demo', () => {
  assert.throws(() => createService('api'), /어댑터/);
  assert.throws(() => createService('api', createDemoService(memory())), /어댑터/);
});

// 저장한 순간과 마지막 재생 곡은 가리키는 곡을 찾아 붙여야 남는다 (S15P21A204-443·446).
test('moments and the last played track are restored through the resolver', async () => {
  const storage = memory();
  const song = { ...demoTracks[0], id: 1777, musicId: 777, duration: 200 };
  const last = { ...demoTracks[1], id: 1888, musicId: 888 };
  storage.setItem('mupid.sky.preferences.v1', JSON.stringify({
    moments: [{ id: 1, track: song, start: 10, end: 20, section: '코러스' }],
    lastPlayedId: 1888,
  }));
  const service = createDemoService(storage);

  // 찾는 방법이 없으면(로그인 전) 곡을 알아야 하는 값은 비운다.
  const anonymous = await service.bootstrap();
  assert.deepEqual(anonymous.preferences.moments, []);
  assert.equal(anonymous.preferences.lastPlayedId, null);

  const asked = [];
  const { preferences, knownTracks } = await service.bootstrap(async (ids) => {
    asked.push(...ids);
    return [song, last].filter((track) => ids.includes(track.id));
  });
  assert.deepEqual(asked, [1777, 1888], '한 번에 묻는다');
  assert.equal(preferences.moments.length, 1);
  assert.equal(preferences.moments[0].track.musicId, 777);
  assert.equal(preferences.lastPlayedId, 1888);
  assert.deepEqual(knownTracks.map((track) => track.id), [1777, 1888]);
});
