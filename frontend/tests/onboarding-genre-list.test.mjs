import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';

const read = (path) => readFile(new URL('../' + path, import.meta.url), 'utf8');
const [screen, css] = await Promise.all([
  read('components/mupid/collection.tsx'),
  read('app/mupid.css'),
]);

/*
 * 장르 코드가 8개에서 45개가 된다. 음악 메타데이터의 코드를 전부 등록하기로 했고,
 * 화면은 그 목록을 그대로 받는다(`GET /api/v1/onboarding/options`).
 *
 * 45개를 한 화면에 펼치면 '다음' 버튼이 화면 밖으로 밀린다. 고장이 아니라서 오류도
 * 안 나고, 처음 들어온 사람이 고르다 말고 스크롤을 한참 내려야 한다.
 */

void test('genre list is scrollable on its own so the submit button stays reachable', () => {
  assert.match(screen, /filter-chips wrap onboarding-scroll/,
    '장르 목록에 스크롤 영역이 없다');
  assert.match(css, /\.onboarding-scroll\s*\{[^}]*max-height/,
    '스크롤 영역에 높이 제한이 없으면 목록이 버튼을 밀어낸다');
  assert.match(css, /\.onboarding-scroll\s*\{[^}]*overflow-y:\s*auto/);
});

void test('search narrows the list but never hides what was already picked', () => {
  // 고른 것은 별도 영역에 남긴다. 검색어를 바꿨다고 사라지면 무엇을 골랐는지
  // 확인할 방법이 없다.
  assert.match(screen, /onboarding-picked/,
    '고른 항목을 고정해 두는 영역이 없다');
  assert.match(screen, /visibleGenres/, '검색으로 좁히는 목록이 없다');

  // 걸러내기는 화면에 뿌리는 목록에만 건다. 선택 상태를 건드리면 안 된다.
  const filter = screen.match(/const visibleGenres[\s\S]{0,220}/)?.[0] ?? '';
  assert.match(filter, /options\.genres\.filter/);
  assert.doesNotMatch(filter, /setSelectedGenres/,
    '걸러내기가 선택 상태를 바꾸면 검색만으로 고른 것이 사라진다');
});

void test('search appears only when the list is long enough to need it', () => {
  // 8개뿐이던 때와 화면이 달라지지 않아야 한다. 짧은 목록에 검색창을 두면
  // 고를 것이 몇 개 없는데 입력부터 보인다.
  assert.match(screen, /options\.genres\.length > \d+ && \(/,
    '목록 길이와 무관하게 검색창이 항상 뜬다');
});

void test('an empty search result says so instead of showing a blank area', () => {
  // 빈 화면만 남으면 고장으로 보인다.
  assert.match(screen, /visibleGenres\.length === 0/);
  assert.match(screen, /onboarding-empty/);
});

void test('going back clears the query so the list is not silently narrowed', () => {
  // 검색어가 남은 채 되돌아오면 목록이 좁혀진 상태로 보이고, 고를 것이 없어진
  // 것처럼 읽힌다.
  const back = screen.match(/aria-label=\{step > 0[\s\S]{0,320}/)?.[0] ?? '';
  assert.match(back, /setGenreQuery\(''\)/);
});

void test('the mood step stays a plain list', () => {
  // 분위기는 8~9개다. 검색을 붙이면 두 단계의 조작이 달라져 헷갈린다.
  const moods = screen.match(/step === 1 && <fieldset[\s\S]{0,1400}/)?.[0] ?? '';
  assert.doesNotMatch(moods, /onboarding-search/);
  assert.doesNotMatch(moods, /onboarding-scroll/);
});
