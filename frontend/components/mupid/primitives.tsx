'use client';
import Image from 'next/image';
import { MascotAccent, MupidMascot } from './mascot';
import type { ReactNode } from 'react';
import {
  ChevronLeft,
  ChevronRight,
  Heart,
  Pause,
  Play,
  SkipBack,
  SkipForward,
  ListEnd,
  Repeat,
  Repeat1,
  Shuffle,
} from 'lucide-react';
import type { MupidModel, RepeatMode } from '@/hooks/use-mupid';
import { formatTime, type Track } from '@/lib/mupid-data';

export function Brand({ className = '' }: { className?: string }) {
  return (
    <Image
      unoptimized
      src="/mupid-logo-cupid-black.png"
      alt="Mupid"
      width={1134}
      height={638}
      className={`mupid-logo ${className}`.trim()}
    />
  );
}
export function AlbumArt({
  track,
  className = '',
}: {
  // 아직 고른 곡이 없을 수 있다. 그때는 빈 자리만 남긴다.
  track: Track | null;
  className?: string;
}) {
  if (!track) return <span className={`album-art album-art-empty ${className}`} aria-hidden="true" />;
  return (
    <span className={`album-art art-${track.id - 1} ${className}`}>
      <Image
        unoptimized
        width={320}
        height={320}
        src={track.image}
        alt={`${track.title} 앨범 커버`}
        loading="lazy"
      />
    </span>
  );
}
export function Heading({
  title,
  // 데스크톱 섹션처럼 무엇을 모아 둔 자리인지 작은 윗줄로 먼저 알려 준다.
  eyebrow,
  action,
  onAction,
}: {
  title: string;
  eyebrow?: string;
  action?: string;
  onAction?: () => void;
}) {
  return (
    <div className="section-heading">
      <div className="section-heading-copy">
        {eyebrow && <span className="eyebrow">{eyebrow}</span>}
        <h2>{title}</h2>
      </div>
      {action && (
        <button className="text-link" onClick={onAction}>
          {action}
          <ChevronRight />
        </button>
      )}
    </div>
  );
}
export function PageHeader({
  title,
  onBack,
  aside,
  mascot = true,
}: {
  title: string;
  onBack: () => void;
  aside?: ReactNode;
  mascot?: boolean;
}) {
  return (
    <div className="page-heading">
      <button className="icon-button" aria-label="뒤로 가기" onClick={onBack}>
        <ChevronLeft />
      </button>
      <div className="page-title-group">
        <h1>{title}</h1>
        {mascot && <MascotAccent size={56} className="page-title-mascot" />}
      </div>
      <div>{aside}</div>
    </div>
  );
}
export function TrackRow({
  track,
  model: m,
  detail,
  action,
  onPlay,
  // 추천·검색처럼 둘러보는 목록에서만 켠다. 자리가 빠듯한 목록에서는 생략한다.
  queueable = false,
  // 여러 곡 고르기. onSelect가 오면 체크 박스가 붙고 나머지 버튼은 잠시 물러난다.
  selected = false,
  onSelect,
}: {
  track: Track;
  model: MupidModel;
  detail?: string;
  action?: ReactNode;
  onPlay?: () => void;
  queueable?: boolean;
  selected?: boolean;
  onSelect?: () => void;
}) {
  const playing = m.playing && m.active?.id === track.id;
  const picking = !!onSelect;
  return (
    <article
      className={`track-row ${playing ? 'is-playing' : ''} ${picking ? 'is-selectable' : ''} ${selected ? 'is-selected' : ''}`}
    >
      {picking && (
        <input
          type="checkbox"
          className="track-check"
          checked={selected}
          aria-label={`${track.title} 선택`}
          onChange={onSelect}
        />
      )}
      <AlbumArt track={track} />
      <button className="track-info" onClick={picking ? onSelect : () => m.openTrack(track)}>
        <strong>{track.title}</strong>
        <span>{track.artist}</span>
        <span className="moment-meta">
          {detail || `Chorus · ${formatTime(track.moment)}`}
        </span>
      </button>
      {!picking && action}
      {!picking && queueable && (
        <button
          className="icon-button"
          aria-label={`${track.title} 재생 목록에 담기`}
          onClick={() => m.addToQueue(track)}
        >
          <ListEnd />
        </button>
      )}
      {!picking && (
        <button
          className="icon-button play-small"
          aria-label={`${track.title} ${playing ? '일시정지' : '재생'}`}
          onClick={onPlay ?? (() => m.play(track))}
        >
          {playing ? <Pause fill="currentColor" /> : <Play fill="currentColor" />}
        </button>
      )}
    </article>
  );
}
export function Waveform({
  duration,
  start = 0,
  end = 0,
  compact = false,
  progress = 0,
  ranges = [],
}: {
  duration: number;
  start?: number;
  end?: number;
  compact?: boolean;
  progress?: number;
  ranges?: { start: number; end: number }[];
}) {
  const count = compact ? 48 : 86;
  return (
    <div className={`waveform ${compact ? 'compact' : ''}`} aria-hidden="true">
      {Array.from({ length: count }, (_, i) => {
        const second = (i / count) * duration;
        const height =
          18 + Math.abs(Math.sin(i * 1.74) * Math.cos(i * 0.18)) * 72;
        return (
          <i
            className={
              (second >= start && second <= end) || ranges.some(range => second >= range.start && second < range.end)
                ? 'wave-selected'
                : second < progress
                  ? 'wave-played'
                  : ''
            }
            style={{ height: `${height}%` }}
            key={i}
          />
        );
      })}
      {end > start && (
        <span
          className="wave-selection"
          style={{
            left: `${(start / duration) * 100}%`,
            width: `${((end - start) / duration) * 100}%`,
          }}
        />
      )}
    </div>
  );
}
export function PlayerControls({
  model: m,
  compact = false,
  onPlay,
}: {
  model: MupidModel;
  compact?: boolean;
  onPlay?: () => void;
}) {
  return (
    <div
      className={`player-controls ${compact ? 'compact-controls' : 'full-controls'}`}
    >
      {!compact && <ShuffleButton model={m} />}
      <button
        aria-label="이전 곡"
        className="icon-button previous-control"
        onClick={() => m.next(-1)}
      >
        <SkipBack fill="currentColor" />
      </button>
      <button
        className="icon-button play-main"
        aria-label={m.playing ? '일시정지' : '재생'}
        onClick={onPlay ?? (() => m.play())}
      >
        {m.playing ? (
          <Pause fill="currentColor" />
        ) : (
          <Play fill="currentColor" />
        )}
      </button>
      <button
        aria-label="다음 곡"
        className="icon-button"
        onClick={() => m.next()}
      >
        <SkipForward fill="currentColor" />
      </button>
      {!compact && <RepeatButton model={m} />}
    </div>
  );
}
// 셔플은 재생 목록(플레이리스트)을 무작위 순서로 돌린다.
export function ShuffleButton({ model: m }: { model: MupidModel }) {
  const on = m.shuffle;
  return (
    <button
      className="icon-button toggle-control"
      aria-label={`셔플 재생 ${on ? '끄기' : '켜기'}`}
      aria-pressed={on}
      onClick={() => {
        m.setShuffle(!on);
        m.setToast(on ? '재생 목록 순서대로 재생해요.' : '재생 목록을 섞어서 재생해요.');
      }}
    >
      <Shuffle />
    </button>
  );
}
const REPEAT_ORDER: RepeatMode[] = ['off', 'all', 'one'];
const REPEAT_LABELS: Record<RepeatMode, string> = {
  off: '반복 없음',
  all: '전체 반복',
  one: '한곡 반복',
};
export function RepeatButton({ model: m }: { model: MupidModel }) {
  const next = REPEAT_ORDER[(REPEAT_ORDER.indexOf(m.repeatMode) + 1) % REPEAT_ORDER.length];
  return (
    <button
      className="icon-button toggle-control"
      data-repeat={m.repeatMode}
      aria-label={`반복 모드: ${REPEAT_LABELS[m.repeatMode]}. 누르면 ${REPEAT_LABELS[next]}`}
      aria-pressed={m.repeatMode !== 'off'}
      onClick={() => {
        m.setRepeatMode(next);
        m.setToast(
          next === 'one'
            ? '한곡 반복 · 이 곡을 계속 반복해요.'
            : next === 'all'
              ? '전체 반복 · 재생 목록을 계속 돌려요.'
              : '반복 없음 · 목록이 끝나면 멈춰요.',
        );
      }}
    >
      {m.repeatMode === 'one' ? <Repeat1 /> : <Repeat />}
    </button>
  );
}
export function LikeButton({
  model: m,
  track = m.active,
}: {
  model: MupidModel;
  track?: Track | null;
}) {
  // 좋아요를 누를 곡이 없으면 버튼도 없다.
  if (!track) return null;
  return (
    <button
      aria-label={m.liked.includes(track.id) ? '좋아요 취소' : '좋아요'}
      aria-pressed={m.liked.includes(track.id)}
      className="icon-button like-button"
      onClick={() => m.toggleLike(track.id)}
    >
      <Heart fill={m.liked.includes(track.id) ? 'currentColor' : 'none'} />
    </button>
  );
}
export function EmptyState({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children?: ReactNode;
}) {
  return (
    <div className="empty-state">
      <MupidMascot size={95} />
      <h2>{title}</h2>
      <p>{description}</p>
      {children}
    </div>
  );
}
