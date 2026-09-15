/**
 * 최근 검색어 목록을 다루는 규칙.
 *
 * 받아 오는 쪽은 `hooks/use-recent-searches.ts` 에 있다. 여기에는 화면이 무엇을
 * 보여 주고 어디를 칠할지만 둔다 — 그래야 브라우저 없이 그대로 시험할 수 있다.
 */

import type { RecentSearch } from './api/endpoints';

/** 검색창 아래에 한 화면으로 들어오는 만큼. 서버 기본값과 같다. */
export const RECENT_SEARCH_SIZE = 10;

/** 입력과 겹치는 자리. 겹치지 않으면 `null`. */
export type KeywordMatch = {
  before: string;
  match: string;
  after: string;
};

/**
 * 검색어에서 입력과 겹치는 자리를 찾는다.
 *
 * 대소문자는 무시한다 — "kcik" 을 치는 사람이 "KCIK" 을 찾던 것을 못 보면
 * 목록이 있으나 마나다. 첫 번째로 겹치는 자리 하나만 본다. 한 줄에 색이 여러
 * 번 들어가면 어디가 맞은 것인지 오히려 읽기 어렵다.
 *
 * 자른 결과는 **원본 문자열의 조각**이다. 소문자로 바꾼 사본이 아니라 화면에는
 * 저장된 그대로 나온다.
 */
export function matchKeyword(
  keyword: string,
  input: string,
): KeywordMatch | null {
  const needle = input.trim();
  if (!needle) return null;

  const at = keyword.toLocaleLowerCase().indexOf(needle.toLocaleLowerCase());
  if (at < 0) return null;

  return {
    before: keyword.slice(0, at),
    match: keyword.slice(at, at + needle.length),
    after: keyword.slice(at + needle.length),
  };
}

/**
 * 입력에 맞는 항목만 남긴다.
 *
 * 입력이 비어 있으면 최근 검색어를 그대로 보여 준다. 무언가 치고 있다면 겹치는
 * 것만 남긴다 — 겹치지 않는 줄은 색칠할 자리가 없어서, 남겨 두면 목록이 왜
 * 이렇게 생겼는지 설명이 안 된다.
 */
export function filterRecentSearches(
  items: RecentSearch[],
  input: string,
): RecentSearch[] {
  if (!input.trim()) return items;
  return items.filter((item) => matchKeyword(item.keyword, input) !== null);
}
