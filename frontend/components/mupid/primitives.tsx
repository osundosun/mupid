'use client';
import { resampleWaveform } from '@/lib/waveform-display';
import Image from 'next/image';
import { MascotAccent, MupidMascot } from './mascot';
import { useEffect, useState, type ReactNode } from 'react';
import { loadAudioWaveform } from '@/lib/audio-waveform';
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
import { formatTime, getSongSectionAt, resolveAlbumCover, type Track } from '@/lib/mupid-data';

export function Brand({ className = '' }: { className?: string }) {
  return (
    <Image
      unoptimized
      src="/mupid-logo-cupid-sky.png"
      alt="Mupid"
      width={1180}
      height={600}
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
  // 훅은 분기보다 위에 있어야 한다. 곡이 없는 화면과 있는 화면이 같은 자리를
  // 번갈아 쓰기 때문에, 조건 뒤로 내리면 훅 개수가 렌더마다 달라진다.
  //
  // 여기 담기는 것은 "못 읽은 주소" 다. 곡이 바뀌어 주소가 달라지면 저절로
  // 어긋나므로, 곡마다 상태를 지우는 별도의 처리가 필요 없다.
  const [failedSrc, setFailedSrc] = useState<string | null>(null);

  if (!track) return <span className={`album-art album-art-empty ${className}`} aria-hidden="true" />;

  const { src, isFallback } = resolveAlbumCover(track.image, failedSrc);
  return (
    <span className={`album-art art-${track.id - 1} ${className}`}>
      <Image
        unoptimized
        width={320}
        height={320}
        src={src}
        // 기본 커버여도 어떤 곡의 자리인지는 알려 준다. 커버를 못 받아 온 것은
        // 화면 사정이지 그 칸이 장식이 됐다는 뜻이 아니다.
        alt={`${track.title} 앨범 커버`}
        loading="lazy"
        // 기본 커버에는 데모 곡 연출(`.art-*`)과 사진용 크롭이 걸리면 안 된다.
        className={isFallback ? 'album-art-fallback' : undefined}
        // 이미 기본 커버라면 핸들러를 달지 않는다 — 그래야 실패가 한 번으로
        // 끝나고 다시 그리기가 돌지 않는다.
        onError={isFallback ? undefined : () => setFailedSrc(src)}
      />
    </span>
  );
}
export function PlaylistArt({ track, name }: { track?: Track; name: string }) {
  const [failedSrc, setFailedSrc] = useState<string | null>(null);
  const cover = resolveAlbumCover(track?.image, failedSrc);
  const src = cover.isFallback ? '/playlist-cover-default.png' : cover.src;
  return (
    <span className="album-art">
      <Image unoptimized width={320} height={320} src={src}
        alt={`${name} 플레이리스트 커버`} loading="lazy"
        className={cover.isFallback ? 'album-art-fallback' : undefined}
        onError={cover.isFallback ? undefined : () => setFailedSrc(src)} />
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
/**
 * 목록에 적을 구간 이름.
 *
 * 예전에는 `"Chorus"` 를 그대로 박아 두어, 서버가 브리지를 권해도 화면에는 후렴이라고
 * 적혔다. 추천으로 온 곡은 서버가 고른 구간 이름을 쓰고, 그 밖에는 분석 결과에서
 * 위치로 찾는다. 분석 전이면 `getSongSectionAt` 이 `전체` 를 돌려준다.
 */
const sectionNameOf = (track: Track): string =>
  track.recommended?.sectionLabel || getSongSectionAt(track, track.moment).label;

export function RowPlaybackArea({ onPlay }: { onPlay: () => void }) {
  // 키보드·스크린리더는 기존 재생 버튼을 사용한다.
  return <button type="button" className="track-row-hitarea" aria-hidden="true" tabIndex={-1} onClick={onPlay} />;
}

export function TrackRow({
  track,
  model: m,
  detail,
  showDetail = true,
  titleInteractive = true,
  onTitleClick,
  action,
  onPlay,
  // 추천·검색처럼 둘러보는 목록에서만 켠다. 자리가 빠듯한 목록에서는 생략한다.
  queueable = false,
  // 여러 곡 고르기. onSelect가 오면 체크 박스가 붙고 나머지 버튼은 잠시 물러난다.
  selected = false,
  onSelect,
  playingOverride,
  /*
   * 체크 박스를 줄 **안에** 그릴지. 재생 목록처럼 줄 앞에 이미 자리(끌기 손잡이
   * 칸)가 있는 곳에서는 끈다 — 그 칸에 체크를 넣으면 커버와 제목이 제자리를
   * 지키는데, 줄 안에 넣으면 그 폭만큼 통째로 밀린다.
   */
  showCheck = true,
}: {
  track: Track;
  model: MupidModel;
  detail?: string;
  showDetail?: boolean;
  titleInteractive?: boolean;
  onTitleClick?: () => void;
  action?: ReactNode;
  onPlay?: () => void;
  queueable?: boolean;
  selected?: boolean;
  onSelect?: () => void;
  showCheck?: boolean;
  /** 곡 재생 상태와 별개인 전용 재생 버튼이 필요할 때 사용한다. */
  playingOverride?: boolean;
}) {
  const playing = playingOverride ?? (m.playing && m.active?.id === track.id);
  const picking = !!onSelect;
  const handlePlay = onPlay ?? (() => m.play(track));
  return (
    <article
      className={`track-row ${playing ? 'is-playing' : ''} ${picking ? 'is-selectable' : ''} ${selected ? 'is-selected' : ''}`}
    >
      {!picking && <RowPlaybackArea onPlay={handlePlay} />}
      {picking && showCheck && (
        <input
          type="checkbox"
          className="track-check"
          checked={selected}
          aria-label={`${track.title} 선택`}
          onChange={onSelect}
        />
      )}
      <AlbumArt track={track} />
      <div className="track-info">
        {titleInteractive ? (
          <button className="track-title-link" onClick={picking ? onSelect : onTitleClick ?? (() => m.openTrack(track))}><strong>{track.title}</strong></button>
        ) : (
          <span className="track-title-link"><strong>{track.title}</strong></span>
        )}
        <button className="track-artist-link" onClick={picking ? onSelect : () => m.setSelectedArtist(track.artist)}>{track.artist}</button>
        {showDetail && <span className="moment-meta">
          {detail || `${sectionNameOf(track)} · ${formatTime(track.moment)}`}
        </span>}
      </div>
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
          onClick={handlePlay}
        >
          {playing ? <Pause fill="currentColor" /> : <Play fill="currentColor" />}
        </button>
      )}
    </article>
  );
}
export { resampleWaveform } from '@/lib/waveform-display';
export function Waveform({
  samples,
  audioUrl,
  duration,
  start = 0,
  end = 0,
  compact = false,
  progress = 0,
  ranges = [],
}: {
  /** 서버가 미리 뽑아 둔 파형(0~1). 있으면 이것이 1순위다. → `Track.waveform` */
  samples?: number[];
  audioUrl?: string;
  duration: number;
  start?: number;
  end?: number;
  compact?: boolean;
  progress?: number;
  ranges?: { start: number; end: number }[];
}) {
  const count = compact ? 64 : 160;
  const [analysis, setAnalysis] = useState<{ url: string; values: number[] } | null>(null);
  useEffect(() => {
    if (!audioUrl) return;
    let alive = true;
    void loadAudioWaveform(audioUrl, count).then(values => {
      if (alive) setAnalysis({ url: audioUrl, values });
    }).catch(() => { if (alive) setAnalysis(null); });
    return () => { alive = false; };
  }, [audioUrl, count]);
  // 기기에서 계산한 파형 → 로컬 음원 디코드 → 데이터가 없으면 중립적인 기준선.
  const served = samples && samples.length > 1 ? resampleWaveform(samples, count) : null;
  const amplitudes = served ?? (analysis?.url === audioUrl && analysis?.values.length === count ? analysis.values : null);
  return (
    <div className={`waveform ${compact ? 'compact' : ''}`} aria-hidden="true">
      {Array.from({ length: count }, (_, i) => {
        // 막대의 왼쪽 끝이 아니라 중앙 시각으로 색을 판정한다. 그래야 백분율로
        // 그린 구간 오버레이의 양 끝과 분홍 막대 경계가 같은 눈금에 맞는다.
        const second = ((i + 0.5) / count) * duration;
        return (
          <i
            className={
              (end > start && second >= start && second <= end) || ranges.some(range => second >= range.start && second < range.end)
                ? 'wave-selected'
                : second < progress
                  ? 'wave-played'
                  : ''
            }
            style={{ height: `${amplitudes?.[i] ?? 4}%` }}
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
              // 반복을 끄면 멈추지 않는다. 목록 끝에서 추천 한 곡이 이어진다.
              : '반복 없음 · 목록이 끝나면 추천 곡을 이어 재생해요.',
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
      onClick={() => m.toggleLike(track.id, track)}
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
