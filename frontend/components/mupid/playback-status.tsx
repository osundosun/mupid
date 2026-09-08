export function PlaybackStatus({ playing }: { playing: boolean }) {
  return (
    <output
      className="playback-status"
      data-playing={playing}
      aria-live="polite"
    >
      <svg viewBox="0 0 20 20" aria-hidden="true">
        <rect x="1" y="9" width="3" height="9" rx="1.5" />
        <rect x="6" y="2" width="3" height="16" rx="1.5" />
        <rect x="11" y="6" width="3" height="12" rx="1.5" />
        <rect x="16" y="4" width="3" height="14" rx="1.5" />
      </svg>
      <span className="sr-only">{playing ? '재생 중' : '일시정지'}</span>
    </output>
  );
}
