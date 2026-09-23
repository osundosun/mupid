'use client';
import { useEffect, useRef, useState } from 'react';
import { Clock3, Search, X } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { useRecentSearches } from '@/hooks/use-recent-searches';
import { useDismissableOverlay } from '@/hooks/use-dismissable-overlay';
import { filterRecentSearches, matchKeyword } from '@/lib/recent-search';

/**
 * 상단 검색창과 최근 검색어.
 *
 * 커서가 들어오면 검색창 바로 아래에 최근 검색어가 세로로 펼쳐진다. 무언가
 * 치고 있다면 겹치는 것만 남기고, 겹친 자리를 서비스 색으로 칠해서 **왜 이
 * 줄이 남았는지**를 눈으로 보여 준다.
 *
 * 기록은 로그인한 사람 것만 서버에 있다. 비로그인이면 목록을 열지 않는다.
 *
 * 지우기는 한 줄과 전체 둘 다 서버까지 간다. 한 줄을 지우면 그 말로 남은
 * 기록이 전부 사라진다 — 목록의 한 줄이 기록 하나가 아니라 같은 말의 묶음이라
 * 그렇다.
 */
export function HeaderSearch({ model: m }: { model: MupidModel }) {
  const [open, setOpen] = useState(false);
  /*
   * 치고 있는 글자. 검색은 엔터나 검색 버튼으로 **제출할 때만** 확정되므로,
   * 그 전까지는 앱의 검색어(`m.query`)를 건드리지 않고 여기에만 담는다.
   */
  const visibleQuery = m.page === 'search' ? m.query : '';
  const [draft, setDraft] = useState(visibleQuery);
  const [applied, setApplied] = useState(visibleQuery);
  const [draftPage, setDraftPage] = useState(m.page);
  const root = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const signedIn = m.demoSession?.kind === 'authenticated';
  const { items, loading, error, remove, clear } = useRecentSearches(open && signedIn);
  const visible = filterRecentSearches(items, draft);
  // 전체 삭제를 한 번 더 묻는 중인가. 되돌릴 수 없어서 바로 지우지 않는다.
  const [confirming, setConfirming] = useState(false);

  // 바깥에서 검색어가 바뀌면(탐색 화면의 "검색 해제" 등) 입력창도 따라간다.
  // 그리는 중에 맞추는 편이 효과로 미루는 것보다 한 번 덜 그린다.
  if (applied !== visibleQuery || draftPage !== m.page) {
    setApplied(visibleQuery);
    setDraft(visibleQuery);
    setDraftPage(m.page);
  }

  // 치는 동안에는 안내 문구를 띄우지 않는다. 겹치는 게 없으면 그냥 닫힌다 —
  // "없어요" 가 입력할 때마다 깜빡이면 글자보다 그쪽에 눈이 간다.
  const notice = loading || items.length ? '' : '최근 검색어가 없어요.';
  const showNotice = !draft.trim() && !!notice;
  // 지우기가 실패했다면 치는 중이어도 알린다. 목록이 되돌아온 이유다.
  const showPanel = open && signedIn && (visible.length > 0 || showNotice || !!error);

  /*
   * 뒤로가기로 닫히게 한다 (S15P21A204-410). `showPanel` 을 그대로 쓴다 —
   * 목록이 실제로 펼쳐진 동안에만 스택에 오르므로, 비로그인이나 빈 목록에서
   * 뒤로가기가 이 패널에 먹히지 않는다.
   *
   * 전체 삭제를 한 번 더 묻는 중이면 **그 확인부터** 접는다. 그때 패널을
   * 통째로 닫으면 되돌릴 수 없는 삭제를 눈앞에서 감추는 셈이 된다.
   */
  useDismissableOverlay(showPanel && confirming, () => setConfirming(false));
  useDismissableOverlay(showPanel, () => { setOpen(false); setConfirming(false); });

  useEffect(() => {
    if (!open) return;
    const outside = (event: PointerEvent) => {
      if (!root.current?.contains(event.target as Node)) { setOpen(false); setConfirming(false); }
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      setConfirming(false);
      input.current?.focus();
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
  }, [open]);

  // 화면을 옮기면 닫는다. 목록이 남아 다음 화면 위를 덮는 일이 없게.
  useEffect(() => { setOpen(false); setConfirming(false); }, [m.page]);

  /**
   * 검색 확정. 엔터·검색 버튼·최근 검색어 선택, 이 셋만 여기로 온다.
   *
   * 최근 검색어로 남는 시점도 여기다. 고른 말을 다시 제출로 치는 것은 검색
   * 엔진들과 같다 — 방금 쓴 말이 목록 맨 위로 올라온다.
   */
  const submit = (keyword: string) => {
    setOpen(false);
    setConfirming(false);
    setDraft(keyword.trim());
    m.submitSearch(keyword);
    m.go('search', true);
  };

  return <div className="nav-search-area" ref={root}>
    <form className="nav-search" role="search" onSubmit={event => { event.preventDefault(); submit(draft); }}>
      <input
        ref={input}
        aria-label="곡 또는 아티스트 검색"
        placeholder="곡, 아티스트 검색"
        value={draft}
        onFocus={() => setOpen(true)}
        // 커서가 이미 들어와 있는 창을 다시 누른 경우. focus 는 오지 않는다.
        onClick={() => setOpen(true)}
        onChange={event => { setDraft(event.target.value); setOpen(true); }}
        /*
         * 엔터를 직접 받는다. 폼의 암묵적 제출에 맡기면 **한글을 조합하는
         * 엔터까지 제출이 된다** — "새벽"을 완성하려고 누른 엔터로 검색이
         * 나가고 기록까지 남는다. 조합 중인 엔터는 흘려보낸다.
         */
        onKeyDown={event => {
          if (event.key !== 'Enter' || event.nativeEvent.isComposing) return;
          event.preventDefault();
          submit(draft);
        }}
      />
      <button type="submit" aria-label="검색"><Search size={20} /></button>
    </form>
    {showPanel && <div className="recent-search-panel">
      <div className="recent-search-head">
        <p className="recent-search-title">최근 검색어</p>
        {/*
          * 전체 삭제는 한 번 더 묻는다. 목록의 X 는 한 줄이라 잘못 눌러도 손해가
          * 작지만, 이쪽은 되돌릴 수 없다. 서랍 안의 일이라 모달까지 띄우지 않고
          * 같은 자리에서 묻는다.
          */}
        {items.length > 0 && (confirming
          ? <span className="recent-search-confirm">
              <span>모두 지울까요?</span>
              <button type="button" className="is-danger" onClick={() => { setConfirming(false); clear(); }}>지우기</button>
              <button type="button" onClick={() => setConfirming(false)}>취소</button>
            </span>
          : <button type="button" className="recent-search-clear" onClick={() => setConfirming(true)}>전체 삭제</button>)}
      </div>
      {error && <p className="recent-search-error" role="alert">{error}</p>}
      {showNotice ? <p className="recent-search-empty">{notice}</p> :
        <ul className="recent-search-list">
          {visible.map(({ keyword }) => <li key={keyword}>
            <button type="button" className="recent-search-pick" onClick={() => submit(keyword)}>
              <Clock3 size={16} aria-hidden="true" />
              <RecentKeyword keyword={keyword} query={draft} />
            </button>
            <button
              type="button"
              className="recent-search-remove"
              aria-label={`최근 검색어 ${keyword} 지우기`}
              onClick={() => remove(keyword)}
            >
              <X size={16} aria-hidden="true" />
            </button>
          </li>)}
        </ul>}
    </div>}
  </div>;
}

/** 입력과 겹치는 자리만 서비스 색으로 칠한다. */
function RecentKeyword({ keyword, query }: { keyword: string; query: string }) {
  const match = matchKeyword(keyword, query);
  if (!match) return <span className="recent-search-word">{keyword}</span>;

  return <span className="recent-search-word">
    {match.before}<mark>{match.match}</mark>{match.after}
  </span>;
}
