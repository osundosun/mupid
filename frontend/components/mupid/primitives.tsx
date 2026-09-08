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
  Repeat2,
  Shuffle,
} from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
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
  track: Track;
  className?: string;
}) {
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
  action,
  onAction,
}: {
  title: string;
  action?: string;
  onAction?: () => void;
}) {
  return (
    <div className="section-heading">
      <h2>{title}</h2>
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
}: {
  track: Track;
  model: MupidModel;
  detail?: string;
  action?: ReactNode;
  onPlay?: () => void;
}) {
  const playing = m.playing && m.active.id === track.id;
  return (
    <article className={`track-row ${playing ? 'is-playing' : ''}`}>
      <AlbumArt track={track} />
      <button className="track-info" onClick={() => m.openTrack(track)}>
        <strong>{track.title}</strong>
        <span>{track.artist}</span>
        <span className="moment-meta">
          {detail || `Chorus · ${formatTime(track.moment)}`}
        </span>
      </button>
      {action}
      <button
        className="icon-button play-small"
        aria-label={`${track.title} ${playing ? '일시정지' : '재생'}`}
        onClick={onPlay ?? (() => m.play(track))}
      >
        {playing ? <Pause fill="currentColor" /> : <Play fill="currentColor" />}
      </button>
    </article>
  );
}
export function Waveform({
  duration,
  start = 0,
  end = 0,
  compact = false,
  progress = 0,
}: {
  duration: number;
  start?: number;
  end?: number;
  compact?: boolean;
  progress?: number;
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
              second >= start && second <= end
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
}: {
  model: MupidModel;
  compact?: boolean;
}) {
  return (
    <div
      className={`player-controls ${compact ? 'compact-controls' : 'full-controls'}`}
    >
      {!compact && (
        <button
          className="icon-button toggle-control"
          aria-label="셔플 재생"
          aria-pressed={m.shuffle}
          onClick={() => m.setShuffle(!m.shuffle)}
        >
          <Shuffle />
        </button>
      )}
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
        onClick={() => m.play()}
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
      {!compact && (
        <button
          className="icon-button toggle-control"
          aria-label="한 곡 반복"
          aria-pressed={m.repeat}
          onClick={() => m.setRepeat(!m.repeat)}
        >
          <Repeat2 />
        </button>
      )}
    </div>
  );
}
export function LikeButton({
  model: m,
  track = m.active,
}: {
  model: MupidModel;
  track?: Track;
}) {
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
