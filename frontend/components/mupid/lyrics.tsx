'use client';
import { useEffect, useState } from 'react';
import { api } from '@/lib/api/session';
import { isStreamed } from '@/lib/stream-catalog';
import { formatTime, type Track } from '@/lib/mupid-data';
import type { LyricLine } from '@/lib/api/endpoints';

/**
 * 곡 가사.
 *
 * 서버에서 받는다. 가사가 없는 곡이 많고, 그건 오류가 아니라 "아직 없음" 이다.
 * 데모 곡(서버에 없는 곡)은 부를 데가 없어 안내만 띄운다.
 *
 * 접지 않고 항상 보여 준다. LP 바로 아래에 놓이는 블록이다.
 */
export function Lyrics({ track }: { track: Track }) {
  const [lines, setLines] = useState<LyricLine[] | null>(null);
  const [error, setError] = useState(false);
  const streamed = isStreamed(track);
  useEffect(() => {
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
  }, [track.id, track.musicId, streamed]);
  const note = !streamed
    ? '체험용 곡이라 가사가 없어요.'
    : error
      ? '가사를 불러오지 못했어요.'
      : lines === null
        ? '가사를 불러오고 있어요…'
        : lines.length === 0
          ? '가사가 아직 준비되지 않았어요.'
          : '';
  return <section className="song-lyrics" key={track.id} aria-label={`${track.title} 가사`}>
    <h3>{track.title}</h3>
    {note && <p className="song-lyrics-empty" role={error ? 'alert' : 'status'}>{note}</p>}
    {lines?.map((line) => (
      <p key={line.lyric_id}>
        <small className="lyrics-time">{formatTime(line.time_ms / 1000)}</small>
        {line.text}
      </p>
    ))}
  </section>;
}
