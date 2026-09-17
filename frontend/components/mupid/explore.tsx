'use client';
import { useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronUp, Music2, X } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { filterExploreTracks } from '@/lib/explore';
import { ExploreCategories } from './explore-categories';
import { PageHeader, TrackRow } from './primitives';
import { api } from '@/lib/api/session';
import { localDemoEnabled } from '@/lib/api/local-demo';
import { toTrack } from '@/lib/stream-catalog';
import type { Track } from '@/lib/mupid-data';


export function SearchView({ model: m }: { model: MupidModel }) {
  const [keywords, setKeywords] = useState<string[]>([]);
  // Editing chips must not replace results until explicitly submitted.
  const [appliedKeywords, setAppliedKeywords] = useState<string[] | null>(null);
  const [filterRevision, setFilterRevision] = useState(0);
  const [searchResponse, setSearchResponse] = useState<{
    requestKey: string;
    tracks: Track[];
    total: number;
    error: string;
  }>({ requestKey: '', tracks: [], total: 0, error: '' });
  const [keywordsOpen, setKeywordsOpen] = useState(!m.query.trim());
  const resultColumn = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (filterRevision > 0 || m.query.trim()) {
      resultColumn.current?.scrollIntoView({ block: 'start', behavior: 'instant' });
    }
  }, [filterRevision, m.query]);
  useEffect(() => {
    if (m.query.trim()) setKeywordsOpen(false);
  }, [m.query]);
  useEffect(() => {
    const query = m.query.trim();
    // 로컬 데모에는 API 서버가 없다. 실제 서비스에서는 아래 API 경로만 사용한다.
    if (!query || localDemoEnabled()) return;

    const controller = new AbortController();
    const requestKey = `${m.searchRevision}:${query}`;
    void api.music.search({ q: query, size: 100 }, controller.signal)
      .then(page => {
        if (controller.signal.aborted) return;
        setSearchResponse({
          requestKey,
          tracks: page.items.map(toTrack),
          total: page.total_elements,
          error: '',
        });
      })
      .catch(() => {
        if (!controller.signal.aborted) setSearchResponse({
          requestKey,
          tracks: [],
          total: 0,
          error: '검색 결과를 불러오지 못했어요. 다시 시도해 주세요.',
        });
      });
    return () => controller.abort();
  }, [m.query, m.searchRevision]);

  // Demo keywords are UI-only until the analysis API is connected.
  const query = m.query.trim();
  const requestKey = `${m.searchRevision}:${query}`;
  const localResults = query && localDemoEnabled()
    ? filterExploreTracks(m.tracks, query, '전체', [])
    : null;
  const searching = !!query && !localResults && searchResponse.requestKey !== requestKey;
  const searchError = searchResponse.requestKey === requestKey ? searchResponse.error : '';
  const searchTotal = localResults?.length ??
    (searchResponse.requestKey === requestKey ? searchResponse.total : 0);
  const source = query
    ? localResults ?? (searchResponse.requestKey === requestKey ? searchResponse.tracks : [])
    : m.tracks;
  const results = filterExploreTracks(source, '', '전체', m.ignored);


  const showResults = appliedKeywords !== null || m.query.trim().length > 0;
  return <div className="content-page explore-page keyword-explore">
    <PageHeader title="탐색" onBack={() => m.go('home')} />
    <div className="explore-search-layout"><aside className="explore-filter-sidebar"><button className="explore-keyword-toggle" aria-expanded={keywordsOpen} aria-controls="explore-keyword-panel" onClick={() => setKeywordsOpen(value => !value)}>
      <span>{keywordsOpen ? '검색 조건 접기' : '검색 조건 변경'}{keywords.length > 0 ? ` · ${keywords.length}개 선택` : ''}</span>
      {keywordsOpen ? <ChevronUp size={20} /> : <ChevronDown size={20} />}
    </button>
    <div id="explore-keyword-panel" data-collapsed={!keywordsOpen}>
      <ExploreCategories selected={keywords} onChange={setKeywords} onSearch={() => { setAppliedKeywords([...keywords]); setFilterRevision(value => value + 1); setKeywordsOpen(false); }} />
    </div>
    </aside><div ref={resultColumn} className="explore-result-column">{m.query && <button className="text-link" onClick={() => m.setQuery('')}>“{m.query}” 검색 해제 <X size={16} /></button>}
    {!showResults ? <div className="keyword-idle"><Music2 size={32} aria-hidden="true" /><h2>어떤 소리를 만나볼까요?</h2><p>키워드를 고른 뒤 검색하기를 눌러주세요.</p></div> :
      <section className="keyword-results" aria-label="음악 목록" key={JSON.stringify([filterRevision, m.query])}>
        <header><div><h2>{m.query.trim() ? `“${m.query}” 검색 결과` : '음악 둘러보기'}</h2></div><span>{m.query.trim() ? searchTotal : results.length}곡</span></header>
        {searching ? <output className="keyword-idle">곡, 아티스트, 앨범을 검색하고 있어요.</output> :
        searchError ? <p className="keyword-idle" role="alert">{searchError}</p> :
        results.length ? <div className="explore-track-list">
          {results.map(track => (
            <TrackRow key={track.id} track={track} model={m} queueable showDetail={false} />
          ))}
        </div> : <p className="keyword-idle">검색 결과가 없어요. 검색어를 바꿔보세요.</p>}
      </section>}
    </div></div>
  </div>;
}
