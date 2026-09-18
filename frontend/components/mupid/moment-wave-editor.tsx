'use client';
import { useRef, type PointerEvent } from 'react';
import { formatTime, type SongSection, type Track } from '@/lib/mupid-data';
import { Waveform } from './primitives';
import { useTrackWaveform } from '@/lib/track-waveform';

export function MomentWaveEditor({ track, range, onSelect, onTrim }: {
  track: Track;
  range?: SongSection;
  onSelect: (time: number) => void;
  onTrim: (start: number, end: number) => void;
}) {
  const drag = useRef<'start' | 'end' | null>(null);
  // 진짜 파형. 서버 값이 있으면 그것, 없으면 청크를 받아 여기서 계산한다(`lib/track-waveform.ts`).
  // 받는 동안은 받은 데까지 그려지고, 아래 안내에 진행률이 보인다.
  const wave = useTrackWaveform(track);
  const press = useRef<{ x: number; y: number; moved: boolean } | null>(null);
  const seconds = (event: PointerEvent<HTMLDivElement>) => {
    const box = event.currentTarget.getBoundingClientRect();
    return Math.round(Math.max(0, Math.min(1, (event.clientX - box.left) / box.width)) * track.duration);
  };
  const trim = (edge: 'start' | 'end', value: number) => {
    if (!range) return;
    onTrim(edge === 'start' ? Math.max(0, Math.min(value, range.end - 1)) : range.start,
      edge === 'end' ? Math.min(track.duration, Math.max(value, range.start + 1)) : range.end);
  };
  return <div className="moment-wave-editor">
    <p className="moment-wave-caption">{range ? `${range.label} · ${formatTime(range.start)} – ${formatTime(range.end)}` : '파형에서 저장할 구간을 눌러 주세요'}</p>
    <div className="moment-wave-surface" onPointerDown={event => {
      if (!event.isPrimary || event.button !== 0) return;
      press.current = { x: event.clientX, y: event.clientY, moved: false };
      const edge = (event.target as HTMLElement).closest<HTMLElement>('[data-edge]')?.dataset.edge;
      if (edge === 'start' || edge === 'end') {
        drag.current = edge;
        event.currentTarget.setPointerCapture(event.pointerId);
        event.preventDefault();
      }
    }} onPointerMove={event => {
      if (press.current && Math.hypot(event.clientX - press.current.x, event.clientY - press.current.y) > 8) press.current.moved = true;
      if (drag.current) trim(drag.current, seconds(event));
    }}
      onPointerUp={event => {
        if (drag.current) { drag.current = null; event.currentTarget.releasePointerCapture(event.pointerId); }
        else if (press.current && !press.current.moved) onSelect(seconds(event));
        press.current = null;
      }} onPointerCancel={() => { drag.current = null; press.current = null; }}>
      <Waveform samples={wave.values ?? undefined} duration={track.duration} ranges={range ? [range] : []} />
      {range && track.duration > 0 && <>
        <span className="moment-wave-selection" style={{ left: `${range.start / track.duration * 100}%`, width: `${(range.end - range.start) / track.duration * 100}%` }} />
        {(['start', 'end'] as const).map(edge => <button key={edge} type="button" role="slider" data-edge={edge}
          className="moment-wave-handle" aria-label={edge === 'start' ? '저장 구간 시작' : '저장 구간 끝'}
          aria-valuemin={edge === 'start' ? 0 : range.start + 1} aria-valuemax={edge === 'start' ? range.end - 1 : track.duration}
          aria-valuenow={range[edge]} aria-valuetext={formatTime(range[edge])}
          style={{ left: `${range[edge] / track.duration * 100}%` }}
          onKeyDown={event => {
            if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
              event.preventDefault(); trim(edge, range[edge] + (event.key === 'ArrowRight' ? 1 : -1));
            }
          }}><span /></button>)}
      </>}
    </div>
    <p className="moment-wave-help">
      {wave.progress < 1 && !wave.failed
        ? `파형을 읽고 있어요 … ${Math.round(wave.progress * 100)}%`
        : '구간을 누른 뒤 분홍색 양 끝을 끌어 조절하세요.'}
    </p>
  </div>;
}
