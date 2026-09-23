import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { filterRecentSearches, matchKeyword } from '../lib/recent-search.ts';

const items = [
  { keyword: 'kcik', searched_at: '2026-09-15T14:02:11' },
  { keyword: 'KCIK 라이브', searched_at: '2026-09-15T13:40:02' },
  { keyword: '푸른 오후', searched_at: '2026-09-15T12:10:00' },
];

test('recent search highlights the matching slice, case-insensitively', () => {
  assert.deepEqual(matchKeyword('kcik', 'kc'), {
    before: '',
    match: 'kc',
    after: 'ik',
  });
  // 입력은 소문자, 기록은 대문자여도 찾는다. 색칠은 저장된 글자 그대로.
  assert.deepEqual(matchKeyword('KCIK 라이브', 'kcik'), {
    before: '',
    match: 'KCIK',
    after: ' 라이브',
  });
  assert.deepEqual(matchKeyword('푸른 오후', '오후'), {
    before: '푸른 ',
    match: '오후',
    after: '',
  });
  // 겹치지 않거나 아직 아무것도 치지 않았으면 칠할 자리가 없다.
  assert.equal(matchKeyword('kcik', 'zzz'), null);
  assert.equal(matchKeyword('kcik', '   '), null);
});

test('recent search list shows everything until the user types, then only matches', () => {
  assert.deepEqual(filterRecentSearches(items, ''), items);
  assert.deepEqual(filterRecentSearches(items, '  '), items);
  assert.deepEqual(
    filterRecentSearches(items, 'kc').map((item) => item.keyword),
    ['kcik', 'KCIK 라이브'],
  );
  assert.deepEqual(filterRecentSearches(items, '없는말'), []);
  // 남은 줄은 반드시 칠할 자리가 있다.
  for (const item of filterRecentSearches(items, '오')) {
    assert.notEqual(matchKeyword(item.keyword, '오'), null);
  }
});

test('recent search panel keeps a vertical list with a delete button per row', async () => {
  const view = await readFile(
    new URL('../components/mupid/header-search.tsx', import.meta.url),
    'utf8',
  );
  assert.match(view, /recent-search-list/);
  assert.match(view, /recent-search-remove/);
  assert.match(view, /지우기/);

  const css = await readFile(
    new URL('../app/nav-account.css', import.meta.url),
    'utf8',
  );
  assert.match(css, /\.recent-search-list \{[^}]*flex-direction: column/);
  assert.match(css, /\.recent-search-word mark \{[^}]*color: var\(--primary\)/);
});

test('search is submitted by enter or the button, never by typing', async () => {
  const view = await readFile(
    new URL('../components/mupid/header-search.tsx', import.meta.url),
    'utf8',
  );
  // 입력은 초안에만 담긴다. 치는 동안 앱의 검색어를 바꾸지 않는다.
  assert.match(view, /value=\{draft\}/);
  assert.match(view, /onChange=\{event => \{ setDraft\(event\.target\.value\)/);
  assert.doesNotMatch(view, /onChange=[^}]*m\.submitSearch/);
  // 확정하는 길은 제출 · 최근 검색어 선택 둘뿐이고 둘 다 같은 문으로 들어간다.
  assert.match(view, /onSubmit=\{event => \{ event\.preventDefault\(\); submit\(draft\); \}\}/);
  assert.match(view, /onClick=\{\(\) => submit\(keyword\)\}/);
  assert.match(view, /type="submit"/);
  assert.match(view, /placeholder="곡, 아티스트 검색"/);

  const model = await readFile(
    new URL('../hooks/use-mupid.ts', import.meta.url),
    'utf8',
  );
  // 글자마다 보내던 디바운스는 사라졌다. 제출이 유일한 계기다.
  assert.doesNotMatch(model, /SEARCH_EVENT_DELAY_MS/);
  assert.match(model, /const submitSearch = \(keyword: string\) => \{/);
  assert.match(model, /setSearchRevision\(revision => revision \+ 1\)/);
  assert.match(model, /analytics\.search\(value\);\s*\n\s*void analytics\.flush\(\);/);
});

test('deleting a recent search reaches the server and rolls back when it fails', async () => {
  const hook = await readFile(
    new URL('../hooks/use-recent-searches.ts', import.meta.url),
    'utf8',
  );
  assert.match(hook, /api\.history\.removeRecentSearch\(keyword\)/);
  assert.match(hook, /api\.history\.clearRecentSearches\(\)/);
  // 먼저 지우고 부른다. 실패하면 지우기 직전 목록으로 되돌린다.
  const erase = hook.slice(hook.indexOf('const erase'), hook.indexOf('const remove'));
  assert.match(erase, /const undo = latest\.current;/);
  assert.match(erase, /\.catch\(\(\) => \{\s*latest\.current = undo;/);

  const endpoints = await readFile(
    new URL('../lib/api/endpoints.ts', import.meta.url),
    'utf8',
  );
  assert.match(endpoints, /path: '\/api\/v1\/user\/history\/search',\s*\n\s*query: \{ keyword \}/);
  assert.match(endpoints, /path: '\/api\/v1\/user\/history\/search\/all'/);
});

test('clearing every recent search asks once and is reachable from the panel head', async () => {
  const view = await readFile(
    new URL('../components/mupid/header-search.tsx', import.meta.url),
    'utf8',
  );
  // 목록이 비어 있으면 지울 것이 없으니 버튼도 없다.
  assert.match(view, /\{items\.length > 0 && \(confirming/);
  assert.match(view, /전체 삭제/);
  // 바로 지우지 않는다. 확인을 누른 뒤에야 clear 가 불린다.
  assert.match(view, /setConfirming\(true\)/);
  assert.match(view, /onClick=\{\(\) => \{ setConfirming\(false\); clear\(\); \}\}/);
  // 목록이 닫히면 확인 상태도 풀린다.
  for (const close of [/setOpen\(false\); setConfirming\(false\); \}, \[m\.page\]/, /setOpen\(false\); setConfirming\(false\); \}/]) {
    assert.match(view, close);
  }

  const css = await readFile(
    new URL('../app/nav-account.css', import.meta.url),
    'utf8',
  );
  assert.match(css, /\.recent-search-head \{[^}]*justify-content: space-between/);
  assert.match(css, /\.recent-search-confirm \.is-danger \{[^}]*color: var\(--color-destructive\)/);
});
