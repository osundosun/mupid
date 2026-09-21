'use client';
import { useEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { ChevronDown, ChevronUp, LoaderCircle, X } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { exploreFilterQuery } from '@/lib/explore-filter';
import { ExploreCategories } from './explore-categories';
import { PageHeader, TrackRow } from './primitives';
import { api } from '@/lib/api/session';
import { expandPhraseSearch } from '@/lib/phrase-search';
import { localDemoEnabled } from '@/lib/api/local-demo';
import { toTrack } from '@/lib/stream-catalog';
import type { Track } from '@/lib/mupid-data';
import type { ExploreFilterOptions, MusicPage } from '@/lib/api/endpoints';

const PAGE_SIZE = 8;
const MINIMUM_MORE_LOADING_MS = 320;
const EMPTY_FILTER_OPTIONS: ExploreFilterOptions = { moods: [], genres: [], vocals: [] };

/** 서버가 알려 준 전체 개수와 이번 응답의 실제 개수로 다음 페이지를 판정한다. */
function nextPageOf(page: MusicPage) {
  const loadedThrough = page.page * page.size + page.items.length;
  return page.items.length > 0 && loadedThrough < page.total_elements ? page.page + 1 : null;
}

type ListingState = {
  key: string;
  tracks: Track[];
  total: number;
  nextPage: number | null;
  loading: boolean;
  error: string;
};

const EMPTY_LISTING: ListingState = {
  key: '', tracks: [], total: 0, nextPage: null, loading: true, error: '',
};

export function SearchView({ model: m }: { model: MupidModel }) {
  const [keywords, setKeywords] = useState<string[]>([]);
  const [appliedKeywords, setAppliedKeywords] = useState<string[]>([]);
  const [filterRevision, setFilterRevision] = useState(0);
  const [filterOptions, setFilterOptions] = useState<ExploreFilterOptions>(EMPTY_FILTER_OPTIONS);
  const [filterOptionState, setFilterOptionState] = useState({ loading: true, error: '' });
  const [listing, setListing] = useState<ListingState>(EMPTY_LISTING);
  const [keywordsOpen, setKeywordsOpen] = useState(!m.query.trim());
  const [desktopSidebarStyle, setDesktopSidebarStyle] = useState<CSSProperties>();
  const filterSlot = useRef<HTMLDivElement>(null);
  const resultColumn = useRef<HTMLDivElement>(null);
  const trackList = useRef<HTMLDivElement>(null);

  const query = m.query.trim();
  const appliedFilters = exploreFilterQuery(appliedKeywords);
  const appliedVocal = appliedFilters.vocal;
  const appliedMood = appliedFilters.mood;
  const appliedGenre = appliedFilters.genre;
  const appliedFilterKey = `${appliedVocal ?? ''}:${appliedMood ?? ''}:${appliedGenre ?? ''}`;
  const requestKey = `${m.searchRevision}:${filterRevision}:${query}:${appliedFilterKey}`;

  useEffect(() => {
    const controller = new AbortController();
    void api.music.filterOptions(controller.signal)
      .then((value) => {
        if (!controller.signal.aborted) {
          setFilterOptions(value);
          setFilterOptionState({ loading: false, error: '' });
        }
      })
      .catch(() => {
        if (!controller.signal.aborted) setFilterOptionState({ loading: false, error: '선택지를 불러오지 못했어요.' });
      });
    return () => controller.abort();
  }, []);

  /** 검색어와 확정된 필터가 바뀌면 항상 첫 페이지부터 다시 조회한다. */
  useEffect(() => {
    if (localDemoEnabled()) {
      const lowered = query.toLowerCase();
      const tracks = m.tracks.filter((track) => !lowered || [track.title, track.artist, track.album].some((value) => value.toLowerCase().includes(lowered)));
      queueMicrotask(() => setListing({ key: requestKey, tracks: tracks.slice(0, PAGE_SIZE), total: tracks.length, nextPage: tracks.length > PAGE_SIZE ? 1 : null, loading: false, error: '' }));
      return;
    }

    const controller = new AbortController();
    void api.music.search({ q: query, vocal: appliedVocal, mood: appliedMood, genre: appliedGenre, page: 0, size: PAGE_SIZE }, controller.signal)
      .then(page => expandPhraseSearch(page, query, { vocal: appliedVocal, mood: appliedMood, genre: appliedGenre }, controller.signal, api.music.search))
      .then((page) => {
        if (!controller.signal.aborted) setListing({
          key: requestKey,
          tracks: page.items.map(toTrack),
          total: page.total_elements,
          nextPage: nextPageOf(page),
          loading: false,
          error: '',
        });
      })
      .catch(() => {
        if (!controller.signal.aborted) setListing({ key: requestKey, tracks: [], total: 0, nextPage: null, loading: false, error: '검색 결과를 불러오지 못했어요. 다시 시도해 주세요.' });
      });
    return () => controller.abort();
  }, [requestKey, query, appliedVocal, appliedMood, appliedGenre, m.tracks]);

  /** 현재 마지막 곡이 한 행 높이만큼 뷰포트 안으로 들어오면 다음 8곡을 이어 붙인다. */
  useEffect(() => {
    const target = trackList.current?.lastElementChild;
    const nextPage = listing.nextPage;
    if (!(target instanceof HTMLElement) || listing.key !== requestKey || nextPage === null || listing.loading) return;
    let requested = false;
    let userRequestedNext = false;
    const loadNextPage = () => {
      if (requested) return;
      requested = true;
      observer.disconnect();
      document.removeEventListener('scroll', handleScroll, true);
      const pageNumber = nextPage;
      setListing((current) => ({ ...current, loading: true }));
      const minimumLoadingTime = new Promise<void>((resolve) => {
        window.setTimeout(resolve, MINIMUM_MORE_LOADING_MS);
      });
      if (localDemoEnabled()) {
        const lowered = query.toLowerCase();
        const tracks = m.tracks.filter((track) => !lowered || [track.title, track.artist, track.album].some((value) => value.toLowerCase().includes(lowered)));
        const end = (pageNumber + 1) * PAGE_SIZE;
        void minimumLoadingTime.then(() => setListing((current) => ({ ...current, tracks: tracks.slice(0, end), total: tracks.length, nextPage: end < tracks.length ? pageNumber + 1 : null, loading: false })));
        return;
      }
      void Promise.all([
        api.music.search({ q: query, vocal: appliedVocal, mood: appliedMood, genre: appliedGenre, page: pageNumber, size: PAGE_SIZE }),
        minimumLoadingTime,
      ])
        .then(([page]) => {
          setListing((current) => current.key !== requestKey ? current : {
            ...current,
            tracks: [...current.tracks, ...page.items.map(toTrack).filter((track) => !current.tracks.some((saved) => saved.id === track.id))],
            total: page.total_elements,
            nextPage: nextPageOf(page),
            loading: false,
            error: '',
          });
        })
        .catch(() => {
          void minimumLoadingTime.then(() => setListing((current) => current.key !== requestKey ? current : ({ ...current, nextPage: null, loading: false, error: '다음 곡을 불러오지 못했어요.' })));
        });
    };
    const checkPosition = () => {
      const bounds = target.getBoundingClientRect();
      if (bounds.top <= window.innerHeight - bounds.height) loadNextPage();
    };
    const handleScroll = () => {
      if (userRequestedNext) checkPosition();
    };
    const handleWheel = (event: WheelEvent) => {
      if (event.deltaY <= 0 || (event.target instanceof Element && event.target.closest('.explore-filter-sidebar'))) return;
      userRequestedNext = true;
      checkPosition();
    };
    const handleTouchMove = (event: TouchEvent) => {
      if (event.target instanceof Element && event.target.closest('.explore-filter-sidebar')) return;
      userRequestedNext = true;
      checkPosition();
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (!['ArrowDown', 'PageDown', 'End', ' '].includes(event.key) || (event.target instanceof Element && event.target.closest('.explore-filter-sidebar'))) return;
      userRequestedNext = true;
      checkPosition();
    };
    const observer = new IntersectionObserver((entries) => {
      if (userRequestedNext && entries.some((entry) => entry.isIntersecting)) checkPosition();
    }, { threshold: 1 });
    observer.observe(target);
    document.addEventListener('scroll', handleScroll, { passive: true, capture: true });
    document.addEventListener('wheel', handleWheel, { passive: true });
    document.addEventListener('touchmove', handleTouchMove, { passive: true });
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      observer.disconnect();
      document.removeEventListener('scroll', handleScroll, true);
      document.removeEventListener('wheel', handleWheel);
      document.removeEventListener('touchmove', handleTouchMove);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [listing.key, listing.nextPage, listing.loading, requestKey, query, appliedVocal, appliedMood, appliedGenre, m.tracks]);

  useEffect(() => {
    if (filterRevision > 0) resultColumn.current?.scrollIntoView({ block: 'start', behavior: 'smooth' });
  }, [filterRevision]);

  useEffect(() => {
    const slot = filterSlot.current;
    if (!slot) return;
    const desktop = window.matchMedia('(min-width: 1024px)');
    const appLayout = slot.closest('.app-layout');
    const miniPlayer = slot.closest('.mupid-shell')?.querySelector('.mini-player');
    let frame = 0;
    const sync = () => {
      window.cancelAnimationFrame(frame);
      frame = window.requestAnimationFrame(() => {
        if (!desktop.matches) {
          setDesktopSidebarStyle(undefined);
          return;
        }
        const bounds = slot.getBoundingClientRect();
        const appLayoutBottom = appLayout?.getBoundingClientRect().bottom ?? window.innerHeight;
        const top = Math.max(110, bounds.top);
        const playerBounds = miniPlayer?.getBoundingClientRect();
        const visibleBottom = playerBounds && playerBounds.height > 0
          ? Math.min(window.innerHeight, playerBounds.top)
          : window.innerHeight;
        const maxHeight = Math.max(0, Math.min(visibleBottom, appLayoutBottom) - top - 20);
        setDesktopSidebarStyle((current) =>
          current?.left === bounds.left && current.width === bounds.width && current.top === top && current.maxHeight === maxHeight
            ? current
            : { left: bounds.left, top, width: bounds.width, maxHeight });
      });
    };
    const resize = new ResizeObserver(sync);
    resize.observe(slot);
    if (appLayout) resize.observe(appLayout);
    if (miniPlayer) resize.observe(miniPlayer);
    desktop.addEventListener('change', sync);
    window.addEventListener('resize', sync);
    window.addEventListener('scroll', sync, { passive: true });
    sync();
    return () => {
      window.cancelAnimationFrame(frame);
      resize.disconnect();
      desktop.removeEventListener('change', sync);
      window.removeEventListener('resize', sync);
      window.removeEventListener('scroll', sync);
    };
  }, []);

  const results = listing.key === requestKey ? listing.tracks.filter((track) => !m.ignored.includes(track.id)) : [];
  const initialLoading = listing.key !== requestKey || (listing.loading && listing.tracks.length === 0);

  return <div className="content-page explore-page keyword-explore">
    <PageHeader title="탐색" onBack={() => m.go('home')} />
    <div className="explore-search-layout">
      <div ref={filterSlot} className="explore-filter-slot">
      <aside className={`explore-filter-sidebar${desktopSidebarStyle ? ' is-desktop-floating' : ''}`} style={desktopSidebarStyle}>
        <button className="explore-keyword-toggle" aria-expanded={keywordsOpen} aria-controls="explore-keyword-panel" onClick={() => setKeywordsOpen((value) => !value)}>
          <span>{keywordsOpen ? '검색 조건 접기' : '검색 조건 변경'}{keywords.length > 0 ? ` · ${keywords.length}개 선택` : ''}</span>
          {keywordsOpen ? <ChevronUp size={20} /> : <ChevronDown size={20} />}
        </button>
        <div id="explore-keyword-panel" data-collapsed={!keywordsOpen}>
          <ExploreCategories
            options={filterOptions}
            loading={filterOptionState.loading}
            error={filterOptionState.error}
            selected={keywords}
            onChange={setKeywords}
            onSearch={() => { setAppliedKeywords([...keywords]); setFilterRevision((value) => value + 1); setKeywordsOpen(false); }}
            onReset={() => { setAppliedKeywords([]); setFilterRevision((value) => value + 1); }}
          />
        </div>
      </aside>
      </div>
      <div ref={resultColumn} className="explore-result-column">
        {m.query && <button className="text-link" onClick={() => m.setQuery('')}>“{m.query}” 검색 해제 <X size={16} /></button>}
        <section className="keyword-results" aria-label="음악 목록">
          <header><div><h2>{query ? `“${query}” 검색 결과` : '음악 둘러보기'}</h2></div><span>{listing.total}곡</span></header>
          {(initialLoading || listing.loading) && <div className="explore-loading-anchor">
            <output className="explore-loading-overlay" aria-label="음악을 불러오는 중">
              <span className="explore-loading-orbit"><LoaderCircle size={24} /></span>
              <span>{initialLoading ? '취향에 맞는 음악을 찾고 있어요' : '다음 음악을 불러오고 있어요'}</span>
            </output>
          </div>}
          {listing.error && results.length === 0 ? <p className="keyword-idle" role="alert">{listing.error}</p> :
            results.length ? <div key={requestKey} ref={trackList} className="explore-track-list">{results.map((track) => <TrackRow key={track.id} track={track} model={m} queueable showDetail={false} titleInteractive={false} />)}</div> :
            !initialLoading && <p className="keyword-idle">검색 결과가 없어요. 조건을 바꿔보세요.</p>}
          <div className="explore-load-more-target" aria-hidden="true" />
          {listing.error && results.length > 0 && <p className="explore-load-error" role="alert">{listing.error}</p>}
        </section>
      </div>
    </div>
  </div>;
}
