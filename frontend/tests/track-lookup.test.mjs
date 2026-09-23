import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createTrackResolver } from '../lib/track-lookup.ts';

/*
 * 보관함은 카탈로그(앞 100곡) 밖의 곡도 가리킨다. 새로고침 때 그런 곡을 버리던 것이
 * S15P21A204-443 이다. 없는 곡만 곡 번호로 받아 오는지 본다.
 */
const track = (musicId) => ({ id: 1000 + musicId, musicId, title: `곡 ${musicId}` });

test('카탈로그에 있는 곡은 받지 않고, 없는 곡만 받아 온다', async () => {
  const asked = [];
  const { resolve } = createTrackResolver(async (musicId) => {
    asked.push(musicId);
    return track(musicId);
  });
  const found = await resolve([1, 500, 2, 700], [track(1), track(2)]);

  assert.deepEqual(asked.sort((a, b) => a - b), [500, 700]);
  assert.deepEqual([...found.keys()].sort((a, b) => a - b), [1, 2, 500, 700]);
});

test('지워졌거나 받지 못한 곡만 빠지고 나머지는 남는다', async () => {
  const { resolve } = createTrackResolver(async (musicId) => {
    if (musicId === 404) return null;
    if (musicId === 500) throw new Error('network');
    return track(musicId);
  });
  const found = await resolve([404, 500, 600], []);

  assert.deepEqual([...found.keys()], [600]);
});

test('같은 곡을 나란히 물어도 한 번만 받고, 실패한 곡은 다음에 다시 묻는다', async () => {
  let calls = 0;
  let fail = true;
  const { resolve } = createTrackResolver(async (musicId) => {
    calls += 1;
    if (fail) throw new Error('network');
    return track(musicId);
  });
  await Promise.all([resolve([9], []), resolve([9], [])]);
  assert.equal(calls, 1);

  fail = false;
  const found = await resolve([9], []);
  assert.equal(calls, 2);
  assert.equal(found.get(9)?.id, 1009);
});

test('보관함 화면은 카탈로그가 아니라 knownTracks 에서 곡을 찾는다', async () => {
  const source = await readFile(new URL('../components/mupid/collection.tsx', import.meta.url), 'utf8');
  const library = source.slice(source.indexOf('export function LibraryView'));
  assert.match(library.slice(0, 300), /const tracks = m\.knownTracks;/);
});
