'use client';
import { useState } from 'react';
import { EllipsisVertical, ListEnd, ListPlus, Plus, Search, ThumbsDown, UserRound, Disc3 } from 'lucide-react';
import { TrackInfoDialog } from './track-info-dialog';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { useDismissableOverlay } from '@/hooks/use-dismissable-overlay';
import type { MupidModel } from '@/hooks/use-mupid';
import type { Track } from '@/lib/mupid-data';

export function TrackMenu({ model: m }: { model: MupidModel }) {
  const [track, setTrack] = useState<Track | null>(null);
  const [picker, setPicker] = useState(false);
  const [infoTrack, setInfoTrack] = useState<Track | null>(null);
  const [name, setName] = useState('');
  const close = () => { setTrack(null); setPicker(false); setName(''); };
  // 곡 더보기 창과 그 위에 겹치는 곡 정보 창이 각각 뒤로가기로 닫히게 한다.
  // 곡 정보가 열려 있으면 그쪽이 위라 먼저 닫힌다 (S15P21A204-410).
  useDismissableOverlay(!!track, close);
  useDismissableOverlay(!!infoTrack, () => setInfoTrack(null));
  const add = (id: number) => {
    if (!track) return;
    void m.addToPlaylist(id, track);
    m.setToast('플레이리스트에 추가했어요.'); close();
  };
  return <>
    <button className="icon-button" aria-label="곡 더보기" onClick={() => setTrack(m.active)}><EllipsisVertical /></button>
    <Dialog open={!!track} onOpenChange={open => { if (!open) close(); }}>
      <DialogContent className="sky-dialog track-menu-sheet">
        <DialogHeader><DialogTitle>{picker ? '플레이리스트에 추가' : track?.title}</DialogTitle><DialogDescription>{track?.artist}</DialogDescription></DialogHeader>
        {track && (picker ? <>
          <div className="track-menu-options">
            {m.playlists.map(p => <button key={p.id} disabled={p.tracks.some(t => t.id === track.id)} onClick={() => add(p.id)}><ListPlus /><span>{p.name}</span><small>{p.tracks.some(t => t.id === track.id) ? '추가됨' : `${p.tracks.length}곡`}</small></button>)}
          </div>
          <form onSubmit={event => {
            event.preventDefault(); if (!name.trim()) return;
            void m.createPlaylist(name, track);
            m.setToast('새 플레이리스트를 만들고 곡을 추가했어요.'); close();
          }}>
            <label className="field-label" htmlFor="new-track-playlist">새 플레이리스트 이름</label>
            <input id="new-track-playlist" maxLength={40} value={name} onChange={e => setName(e.target.value)} required />
            <button className="primary-button wide" disabled={!name.trim()}><Plus />만들고 추가</button>
          </form>
        </> : <div className="track-menu-options">
          <button onClick={() => { m.addToQueue(track); close(); }}><ListEnd />재생 목록에 담기</button>
          <button onClick={() => setPicker(true)}><ListPlus />플레이리스트에 추가</button>
          <button onClick={() => { m.openSimilar(track); close(); }}><Search />파생곡 찾기</button>
          <button disabled={m.ignored.includes(track.id)} aria-pressed={m.ignored.includes(track.id)} onClick={() => { m.ignoreTrack(track.id, track); close(); }}><ThumbsDown fill={m.ignored.includes(track.id) ? 'currentColor' : 'none'} />{m.ignored.includes(track.id) ? '관심 없음 설정됨' : '관심 없음'}</button>
          <hr className="track-menu-divider" />
          <button onClick={() => { m.setSelectedArtist(track.artist); close(); }}><UserRound />아티스트 정보</button>
          <button onClick={() => { setInfoTrack(track); close(); }}><Disc3 />곡 정보</button>
        </div>)}
      </DialogContent>
    </Dialog>
    <TrackInfoDialog track={infoTrack} open={!!infoTrack} onOpenChange={open => { if (!open) setInfoTrack(null); }} onArtist={m.setSelectedArtist} />
  </>;
}
