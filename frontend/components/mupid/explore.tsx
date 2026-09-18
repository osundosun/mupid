'use client';
import { useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronUp, Music2, X } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { filterExploreTracks } from '@/lib/explore';
import { vocalQueryValue } from '@/lib/explore-vocal';
import { ExploreCategories } from './explore-categories';
import { PageHeader, TrackRow } from './primitives';
import { api } from '@/lib/api/session';
import { expandPhraseSearch } from '@/lib/phrase-search';
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
  // 확정된 보컬 필터. 편집 중인 `keywords` 와 분리한다 — 검색하기를 눌러야 반영된다.
  const appliedVocal = appliedKeywords ? vocalQueryValue(appliedKeywords) : undefined;
  useEffect(() => {
    if (filterRevision > 0 || m.query.trim()) {
      resultColumn.current?.scrollIntoView({ block: 'start', behavior: 'instant' });
    }
  }, [filterRevision, m.query]);
  useEffect(() => {
    if (m.query.trim()) setKeywordsOpen(false);
  }, [m.query]);

  /**
   * 서버 검색. **검색어가 없어도 보컬 필터만으로 부른다.**
   *
   * 예전에는 `if (!query) return` 이었다. 그때는 서버에 보낼 조건이 검색어뿐이었기
   * 때문이다. 이제 필터가 있으므로, 검색어가 비어도 필터가 걸려 있으면 서버가
   * 걸러 준 목록을 받아야 한다.
   *
   * 요청 키에 필터를 넣는다. 안 넣으면 필터를 바꿔도 같은 키가 나와, 화면이
   * "이미 받아 둔 결과" 로 여기고 **이전 필터의 곡을 그대로 보여 준다.**
   */
  useEffect(() => {
    const query = m.query.trim();
    // 로컬 데모에는 API 서버가 없다. 실제 서비스에서는 아래 API 경로만 사용한다.
    if ((!query && !appliedVocal) || localDemoEnabled()) return;

    const controller = new AbortController();
    const requestKey = `${m.searchRevision}:${query}:${appliedVocal ?? ''}`;
    void api.music.search({ q: query, vocal: appliedVocal, size: 100 }, controller.signal)
      .then(page => expandPhraseSearch(page, query, appliedVocal, controller.signal, api.music.search))
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
  }, [m.query, m.searchRevision, appliedVocal]);

  // Demo keywords are UI-only until the analysis API is connected.
  const query = m.query.trim();
  const requestKey = `${m.searchRevision}:${query}:${appliedVocal ?? ''}`;
  const localResults = query && localDemoEnabled()
    ? filterExploreTracks(m.tracks, query, '전체', [])
    : null;
  const searching = (!!query || !!appliedVocal) && !localResults && searchResponse.requestKey !== requestKey;
  const searchError = searchResponse.requestKey === requestKey ? searchResponse.error : '';
  const searchTotal = localResults?.length ??
    (searchResponse.requestKey === requestKey ? searchResponse.total : 0);
  /*
   * 서버가 걸러 준 목록을 쓴다.
   *
   * 예전에는 검색어가 없으면 `m.tracks`(카탈로그)를 그대로 썼다. 이제 필터만 걸고
   * 검색어가 없을 때도 서버가 걸러 준 목록이 필요하므로, **서버 응답이 있으면
   * 그것을 먼저** 본다. 서버가 무시하는 조건(분위기·장르 등)만 골랐을 때는 응답이
   * 없으므로 예전처럼 카탈로그로 내려간다.
   */
  const serverResults = searchResponse.requestKey === requestKey ? searchResponse.tracks : null;
  const source = query
    ? localResults ?? serverResults ?? []
    : serverResults ?? m.tracks;
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
