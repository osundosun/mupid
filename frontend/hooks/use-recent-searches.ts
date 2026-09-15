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
 * 항목마다 있는 삭제 버튼은 아직 **화면에서만** 지운다. 서버 API 는 별도
 * 작업이라 여기서 부르지 않는다. 다시 열면 지운 항목이 돌아온다.
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
  /** 서버를 부르지 않고 그 자리에서 한 줄만 지운다. */
  dismiss: (keyword: string) => void;
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

  const dismiss = useCallback((keyword: string) => {
    // 서버에는 닿지 않는다. 삭제 API 는 별도 작업이다.
    latest.current = latest.current.filter((item) => item.keyword !== keyword);
    setItems(latest.current);
  }, []);

  return { items, loading, error, dismiss };
}
