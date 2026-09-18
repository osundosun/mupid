'use client';
import { useRef, type PointerEvent } from 'react';
import { formatTime, type SongSection, type Track } from '@/lib/mupid-data';
import { Waveform } from './primitives';
import { useTrackWaveform } from '@/lib/track-waveform';

export function MomentWaveEditor({ track, range, others = [], onSelect, onTrim }: {
  track: Track;
  /** 지금 손보는 구간. 분홍 양 끝을 끌어 조절한다. */
  range?: SongSection;
  /**
   * 함께 고른 나머지 구간. 연한 띠로만 그린다.
   *
   * 저장은 고른 구간을 모두 하는데 파형에는 마지막 것만 보였다. 칩은 여러 개가
   * 켜져 있는데 파형은 하나라, 하나만 저장되는 것처럼 읽혔다.
   */
  others?: SongSection[];
  onSelect: (time: number) => void;
  onTrim: (start: number, end: number) => void;
}) {
  const drag = useRef<'start' | 'end' | null>(null);
  // 현재 기기에서 음원 청크를 받아 파형을 계산한다(`lib/track-waveform.ts`).
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
    <p className="moment-wave-caption">{range ? `${range.label} · ${formatTime(range.start)} – ${formatTime(range.end)}${others.length ? ` · 선택 ${others.length + 1}개` : ''}` : '파형에서 저장할 순간을 눌러 주세요'}</p>
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
      <Waveform samples={wave.values ?? undefined} duration={track.duration} ranges={[...(range ? [range] : []), ...others]} />
      {track.duration > 0 && others.map(part => <span key={`${part.start}-${part.end}`} className="moment-wave-selection is-secondary" aria-hidden="true" style={{ left: `${part.start / track.duration * 100}%`, width: `${(part.end - part.start) / track.duration * 100}%` }} />)}
      {range && track.duration > 0 && <>
        <span className="moment-wave-selection" style={{ left: `${range.start / track.duration * 100}%`, width: `${(range.end - range.start) / track.duration * 100}%` }} />
        {(['start', 'end'] as const).map(edge => <button key={edge} type="button" role="slider" data-edge={edge}
          className="moment-wave-handle" aria-label={edge === 'start' ? '저장 순간 시작' : '저장 순간 끝'}
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
        ? `이 기기에서 파형을 분석하고 있어요 … ${Math.round(wave.progress * 100)}%`
        : wave.failed ? '이 기기에서 파형을 분석하지 못했어요. 시간 범위는 선택할 수 있어요.' : '순간을 누른 뒤 분홍색 양 끝을 끌어 조절하세요.'}
    </p>
  </div>;
}
