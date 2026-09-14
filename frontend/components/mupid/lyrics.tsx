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
 */
export function Lyrics({ track }: { track: Track }) {
  const [lines, setLines] = useState<LyricLine[] | null>(null);
  const [error, setError] = useState(false);
  useEffect(() => {
    if (!isStreamed(track)) {
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
  }, [track.id, track.musicId]);
  return <details className="song-lyrics" key={track.id}>
    <summary>가사 보기 <span>⌄</span></summary>
    <div className="song-lyrics-body">
      <h3>{track.title}</h3>
      {!isStreamed(track) && <p className="lyrics-disclaimer">체험용 곡이라 가사가 없어요.</p>}
      {isStreamed(track) && error && <p role="alert">가사를 불러오지 못했어요.</p>}
      {isStreamed(track) && !error && lines === null && <p role="status">가사를 불러오고 있어요…</p>}
      {isStreamed(track) && !error && lines?.length === 0 && (
        <p className="lyrics-disclaimer">아직 등록된 가사가 없어요.</p>
      )}
      {lines?.map((line) => (
        <p key={line.lyric_id}>
          <small className="lyrics-time">{formatTime(line.time_ms / 1000)}</small> {line.text}
        </p>
      ))}
    </div>
  </details>;
}
