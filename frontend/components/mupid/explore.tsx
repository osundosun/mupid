'use client';
import { useEffect, useRef, useState } from 'react';
import { Music2, Pause, Play, ArrowUpRight, X } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import { filterExploreTracks } from '@/lib/explore';
import { ExploreCategories } from './explore-categories';
import { AlbumArt, PageHeader } from './primitives';


export function SearchView({ model: m }: { model: MupidModel }) {
  const [keywords, setKeywords] = useState<string[]>([]);
  // Editing chips must not replace results until explicitly submitted.
  const [appliedKeywords, setAppliedKeywords] = useState<string[] | null>(null);
  const [searchRevision, setSearchRevision] = useState(0);
  const [pageSize, setPageSize] = useState(5);
  const swipe = useRef<{ id: number; y: number; top: number; moved: boolean } | null>(null);
  const blockClick = useRef(false);
  const albumRail = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const media = window.matchMedia('(max-width: 767px)');
    // Keep every batch to one row: one album on mobile, five on desktop.
    const update = () => setPageSize(media.matches ? 1 : 5);
    update(); media.addEventListener('change', update);
    return () => media.removeEventListener('change', update);
  }, []);
  // Demo keywords are UI-only until the analysis API is connected.
  const results = filterExploreTracks(m.tracks, m.query, '전체', m.ignored);
  const pageCount = Math.ceil(results.length / pageSize);

  const showResults = appliedKeywords !== null || m.query.trim().length > 0;
  return <div className="content-page explore-page keyword-explore">
    <PageHeader title="탐색" onBack={() => m.go('home')} />
    <ExploreCategories selected={keywords} onChange={setKeywords} onSearch={() => { setAppliedKeywords([...keywords]); setSearchRevision(value => value + 1); }} />
    {m.query && <button className="text-link" onClick={() => m.setQuery('')}>“{m.query}” 검색 해제 <X size={16} /></button>}
    {!showResults ? <div className="keyword-idle"><Music2 size={32} aria-hidden="true" /><h2>어떤 소리를 만나볼까요?</h2><p>키워드를 고른 뒤 검색하기를 눌러주세요.</p></div> :
      <section className="keyword-results" aria-label="음악 카드" key={JSON.stringify([searchRevision, m.query])}>
        <header><div><h2>음악 둘러보기</h2></div><span>{results.length}곡</span></header>
        <p className="keyword-preview">예시 음악 · 데모 음원 재사용 · 실제 추천 연결 전</p>
        {results.length ? <><div ref={albumRail} key={pageSize} className="album-horizontal-scroll" tabIndex={0} aria-label="앨범 목록. 좌우로 스크롤해서 넘기세요"
          onDragStart={event => event.preventDefault()}
          onPointerDown={event => { blockClick.current = false; if (event.pointerType !== 'mouse' || event.button !== 0) return; swipe.current = { id: event.pointerId, y: event.clientX, top: event.currentTarget.scrollLeft, moved: false }; }}
          onPointerMove={event => { const start = swipe.current; if (!start || start.id !== event.pointerId) return; const dy = event.clientX - start.y; if (Math.abs(dy) > 6) { start.moved = true; blockClick.current = true; event.currentTarget.style.scrollSnapType = 'none'; event.currentTarget.setPointerCapture(event.pointerId); event.currentTarget.scrollLeft = start.top - dy; } }}
          onPointerUp={event => { swipe.current = null; event.currentTarget.style.scrollSnapType = ''; }}
          onPointerCancel={event => { swipe.current = null; event.currentTarget.style.scrollSnapType = ''; }}
          onLostPointerCapture={event => { swipe.current = null; event.currentTarget.style.scrollSnapType = ''; }}
          onClickCapture={event => { if (blockClick.current) { event.preventDefault(); event.stopPropagation(); blockClick.current = false; } }}>
          {Array.from({ length: pageCount }, (_, batch) => <div key={batch} className="vertical-album-grid album-scroll-batch" aria-label={`${batch + 1}번째 음악 묶음`}>
          {results.slice(batch * pageSize, (batch + 1) * pageSize).map((track, index) => {
          const playing = m.active?.id === track.id && m.playing;
          return <article className="vertical-album" key={track.id} aria-label={`${batch * pageSize + index + 1} / ${results.length}: ${track.title}`}>
            <div className="keyword-cover"><button className="keyword-detail" aria-label={`${track.title} ${playing ? '일시정지' : '재생'}`} onClick={() => m.play(track)}><AlbumArt track={track} /><span className="keyword-play" aria-hidden="true">{playing ? <Pause size={20} fill="currentColor" /> : <Play size={20} fill="currentColor" />}</span></button></div>
            <div className="keyword-card-copy"><h3>{track.title}</h3><p>{track.artist}</p><button aria-label={`${track.title} 곡 상세`} onClick={() => m.openTrack(track)}><ArrowUpRight size={18} /></button></div>
          </article>;
        })}</div>)}</div></> : <p className="keyword-idle">검색 결과가 없어요. 검색어를 바꿔보세요.</p>}
      </section>}
  </div>;
}
