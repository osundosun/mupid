import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { BATCH_SIZE, createTrackResolver } from '../lib/track-lookup.ts';

/*
 * 곡 번호로 곡을 찾는다. 이미 아는 곡은 묻지 않고, 모르는 곡만 일괄 조회로 묶어 묻는다
 * (S15P21A204-443 → 446). 곡 목록(카탈로그)을 미리 받아 두지 않는다.
 */
const track = (musicId) => ({ id: 1000 + musicId, musicId, title: `곡 ${musicId}` });
/** 서버처럼 굴어 준다. 없는 곡은 결과에서 뺀다. */
const server = (exists = () => true) => {
  const calls = [];
  const get = async (musicIds) => {
    calls.push([...musicIds]);
    return musicIds.filter(exists).map(track);
  };
  return { calls, get };
};

test('이미 아는 곡은 묻지 않고, 모르는 곡만 한 번에 묻는다', async () => {
  const { calls, get } = server();
  const { resolve } = createTrackResolver(get);
  const found = await resolve([1, 500, 2, 700], [track(1), track(2)]);

  assert.deepEqual(calls, [[500, 700]]);
  assert.deepEqual([...found.keys()], [1, 500, 2, 700], '보낸 순서를 지킨다');
});

test('없는 곡(지워진 곡)은 빠지고 다시 묻지 않는다', async () => {
  const { calls, get } = server((id) => id !== 404);
  const { resolve } = createTrackResolver(get);
  assert.deepEqual([...(await resolve([404, 600], [])).keys()], [600]);
  assert.deepEqual([...(await resolve([404], [])).keys()], []);
  assert.deepEqual(calls, [[404, 600]]);
});

test('상한보다 많으면 나눠 묻는다', async () => {
  const { calls, get } = server();
  const { resolve } = createTrackResolver(get);
  const ids = Array.from({ length: BATCH_SIZE + 5 }, (_, index) => index + 1);
  const found = await resolve(ids, []);

  assert.equal(found.size, ids.length);
  assert.deepEqual(calls.map((batch) => batch.length), [BATCH_SIZE, 5]);
});

test('같은 곡을 나란히 물어도 한 번만 묻고, 실패한 묶음은 다음에 다시 묻는다', async () => {
  let fail = true;
  const calls = [];
  const { resolve } = createTrackResolver(async (musicIds) => {
    calls.push([...musicIds]);
    if (fail) throw new Error('network');
    return musicIds.map(track);
  });
  const [first, second] = await Promise.all([resolve([9], []), resolve([9], [])]);
  assert.equal(calls.length, 1);
  assert.equal(first.size + second.size, 0, '실패하면 빈 결과다. 던지지 않는다');

  fail = false;
  const found = await resolve([9], []);
  assert.equal(calls.length, 2);
  assert.equal(found.get(9)?.id, 1009);
});

test('보관함 화면은 카탈로그가 아니라 knownTracks 에서 곡을 찾는다', async () => {
  const source = await readFile(new URL('../components/mupid/collection.tsx', import.meta.url), 'utf8');
  const library = source.slice(source.indexOf('export function LibraryView'));
  assert.match(library.slice(0, 300), /const tracks = m\.knownTracks;/);
});
