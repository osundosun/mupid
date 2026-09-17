'use client';
import { useEffect, useState } from 'react';
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
export function Lyrics({ track, time = 0, expanded = false }: { track: Track; time?: number; expanded?: boolean }) {
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
  const currentIndex = Math.max(0, (displayedLines ?? []).findLastIndex(line => line.time_ms <= time * 1000));
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
  return <section className="song-lyrics" key={track.id} aria-label={`${track.title} 가사`}>
    <h3>{track.title}</h3>
    {note && <p className="song-lyrics-empty" role={error ? 'alert' : 'status'}>{note}</p>}
    {previewLines?.map((line) => (
      <p key={line.lyric_id}>
        {line.text}
      </p>
    ))}
  </section>;
}
