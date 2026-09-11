'use client';
import { useState } from 'react';
import { Bookmark, EllipsisVertical, Heart, ListEnd, ListPlus, Plus } from 'lucide-react';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import type { MupidModel } from '@/hooks/use-mupid';
import type { Track } from '@/lib/mupid-data';

export function TrackMenu({ model: m }: { model: MupidModel }) {
  const [track, setTrack] = useState<Track | null>(null);
  const [picker, setPicker] = useState(false);
  const [name, setName] = useState('');
  const close = () => { setTrack(null); setPicker(false); setName(''); };
  const add = (id: number) => {
    if (!track) return;
    m.setPlaylists(list => list.map(p => p.id === id && !p.tracks.some(t => t.id === track.id) ? { ...p, tracks: [...p.tracks, track] } : p));
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
            m.setPlaylists(list => [...list, { id: Date.now(), name: name.trim(), tracks: [track] }]);
            m.setToast('새 플레이리스트를 만들고 곡을 추가했어요.'); close();
          }}>
            <label className="field-label" htmlFor="new-track-playlist">새 플레이리스트 이름</label>
            <input id="new-track-playlist" maxLength={40} value={name} onChange={e => setName(e.target.value)} required />
            <button className="primary-button wide" disabled={!name.trim()}><Plus />만들고 추가</button>
          </form>
        </> : <div className="track-menu-options">
          <button onClick={() => { m.addToQueue(track); close(); }}><ListEnd />재생 목록에 담기</button>
          <button onClick={() => setPicker(true)}><ListPlus />플레이리스트에 추가</button>
          <button aria-pressed={m.liked.includes(track.id)} onClick={() => { m.toggleLike(track.id); close(); }}><Heart fill={m.liked.includes(track.id) ? 'currentColor' : 'none'} />좋아한 곡</button>
          <button onClick={() => { close(); m.openMoment(track); }}><Bookmark />저장한 순간</button>
        </div>)}
      </DialogContent>
    </Dialog>
  </>;
}
