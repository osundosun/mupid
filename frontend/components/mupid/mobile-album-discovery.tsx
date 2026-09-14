'use client';
import { useRef, useState } from 'react';
import { Play, Pause } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import type { Track } from '@/lib/mupid-data';
import { AlbumArt } from './primitives';
import { FadeContent } from './fade-content';

export function MobileAlbumDiscovery({ tracks, model: m }: { tracks: Track[]; model: MupidModel }) {
  const [selectedId, setSelectedId] = useState(m.active?.id ?? 0);
  const rail = useRef<HTMLDivElement>(null);
  const selected = tracks.find(t => t.id === selectedId) ?? tracks[0];
  if (!selected) return null;
  const playing = m.active?.id === selected.id && m.playing;
  const select = (track: Track) => {
    setSelectedId(track.id);
    const index = tracks.findIndex(t => t.id === track.id);
    const item = rail.current?.children[index] as HTMLElement | undefined;
    if (item && rail.current) rail.current.scrollTo({ left: item.offsetLeft - (rail.current.children[0] as HTMLElement).offsetLeft,
      behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth' });
  };
  return <section className="mobile-album-discovery" aria-label="앨범으로 음악 탐색">
    <div className="discovery-shelf-heading"><h2>오늘의 발견</h2><span>옆으로 넘겨보세요</span></div>
    <div className="discovery-album-rail" ref={rail} onScroll={() => {
      const node = rail.current;
      if (!node || node.children.length < 2) return;
      const step = (node.children[1] as HTMLElement).offsetLeft - (node.children[0] as HTMLElement).offsetLeft;
      const index = Math.min(tracks.length - 1, Math.max(0, Math.round(node.scrollLeft / step)));
      setSelectedId(tracks[index].id);
    }}>
      {tracks.map(track => <button key={track.id} className="discovery-album-pick" aria-pressed={selected.id === track.id}
        aria-label={`${track.title}, ${track.artist} 선택`} onClick={() => select(track)}>
        <AlbumArt track={track} />
      </button>)}
    </div>
    <div className="discovery-feature-info">
      <FadeContent contentKey={String(selected.id)}><div className="discovery-feature-copy">
        <span>{selected.mood} · {selected.genre}</span><h3>{selected.title}</h3><p>{selected.artist}</p>
      </div></FadeContent>
      <button className="discovery-feature-play" aria-label={`${selected.title} ${playing ? '일시정지' : '재생'}`} onClick={() => m.play(selected)}>
        {playing ? <Pause fill="currentColor" /> : <Play fill="currentColor" />}
      </button>
    </div>
    <div className="discovery-shelf-heading"><h2>이 음악도 들어보세요</h2><span>{tracks.length}곡</span></div>
    <div className="discovery-small-grid">
      {tracks.filter(t => t.id !== selected.id).slice(0, 4).map(track => <button key={track.id}
        className="discovery-small-pick" onClick={() => { select(track); m.play(track); }} aria-label={`${track.title} 재생`}>
        <span className="discovery-small-art"><AlbumArt track={track} /><Play fill="currentColor" /></span>
        <strong>{track.title}</strong><span>{track.artist}</span>
      </button>)}
    </div>
  </section>;
}
