import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

/*
 * 곡 목록(카탈로그)을 미리 받아 두지 않는다 (S15P21A204-446).
 *
 * 앱을 열 때 받던 앞 100곡이 곡 번호 → 곡 사전이었고, 그 밖의 곡이 좋아요·큐·보관함에서
 * 사라지는 버그가 되풀이됐다(441·443). 곡 찾기는 `knownTracks` + 일괄 조회로, 추천이
 * 없을 때의 대체 목록은 `popularTracks` 로 떼었다. 둘이 다시 한 벌로 뭉치지 않는지 본다.
 *
 * 소스를 읽어 확인하는 이유는 모델(`hooks/use-mupid.ts`)이 브라우저 없이는 돌지 않기 때문이다.
 */
const read = (path) => readFile(new URL(`../${path}`, import.meta.url), 'utf8');

test('앱을 열 때 곡 목록을 받지 않는다', async () => {
  const [app, catalog] = await Promise.all([read('components/mupid-app.tsx'), read('lib/stream-catalog.ts')]);
  assert.doesNotMatch(app, /fetchCatalog|fetchPopularTracks/);
  assert.doesNotMatch(catalog, /export async function fetchCatalog|CATALOG_SIZE/);
});

test('모델은 카탈로그를 내보내지 않고, 곡 찾기와 대체 목록을 따로 둔다', async () => {
  const model = await read('hooks/use-mupid.ts');
  assert.doesNotMatch(model, /^\s{4}tracks,$/m, '`m.tracks` 가 되살아나면 화면이 다시 카탈로그로 곡을 찾는다');
  assert.match(model, /^\s{4}knownTracks,$/m);
  assert.match(model, /^\s{4}popularTracks,$/m);
  // 처음 큐는 비어 있고, 한 번도 저장하지 않은 계정만 인기곡으로 채운다.
  assert.match(model, /const \[queue, setQueue\] = useState<QueueEntry\[\]>\(\[\]\);/);
  assert.match(model, /if \(!saved\.ids\.length && saved\.revision === 0\) \{\s*const popular = await loadPopularTracks\(\);/);
});

test('화면은 m.tracks 를 쓰지 않는다', async () => {
  const files = [
    'components/mupid/home.tsx', 'components/mupid/desktop-home.tsx', 'components/mupid/discovery.tsx',
    'components/mupid/explore.tsx', 'components/mupid/today-moment.tsx', 'components/mupid/artist-info.tsx',
    'components/mupid/daily-album-treemap.tsx', 'components/mupid/collection.tsx',
  ];
  for (const file of files) assert.doesNotMatch(await read(file), /\bm\.tracks\b/, file);
});

test('인기곡으로 곡을 찾거나 저장된 값을 거르지 않는다', async () => {
  const [model, reactions, playlists] = await Promise.all([
    read('hooks/use-mupid.ts'), read('lib/reactions.ts'), read('lib/playlists.ts'),
  ]);
  assert.doesNotMatch(model, /popularTracks\.find\(/);
  assert.doesNotMatch(reactions + playlists, /popular/i);
});
