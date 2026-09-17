'use client';
import { useState } from 'react';
import { ChevronLeft, ChevronRight, Disc3, Music2, UserRound } from 'lucide-react';
import type { MupidModel } from '@/hooks/use-mupid';
import type { Track } from '@/lib/mupid-data';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '@/components/ui/dialog';
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/components/ui/tabs';
import { AlbumArt } from './primitives';

export function ArtistInfo({ model: m }: { model: MupidModel }) {
  return <Dialog open={m.selectedArtist !== null} onOpenChange={open => { if (!open) m.setSelectedArtist(null); }}>
    <DialogContent className="sky-dialog artist-info-dialog">
      <ArtistContent key={m.selectedArtist} model={m} />
    </DialogContent>
  </Dialog>;
}

function ArtistContent({ model: m }: { model: MupidModel }) {
  const [tab, setTab] = useState('info');
  const [album, setAlbum] = useState<string | null>(null);
  const tracks = m.tracks.filter(track => track.artist === m.selectedArtist);
  const albums = [...new Set(tracks.map(track => track.album))];
  const genres = [...new Set(tracks.map(track => track.genre).filter(Boolean))];
  const song = (track: Track) => <button className="artist-song" key={track.id} onClick={() => { m.setSelectedArtist(null); m.openTrack(track); }}>
    <AlbumArt track={track} /><span>{track.title}</span><ChevronRight size={18} aria-hidden="true" />
  </button>;
  return <>
    <div className="artist-profile-header">
      <div className="artist-mock-photo"><img src="/artist-profile-mock.png" alt="가상 아티스트 목업 프로필" width={72} height={72} /><span>목업</span></div>
      <DialogHeader><DialogTitle>{m.selectedArtist}</DialogTitle><DialogDescription>아티스트의 음악과 앨범을 둘러보세요.</DialogDescription></DialogHeader>
    </div>
    <Tabs className="sky-tabs artist-tabs" value={tab} onValueChange={value => { setTab(value); setAlbum(null); }}>
      <TabsList aria-label="아티스트 정보 메뉴">
        <TabsTrigger value="info">아티스트 정보</TabsTrigger>
        <TabsTrigger value="songs">곡</TabsTrigger>
        <TabsTrigger value="albums">앨범</TabsTrigger>
      </TabsList>
      <TabsContent value="info">
        <div className="artist-overview">
          <div className="artist-stat-grid">
            <button onClick={() => setTab('songs')}><Music2 size={18} /><span>곡</span><strong>{tracks.length}</strong><ChevronRight size={16} /></button>
            <button onClick={() => setTab('albums')}><Disc3 size={18} /><span>앨범</span><strong>{albums.length}</strong><ChevronRight size={16} /></button>
          </div>
          {genres.length > 0 && <div className="artist-genres"><h3>장르</h3><div className="tags">{genres.map(genre => <span key={genre}>{genre === 'contemporary_pop' ? '컨템포러리 팝' : genre.replaceAll('_', ' ')}</span>)}</div></div>}
          <p className="artist-info-note">현재 등록된 음악 기준</p>
        </div>
      </TabsContent>
      <TabsContent value="songs"><div className="artist-song-list">{tracks.map(song)}</div></TabsContent>
      <TabsContent value="albums">
        {album === null ? <div className="artist-album-grid">{albums.map(name => {
          const songs = tracks.filter(track => track.album === name);
          return <button className="artist-album-card" key={name} onClick={() => setAlbum(name)}>
            <AlbumArt track={songs[0]} /><strong>{name || '앨범 정보 없음'}</strong><span>{songs.length}곡</span>
          </button>;
        })}</div> : <section className="artist-album-detail">
          <button className="text-link" onClick={() => setAlbum(null)}><ChevronLeft size={18} />앨범 목록</button>
          <h3>{album || '앨범 정보 없음'}</h3>
          <div className="artist-song-list">{tracks.filter(track => track.album === album).map(song)}</div>
        </section>}
      </TabsContent>
    </Tabs>
  </>;
}
