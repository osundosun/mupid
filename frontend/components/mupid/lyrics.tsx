'use client';
import { useEffect, useRef, useState } from 'react';
import { api } from '@/lib/api/session';
import { isStreamed } from '@/lib/stream-catalog';
import { formatTime, type Track } from '@/lib/mupid-data';
import type { LyricLine } from '@/lib/api/endpoints';
import { localDemoEnabled } from '@/lib/api/local-demo';

/**
 * 곡 가사.
 *
 * 서버에서 받는다. 가사가 없는 곡이 많고, 그건 오류가 아니라 "아직 없음" 이다.
 * 데모 곡(서버에 없는 곡)은 부를 데가 없어 안내만 띄운다.
 *
 * 기본은 현재 가사 두 줄, 가사 대화상자는 전체 가사를 보여 준다.
 */
export function Lyrics({ track, time = 0, expanded = false, onSeek }: { track: Track; time?: number; expanded?: boolean; onSeek?: (seconds: number) => void }) {
  const [lines, setLines] = useState<LyricLine[] | null>(null);
  const [error, setError] = useState(false);
  const streamed = isStreamed(track);
  const local = localDemoEnabled() && Boolean(track.localAudio);
  useEffect(() => {
    setError(false);
    if (local) {
      setLines(track.localLyrics ?? []);
      return;
    }
    if (!streamed) {
      setLines([]);
      return;
    }
    const abort = new AbortController();
    setLines(null);
    setError(false);
    void api.music
      .lyrics(track.musicId, abort.signal)
      .then((value) => {
        if (!abort.signal.aborted) setLines(value?.lines ?? []);
      })
      .catch(() => {
        if (!abort.signal.aborted) setError(true);
      });
    return () => abort.abort();
  }, [track.id, track.musicId, streamed, local, track.localLyrics]);
  const displayedLines = lines;
  const activeIndex = (displayedLines ?? []).findLastIndex(line => line.time_ms <= time * 1000);
  const currentIndex = Math.max(0, activeIndex);
  const activeId = activeIndex >= 0 ? displayedLines?.[activeIndex]?.lyric_id ?? null : null;
  const previewLines = expanded ? displayedLines : displayedLines?.slice(currentIndex, currentIndex + 2);
  const note = !streamed && !local
    ? '체험용 곡이라 가사가 없어요.'
    : error
      ? '가사를 불러오지 못했어요.'
      : displayedLines === null
        ? '가사를 불러오고 있어요…'
        : displayedLines.length === 0
          // 원래 없는 곡과 아직 못 붙인 곡을 같은 말로 덮으면 사실이 아니게 된다.
          ? (track.instrumental ? '연주곡이라 원래 가사가 없어요.' : '등록된 가사가 없습니다.')
          : '';
  const activeLine = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (!expanded) return;
    const line = activeLine.current;
    // 대화상자에서만 `.song-lyrics` 가 스크롤 상자다. 그 밖에서는 넘치는 부분을
    // 잘라 두므로 움직일 것이 없다.
    const box = line?.parentElement;
    if (!line || !box || box.scrollHeight <= box.clientHeight) return;
    // `offsetTop` 은 가장 가까운 positioned 조상 기준이라, 상자가 positioned 가 아니면
    // 페이지 어딘가를 기준으로 잰 큰 값이 나와 상자가 맨 아래로 튀었다(PC 플레이어의
    // 인라인 가사에서 그랬다). 화면 좌표 차이로 재면 어디에 놓여도 같다.
    const lineRect = line.getBoundingClientRect();
    const boxRect = box.getBoundingClientRect();
    // 이미 보이는 줄이면 움직이지 않는다. 줄을 눌러 그 시점으로 갔을 때 상자가
    // 가운데로 미끄러지는 것이 어색했다 — 눌렀던 자리에서 그대로 이어진다.
    // 노래가 흘러 현재 줄이 상자 밖으로 나가면 그때 가운데로 데려온다.
    if (lineRect.top >= boxRect.top && lineRect.bottom <= boxRect.bottom) return;
    const offset = lineRect.top - boxRect.top + box.scrollTop;
    const top = offset - box.clientHeight / 2 + line.clientHeight / 2;
    const gentle = !window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    box.scrollTo({ top: Math.max(0, top), behavior: gentle ? 'smooth' : 'auto' });
  }, [activeId, expanded]);
  return <section className="song-lyrics" key={track.id} aria-label={`${track.title} 가사`}>
    <h3>{track.title}</h3>
    {note && <p className="song-lyrics-empty" role={error ? 'alert' : 'status'}>{note}</p>}
    {previewLines?.map((line) => {
      const current = line.lyric_id === activeId;
      return <p
        key={line.lyric_id}
        ref={current ? activeLine : undefined}
        className={current ? 'lyric-current' : undefined}
        aria-current={current ? 'true' : undefined}
      >
        {onSeek
          ? <button type="button" className="lyric-seek" onClick={() => onSeek(line.time_ms / 1000)} aria-label={`${formatTime(line.time_ms / 1000)}부터 듣기: ${line.text}`}>{line.text}</button>
          : line.text}
      </p>;
    })}
  </section>;
}
