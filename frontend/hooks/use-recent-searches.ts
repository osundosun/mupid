/**
 * 최근 검색어 받아 오기.
 *
 * 헤더 검색창에 커서가 들어오면 그 아래에 펼쳐지는 목록이다. 서버가 같은 말을
 * 한 번만, 최신순으로 내려 주므로 화면은 받은 순서를 그대로 쓴다. 무엇을
 * 보여 주고 어디를 칠할지는 `lib/recent-search.ts` 가 정한다.
 *
 * <h2>왜 열 때마다 다시 받는가</h2>
 *
 * 검색어는 **화면이 직접 남기지 않는다.** 엔터나 검색 버튼으로 제출하면 청취
 * 이벤트 SDK 가 `SEARCH` 를 보내고(`useMupid.submitSearch`), 서버가 그것을
 * 기록으로 남긴다. 즉 목록은 이 화면이 아니라 서버 쪽에서 바뀐다. 검색창을
 * 다시 누를 때마다 받아 오면 방금 찾은 말이 맨 위에 올라와 있다.
 *
 * 폴링은 하지 않는다. 목록이 열려 있는 동안 사용자가 하는 일은 고르는 것뿐이라
 * 스스로 바뀔 이유가 없다.
 *
 * <h2>삭제</h2>
 *
 * 한 줄 지우기와 전체 지우기 모두 **먼저 화면에서 지우고** 서버를 부른다.
 * 지우기는 사용자가 결과를 이미 아는 동작이라, 왕복을 기다리는 동안 지워지지
 * 않은 줄이 남아 있으면 버튼이 안 먹은 것처럼 보인다. 실패하면 되돌리고 그때
 * 알린다.
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api/session';
import type { RecentSearch } from '@/lib/api/endpoints';
import { RECENT_SEARCH_SIZE } from '@/lib/recent-search';

export type RecentSearchState = {
  items: RecentSearch[];
  loading: boolean;
  /** 화면에 보여 줄 오류 문구. 없으면 빈 문자열. */
  error: string;
  /** 한 줄 지우기. 그 말로 남은 기록이 서버에서도 사라진다. */
  remove: (keyword: string) => void;
  /** 전체 지우기. */
  clear: () => void;
};

/**
 * 최근 검색어를 받아 둔다.
 *
 * @param active 목록이 열려 있는 동안만 true. 열릴 때마다 다시 받는다.
 */
export function useRecentSearches(active: boolean): RecentSearchState {
  const [items, setItems] = useState<RecentSearch[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  // 다시 받는 동안 목록을 비우지 않으려고 직전 값을 들고 있는다.
  const latest = useRef<RecentSearch[]>([]);

  useEffect(() => {
    if (!active) return;
    const abort = new AbortController();
    let stopped = false;

    void (async () => {
      setLoading(true);
      try {
        const value = await api.history.recentSearches(
          RECENT_SEARCH_SIZE,
          abort.signal,
        );
        if (stopped) return;
        latest.current = value.keywords ?? [];
        setItems(latest.current);
        setError('');
      } catch {
        if (stopped || abort.signal.aborted) return;
        /*
         * 이미 받아 둔 목록이 있으면 지우지 않는다. 한 번 실패했다고 목록이
         * 비면, 잠깐 끊긴 것과 검색한 적이 없는 것을 구분할 수 없다.
         */
        setItems(latest.current);
        setError(
          latest.current.length ? '' : '최근 검색어를 불러오지 못했어요.',
        );
      } finally {
        if (!stopped) setLoading(false);
      }
    })();

    return () => {
      stopped = true;
      abort.abort();
    };
  }, [active]);

  /**
   * 먼저 지우고 서버를 부른다. 실패하면 있던 자리로 되돌린다.
   *
   * `undo` 는 지우기 직전의 목록이다. 실패했을 때 남은 목록에 다시 끼워 넣는
   * 대신 통째로 되돌리는 쪽이, 그 사이 다른 줄을 지웠더라도 순서가 안 꼬인다.
   */
  const erase = useCallback(
    (next: RecentSearch[], call: () => Promise<void>, failure: string) => {
      const undo = latest.current;
      latest.current = next;
      setItems(next);
      setError('');

      void call().catch(() => {
        latest.current = undo;
        setItems(undo);
        setError(failure);
      });
    },
    [],
  );

  const remove = useCallback(
    (keyword: string) =>
      erase(
        latest.current.filter((item) => item.keyword !== keyword),
        () => api.history.removeRecentSearch(keyword),
        '최근 검색어를 지우지 못했어요.',
      ),
    [erase],
  );

  const clear = useCallback(
    () =>
      erase(
        [],
        () => api.history.clearRecentSearches(),
        '최근 검색어를 지우지 못했어요.',
      ),
    [erase],
  );

  return { items, loading, error, remove, clear };
}
