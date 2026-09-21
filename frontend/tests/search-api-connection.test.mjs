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

  assert.match(header, /m\.submitSearch\(keyword\);\s*m\.go\('search', true\);/);
  assert.match(header, /placeholder="곡, 아티스트, 앨범 검색"/);
  assert.match(model, /setSearchRevision\(revision => revision \+ 1\)/);
  assert.match(explore, /api\.music\.search\(\{ q: query, vocal: appliedVocal, mood: appliedMood, genre: appliedGenre, page: 0, size: PAGE_SIZE \}, controller\.signal\)/);
  assert.doesNotMatch(explore, /q: query \|\| undefined/, '빈 검색어도 q= 로 요청해야 한다');
  assert.match(explore, /page\.items\.map\(toTrack\)/);
  assert.match(explore, /loadedThrough < page\.total_elements \? page\.page \+ 1 : null/);
  assert.doesNotMatch(explore, /page\.page \+ 1 < page\.total_pages/, '다음 페이지는 전체 곡 수와 실제 수신 개수로 판정한다');
});

test('explore reports loading, failure and empty integrated-search states', async () => {
  const explore = await read('components/mupid/explore.tsx');
  assert.match(explore, /취향에 맞는 음악을 찾고 있어요/);
  assert.match(explore, /다음 음악을 불러오고 있어요/);
  assert.match(explore, /검색 결과를 불러오지 못했어요/);
  assert.match(explore, /검색 결과가 없어요/);
  assert.match(explore, /page\.total_elements/);
});

test('explore sends all sidebar filters and keeps them in its request key', async () => {
  const explore = await read('components/mupid/explore.tsx');
  // 필터가 요청 키에 안 들어가면 필터를 바꿔도 화면이 이전 결과를 그대로 쓴다.
  assert.match(explore, /requestKey = `\$\{m\.searchRevision\}:\$\{filterRevision\}:\$\{query\}:\$\{appliedFilterKey\}`/);
  // 검색어나 필터가 없어도 첫 8곡을 불러야 한다.
  assert.match(explore, /page: 0, size: PAGE_SIZE/);
  // 확정된 선택만 서버로 보낸다 — 편집 중인 chips 가 아니다.
  assert.match(explore, /exploreFilterQuery\(appliedKeywords\)/);
  assert.match(explore, /new IntersectionObserver/);
  assert.match(explore, /const PAGE_SIZE = 8/);
  assert.match(explore, /bounds\.top <= window\.innerHeight - bounds\.height/);
  assert.match(explore, /threshold: 1/);
  assert.match(explore, /MINIMUM_MORE_LOADING_MS = 320/);
  assert.match(explore, /key=\{requestKey\} ref=\{trackList\} className="explore-track-list"/);
  assert.match(explore, /className={`explore-filter-sidebar\$\{desktopSidebarStyle \? ' is-desktop-floating' : ''\}/);
  assert.match(explore, /' is-height-constrained'/);
  assert.match(explore, /new ResizeObserver\(sync\)/);
  assert.match(explore, /const headerBottom = header\?\.getBoundingClientRect\(\)\.bottom/);
  assert.match(explore, /const top = Math\.max\(headerBottom \+ 20, bounds\.top\)/);
  // 하단 재생바 위에서 패널이 끝나야 검색 버튼이 가려지지 않는다.
  assert.match(explore, /Math\.min\(window\.innerHeight, playerBounds\.top\)/);
  assert.match(explore, /Math\.min\(visibleBottom, appLayoutBottom\) - top - 20/);
  assert.match(explore, /if \(miniPlayer\) resize\.observe\(miniPlayer\)/);
  assert.match(explore, /if \(appLayout\) resize\.observe\(appLayout\)/);
  assert.match(explore, /document\.addEventListener\('scroll', handleScroll/);
  assert.match(explore, /userRequestedNext && entries\.some\(\(entry\) => entry\.isIntersecting\)\) checkPosition\(\)/);
  assert.match(explore, /event\.deltaY <= 0[\s\S]*userRequestedNext = true;\s*checkPosition\(\);/);
  assert.doesNotMatch(explore, /observer\.observe\(target\);\s*checkPosition\(\)/, '사용자 스크롤 전에 다음 페이지를 자동 조회하면 안 된다');
  assert.match(explore, /page: pageNumber, size: PAGE_SIZE/);
  // 초기화는 편집 중 선택과 적용된 필터를 함께 지우고 즉시 재조회한다.
  assert.match(explore, /onReset=\{\(\) => \{ setAppliedKeywords\(\[\]\); setFilterRevision\(\(value\) => value \+ 1\); \}\}/);
  const categories = await read('components/mupid/explore-categories.tsx');
  assert.match(categories, /setSelected\(\[\]\); onReset\(\);/);
});

