'use client';
import { useEffect, useRef, useState } from 'react';
import type { Track } from '@/lib/mupid-data';

export function DailyAlbumTreemap({ tracks }: { tracks: Track[] }) {
  // Demo-only values; equal tiles intentionally do not encode time by area.
  const items = tracks.slice(0, 30).map((track, index) => ({ track, seconds: 180 + (index % 5) * 8 }));
  const total = items.reduce((sum, item) => sum + item.seconds, 0);
  const [selectedId, setSelectedId] = useState<number | null>(null);
  const [position, setPosition] = useState({ x: 0, y: 0 });
  const map = useRef<HTMLDivElement>(null);
  const trigger = useRef<HTMLButtonElement | null>(null);
  const selected = items.find(item => item.track.id === selectedId);
  useEffect(() => {
    if (selectedId === null) return;
    const outside = (event: PointerEvent) => { if (!map.current?.contains(event.target as Node)) setSelectedId(null); };
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') { setSelectedId(null); trigger.current?.focus(); } };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    return () => { document.removeEventListener('pointerdown', outside); document.removeEventListener('keydown', escape); };
  }, [selectedId]);
  return <section className="report-card daily-album-report">
    <h2>오늘 들은 음악</h2><p>일간 리포트 · 예시 데이터</p>
    <div className="daily-album-map uniform-album-map" ref={map} aria-label="곡별 청취 비율">
      {items.map(item => <button key={item.track.id} className="daily-album-cell" aria-expanded={selectedId === item.track.id} aria-controls={selectedId === item.track.id ? 'daily-album-popup' : undefined} aria-label={`${item.track.title}, 청취 정보 보기`} onClick={event => {
        trigger.current = event.currentTarget;
        const area = map.current!.getBoundingClientRect();
        const rect = event.currentTarget.getBoundingClientRect();
        setPosition({ x: Math.max(0, Math.min(rect.left - area.left, area.width - Math.min(240, area.width))), y: Math.max(0, Math.min(rect.bottom - area.top + 6, area.height - 145)) });
        setSelectedId(value => value === item.track.id ? null : item.track.id);
      }}>
        <img src={item.track.image} alt="" /><span>{item.track.title}</span>
      </button>)}
      {selected && <section id="daily-album-popup" className="daily-album-popup" aria-label="선택한 곡 청취 정보" style={{ left: position.x, top: position.y }}>
        <button className="daily-popup-close" aria-label="청취 정보 닫기" onClick={() => { setSelectedId(null); trigger.current?.focus(); }}>×</button>
        <div role="status"><strong>{selected.track.title}</strong><p>{selected.track.artist}</p><p>청취 시간 · {Math.floor(selected.seconds / 60)}분 {selected.seconds % 60}초</p><p>비율 · {(selected.seconds / total * 100).toFixed(1)}%</p></div>
      </section>}
    </div>
    <p className="demo-note">같은 크기의 앨범을 눌러 시간과 비율을 확인하세요. 실제 집계 연결 전 예시입니다.</p>
  </section>;
}
