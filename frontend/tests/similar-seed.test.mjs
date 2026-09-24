/*
 * 파생곡 추천의 기준 구간과 앱 시작 시 오늘의 모먼트 (S15P21A204-447).
 *
 * 1. 기준은 「파생곡 찾기」를 누른 순간 한 번 정한다. 화면이 재생 위치로 다시 고르면
 *    구간이 넘어갈 때마다 `/similar` 를 또 묻고 목록이 바뀐다.
 * 2. 앱이 켜질 때 오늘의 모먼트(`/recommendation/daily`)를 묻지 않는다.
 */
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { similarSeedAt } from '../lib/mupid-data.ts';

const read = (path) => readFile(new URL(path, import.meta.url), 'utf8');

const analyzed = {
  id: 7,
  musicId: 70,
  moment: 50,
  duration: 120,
  sections: [
    { label: '벌스 1', start: 0, end: 40, sectionId: 701 },
    { label: '코러스 1', start: 40, end: 80, sectionId: 702 },
    { label: '아웃트로', start: 80, end: 120, sectionId: 703 },
  ],
};

test('누른 순간의 곡·구간이 기준이 된다', () => {
  assert.deepEqual(
    { ...similarSeedAt(analyzed, 10), track: undefined },
    { track: undefined, musicId: 70, sectionId: 701, label: '벌스 1' },
  );
  assert.equal(similarSeedAt(analyzed, 90).sectionId, 703);
});

test('분석 전 곡은 구간 번호 없이 기준이 된다 — 화면은 인기곡으로 물러난다', () => {
  const seed = similarSeedAt({ ...analyzed, sections: [] }, 50);
  assert.equal(seed.sectionId, undefined);
  assert.equal(seed.label, '전체');
});

test('파생곡 화면은 재생 위치가 아니라 저장된 기준으로만 묻는다', async () => {
  const src = await read('../components/mupid/discovery.tsx');
  const view = src.slice(src.indexOf('export function SimilarView'));
  assert.ok(view.length > 0, 'SimilarView 를 찾지 못했다');
  assert.ok(!/\bm\.time\b/.test(view), 'SimilarView 가 재생 위치를 읽는다');
  assert.ok(!/getSongSectionAt\(/.test(view), 'SimilarView 가 구간을 다시 고른다');
  assert.match(view, /m\.similarSeed/);
  // 재질문은 기준의 곡·구간 번호가 바뀔 때뿐이다.
  assert.match(view, /\[musicId, sectionId, loadSimilarSections\]/);
});

test('파생곡 찾기는 진입 액션에서 기준을 저장한다', async () => {
  const menu = await read('../components/mupid/track-menu.tsx');
  assert.match(menu, /m\.openSimilar\(track\)/);
  assert.ok(!/m\.go\('similar'\)/.test(menu), '기준 없이 화면만 연다');
  const hook = await read('../hooks/use-mupid.ts');
  const open = hook.slice(hook.indexOf('const openSimilar'), hook.indexOf("go('similar')"));
  assert.match(open, /setSimilarSeed\(similarSeedAt\(/);
});

test('앱이 켜질 때 오늘의 모먼트를 묻지 않는다', async () => {
  const hook = await read('../hooks/use-mupid.ts');
  assert.ok(!/fetchTodayMoments/.test(hook), 'useMupid 가 오늘의 모먼트를 부른다');
  assert.ok(!/recommendation\.daily|recommendation\/daily/.test(hook));
  // 당신을 위한 추천은 그대로 받는다.
  assert.match(hook, /fetchNextTracks\(\{ limit: 6 \}/);
});
