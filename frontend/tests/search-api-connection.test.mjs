import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');

test('submitted header search moves to explore and calls the integrated music API', async () => {
  const [header, model, explore] = await Promise.all([
    read('components/mupid/header-search.tsx'),
    read('hooks/use-mupid.ts'),
    read('components/mupid/explore.tsx'),
  ]);

  assert.match(header, /m\.submitSearch\(keyword\);\s*m\.go\('search'\);/);
  assert.match(header, /placeholder="곡, 아티스트, 앨범 검색"/);
  assert.match(model, /setSearchRevision\(revision => revision \+ 1\)/);
  assert.match(explore, /api\.music\.search\(\{ q: query, vocal: appliedVocal, size: 100 \}, controller\.signal\)/);
  assert.match(explore, /page\.items\.map\(toTrack\)/);
  assert.match(explore, /\[m\.query, m\.searchRevision, appliedVocal\]/);
});

test('explore reports loading, failure and empty integrated-search states', async () => {
  const explore = await read('components/mupid/explore.tsx');
  assert.match(explore, /곡, 아티스트, 앨범을 검색하고 있어요/);
  assert.match(explore, /검색 결과를 불러오지 못했어요/);
  assert.match(explore, /검색 결과가 없어요/);
  assert.match(explore, /page\.total_elements/);
});

test('explore sends the vocal filter and keeps its own request key', async () => {
  const explore = await read('components/mupid/explore.tsx');
  // 필터가 요청 키에 안 들어가면 필터를 바꿔도 화면이 이전 결과를 그대로 쓴다.
  assert.match(explore, /requestKey = `\$\{m\.searchRevision\}:\$\{query\}:\$\{appliedVocal \?\? ''\}`/);
  // 검색어가 없어도 필터만으로 서버를 불러야 한다.
  assert.match(explore, /if \(\(!query && !appliedVocal\) \|\| localDemoEnabled\(\)\) return;/);
  // 확정된 선택만 서버로 보낸다 — 편집 중인 chips 가 아니다.
  assert.match(explore, /const appliedVocal = appliedKeywords \? vocalQueryValue\(appliedKeywords\) : undefined;/);
});

