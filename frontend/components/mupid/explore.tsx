'use client';
import { useEffect, useRef, useState } from 'react';
import { ChevronDown, ChevronUp, Music2, X } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { filterExploreTracks } from '@/lib/explore';
import { ExploreCategories } from './explore-categories';
import { PageHeader, TrackRow } from './primitives';


export function SearchView({ model: m }: { model: MupidModel }) {
  const [keywords, setKeywords] = useState<string[]>([]);
  // Editing chips must not replace results until explicitly submitted.
  const [appliedKeywords, setAppliedKeywords] = useState<string[] | null>(null);
  const [searchRevision, setSearchRevision] = useState(0);
  const [keywordsOpen, setKeywordsOpen] = useState(!m.query.trim());
  const resultColumn = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (searchRevision > 0 || m.query.trim()) {
      resultColumn.current?.scrollIntoView({ block: 'start', behavior: 'instant' });
    }
  }, [searchRevision, m.query]);
  useEffect(() => {
    if (m.query.trim()) setKeywordsOpen(false);
  }, [m.query]);
  // Demo keywords are UI-only until the analysis API is connected.
  const results = filterExploreTracks(m.tracks, m.query, '전체', m.ignored);


  const showResults = appliedKeywords !== null || m.query.trim().length > 0;
  return <div className="content-page explore-page keyword-explore">
    <PageHeader title="탐색" onBack={() => m.go('home')} />
    <div className="explore-search-layout"><aside className="explore-filter-sidebar"><button className="explore-keyword-toggle" aria-expanded={keywordsOpen} aria-controls="explore-keyword-panel" onClick={() => setKeywordsOpen(value => !value)}>
      <span>{keywordsOpen ? '검색 조건 접기' : '검색 조건 변경'}{keywords.length > 0 ? ` · ${keywords.length}개 선택` : ''}</span>
      {keywordsOpen ? <ChevronUp size={20} /> : <ChevronDown size={20} />}
    </button>
    <div id="explore-keyword-panel" data-collapsed={!keywordsOpen}>
      <ExploreCategories selected={keywords} onChange={setKeywords} onSearch={() => { setAppliedKeywords([...keywords]); setSearchRevision(value => value + 1); setKeywordsOpen(false); }} />
    </div>
    </aside><div ref={resultColumn} className="explore-result-column">{m.query && <button className="text-link" onClick={() => m.setQuery('')}>“{m.query}” 검색 해제 <X size={16} /></button>}
    {!showResults ? <div className="keyword-idle"><Music2 size={32} aria-hidden="true" /><h2>어떤 소리를 만나볼까요?</h2><p>키워드를 고른 뒤 검색하기를 눌러주세요.</p></div> :
      <section className="keyword-results" aria-label="음악 목록" key={JSON.stringify([searchRevision, m.query])}>
        <header><div><h2>음악 둘러보기</h2></div><span>{results.length}곡</span></header>
        <p className="keyword-preview">예시 음악 · 데모 음원 재사용 · 실제 추천 연결 전</p>
        {results.length ? <div className="explore-track-list">
          {results.map(track => (
            <TrackRow key={track.id} track={track} model={m} queueable showDetail={false} />
          ))}
        </div> : <p className="keyword-idle">검색 결과가 없어요. 검색어를 바꿔보세요.</p>}
      </section>}
    </div></div>
  </div>;
}
